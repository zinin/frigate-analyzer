package ru.zinin.frigate.analyzer.ai.description.core

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.PresetChoiceSource
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Активный пресет на каждый вызов: чтение дешёвое, потому что реализация настроек кэширует значение
 * на процесс и сбрасывает кэш только на собственной записи.
 *
 * Читает [PresetChoiceSource] fail-open и с потолком [READ_TIMEOUT]: ключ `ai.description.*` —
 * про удобство, а не про безопасность, поэтому и отказ, и зависание чтения дают предупреждение и
 * пресет по умолчанию, но никогда не исключение. Реализация поверх `app_settings` намеренно НЕ
 * кэширует неудачные чтения, так что отказ БД бил бы по каждой записи подряд, а сырое исключение
 * R2DBC покинуло бы контракт `DescriptionException`, который обещает агент.
 *
 * Не бин и не [ru.zinin.frigate.analyzer.ai.description.api.ActiveDescriptionPreset]: два резолвера
 * (описания и судья) делят один каталог, но не fallback, и `ObjectProvider.getIfAvailable()` падает,
 * если два бина одного типа. Адаптер описаний — [DescriptionPresetResolver].
 */
class ActivePresetResolver(
    private val catalog: DescriptionPresetCatalog,
    private val source: PresetChoiceSource,
    val fallbackId: String,
    private val label: String,
) {
    init {
        require(catalog.byId(fallbackId)?.backend != null) {
            "fallback preset '$fallbackId' for $label is missing or unavailable"
        }
    }

    /** Последнее залогированное предупреждение: иначе каждая запись повторяла бы одну строку. */
    private val lastWarning = AtomicReference<String?>(null)

    /** Последняя залогированная строка об активном пресете: см. [logActiveOnChange]. */
    private val lastActive = AtomicReference<String?>(null)

    /** Результат чтения: отказ и «ничего не выбрано» ведут себя одинаково, кроме INFO-строки. */
    private class StoredRead(
        val id: String?,
        val failed: Boolean,
    )

    suspend fun resolve(): DescriptionPresetCatalog.Entry {
        val read = readStoredId()
        if (read.id != null) {
            val stored = catalog.byId(read.id)
            if (stored?.backend != null) {
                logActiveOnChange(stored.view, source = source.sourceName)
                return stored
            }
            warnOnce(
                if (stored == null) {
                    "Active $label preset '${read.id}' is not configured; using '$fallbackId'"
                } else {
                    "Active $label preset '${read.id}' is unavailable " +
                        "(${stored.view.unavailableReason}); using '$fallbackId'"
                },
            )
        }
        val fallback = requireNotNull(catalog.byId(fallbackId))
        // При отказе чтения источник неизвестен: предупреждение выше уже сказало правду, а INFO
        // «from default-preset» соврала бы про выбор владельца, который так и не был прочитан.
        if (!read.failed) logActiveOnChange(fallback.view, source = DEFAULT_PRESET_SOURCE)
        return fallback
    }

    suspend fun storedId(): String? = readStoredId().id

    suspend fun effective(): DescriptionPreset = resolve().view

    /**
     * Пустая строка — это «не выбрано», а не сломанный id: иначе на неё шло бы предупреждение.
     *
     * Чтение ограничено сверху: `describe` зовёт резолвер вне обоих своих `withTimeout`, поэтому без
     * потолка зависшая реализация настроек подвесила бы вызов целиком, а `AppSettingsServiceImpl`
     * сериализует чтения одним мьютексом — встал бы каждый describe-job, удерживая свои кадры.
     */
    private suspend fun readStoredId(): StoredRead =
        try {
            val stored = withTimeout(READ_TIMEOUT) { source.activePresetId() }
            StoredRead(stored?.takeIf { it.isNotBlank() }, failed = false)
        } catch (e: TimeoutCancellationException) {
            // Раньше CancellationException: TimeoutCancellationException — её наследник, и общий
            // catch увёл бы истечение потолка в отмену вызова вместо fail-open. В
            // DescriptionException.Timeout это тоже не превращается: тогда лог и владелец винили бы
            // в медлительности модель, тогда как задержалась база.
            warnOnce(
                "Reading the active $label preset timed out after $READ_TIMEOUT; " +
                    "using '$fallbackId'",
            )
            StoredRead(null, failed = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnOnce("Cannot read the active $label preset; using '$fallbackId': ${e.message}")
            StoredRead(null, failed = true)
        }

    /**
     * Активный пресет — на INFO и лениво, при КАЖДОЙ смене строки, а не один раз за процесс. Один раз
     * не хватает: владелец переключает пресет в `/ai` без рестарта, запись настроек кладёт в лог
     * только id и только на DEBUG, и после переключения ни одна строка не называет работающую модель —
     * то есть на вопрос «что работает сейчас» лог перестаёт отвечать ровно тогда, когда его задают.
     *
     * Место — резолвер, а не обработчик клика: сюда попадает и смена, которую никто не выбирал
     * (сохранённый пресет стал непригоден и его подменил fallback), и [logSignature] остаётся
     * `internal` — из `telegram` он не виден. Принятая плата: переключение видно в логе не в момент
     * клика, а на ближайшем вызове описания (или на открытии `/ai`, которое тоже резолвит).
     *
     * Строка обязана называть источник: `default-preset` после первого выбора владельца перестаёт
     * действовать, и оператор, поправивший его в yaml и перезапустивший контейнер, иначе не получает
     * никакого сигнала. На старте её печатать нельзя: назвать источник — значит прочитать настройки,
     * то есть сходить в БД из контекста обновления Spring.
     *
     * Сравнивается вся строка, а не один id: сменившийся источник при том же пресете — это тоже
     * событие (владелец явно выбрал то, что и так работало по умолчанию), а в установившемся режиме
     * строка неизменна, и лог молчит.
     */
    private fun logActiveOnChange(
        preset: DescriptionPreset,
        source: String,
    ) {
        val overridden =
            if (source != DEFAULT_PRESET_SOURCE && preset.id != fallbackId) {
                ", overriding default-preset='$fallbackId'"
            } else {
                ""
            }
        // Та же форма, что у стартовой строки каталога: оператор сверяет эти строки между собой.
        val message = "Active $label preset '${preset.id}' (${preset.logSignature()}) from $source$overridden"
        if (lastActive.getAndSet(message) != message) {
            logger.info { message }
        }
    }

    private fun warnOnce(message: String) {
        if (lastWarning.getAndSet(message) != message) {
            logger.warn { message }
        }
    }

    private companion object {
        const val DEFAULT_PRESET_SOURCE = "default-preset"

        /**
         * Потолок на чтение настроек. Нормальный случай — попадание в кэш процесса, микросекунды, так
         * что величина нужна только против зависшего пула R2DBC. Пять секунд — тот же порядок, что и
         * у собственных констант повторов агента (`TRANSPORT_RETRY_DELAY`,
         * `INVALID_RESPONSE_RETRY_MIN_BUDGET`), поэтому новой величины в системе не появляется.
         */
        val READ_TIMEOUT = 5.seconds
    }
}

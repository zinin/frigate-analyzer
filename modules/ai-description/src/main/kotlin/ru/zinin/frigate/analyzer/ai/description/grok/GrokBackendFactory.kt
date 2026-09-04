package ru.zinin.frigate.analyzer.ai.description.grok

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.UnavailableReason
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionBackend
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionBackendFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Проверки окружения Grok живут здесь, а не в `init` backend-а: backend-ов на провайдера теперь
 * столько, сколько grok-пресетов, и повторять создание каталогов и предупреждения на каждый из них
 * незачем.
 *
 * Конструктор строго пассивен — ни файловой системы, ни `PATH`, ни лога. Осмотр целиком в
 * [availability], которую `DescriptionPresetCatalogBuilder` зовёт только для провайдеров,
 * встречающихся хотя бы в одном объявленном пресете: `GROK_HOME` в compose задан всегда, и
 * claude-деплой иначе создавал бы и осматривал чужой том.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class GrokBackendFactory(
    private val properties: GrokProperties,
    private val promptFileWriter: GrokPromptFileWriter,
    private val commandBuilder: GrokCommandBuilder,
    private val runner: GrokProcessRunner,
    private val outputParser: GrokOutputParser,
    private val exceptionMapper: GrokExceptionMapper,
    private val guard: GrokHomeGuard,
) : DescriptionBackendFactory {
    override val providerId: String = GrokBackend.PROVIDER_ID

    /** Каталоги и `PATH` приходят из окружения процесса — осматриваем один раз. */
    private val inspected: DescriptionBackendFactory.Availability by lazy { inspectEnvironment() }

    override fun availability(): DescriptionBackendFactory.Availability = inspected

    /**
     * Авторизация принадлежит паре «провайдер плюс модель»: два пресета на одной модели живут в
     * общем `auth.json`, а BYOK-модель ходит по собственному ключу из `config.toml`, и её отказ
     * ничего не говорит о сессии xAI.
     */
    override fun authScopeId(preset: DescriptionProperties.Preset): String = "$providerId:${preset.model}"

    override fun create(preset: DescriptionProperties.Preset): DescriptionBackend =
        GrokBackend(
            model = preset.model,
            effort = preset.effort,
            // Через authScopeId(preset), а не сборкой строки заново: область считает ровно одно
            // место, и строка на backend-е совпадает с той, что каталог кладёт в DescriptionPreset.
            authScopeId = authScopeId(preset),
            promptFileWriter = promptFileWriter,
            commandBuilder = commandBuilder,
            runner = runner,
            outputParser = outputParser,
            exceptionMapper = exceptionMapper,
            guard = guard,
        )

    /**
     * Непригодный каталог помечает пресеты провайдера, а не роняет контекст: исключение отсюда
     * пришло бы раньше каталога и уронило бы деплой с годными claude-пресетами из-за чужого тома.
     * Отсутствие `auth.json` непригодностью не считается — BYOK-модель ходит по собственному ключу,
     * а протухшую сессию ловит `Unauthorized` и сообщение владельцу.
     */
    private fun inspectEnvironment(): DescriptionBackendFactory.Availability {
        val home = properties.homePath
        val cwd = properties.workingDirectoryPath
        createDirectory(home)?.let { return it }
        createDirectory(cwd)?.let { return it }
        if (!Files.isWritable(home)) {
            logger.warn {
                "Grok home $home is not writable; grok login and token refresh will fail " +
                    "(fix: chown the volume to uid 1000)"
            }
        }
        if (!cliAvailable()) {
            logger.warn {
                "grok CLI not found (cli-path='${properties.cliPath}', PATH lookup otherwise); " +
                    "grok presets will return fallback"
            }
        }
        if (!Files.isRegularFile(home.resolve("auth.json"))) {
            logger.warn {
                "No auth.json in $home; run `${GrokBackend.AUTH_RECOVERY_HINT}`. Not needed only for " +
                    "BYOK models with their own api_key in config.toml"
            }
        }
        logger.info { "Grok description provider: home=$home, cwd=$cwd" }
        return DescriptionBackendFactory.Availability.Available
    }

    /** @return причину непригодности, если каталог создать не удалось, иначе null. */
    private fun createDirectory(dir: Path): DescriptionBackendFactory.Availability.Unavailable? =
        try {
            Files.createDirectories(dir)
            null
        } catch (e: IOException) {
            // Подробности — в лог: на экран владельца уходит только код причины.
            logger.warn(e) { "Cannot create Grok directory $dir: ${e.message}" }
            DescriptionBackendFactory.Availability.Unavailable(UnavailableReason.HomeUnwritable(dir.toString()))
        }

    private fun cliAvailable(): Boolean {
        val cliPath = properties.cliPath
        if (cliPath.isNotBlank()) return Files.isExecutable(Path.of(cliPath))
        return System
            .getenv("PATH")
            ?.split(File.pathSeparator)
            .orEmpty()
            .filter { it.isNotBlank() }
            .any { Files.isExecutable(Path.of(it, "grok")) }
    }
}

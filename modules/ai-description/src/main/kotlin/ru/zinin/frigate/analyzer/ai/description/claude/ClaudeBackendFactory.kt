package ru.zinin.frigate.analyzer.ai.description.claude

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springaicommunity.claude.agent.sdk.Query
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.UnavailableReason
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import ru.zinin.frigate.analyzer.ai.description.core.VisionBackend
import ru.zinin.frigate.analyzer.ai.description.core.VisionBackendFactory
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Проверки окружения Claude живут здесь, а не в `init` backend-а: backend-ов на провайдера теперь
 * столько, сколько claude-пресетов, и повторять осмотр на каждый из них незачем.
 *
 * Конструктор строго пассивен — ни файловой системы, ни `PATH`, ни лога. Осмотр целиком в
 * [availability], которую `DescriptionPresetCatalogBuilder` зовёт только для провайдеров,
 * встречающихся хотя бы в одном объявленном пресете: grok-only деплой иначе получал бы на каждом
 * старте предупреждение про чужой CLI.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class ClaudeBackendFactory(
    private val claudeProperties: ClaudeProperties,
    private val promptBuilder: ClaudePromptBuilder,
    private val imageStager: ClaudeImageStager,
    private val invoker: ClaudeInvoker,
    private val exceptionMapper: ClaudeExceptionMapper,
) : VisionBackendFactory {
    override val providerId: String = "claude"

    /** Токен и наличие CLI приходят из окружения процесса — осматриваем один раз. */
    private val inspected: VisionBackendFactory.Availability by lazy { inspectEnvironment() }

    override fun availability(): VisionBackendFactory.Availability = inspected

    /**
     * `ANTHROPIC_MODEL` вытесняет объявленную модель на уровне процесса, поэтому два claude-пресета
     * с разными `model` уйдут в один и тот же запрос; экран обязан показывать то, что уйдёт.
     */
    override fun effectiveModel(preset: DescriptionProperties.Preset): String =
        claudeProperties.anthropic.modelOverride.ifBlank { preset.model }

    /** Один токен обслуживает все модели Claude, поэтому область у всех пресетов общая. */
    override fun authScopeId(preset: DescriptionProperties.Preset): String = providerId

    override fun create(preset: DescriptionProperties.Preset): VisionBackend =
        ClaudeBackend(
            model = preset.model,
            // Через authScopeId(preset), а не providerId напрямую: область считает ровно одно
            // место, и строка на backend-е совпадает с той, что каталог кладёт в DescriptionPreset.
            authScopeId = authScopeId(preset),
            promptBuilder = promptBuilder,
            imageStager = imageStager,
            invoker = invoker,
            exceptionMapper = exceptionMapper,
        )

    /**
     * Отсутствие токена больше не роняет приложение: claude-пресет может стоять в конфиге стенда,
     * где живёт только Grok. Такой пресет помечается недоступным, а старт падает, только если
     * недоступны все.
     */
    private fun inspectEnvironment(): VisionBackendFactory.Availability {
        if (claudeProperties.oauthToken.isBlank() && claudeProperties.anthropic.authToken.isBlank()) {
            return VisionBackendFactory.Availability.Unavailable(UnavailableReason.NoToken)
        }
        warnIfCliMissing()
        return VisionBackendFactory.Availability.Available
    }

    /**
     * Пропавший CLI не делает провайдер непригодным: это отказ опциональной фичи, а не повод
     * останавливать наблюдение за камерами. Описания уйдут в fallback, о чём и говорит WARN.
     */
    private fun warnIfCliMissing() {
        // CLI detection зависит от cliPath: пустой → which claude; non-empty → проверяем executable напрямую.
        if (claudeProperties.cliPath.isBlank()) {
            if (!Query.isCliInstalled()) {
                logger.warn {
                    "Claude CLI not found in PATH (Query.isCliInstalled()==false); claude presets will return " +
                        "fallback. Check Dockerfile ENV PATH=... and claude install."
                }
            }
        } else if (!Files.isExecutable(Path.of(claudeProperties.cliPath))) {
            logger.warn {
                "Explicit claude.cli-path='${claudeProperties.cliPath}' not found or not executable; " +
                    "claude presets will return fallback."
            }
        }
    }
}

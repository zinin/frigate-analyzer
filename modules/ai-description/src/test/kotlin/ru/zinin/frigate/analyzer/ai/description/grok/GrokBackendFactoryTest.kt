package ru.zinin.frigate.analyzer.ai.description.grok

import io.mockk.mockk
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.ai.description.api.UnavailableReason
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import ru.zinin.frigate.analyzer.ai.description.core.VisionBackendFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GrokBackendFactoryTest {
    @TempDir
    lateinit var tempDir: Path

    private fun factory(home: Path = tempDir.resolve("home")): GrokBackendFactory =
        GrokBackendFactory(
            properties =
                GrokProperties(
                    cliPath = tempDir.resolve("missing-grok").toString(),
                    model = "grok-4.6",
                    effort = "low",
                    home = home.toString(),
                    workingDirectory = tempDir.resolve("cwd").toString(),
                    proxy = GrokProperties.ProxySection("", "", ""),
                ),
            promptFileWriter = mockk(relaxed = true),
            commandBuilder = mockk(relaxed = true),
            runner = mockk(relaxed = true),
            outputParser = mockk(relaxed = true),
            exceptionMapper = mockk(relaxed = true),
            guard = mockk(relaxed = true),
        )

    private fun preset(
        model: String = "grok-4.6",
        effort: String = "low",
    ) = DescriptionProperties.Preset(provider = "grok", model = model, effort = effort)

    /**
     * Конструктор строго пассивен: каталог Grok смонтирован и на claude-деплое, а его пресеты этого
     * провайдера не называют. Осмотр окружения делает только [VisionBackendFactory.availability],
     * которую билдер каталога зовёт лишь для объявленных провайдеров.
     */
    @Test
    fun `constructing the factory touches nothing on disk`() {
        factory()

        assertFalse(Files.exists(tempDir.resolve("home")))
        assertFalse(Files.exists(tempDir.resolve("cwd")))
    }

    @Test
    fun `availability creates the grok directories`() {
        assertIs<VisionBackendFactory.Availability.Available>(factory().availability())

        assertTrue(Files.isDirectory(tempDir.resolve("home")))
        assertTrue(Files.isDirectory(tempDir.resolve("cwd")))
    }

    /** Осмотр окружения стоит системных вызовов и предупреждений в логе — он делается один раз. */
    @Test
    fun `availability is computed once`() {
        val factory = factory()
        factory.availability()
        Files.delete(tempDir.resolve("home"))

        factory.availability()

        assertFalse(Files.exists(tempDir.resolve("home")), "the second call must not re-inspect the environment")
    }

    /**
     * Непригодный каталог помечает пресет, а не роняет контекст: исключение из фабрики пришло бы
     * раньше каталога и уронило бы claude-деплой с годными claude-пресетами из-за чужого тома.
     */
    @Test
    fun `a home path that is a file makes grok unavailable instead of failing the startup`() {
        val file = tempDir.resolve("home-file")
        file.writeText("x")

        val availability = factory(home = file).availability()

        val unavailable = assertIs<VisionBackendFactory.Availability.Unavailable>(availability)
        assertEquals(UnavailableReason.HomeUnwritable(file.toAbsolutePath().normalize().toString()), unavailable.reason)
    }

    @Test
    fun `grok is available even without auth json - BYOK models carry their own key`() {
        assertIs<VisionBackendFactory.Availability.Available>(factory().availability())
        assertFalse(Files.exists(tempDir.resolve("home/auth.json")))
    }

    @Test
    fun `the created backend carries the preset model and effort`() {
        val backend = factory().create(preset(model = "codex-luna", effort = "")) as GrokBackend

        assertEquals("grok", backend.providerId)
        assertEquals("codex-luna", backend.model)
        assertEquals("", backend.effort)
    }

    /**
     * Область учётных данных — пара «провайдер плюс модель»: два пресета на одной модели живут в
     * общем `auth.json`, а BYOK-модель ходит по собственному ключу из `config.toml`.
     */
    @Test
    fun `the auth scope is the provider and the model`() {
        val factory = factory()

        assertEquals("grok:grok-4.6", factory.authScopeId(preset()))
        assertEquals("grok:codex-luna", factory.authScopeId(preset(model = "codex-luna")))
    }

    @Test
    fun `the declared model is the effective one`() {
        assertEquals("codex-luna", factory().effectiveModel(preset(model = "codex-luna")))
    }
}

package ru.zinin.frigate.analyzer.ai.description.grok

import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import ru.zinin.frigate.analyzer.ai.description.grok.GrokUnifiedLogFixtures.OTHER
import ru.zinin.frigate.analyzer.ai.description.grok.GrokUnifiedLogFixtures.SID
import ru.zinin.frigate.analyzer.ai.description.grok.GrokUnifiedLogFixtures.inferenceDone
import ru.zinin.frigate.analyzer.ai.description.grok.GrokUnifiedLogFixtures.stripped
import ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GrokImageStripDetectorTest {
    @TempDir
    lateinit var tempDir: Path

    private val home: Path get() = tempDir.resolve("home")
    private val log: Path get() = home.resolve("logs/unified.jsonl")
    private val window: Int = GrokImageStripDetector.CAPTURE_TAIL_BYTES.toInt()

    private fun detector() =
        GrokImageStripDetector(
            GrokProperties(
                cliPath = "",
                model = "grok-4.6",
                effort = "low",
                home = home.toString(),
                workingDirectory = tempDir.resolve("cwd").toString(),
                proxy = GrokProperties.ProxySection("", "", ""),
            ),
            TestObjectMappers.internalMapper(),
        )

    private fun writeLog(content: String) {
        Files.createDirectories(log.parent)
        log.writeText(content)
    }

    private fun lines(vararg entries: String): String = entries.joinToString("") { "$it\n" }

    private fun check(sessionId: String? = SID): StripCheck = detector().let { it.inspect(it.capture(), sessionId) }

    /** ASCII-строка чужой сессии ровно на [bytes] байт вместе с переводом строки. */
    private fun paddingLine(bytes: Int): String {
        val head = "{\"sid\":\"$OTHER\",\"msg\":\"pad\",\"ctx\":{\"p\":\""
        val tail = "\"}}"
        return head + "x".repeat(bytes - 1 - head.length - tail.length) + tail + "\n"
    }

    @Test
    fun `a strip event of our session is reported with its count and reason`() {
        writeLog(lines(inferenceDone(SID), stripped(SID, 10)))

        assertEquals(StripCheck.Stripped(10, setOf("payload_heuristic")), check())
    }

    @Test
    fun `strip events of our session are summed`() {
        writeLog(lines(stripped(SID, 3), stripped(SID, 2, reason = "image_error")))

        assertEquals(StripCheck.Stripped(5, setOf("payload_heuristic", "image_error")), check())
    }

    @Test
    fun `a strip event of another session leaves ours clean`() {
        writeLog(lines(stripped(OTHER, 10), inferenceDone(SID)))

        assertEquals(StripCheck.Clean, check())
    }

    @Test
    fun `our session id inside another session's context does not make the line ours`() {
        writeLog(
            lines(
                """{"sid":"$OTHER","msg":"shell.turn.images_stripped","ctx":{"stripped":4,"parent_session_id":"$SID"}}""",
                inferenceDone(SID),
            ),
        )

        assertEquals(StripCheck.Clean, check())
    }

    @Test
    fun `no entries of our session is blind`() {
        writeLog(lines(inferenceDone(OTHER)))

        assertIs<StripCheck.Blind>(check())
    }

    @Test
    fun `a missing session id is blind`() {
        writeLog(lines(stripped(SID, 10)))

        assertIs<StripCheck.Blind>(check(sessionId = null))
    }

    @Test
    fun `a missing log file is blind and says so`() {
        val result = assertIs<StripCheck.Blind>(check())

        assertTrue(result.reason.contains("not found"), result.reason)
    }

    @Test
    fun `an unreadable log is blind and says so`() {
        // Каталог на месте файла: читать его нельзя. Файл внутри — чтобы размер каталога не был 0 (пустой каталог на btrfs).
        Files.createDirectories(log)
        Files.writeString(log.resolve("entry"), "x")
        val captured = detector().capture()
        val unavailable = assertIs<CapturedLog.Unavailable>(captured)
        assertTrue(unavailable.reason.startsWith("cannot read"), unavailable.reason)
        assertEquals(StripCheck.Blind(unavailable.reason), detector().inspect(captured, SID))
    }

    @Test
    fun `an unterminated last line and a garbage line are skipped`() {
        // Последнюю строку без перевода строки мог дописывать другой запуск — её не читаем.
        writeLog(lines("not json at all", inferenceDone(SID)) + stripped(SID, 10))

        assertEquals(StripCheck.Clean, check())
    }

    /** Review Focus 3. */
    @Test
    fun `a strip without an integer count still counts as one dropped frame`() {
        writeLog(
            lines(
                """{"sid":"$SID","msg":"shell.turn.images_stripped","ctx":{"reason":"payload_heuristic"}}""",
                """{"sid":"$SID","msg":"shell.turn.images_stripped","ctx":{"stripped":"many"}}""",
            ),
        )

        assertEquals(StripCheck.Stripped(2, setOf("payload_heuristic")), check())
    }

    @Test
    fun `a strip event with a zero or negative count still counts as one dropped frame`() {
        writeLog(lines(stripped(SID, 0), stripped(SID, -2)))

        assertEquals(StripCheck.Stripped(2, setOf("payload_heuristic")), check())
    }

    @Test
    fun `a large log is read from its tail`() {
        val filler = inferenceDone(OTHER) + "\n"
        writeLog(filler.repeat(window / filler.length + 100) + lines(stripped(SID, 10)))

        assertEquals(StripCheck.Stripped(10, setOf("payload_heuristic")), check())
    }

    /** Review Focus 1: окно начинается ровно с нашей строки — отбросить её как обрывок значило бы ослепнуть. */
    @Test
    fun `a line starting exactly at the window boundary is kept`() {
        val prefix = lines(inferenceDone(OTHER))
        val ours = lines(stripped(SID, 10))
        writeLog(prefix + ours + paddingLine(window - ours.length))

        assertEquals(window.toLong(), Files.size(log) - prefix.length)
        assertEquals(StripCheck.Stripped(10, setOf("payload_heuristic")), check())
    }

    /**
     * Review Focus 2: заметки о камерах в промпте судьи — кириллица, такой текст может оказаться в
     * логе grok, и граница окна рвёт двухбайтовый символ.
     */
    @Test
    fun `multi-byte text cut by the window boundary does not hide our lines`() {
        val cyrillic = lines("""{"sid":"$OTHER","msg":"title","ctx":{"t":"${"Двор дачи ".repeat(70_000)}"}}""")
        writeLog(cyrillic + lines(stripped(SID, 10)))

        assertTrue(Files.size(log) > window)
        assertEquals(StripCheck.Stripped(10, setOf("payload_heuristic")), check())
    }
}

package ru.zinin.frigate.analyzer.ai.description.grok

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GrokHomeSweeperTest {
    @TempDir
    lateinit var home: Path

    private fun sweeper() =
        GrokHomeSweeper(
            GrokProperties(
                cliPath = "",
                model = "grok-4.6",
                effort = "",
                home = home.toString(),
                workingDirectory = home.resolve("cwd").toString(),
                proxy = GrokProperties.ProxySection("", "", ""),
            ),
            GrokHomeGuard(),
        )

    @Test
    fun `removes session directories, the search index and log files, keeps credentials and config`() {
        val session = home.resolve("sessions/%2Ftmp%2Fcwd/01a06332-cee4-7a82-ac73-8556a6ea21c4").createDirectories()
        session.resolve("chat_history.jsonl").writeText("{}")
        session
            .resolve("compaction_checkpoints")
            .createDirectories()
            .resolve("c1.json")
            .writeText("{}")
        home.resolve("sessions/session_search.sqlite").writeText("db")
        home.resolve("sessions/session_search.sqlite-wal").writeText("wal")
        home
            .resolve("logs")
            .createDirectories()
            .resolve("unified.jsonl")
            .writeText("log")
        home
            .resolve("logs/mcp")
            .createDirectories()
            .resolve("x.log")
            .writeText("mcp")
        home.resolve("auth.json").writeText("secret")
        home.resolve("config.toml").writeText("[models]")

        val removed = runBlocking { sweeper().sweep() }

        assertEquals(4, removed, "one cwd dir, two index files, one log file")
        assertTrue(home.resolve("sessions").exists())
        assertTrue(home.resolve("sessions").listDirectoryEntries().isEmpty())
        assertTrue(home.resolve("logs/mcp/x.log").exists(), "subdirectories under logs/ are left alone")
        assertTrue(home.resolve("auth.json").exists())
        assertTrue(home.resolve("config.toml").exists())
    }

    @Test
    fun `missing directories are not an error`() {
        assertEquals(0, runBlocking { sweeper().sweep() })
        assertTrue(Files.notExists(home.resolve("sessions")))
    }
}

# План реализации: экспорт видео в лимит Telegram

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Любой экспорт видео из Telegram-бота, «Оригинал» и «С объектами», укладывается в лимит загрузки Telegram, а если не укладывается даже после повтора, пользователь видит сообщение о размере, а не «Файлы записи недоступны».

**Architecture:** В `core` появляется пакет `core.video`: `FfmpegProcessRunner` запускает ffmpeg и ffprobe, `VideoProbe` читает параметры файла, `CompressionPlanner` считает разрешение и потолок битрейта из бюджета, `TelegramVideoFitter` укладывает файл в лимит с одной проверкой и одним повтором. `VideoExportServiceImpl` вызывает fitter после склейки и после аннотации. В `telegram` добавляются стадия `COMPRESSING_RESULT` и маппинг `VideoTooLargeException` на собственные тексты.

**Tech Stack:** Kotlin 2.4.10, Spring Boot 4.1.0, kotlinx-coroutines, Jackson 3 (`tools.jackson`), ffmpeg и ffprobe как внешние процессы, JUnit 5, mockk 1.14, kotlinx-coroutines-test, ktlint.

**Spec:** `docs/superpowers/specs/2026-09-02-export-video-size-limit-design.md`

## Global Constraints

- Лимиты (`FitLimits.TELEGRAM`): порог сжатия и бюджет `45L * 1024 * 1024` (47 185 920 байт); лимит приёмки `50_000_000L`.
- Планировщик: высоты `1080, 720, 540`; минимум бит на пиксель `0.1`; CRF `23`; preset `fast`; звук `64` кбит/с; резерв контейнера `0.03`; множитель повтора `0.9`.
- Таймауты: ffmpeg `300` с (`VideoMergeHelper.FFMPEG_TIMEOUT_SECONDS`, без изменений), ffprobe `30` с. Внешние таймауты экспорта (5 и 50 минут) не меняются.
- Команда сжатия: `ffmpeg -hide_banner -nostdin -i <in> [-vf scale=-2:<h>] -c:v libx264 -preset <preset> -crf <crf> -maxrate <k>k -bufsize <2k>k -pix_fmt yuv420p (-c:a aac -b:a 64k | -an) -movflags +faststart -y <out>`. `-nostdin` добавляется и в команду склейки.
- Переменные окружения: `FFPROBE_PATH` (`/usr/bin/ffprobe`), `EXPORT_COMPRESS_PRESET` (`fast`), `EXPORT_COMPRESS_CRF` (`23`), `EXPORT_COMPRESS_MIN_BITS_PER_PIXEL` (`0.1`).
- Тексты i18n (дословно):
  - `quickexport.error.too.large` ru: `Видео не помещается в лимит Telegram 50 МБ даже после сжатия. Попробуйте /export с меньшим диапазоном.` en: `The video exceeds Telegram's 50 MB limit even after compression. Try /export with a shorter range.`
  - `export.error.too.large` ru: `Видео не помещается в лимит Telegram 50 МБ даже после сжатия. Попробуйте меньший диапазон.` en: `The video exceeds Telegram's 50 MB limit even after compression. Try a shorter range.`
  - `export.progress.compressing.result` ru: `Сжатие результата` en: `Compressing result`
  - `quickexport.progress.compressing.result` ru: `⚙️ Сжатие результата...` en: `⚙️ Compressing result...` (в properties-файлах эмодзи пишется как `\u2699\uFE0F`, как у соседних ключей)
- Сборка и тесты только через агента `claude-forge:build-runner` (модель opus, не haiku). Команда всегда с `JAVA_HOME=/usr/lib/jvm/zulu25` и флагами `--rerun-tasks --no-watch-fs --console=plain` (репозиторий на VMware-шаре, up-to-date check Gradle врёт). При ошибках ktlint: `JAVA_HOME=/usr/lib/jvm/zulu25 ./gradlew ktlintFormat --no-watch-fs --console=plain`, затем повтор.
- Git: только `git add <явные пути>`; коммит только `git commit -F "$SCRATCH/commit.txt" -- <явные пути>`, где `$SCRATCH` — scratchpad-каталог текущей сессии (указан в системном промпте). Сообщение коммита заканчивается строкой `Claude-Session: https://claude.ai/code/session_01EytYTJW3JyptT4D4V6aEj6`. Файл `docs/deep-research-review-report.md` лежит в индексе намеренно, в коммиты не включать. Никогда `git add -A`, `git add .`, голый `git commit`.
- Ветка `fix/export-video-size-limit` уже создана. Перед PR удалить `docs/superpowers/` из ветки (`git rm -r docs/superpowers`) отдельным коммитом.
- Стиль: ktlint_official (trailing commas, один аргумент на строку в многострочных вызовах, один параметр на строку в многострочных сигнатурах). Комментарии в коде на английском, как в соседних файлах `core`.
- Никаких обращений к проду в ходе реализации.

## Как выполнять шаги «Run»

Каждая команда `./gradlew` передаётся агенту `claude-forge:build-runner` дословно. Для нового тестового класса шаг «убедиться, что тест падает» заканчивается ошибкой компиляции тестового набора (`Unresolved reference`), это ожидаемое падение. Для изменения существующего кода ожидается падение конкретного assert, оно указано в шаге.

## Структура файлов

| Файл | Ответственность |
|---|---|
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/FfmpegProcessRunner.kt` (новый) | Запуск внешнего процесса с таймаутом и хвостом вывода |
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/VideoInfo.kt` (новый) | Результат ffprobe |
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/CompressionPlan.kt` (новый) | Параметры одного перекодирования |
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/CompressionPlanner.kt` (новый) | Бюджет, выбор высоты, повтор; без ввода-вывода |
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/VideoProbe.kt` (новый) | ffprobe, разбор JSON |
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/FitLimits.kt` (новый) | Порог, лимит и константа `TELEGRAM` |
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/TelegramVideoFitter.kt` (новый) | Цикл «probe, план, кодирование, проверка, повтор», владение временными файлами |
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/VideoMergeHelper.kt` (изменить) | Склейка и команда сжатия из плана; запуск через runner |
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImpl.kt` (изменить) | Оркестрация: склейка, fit, аннотация, fit |
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/ExportProperties.kt` (новый) | `application.export.compress` |
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/ApplicationProperties.kt` (изменить) | `ffprobePath` |
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/FrigateAnalyzerApplication.kt` (изменить) | Регистрация `ExportProperties` |
| `modules/core/src/main/resources/application.yaml` (изменить) | `ffprobe-path`, блок `export.compress` |
| `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/exception/VideoTooLargeException.kt` (новый) | Исключение «не помещается» |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/model/VideoExportProgress.kt` (изменить) | Стадия `COMPRESSING_RESULT` |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportModels.kt` (изменить) | Отрисовка новой стадии |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportExecutor.kt` (изменить) | Флаг новой стадии, маппинг ошибки |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt` (изменить) | Текст кнопки новой стадии, маппинг ошибки |
| `modules/telegram/src/main/resources/messages_ru.properties`, `messages_en.properties` (изменить) | Четыре новых ключа |
| `.github/workflows/ci.yml`, `.github/workflows/docker-publish.yml` (изменить) | Шаг установки ffmpeg |
| `.claude/rules/configuration.md`, `.claude/rules/telegram-export.md` (изменить) | Документация |
| Тесты: `core/video/FfmpegProcessRunnerTest.kt`, `core/video/CompressionPlannerTest.kt`, `core/video/VideoProbeTest.kt`, `core/video/TelegramVideoFitterTest.kt`, `core/video/FfmpegCompressionIntegrationTest.kt`, `core/helper/VideoMergeHelperTest.kt` (новые); `core/service/VideoExportServiceImplTest.kt`, `telegram/.../export/ExportModelsTest.kt`, `telegram/.../export/ExportExecutorTest.kt`, `telegram/.../quickexport/QuickExportHandlerTest.kt` (изменить) | |

---

### Task 1: FfmpegProcessRunner и перевод склейки на него

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/FfmpegProcessRunner.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/VideoMergeHelper.kt`
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/video/FfmpegProcessRunnerTest.kt`
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/helper/VideoMergeHelperTest.kt`

**Interfaces:**
- Consumes: `TempFileHelper.createTempFile(prefix, suffix): Path`, `TempFileHelper.deleteIfExists(path): Boolean`, `ApplicationProperties.ffmpegPath: Path`.
- Produces: `class FfmpegProcessRunner { suspend fun run(command: List<String>, timeout: Duration): List<String> }` (бросает `RuntimeException` при ненулевом коде или таймауте); `VideoMergeHelper(applicationProperties, tempFileHelper, processRunner: FfmpegProcessRunner)`; `internal fun VideoMergeHelper.buildMergeCommand(concatFile: Path, outputFile: Path): List<String>`. Старый `compressVideo(inputPath: Path): Path` остаётся до Task 7.

- [ ] **Step 1: Написать падающий тест runner**

Создать `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/video/FfmpegProcessRunnerTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.video

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FfmpegProcessRunnerTest {
    private val runner = FfmpegProcessRunner()

    @Test
    fun `returns merged stdout and stderr lines on success`() =
        runTest {
            val output =
                runner.run(
                    listOf("/bin/sh", "-c", "echo first; echo second >&2; echo third"),
                    Duration.ofSeconds(10),
                )

            assertEquals(listOf("first", "second", "third"), output)
        }

    @Test
    fun `throws with the output tail when the exit code is not zero`() =
        runTest {
            val exception =
                assertThrows<RuntimeException> {
                    runner.run(listOf("/bin/sh", "-c", "echo boom; exit 3"), Duration.ofSeconds(10))
                }

            assertTrue(exception.message!!.contains("sh exited with code 3"), exception.message)
            assertTrue(exception.message!!.contains("boom"), exception.message)
        }

    @Test
    fun `kills the process and throws when the timeout expires`() =
        runTest {
            val startedAt = System.nanoTime()

            val exception =
                assertThrows<RuntimeException> {
                    runner.run(listOf("/bin/sh", "-c", "exec sleep 30"), Duration.ofMillis(500))
                }

            val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)
            assertTrue(exception.message!!.contains("sh timed out after 500 ms"), exception.message)
            assertTrue(elapsed < Duration.ofSeconds(10), "runner waited $elapsed instead of killing the process")
        }

    @Test
    fun `rejects an empty command`() =
        runTest {
            assertThrows<IllegalArgumentException> { runner.run(emptyList(), Duration.ofSeconds(1)) }
        }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run (через build-runner):
```
JAVA_HOME=/usr/lib/jvm/zulu25 ./gradlew :frigate-analyzer-core:test --tests 'ru.zinin.frigate.analyzer.core.video.FfmpegProcessRunnerTest' --rerun-tasks --no-watch-fs --console=plain
```
Expected: FAIL, ошибка компиляции `Unresolved reference 'FfmpegProcessRunner'`.

- [ ] **Step 3: Написать FfmpegProcessRunner**

Создать `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/FfmpegProcessRunner.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.video

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private val logger = KotlinLogging.logger {}

/**
 * Runs an external ffmpeg or ffprobe command and waits for it with a timeout.
 *
 * stdout and stderr are merged and drained in a daemon thread so that `waitFor(timeout)` never
 * blocks on a full pipe. Only the first [MAX_OUTPUT_LINES] lines are kept: ffprobe JSON is far
 * below that, and ffmpeg output is only needed as an error tail.
 */
@Component
class FfmpegProcessRunner {
    /**
     * @return captured output lines, stdout and stderr together, at most [MAX_OUTPUT_LINES].
     * @throws RuntimeException when the exit code is not zero (the message carries the last
     *   [ERROR_TAIL_LINES] lines of output) or when the process does not finish within [timeout]
     *   (the process is killed first).
     */
    suspend fun run(
        command: List<String>,
        timeout: Duration,
    ): List<String> {
        require(command.isNotEmpty()) { "command must not be empty" }
        val tool = Path.of(command.first()).fileName.toString()
        logger.debug { "Running $tool: ${command.joinToString(" ")}" }

        return withContext(Dispatchers.IO) {
            val process =
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()

            val outputLines = mutableListOf<String>()
            val outputThread =
                thread(isDaemon = true) {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            logger.trace { "$tool: $line" }
                            synchronized(outputLines) {
                                if (outputLines.size < MAX_OUTPUT_LINES) {
                                    outputLines.add(line)
                                }
                            }
                        }
                    }
                }

            val completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                outputThread.join(OUTPUT_THREAD_JOIN_TIMEOUT_MS)
                throw RuntimeException("$tool timed out after ${timeout.toMillis()} ms")
            }
            outputThread.join(OUTPUT_THREAD_JOIN_TIMEOUT_MS)

            val captured = synchronized(outputLines) { outputLines.toList() }
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                val lastLines = captured.takeLast(ERROR_TAIL_LINES)
                val errorDetail = if (lastLines.isNotEmpty()) ": ${lastLines.joinToString("\n")}" else ""
                throw RuntimeException("$tool exited with code $exitCode$errorDetail")
            }
            captured
        }
    }

    companion object {
        private const val MAX_OUTPUT_LINES = 500
        private const val ERROR_TAIL_LINES = 20
        private const val OUTPUT_THREAD_JOIN_TIMEOUT_MS = 5000L
    }
}
```

- [ ] **Step 4: Убедиться, что тест runner проходит**

Run (через build-runner): та же команда, что в Step 2.
Expected: PASS, 4 теста.

- [ ] **Step 5: Написать падающий тест склейки**

Создать `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/helper/VideoMergeHelperTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.helper

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import ru.zinin.frigate.analyzer.core.video.FfmpegProcessRunner
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoMergeHelperTest {
    @TempDir
    lateinit var tempDir: Path

    private val runner = mockk<FfmpegProcessRunner>()
    private lateinit var helper: VideoMergeHelper

    @BeforeEach
    fun setUp() {
        val properties =
            ApplicationProperties(
                tempFolder = tempDir,
                ffmpegPath = Path.of("/usr/bin/ffmpeg"),
                connectionTimeout = Duration.ofSeconds(5),
                readTimeout = Duration.ofSeconds(5),
                writeTimeout = Duration.ofSeconds(5),
                responseTimeout = Duration.ofSeconds(5),
            )
        val tempFileHelper =
            TempFileHelper(properties, Clock.fixed(Instant.parse("2026-09-02T12:00:00Z"), ZoneOffset.UTC))
        tempFileHelper.init()
        helper = VideoMergeHelper(properties, tempFileHelper, runner)
    }

    private fun sourceFile(name: String): Path = tempDir.resolve(name).also { Files.write(it, byteArrayOf(1, 2, 3)) }

    @Test
    fun `mergeVideos writes an escaped concat list and runs ffmpeg with stream copy`() =
        runTest {
            val first = sourceFile("a.mp4")
            val second = sourceFile("it's.mp4")
            val commands = mutableListOf<List<String>>()
            var concatLines: List<String>? = null
            coEvery { runner.run(capture(commands), Duration.ofSeconds(300)) } coAnswers {
                val command = firstArg<List<String>>()
                concatLines = Files.readAllLines(Path.of(command[command.indexOf("-i") + 1]))
                emptyList()
            }

            val output = helper.mergeVideos(listOf(first, second))

            val command = commands.single()
            val concatPath = Path.of(command[command.indexOf("-i") + 1])
            assertEquals(
                listOf(
                    "/usr/bin/ffmpeg",
                    "-hide_banner",
                    "-nostdin",
                    "-f",
                    "concat",
                    "-safe",
                    "0",
                    "-i",
                    concatPath.toString(),
                    "-c",
                    "copy",
                    "-y",
                    output.toString(),
                ),
                command,
            )
            assertEquals(
                listOf("file '$first'", "file '${tempDir.resolve("it")}'\\''s.mp4'"),
                concatLines,
            )
            assertTrue(output.startsWith(tempDir))
            assertTrue(output.fileName.toString().contains("merged-"))
            assertFalse(Files.exists(concatPath), "concat list must be deleted after the merge")
        }

    @Test
    fun `mergeVideos with a single file copies it without ffmpeg`() =
        runTest {
            val only = sourceFile("only.mp4")

            val output = helper.mergeVideos(listOf(only))

            assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(output))
            assertTrue(output.startsWith(tempDir))
            coVerify(exactly = 0) { runner.run(any(), any()) }
        }

    @Test
    fun `mergeVideos deletes the output and the concat list when ffmpeg fails`() =
        runTest {
            val first = sourceFile("a.mp4")
            val second = sourceFile("b.mp4")
            val commands = mutableListOf<List<String>>()
            coEvery { runner.run(capture(commands), any()) } throws RuntimeException("ffmpeg exited with code 1")

            assertThrows<RuntimeException> { helper.mergeVideos(listOf(first, second)) }

            val command = commands.single()
            assertFalse(Files.exists(Path.of(command.last())), "merged output must be deleted")
            assertFalse(Files.exists(Path.of(command[command.indexOf("-i") + 1])), "concat list must be deleted")
        }

    @Test
    fun `mergeVideos rejects an empty list`() =
        runTest {
            assertThrows<IllegalArgumentException> { helper.mergeVideos(emptyList()) }
        }
}
```

- [ ] **Step 6: Убедиться, что тест склейки падает**

Run (через build-runner):
```
JAVA_HOME=/usr/lib/jvm/zulu25 ./gradlew :frigate-analyzer-core:test --tests 'ru.zinin.frigate.analyzer.core.helper.VideoMergeHelperTest' --rerun-tasks --no-watch-fs --console=plain
```
Expected: FAIL, ошибка компиляции: конструктор `VideoMergeHelper` не принимает третий аргумент.

- [ ] **Step 7: Перевести VideoMergeHelper на runner**

Заменить содержимое `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/VideoMergeHelper.kt` целиком:

```kotlin
package ru.zinin.frigate.analyzer.core.helper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import ru.zinin.frigate.analyzer.core.video.FfmpegProcessRunner
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

@Component
class VideoMergeHelper(
    private val applicationProperties: ApplicationProperties,
    private val tempFileHelper: TempFileHelper,
    private val processRunner: FfmpegProcessRunner,
) {
    suspend fun mergeVideos(filePaths: List<Path>): Path {
        require(filePaths.isNotEmpty()) { "filePaths must not be empty" }

        if (filePaths.size == 1) {
            return copyToTemp(filePaths.first())
        }

        val concatFile = tempFileHelper.createTempFile("concat-", ".txt")
        try {
            withContext(Dispatchers.IO) {
                Files.write(
                    concatFile,
                    filePaths.map { "file '${escapePath(it)}'" },
                )
            }

            val outputFile = tempFileHelper.createTempFile("merged-", ".mp4")
            try {
                runFfmpeg(buildMergeCommand(concatFile, outputFile))
                return outputFile
            } catch (e: Exception) {
                tempFileHelper.deleteIfExists(outputFile)
                throw e
            }
        } finally {
            tempFileHelper.deleteIfExists(concatFile)
        }
    }

    suspend fun compressVideo(inputPath: Path): Path {
        val outputFile = tempFileHelper.createTempFile("compressed-", ".mp4")
        try {
            runFfmpeg(
                listOf(
                    applicationProperties.ffmpegPath.toString(),
                    "-hide_banner",
                    "-i",
                    inputPath.toString(),
                    "-vcodec",
                    "libx264",
                    "-crf",
                    "28",
                    "-preset",
                    "fast",
                    "-acodec",
                    "aac",
                    "-y",
                    outputFile.toString(),
                ),
            )
            return outputFile
        } catch (e: Exception) {
            tempFileHelper.deleteIfExists(outputFile)
            throw e
        }
    }

    internal fun buildMergeCommand(
        concatFile: Path,
        outputFile: Path,
    ): List<String> =
        listOf(
            applicationProperties.ffmpegPath.toString(),
            "-hide_banner",
            "-nostdin",
            "-f",
            "concat",
            "-safe",
            "0",
            "-i",
            concatFile.toString(),
            "-c",
            "copy",
            "-y",
            outputFile.toString(),
        )

    private suspend fun copyToTemp(source: Path): Path {
        val outputFile = tempFileHelper.createTempFile("merged-", ".mp4")
        try {
            withContext(Dispatchers.IO) {
                Files.copy(source, outputFile, StandardCopyOption.REPLACE_EXISTING)
            }
            return outputFile
        } catch (e: Exception) {
            tempFileHelper.deleteIfExists(outputFile)
            throw e
        }
    }

    private suspend fun runFfmpeg(command: List<String>) {
        processRunner.run(command, Duration.ofSeconds(FFMPEG_TIMEOUT_SECONDS))
    }

    private fun escapePath(path: Path): String = path.toAbsolutePath().toString().replace("'", "'\\''")

    companion object {
        const val MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024
        const val COMPRESS_THRESHOLD_BYTES = 45L * 1024 * 1024
        const val FFMPEG_TIMEOUT_SECONDS = 300L
    }
}
```

- [ ] **Step 8: Убедиться, что оба теста и старые тесты сервиса проходят**

Run (через build-runner):
```
JAVA_HOME=/usr/lib/jvm/zulu25 ./gradlew :frigate-analyzer-core:test --tests 'ru.zinin.frigate.analyzer.core.helper.VideoMergeHelperTest' --tests 'ru.zinin.frigate.analyzer.core.video.FfmpegProcessRunnerTest' --tests 'ru.zinin.frigate.analyzer.core.service.VideoExportServiceImplTest' --rerun-tasks --no-watch-fs --console=plain
```
Expected: PASS. `VideoExportServiceImplTest` мокает `VideoMergeHelper` целиком и от конструктора не зависит.

- [ ] **Step 9: Commit**

```bash
cat > "$SCRATCH/commit.txt" <<'MSG'
refactor(core): extract FfmpegProcessRunner from VideoMergeHelper

The process launch with its timeout and output tail moves into a reusable
runner so that ffprobe and the size-fitting encode can share it. The merge
command gains -nostdin; behaviour is otherwise unchanged and now covered
by tests for the concat list, the single-file copy and cleanup on failure.

Claude-Session: https://claude.ai/code/session_01EytYTJW3JyptT4D4V6aEj6
MSG
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/FfmpegProcessRunner.kt modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/VideoMergeHelper.kt modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/video/FfmpegProcessRunnerTest.kt modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/helper/VideoMergeHelperTest.kt
git commit -F "$SCRATCH/commit.txt" -- modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/FfmpegProcessRunner.kt modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/VideoMergeHelper.kt modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/video/FfmpegProcessRunnerTest.kt modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/helper/VideoMergeHelperTest.kt
```

---

### Task 2: CompressionPlanner, ExportProperties, VideoTooLargeException

**Files:**
- Create: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/exception/VideoTooLargeException.kt`
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/ExportProperties.kt`
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/VideoInfo.kt`
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/CompressionPlan.kt`
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/CompressionPlanner.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/FrigateAnalyzerApplication.kt` (список `@EnableConfigurationProperties`)
- Modify: `modules/core/src/main/resources/application.yaml` (блок `export`)
- Modify: `.claude/rules/configuration.md` (раздел «Video Export»)
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/video/CompressionPlannerTest.kt`

**Interfaces:**
- Consumes: ничего из предыдущих задач.
- Produces:
  - `class VideoTooLargeException(message: String, cause: Throwable? = null) : RuntimeException`
  - `data class VideoInfo(durationSeconds: Double, width: Int, height: Int, fps: Double, hasAudio: Boolean)`
  - `data class CompressionPlan(scaleHeight: Int?, videoMaxrateKbps: Int, audioBitrateKbps: Int?, crf: Int, preset: String)`
  - `class CompressionPlanner(exportProperties: ExportProperties)` с `fun plan(info: VideoInfo, targetBytes: Long): CompressionPlan`, `fun shrink(previous: CompressionPlan, info: VideoInfo, actualBytes: Long, targetBytes: Long): CompressionPlan`, `internal fun scaledWidth(info: VideoInfo, height: Int): Int`
  - `data class ExportProperties(compress: CompressProperties = CompressProperties())`, `data class CompressProperties(preset: String = "fast", crf: Int = 23, minBitsPerPixel: Double = 0.1)`

- [ ] **Step 1: Написать падающий тест планировщика**

Создать `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/video/CompressionPlannerTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.video

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.zinin.frigate.analyzer.core.config.properties.ExportProperties
import ru.zinin.frigate.analyzer.model.exception.VideoTooLargeException
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompressionPlannerTest {
    private val planner = CompressionPlanner(ExportProperties())

    private fun info(
        width: Int,
        height: Int,
        durationSeconds: Double,
        hasAudio: Boolean = false,
    ) = VideoInfo(
        durationSeconds = durationSeconds,
        width = width,
        height = height,
        fps = 12.5,
        hasAudio = hasAudio,
    )

    @Test
    fun `two minutes of a 4 by 3 5MP camera go to 1080p at the full budget`() {
        val plan = planner.plan(info(2560, 1920, 120.0), TARGET)

        assertEquals(
            CompressionPlan(scaleHeight = 1080, videoMaxrateKbps = 3051, audioBitrateKbps = null, crf = 23, preset = "fast"),
            plan,
        )
    }

    @Test
    fun `two minutes of a 16 by 9 5MP camera go to 1080p`() {
        val plan = planner.plan(info(2880, 1620, 120.0), TARGET)

        assertEquals(1080, plan.scaleHeight)
        assertEquals(3051, plan.videoMaxrateKbps)
    }

    @Test
    fun `five minutes of a 4 by 3 5MP camera go to 720p`() {
        val plan = planner.plan(info(2560, 1920, 300.0), TARGET)

        assertEquals(720, plan.scaleHeight)
        assertEquals(1220, plan.videoMaxrateKbps)
    }

    @Test
    fun `five minutes of a 16 by 9 5MP camera go to 720p`() {
        val plan = planner.plan(info(2880, 1620, 300.0), TARGET)

        assertEquals(720, plan.scaleHeight)
        assertEquals(1220, plan.videoMaxrateKbps)
    }

    @Test
    fun `audio takes 64 kbps out of the video budget and stays in the plan`() {
        val plan = planner.plan(info(2560, 1920, 120.0, hasAudio = true), TARGET)

        assertEquals(2987, plan.videoMaxrateKbps)
        assertEquals(64, plan.audioBitrateKbps)
        assertEquals(1080, plan.scaleHeight)
    }

    @Test
    fun `a 720p source that fits the budget keeps its size without a scale filter`() {
        val plan = planner.plan(info(1280, 720, 120.0), TARGET)

        assertNull(plan.scaleHeight)
    }

    @Test
    fun `a 720p source over a long window falls to the smallest candidate`() {
        val plan = planner.plan(info(1280, 720, 600.0), TARGET)

        assertEquals(540, plan.scaleHeight)
        assertEquals(610, plan.videoMaxrateKbps)
    }

    @Test
    fun `a source below every candidate height is never upscaled`() {
        val plan = planner.plan(info(640, 480, 120.0), TARGET)

        assertNull(plan.scaleHeight)
        assertEquals(3051, plan.videoMaxrateKbps)
    }

    @Test
    fun `scaled width follows the source aspect and is even`() {
        assertEquals(1440, planner.scaledWidth(info(2560, 1920, 1.0), 1080))
        assertEquals(1920, planner.scaledWidth(info(2880, 1620, 1.0), 1080))
        assertEquals(960, planner.scaledWidth(info(2560, 1920, 1.0), 720))
        assertEquals(1280, planner.scaledWidth(info(1366, 768, 1.0), 720))
        assertEquals(500, planner.scaledWidth(info(1001, 1000, 1.0), 500))
    }

    @Test
    fun `plan rejects a non-positive duration`() {
        assertThrows<IllegalArgumentException> { planner.plan(info(2560, 1920, 0.0), TARGET) }
    }

    @Test
    fun `plan rejects a non-positive target`() {
        assertThrows<IllegalArgumentException> { planner.plan(info(2560, 1920, 120.0), 0L) }
    }

    @Test
    fun `plan reports VideoTooLargeException when audio alone exhausts the budget`() {
        assertThrows<VideoTooLargeException> { planner.plan(info(2560, 1920, 1_000_000.0, hasAudio = true), TARGET) }
    }

    @Test
    fun `shrink lowers the cap by the overshoot ratio and 10 percent and keeps the height`() {
        val previous = planner.plan(info(2560, 1920, 120.0), TARGET)

        val retry = planner.shrink(previous, info(2560, 1920, 120.0), actualBytes = 52_000_000L, targetBytes = TARGET)

        assertEquals(2491, retry.videoMaxrateKbps)
        assertNull(retry.scaleHeight, "1080p input re-encoded at 1080p needs no scale filter")
        assertEquals(23, retry.crf)
        assertEquals("fast", retry.preset)
    }

    @Test
    fun `shrink steps down to a smaller height when the new cap is too thin`() {
        val previous = planner.plan(info(2560, 1920, 300.0), TARGET)

        val retry = planner.shrink(previous, info(2560, 1920, 300.0), actualBytes = 60_000_000L, targetBytes = TARGET)

        assertEquals(863, retry.videoMaxrateKbps)
        assertEquals(540, retry.scaleHeight)
    }

    @Test
    fun `shrink never goes above the height of the first result`() {
        val previous = CompressionPlan(scaleHeight = 540, videoMaxrateKbps = 1000, audioBitrateKbps = null, crf = 23, preset = "fast")

        val retry = planner.shrink(previous, info(2560, 1920, 120.0), actualBytes = 47_500_000L, targetBytes = TARGET)

        assertNull(retry.scaleHeight, "540p input stays 540p")
        assertEquals(894, retry.videoMaxrateKbps)
    }

    @Test
    fun `shrink keeps the audio bitrate of the first plan`() {
        val previous = planner.plan(info(2560, 1920, 120.0, hasAudio = true), TARGET)

        val retry = planner.shrink(previous, info(2560, 1920, 120.0, hasAudio = true), actualBytes = 52_000_000L, targetBytes = TARGET)

        assertEquals(64, retry.audioBitrateKbps)
    }

    @Test
    fun `shrink rejects a non-positive actual size`() {
        val previous = planner.plan(info(2560, 1920, 120.0), TARGET)

        assertThrows<IllegalArgumentException> { planner.shrink(previous, info(2560, 1920, 120.0), 0L, TARGET) }
    }

    companion object {
        private const val TARGET = 45L * 1024 * 1024
    }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run (через build-runner):
```
JAVA_HOME=/usr/lib/jvm/zulu25 ./gradlew :frigate-analyzer-core:test --tests 'ru.zinin.frigate.analyzer.core.video.CompressionPlannerTest' --rerun-tasks --no-watch-fs --console=plain
```
Expected: FAIL, ошибка компиляции `Unresolved reference 'CompressionPlanner'`.

- [ ] **Step 3: Создать исключение, модели и настройки**

`modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/exception/VideoTooLargeException.kt`:

```kotlin
package ru.zinin.frigate.analyzer.model.exception

/**
 * The exported video does not fit into the Telegram upload limit, even after re-encoding.
 */
class VideoTooLargeException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
```

`modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/ExportProperties.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.config.properties

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@ConfigurationProperties(prefix = "application.export")
@Validated
data class ExportProperties(
    @field:Valid
    val compress: CompressProperties = CompressProperties(),
)

/** Tunables of the budget-driven re-encode that fits an export into the Telegram size limit. */
data class CompressProperties(
    /** libx264 preset: speed versus compression on the host CPU. */
    @field:NotBlank
    val preset: String = "fast",
    /** libx264 quality target; the bitrate cap derived from the budget still applies. */
    @field:Min(0)
    @field:Max(51)
    val crf: Int = 23,
    /** Smallest bits-per-pixel a candidate height may have before the next smaller one is tried. */
    @field:Positive
    val minBitsPerPixel: Double = 0.1,
)
```

`modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/VideoInfo.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.video

/** What ffprobe reports about a video file; the input of [CompressionPlanner]. */
data class VideoInfo(
    val durationSeconds: Double,
    val width: Int,
    val height: Int,
    val fps: Double,
    val hasAudio: Boolean,
)
```

`modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/CompressionPlan.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.video

/**
 * ffmpeg parameters for one re-encode.
 *
 * @property scaleHeight target frame height for `scale=-2:<h>`, or null to keep the source size
 * @property videoMaxrateKbps `-maxrate` in kbit/s; `-bufsize` is twice this value
 * @property audioBitrateKbps AAC bitrate in kbit/s, or null to drop audio (`-an`)
 */
data class CompressionPlan(
    val scaleHeight: Int?,
    val videoMaxrateKbps: Int,
    val audioBitrateKbps: Int?,
    val crf: Int,
    val preset: String,
)
```

- [ ] **Step 4: Написать CompressionPlanner**

`modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/CompressionPlanner.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.video

import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.CompressProperties
import ru.zinin.frigate.analyzer.core.config.properties.ExportProperties
import ru.zinin.frigate.analyzer.model.exception.VideoTooLargeException
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Chooses how to re-encode a video so that it fits into a byte budget.
 *
 * Pure arithmetic, no I/O: the caller probes the source ([VideoProbe]) and runs ffmpeg
 * (`VideoMergeHelper.compressVideo`).
 *
 * Budget: `targetBytes * 8 / 1000 / duration` kbit/s, minus [CONTAINER_RESERVE], minus the audio
 * track when there is one. Resolution: the largest of [HEIGHTS] (never above the source) whose
 * bits-per-pixel at that budget stays at or above [CompressProperties.minBitsPerPixel]; if none
 * qualifies, the smallest candidate.
 */
@Component
class CompressionPlanner(
    exportProperties: ExportProperties,
) {
    private val settings: CompressProperties = exportProperties.compress

    /** Plan for the first encode of [info] into [targetBytes]. */
    fun plan(
        info: VideoInfo,
        targetBytes: Long,
    ): CompressionPlan {
        require(info.durationSeconds > 0) { "durationSeconds must be positive, got ${info.durationSeconds}" }
        require(targetBytes > 0) { "targetBytes must be positive, got $targetBytes" }

        val totalKbps = targetBytes.toDouble() * BITS_PER_BYTE / BITS_PER_KBIT / info.durationSeconds
        val usableKbps = totalKbps * (1 - CONTAINER_RESERVE)
        val audioKbps = if (info.hasAudio) AUDIO_BITRATE_KBPS else null
        val videoKbps = floor(usableKbps - (audioKbps ?: 0)).toInt()
        if (videoKbps <= 0) {
            throw VideoTooLargeException(
                "No bitrate budget for video: ${info.durationSeconds}s must fit into $targetBytes bytes",
            )
        }
        return buildPlan(info, sourceHeight = info.height, videoKbps = videoKbps, audioKbps = audioKbps)
    }

    /**
     * Plan for the retry after the first encode produced [actualBytes] instead of [targetBytes].
     * The retry re-encodes the first result, so that result's height is the new source height.
     */
    fun shrink(
        previous: CompressionPlan,
        info: VideoInfo,
        actualBytes: Long,
        targetBytes: Long,
    ): CompressionPlan {
        require(actualBytes > 0) { "actualBytes must be positive, got $actualBytes" }
        require(targetBytes > 0) { "targetBytes must be positive, got $targetBytes" }

        val videoKbps = floor(previous.videoMaxrateKbps * targetBytes.toDouble() / actualBytes * SHRINK_FACTOR).toInt()
        if (videoKbps <= 0) {
            throw VideoTooLargeException(
                "No bitrate budget for video after overshoot: $actualBytes bytes produced for a $targetBytes target",
            )
        }
        return buildPlan(
            info,
            sourceHeight = previous.scaleHeight ?: info.height,
            videoKbps = videoKbps,
            audioKbps = previous.audioBitrateKbps,
        )
    }

    /** Width of [info] scaled to [height], rounded to the nearest even number as `scale=-2:<h>` does. */
    internal fun scaledWidth(
        info: VideoInfo,
        height: Int,
    ): Int {
        val exact = info.width.toDouble() * height / info.height
        return maxOf(2, (exact / 2).roundToInt() * 2)
    }

    private fun buildPlan(
        info: VideoInfo,
        sourceHeight: Int,
        videoKbps: Int,
        audioKbps: Int?,
    ): CompressionPlan {
        val chosenHeight = chooseHeight(info, sourceHeight, videoKbps)
        return CompressionPlan(
            scaleHeight = chosenHeight.takeIf { it != sourceHeight },
            videoMaxrateKbps = videoKbps,
            audioBitrateKbps = audioKbps,
            crf = settings.crf,
            preset = settings.preset,
        )
    }

    private fun chooseHeight(
        info: VideoInfo,
        sourceHeight: Int,
        videoKbps: Int,
    ): Int {
        val candidates = (HEIGHTS + sourceHeight).filter { it <= sourceHeight }.distinct().sortedDescending()
        return candidates.firstOrNull { height ->
            val pixelsPerSecond = scaledWidth(info, height).toDouble() * height * info.fps
            videoKbps * BITS_PER_KBIT / pixelsPerSecond >= settings.minBitsPerPixel
        } ?: candidates.last()
    }

    companion object {
        val HEIGHTS = listOf(1080, 720, 540)
        const val AUDIO_BITRATE_KBPS = 64
        const val CONTAINER_RESERVE = 0.03
        const val SHRINK_FACTOR = 0.9
        private const val BITS_PER_BYTE = 8
        private const val BITS_PER_KBIT = 1000
    }
}
```

- [ ] **Step 5: Зарегистрировать настройки**

В `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/FrigateAnalyzerApplication.kt` добавить импорт и элемент списка:

```kotlin
import ru.zinin.frigate.analyzer.core.config.properties.ExportProperties
```

и внутри `@EnableConfigurationProperties(` после строки `DetectProperties::class,` добавить строку:

```kotlin
    ExportProperties::class,
```

В `modules/core/src/main/resources/application.yaml` сразу после строки `  ffmpeg-path: ${FFMPEG_PATH:/usr/bin/ffmpeg}` добавить блок (отступ два пробела, как у `ffmpeg-path`):

```yaml
  export:
    compress:
      preset: ${EXPORT_COMPRESS_PRESET:fast}
      crf: ${EXPORT_COMPRESS_CRF:23}
      min-bits-per-pixel: ${EXPORT_COMPRESS_MIN_BITS_PER_PIXEL:0.1}
```

- [ ] **Step 6: Убедиться, что тест планировщика проходит**

Run (через build-runner): та же команда, что в Step 2.
Expected: PASS, 17 тестов.

- [ ] **Step 7: Документировать переменные**

В `.claude/rules/configuration.md` перед строкой `## Telegram` вставить раздел:

```markdown
## Video Export

Settings under `application.export.compress` in `application.yaml`. They tune the re-encode that
`TelegramVideoFitter` (core, `video/`) runs when a merged export exceeds 45 MiB — see
`telegram-export.md`, "Size limit". The 45 MiB budget and the 50 MB acceptance limit are constants
(`FitLimits.TELEGRAM`), not settings.

| Variable | Default | Purpose |
|----------|---------|---------|
| `EXPORT_COMPRESS_PRESET` | fast | libx264 preset: speed versus compression on the host CPU |
| `EXPORT_COMPRESS_CRF` | 23 | libx264 quality target (0–51); the bitrate cap from the budget still applies |
| `EXPORT_COMPRESS_MIN_BITS_PER_PIXEL` | 0.1 | Smallest bits-per-pixel a candidate height (1080/720/540, never above the source) may have before the next smaller one is tried |

```

- [ ] **Step 8: Commit**

```bash
cat > "$SCRATCH/commit.txt" <<'MSG'
feat(core): plan a budget-driven re-encode for oversized exports

CompressionPlanner turns a probe result and a byte budget into a libx264
plan: the largest of 1080/720/540 that keeps at least 0.1 bits per pixel,
a maxrate cap from the budget minus a 3% container reserve and 64 kbps of
audio, and a shrink step for one retry after an overshoot. Settings live
under application.export.compress; VideoTooLargeException is the signal
that no budget is left.

Claude-Session: https://claude.ai/code/session_01EytYTJW3JyptT4D4V6aEj6
MSG
git add modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/exception/VideoTooLargeException.kt modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/ExportProperties.kt modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/VideoInfo.kt modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/CompressionPlan.kt modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/CompressionPlanner.kt modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/FrigateAnalyzerApplication.kt modules/core/src/main/resources/application.yaml .claude/rules/configuration.md modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/video/CompressionPlannerTest.kt
git commit -F "$SCRATCH/commit.txt" -- modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/exception/VideoTooLargeException.kt modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/ExportProperties.kt modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/VideoInfo.kt modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/CompressionPlan.kt modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/CompressionPlanner.kt modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/FrigateAnalyzerApplication.kt modules/core/src/main/resources/application.yaml .claude/rules/configuration.md modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/video/CompressionPlannerTest.kt
```

---

### Task 3: VideoProbe (ffprobe) и `FFPROBE_PATH`

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/VideoProbe.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/ApplicationProperties.kt`
- Modify: `modules/core/src/main/resources/application.yaml` (`ffprobe-path`)
- Modify: `.claude/rules/configuration.md` (строка `FFPROBE_PATH`)
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/video/VideoProbeTest.kt`

**Interfaces:**
- Consumes: `FfmpegProcessRunner.run(command, timeout): List<String>` (Task 1), `VideoInfo` (Task 2), бин `tools.jackson.databind.ObjectMapper` (в приложении это `JsonMapper` из `JacksonConfiguration`).
- Produces: `class VideoProbe(applicationProperties, processRunner, objectMapper)` с `suspend fun probe(path: Path): VideoInfo`, `internal fun buildCommand(path: Path): List<String>`, `internal fun parse(json: String, path: Path): VideoInfo`, `internal fun parseFrameRate(raw: String?): Double?`; `ApplicationProperties.ffprobePath: Path` (по умолчанию `/usr/bin/ffprobe`).

- [ ] **Step 1: Написать падающий тест**

Создать `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/video/VideoProbeTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.video

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoProbeTest {
    private val runner = mockk<FfmpegProcessRunner>()
    private val properties =
        ApplicationProperties(
            tempFolder = Path.of("/tmp/frigate-analyzer-test"),
            ffmpegPath = Path.of("/usr/bin/ffmpeg"),
            ffprobePath = Path.of("/opt/tools/ffprobe"),
            connectionTimeout = Duration.ofSeconds(5),
            readTimeout = Duration.ofSeconds(5),
            writeTimeout = Duration.ofSeconds(5),
            responseTimeout = Duration.ofSeconds(5),
        )
    private val probe = VideoProbe(properties, runner, JsonMapper.builder().build())
    private val path: Path = Path.of("/data/merged.mp4")

    private val videoAndAudio =
        """
        {
          "streams": [
            {"codec_type": "video", "width": 2560, "height": 1920, "r_frame_rate": "25/2", "avg_frame_rate": "25/2"},
            {"codec_type": "audio", "r_frame_rate": "0/0", "avg_frame_rate": "0/0"}
          ],
          "format": {"duration": "120.064000"}
        }
        """.trimIndent()

    @Test
    fun `probe runs ffprobe with json output and parses the result`() =
        runTest {
            val commands = mutableListOf<List<String>>()
            coEvery { runner.run(capture(commands), Duration.ofSeconds(30)) } returns videoAndAudio.lines()

            val info = probe.probe(path)

            assertEquals(
                VideoInfo(durationSeconds = 120.064, width = 2560, height = 1920, fps = 12.5, hasAudio = true),
                info,
            )
            assertEquals(
                listOf(
                    "/opt/tools/ffprobe",
                    "-v",
                    "error",
                    "-show_entries",
                    "stream=codec_type,width,height,avg_frame_rate,r_frame_rate:format=duration",
                    "-of",
                    "json",
                    "/data/merged.mp4",
                ),
                commands.single(),
            )
            coVerify(exactly = 1) { runner.run(any(), any()) }
        }

    @Test
    fun `parse reports no audio when there is no audio stream`() {
        val json =
            """
            {"streams": [{"codec_type": "video", "width": 1280, "height": 720, "avg_frame_rate": "25/1"}],
             "format": {"duration": "10.5"}}
            """.trimIndent()

        val info = probe.parse(json, path)

        assertFalse(info.hasAudio)
        assertEquals(25.0, info.fps)
        assertEquals(10.5, info.durationSeconds)
    }

    @Test
    fun `parse falls back to r_frame_rate when avg_frame_rate is unusable`() {
        val json =
            """
            {"streams": [{"codec_type": "video", "width": 1280, "height": 720, "avg_frame_rate": "0/0", "r_frame_rate": "25/2"}],
             "format": {"duration": "10"}}
            """.trimIndent()

        assertEquals(12.5, probe.parse(json, path).fps)
    }

    @Test
    fun `parse assumes 25 fps when both frame rates are unusable`() {
        val json =
            """
            {"streams": [{"codec_type": "video", "width": 1280, "height": 720, "avg_frame_rate": "0/0", "r_frame_rate": "garbage"}],
             "format": {"duration": "10"}}
            """.trimIndent()

        assertEquals(25.0, probe.parse(json, path).fps)
    }

    @Test
    fun `parse rejects output without a video stream with a plain RuntimeException`() {
        val json = """{"streams": [{"codec_type": "audio"}], "format": {"duration": "10"}}"""

        val exception = assertThrows<RuntimeException> { probe.parse(json, path) }

        assertTrue(exception.message!!.contains("no video stream"), exception.message)
        assertEquals(RuntimeException::class, exception::class, "must not be IllegalStateException")
    }

    @Test
    fun `parse rejects output without duration`() {
        val json = """{"streams": [{"codec_type": "video", "width": 1280, "height": 720}], "format": {}}"""

        val exception = assertThrows<RuntimeException> { probe.parse(json, path) }

        assertTrue(exception.message!!.contains("duration"), exception.message)
    }

    @Test
    fun `parse rejects unreadable json`() {
        val exception = assertThrows<RuntimeException> { probe.parse("not json at all", path) }

        assertTrue(exception.message!!.contains("unreadable JSON"), exception.message)
    }

    @Test
    fun `parseFrameRate handles rationals, decimals and garbage`() {
        assertEquals(12.5, probe.parseFrameRate("25/2"))
        assertEquals(12.5, probe.parseFrameRate("12.5"))
        assertEquals(30.0, probe.parseFrameRate("30/1"))
        assertNull(probe.parseFrameRate("0/0"))
        assertNull(probe.parseFrameRate("30/0"))
        assertNull(probe.parseFrameRate("garbage"))
        assertNull(probe.parseFrameRate(""))
        assertNull(probe.parseFrameRate(null))
    }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run (через build-runner):
```
JAVA_HOME=/usr/lib/jvm/zulu25 ./gradlew :frigate-analyzer-core:test --tests 'ru.zinin.frigate.analyzer.core.video.VideoProbeTest' --rerun-tasks --no-watch-fs --console=plain
```
Expected: FAIL, ошибка компиляции: `Unresolved reference 'VideoProbe'` и неизвестный параметр `ffprobePath`.

- [ ] **Step 3: Добавить `ffprobePath` в настройки**

В `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/ApplicationProperties.kt` после параметра `ffmpegPath` добавить:

```kotlin
    @field:NotNull
    val ffprobePath: Path = Path.of("/usr/bin/ffprobe"),
```

Значение по умолчанию нужно, чтобы четыре существующих теста, которые строят `ApplicationProperties` вручную (`TempFileHelperTest`, `DetectServiceTest`, `DetectServiceCancelJobTest`, `VideoVisualizationServiceTest`), не менялись.

В `modules/core/src/main/resources/application.yaml` сразу после строки `  ffmpeg-path: ${FFMPEG_PATH:/usr/bin/ffmpeg}` (перед блоком `export:` из Task 2) добавить:

```yaml
  ffprobe-path: ${FFPROBE_PATH:/usr/bin/ffprobe}
```

- [ ] **Step 4: Написать VideoProbe**

`modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/VideoProbe.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.video

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.time.Duration

private val logger = KotlinLogging.logger {}

/** Reads duration, frame size, frame rate and audio presence of a video file through ffprobe. */
@Component
class VideoProbe(
    private val applicationProperties: ApplicationProperties,
    private val processRunner: FfmpegProcessRunner,
    private val objectMapper: ObjectMapper,
) {
    /**
     * @throws RuntimeException when ffprobe fails, prints unreadable JSON, or reports no video
     *   stream, frame size or duration. Deliberately a plain RuntimeException: Quick Export maps
     *   IllegalStateException to "recording files unavailable", which would be misleading here.
     */
    suspend fun probe(path: Path): VideoInfo {
        val output = processRunner.run(buildCommand(path), PROBE_TIMEOUT)
        return parse(output.joinToString("\n"), path)
    }

    internal fun buildCommand(path: Path): List<String> =
        listOf(
            applicationProperties.ffprobePath.toString(),
            "-v",
            "error",
            "-show_entries",
            "stream=codec_type,width,height,avg_frame_rate,r_frame_rate:format=duration",
            "-of",
            "json",
            path.toString(),
        )

    internal fun parse(
        json: String,
        path: Path,
    ): VideoInfo {
        val root =
            try {
                objectMapper.readTree(json)
            } catch (e: Exception) {
                throw RuntimeException("ffprobe returned unreadable JSON for $path", e)
            }
        val streams = root.path("streams").childNodes()
        val video =
            streams.firstOrNull { it.path("codec_type").textOrNull() == "video" }
                ?: throw RuntimeException("ffprobe found no video stream in $path")
        val hasAudio = streams.any { it.path("codec_type").textOrNull() == "audio" }
        val width = video.path("width").intOrNull() ?: throw RuntimeException("ffprobe reported no width for $path")
        val height = video.path("height").intOrNull() ?: throw RuntimeException("ffprobe reported no height for $path")
        val duration =
            root
                .path("format")
                .path("duration")
                .textOrNull()
                ?.toDoubleOrNull()
                ?.takeIf { it > 0 }
                ?: throw RuntimeException("ffprobe reported no duration for $path")
        val fps =
            parseFrameRate(video.path("avg_frame_rate").textOrNull())
                ?: parseFrameRate(video.path("r_frame_rate").textOrNull())
                ?: DEFAULT_FPS.also { logger.warn { "ffprobe reported no usable frame rate for $path, assuming $it fps" } }
        return VideoInfo(durationSeconds = duration, width = width, height = height, fps = fps, hasAudio = hasAudio)
    }

    /** Parses an ffprobe rational such as `25/2` or `12.5`; null when absent, zero or malformed. */
    internal fun parseFrameRate(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split("/")
        val numerator = parts[0].trim().toDoubleOrNull() ?: return null
        val denominator =
            if (parts.size > 1) {
                parts[1].trim().toDoubleOrNull() ?: return null
            } else {
                1.0
            }
        if (numerator <= 0 || denominator <= 0) return null
        return numerator / denominator
    }

    private fun JsonNode.childNodes(): List<JsonNode> = (0 until size()).map { get(it) }

    private fun JsonNode.textOrNull(): String? = if (isTextual) asText() else null

    private fun JsonNode.intOrNull(): Int? = if (isIntegralNumber) intValue() else null

    companion object {
        val PROBE_TIMEOUT: Duration = Duration.ofSeconds(30)
        const val DEFAULT_FPS = 25.0
    }
}
```

Если компилятор не найдёт `isTextual`/`asText` (в этом проекте они уже используются в `DetectService.kt:199` с Jackson 3, так что ожидается, что найдёт), заменить на `isString`/`asString()`; для `isIntegralNumber`/`intValue()` замен не нужно.

- [ ] **Step 5: Убедиться, что тест проходит**

Run (через build-runner): та же команда, что в Step 2.
Expected: PASS, 8 тестов.

- [ ] **Step 6: Документировать `FFPROBE_PATH`**

В `.claude/rules/configuration.md` в таблице «Core Settings» после строки

```markdown
| `FFMPEG_PATH` | /usr/bin/ffmpeg | ffmpeg binary path |
```

добавить строку:

```markdown
| `FFPROBE_PATH` | /usr/bin/ffprobe | ffprobe binary path; read by `VideoProbe` before an export is re-encoded to fit the Telegram limit. The Alpine image installs it together with ffmpeg |
```

- [ ] **Step 7: Commit**

```bash
cat > "$SCRATCH/commit.txt" <<'MSG'
feat(core): read export video parameters through ffprobe

VideoProbe runs ffprobe with JSON output and returns duration, frame size,
frame rate and audio presence for the compression planner. The frame rate
falls back from avg_frame_rate to r_frame_rate and then to 25 fps; missing
streams or duration surface as a plain RuntimeException so that Quick
Export shows its generic error rather than "recording files unavailable".
FFPROBE_PATH configures the binary, defaulting to /usr/bin/ffprobe.

Claude-Session: https://claude.ai/code/session_01EytYTJW3JyptT4D4V6aEj6
MSG
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/VideoProbe.kt modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/ApplicationProperties.kt modules/core/src/main/resources/application.yaml .claude/rules/configuration.md modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/video/VideoProbeTest.kt
git commit -F "$SCRATCH/commit.txt" -- modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/VideoProbe.kt modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/ApplicationProperties.kt modules/core/src/main/resources/application.yaml .claude/rules/configuration.md modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/video/VideoProbeTest.kt
```

---

### Task 4: `VideoMergeHelper.compressVideo(input, plan)`

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/VideoMergeHelper.kt`
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/helper/VideoMergeHelperTest.kt`

**Interfaces:**
- Consumes: `CompressionPlan` (Task 2), `FfmpegProcessRunner` (Task 1).
- Produces: `suspend fun compressVideo(inputPath: Path, plan: CompressionPlan): Path`, `internal fun buildCompressCommand(inputPath: Path, outputFile: Path, plan: CompressionPlan): List<String>`. Старая перегрузка `compressVideo(inputPath: Path)` пока остаётся, её удаляет Task 7.

- [ ] **Step 1: Добавить падающие тесты**

В `VideoMergeHelperTest.kt` добавить импорт:

```kotlin
import ru.zinin.frigate.analyzer.core.video.CompressionPlan
```

и три теста перед закрывающей скобкой класса:

```kotlin
    @Test
    fun `compressVideo builds a capped libx264 command with scaling and audio`() =
        runTest {
            val input = sourceFile("merged.mp4")
            val plan = CompressionPlan(scaleHeight = 1080, videoMaxrateKbps = 3051, audioBitrateKbps = 64, crf = 23, preset = "fast")
            val commands = mutableListOf<List<String>>()
            coEvery { runner.run(capture(commands), Duration.ofSeconds(300)) } returns emptyList()

            val output = helper.compressVideo(input, plan)

            assertEquals(
                listOf(
                    "/usr/bin/ffmpeg",
                    "-hide_banner",
                    "-nostdin",
                    "-i",
                    input.toString(),
                    "-vf",
                    "scale=-2:1080",
                    "-c:v",
                    "libx264",
                    "-preset",
                    "fast",
                    "-crf",
                    "23",
                    "-maxrate",
                    "3051k",
                    "-bufsize",
                    "6102k",
                    "-pix_fmt",
                    "yuv420p",
                    "-c:a",
                    "aac",
                    "-b:a",
                    "64k",
                    "-movflags",
                    "+faststart",
                    "-y",
                    output.toString(),
                ),
                commands.single(),
            )
            assertTrue(output.startsWith(tempDir))
            assertTrue(output.fileName.toString().contains("compressed-"))
        }

    @Test
    fun `compressVideo keeps the source size and drops audio when the plan says so`() =
        runTest {
            val input = sourceFile("merged.mp4")
            val plan = CompressionPlan(scaleHeight = null, videoMaxrateKbps = 1220, audioBitrateKbps = null, crf = 26, preset = "veryfast")
            val commands = mutableListOf<List<String>>()
            coEvery { runner.run(capture(commands), any()) } returns emptyList()

            helper.compressVideo(input, plan)

            val command = commands.single()
            assertFalse(command.contains("-vf"), "no scale filter expected: $command")
            assertFalse(command.any { it.startsWith("scale=") }, "no scale filter expected: $command")
            assertTrue(command.contains("-an"), "audio must be dropped: $command")
            assertFalse(command.contains("-c:a"), "audio must be dropped: $command")
            assertEquals("veryfast", command[command.indexOf("-preset") + 1])
            assertEquals("26", command[command.indexOf("-crf") + 1])
            assertEquals("1220k", command[command.indexOf("-maxrate") + 1])
            assertEquals("2440k", command[command.indexOf("-bufsize") + 1])
        }

    @Test
    fun `compressVideo deletes its output when ffmpeg fails`() =
        runTest {
            val input = sourceFile("merged.mp4")
            val plan = CompressionPlan(scaleHeight = 720, videoMaxrateKbps = 1220, audioBitrateKbps = null, crf = 23, preset = "fast")
            val commands = mutableListOf<List<String>>()
            coEvery { runner.run(capture(commands), any()) } throws RuntimeException("ffmpeg exited with code 1")

            assertThrows<RuntimeException> { helper.compressVideo(input, plan) }

            assertFalse(Files.exists(Path.of(commands.single().last())), "compressed output must be deleted")
            assertTrue(Files.exists(input), "the input belongs to the caller and must survive")
        }
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run (через build-runner):
```
JAVA_HOME=/usr/lib/jvm/zulu25 ./gradlew :frigate-analyzer-core:test --tests 'ru.zinin.frigate.analyzer.core.helper.VideoMergeHelperTest' --rerun-tasks --no-watch-fs --console=plain
```
Expected: FAIL, ошибка компиляции: `compressVideo` не принимает два аргумента.

- [ ] **Step 3: Добавить перегрузку и сборку команды**

В `VideoMergeHelper.kt` добавить импорты:

```kotlin
import kotlinx.coroutines.NonCancellable
import ru.zinin.frigate.analyzer.core.video.CompressionPlan
```

и после существующего `compressVideo(inputPath: Path)` добавить:

```kotlin
    /**
     * Re-encodes [inputPath] with [plan]: libx264 at CRF quality capped by `-maxrate`/`-bufsize`,
     * optionally downscaled, AAC audio or none, `faststart` for streaming playback. The caller
     * owns both files; on failure the partial output is deleted under [NonCancellable] so that a
     * cancelled export does not leak it.
     */
    suspend fun compressVideo(
        inputPath: Path,
        plan: CompressionPlan,
    ): Path {
        val outputFile = tempFileHelper.createTempFile("compressed-", ".mp4")
        try {
            runFfmpeg(buildCompressCommand(inputPath, outputFile, plan))
            return outputFile
        } catch (e: Exception) {
            withContext(NonCancellable) { tempFileHelper.deleteIfExists(outputFile) }
            throw e
        }
    }

    internal fun buildCompressCommand(
        inputPath: Path,
        outputFile: Path,
        plan: CompressionPlan,
    ): List<String> =
        buildList {
            add(applicationProperties.ffmpegPath.toString())
            add("-hide_banner")
            add("-nostdin")
            add("-i")
            add(inputPath.toString())
            plan.scaleHeight?.let { height ->
                add("-vf")
                add("scale=-2:$height")
            }
            add("-c:v")
            add("libx264")
            add("-preset")
            add(plan.preset)
            add("-crf")
            add(plan.crf.toString())
            add("-maxrate")
            add("${plan.videoMaxrateKbps}k")
            add("-bufsize")
            add("${plan.videoMaxrateKbps * 2}k")
            add("-pix_fmt")
            add("yuv420p")
            val audioKbps = plan.audioBitrateKbps
            if (audioKbps != null) {
                add("-c:a")
                add("aac")
                add("-b:a")
                add("${audioKbps}k")
            } else {
                add("-an")
            }
            add("-movflags")
            add("+faststart")
            add("-y")
            add(outputFile.toString())
        }
```

- [ ] **Step 4: Убедиться, что тесты проходят**

Run (через build-runner): та же команда, что в Step 2.
Expected: PASS, 7 тестов.

- [ ] **Step 5: Commit**

```bash
cat > "$SCRATCH/commit.txt" <<'MSG'
feat(core): encode exports from a compression plan

VideoMergeHelper.compressVideo(input, plan) builds the libx264 command from
a CompressionPlan: optional scale=-2:<h>, CRF plus maxrate/bufsize cap,
yuv420p, AAC or no audio, faststart. A failed encode deletes its output
under NonCancellable. The old CRF-only overload stays until the export
service is rewired.

Claude-Session: https://claude.ai/code/session_01EytYTJW3JyptT4D4V6aEj6
MSG
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/VideoMergeHelper.kt modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/helper/VideoMergeHelperTest.kt
git commit -F "$SCRATCH/commit.txt" -- modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/VideoMergeHelper.kt modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/helper/VideoMergeHelperTest.kt
```

---

### Task 5: FitLimits и TelegramVideoFitter

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/FitLimits.kt`
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/TelegramVideoFitter.kt`
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/video/TelegramVideoFitterTest.kt`

**Interfaces:**
- Consumes: `VideoProbe.probe(path): VideoInfo` (Task 3), `CompressionPlanner.plan/shrink` (Task 2), `VideoMergeHelper.compressVideo(input, plan): Path` (Task 4), `TempFileHelper.deleteIfExists(path)`, `VideoTooLargeException` (Task 2).
- Produces: `data class FitLimits(thresholdBytes: Long, maxBytes: Long)` с `FitLimits.TELEGRAM`; `class TelegramVideoFitter` с `suspend fun fit(input: Path, onCompressStart: suspend () -> Unit = {}): Path`; внутренний конструктор с пятью параметрами (последний `limits`) и `@Autowired`-конструктор с четырьмя.

- [ ] **Step 1: Написать падающий тест**

Создать `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/video/TelegramVideoFitterTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.video

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.core.helper.TempFileHelper
import ru.zinin.frigate.analyzer.core.helper.VideoMergeHelper
import ru.zinin.frigate.analyzer.model.exception.VideoTooLargeException
import java.io.RandomAccessFile
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramVideoFitterTest {
    @TempDir
    lateinit var tempDir: Path

    private val probe = mockk<VideoProbe>()
    private val planner = mockk<CompressionPlanner>()
    private val mergeHelper = mockk<VideoMergeHelper>()
    private val tempFileHelper = mockk<TempFileHelper>()
    private val limits = FitLimits(thresholdBytes = 1000, maxBytes = 1200)
    private val fitter = TelegramVideoFitter(probe, planner, mergeHelper, tempFileHelper, limits)

    private val info = VideoInfo(durationSeconds = 120.0, width = 2560, height = 1920, fps = 12.5, hasAudio = false)
    private val plan = CompressionPlan(scaleHeight = 1080, videoMaxrateKbps = 3051, audioBitrateKbps = null, crf = 23, preset = "fast")
    private val retryPlan = CompressionPlan(scaleHeight = null, videoMaxrateKbps = 2491, audioBitrateKbps = null, crf = 23, preset = "fast")

    @BeforeEach
    fun setUp() {
        coEvery { tempFileHelper.deleteIfExists(any()) } returns true
    }

    private fun file(
        name: String,
        size: Long,
    ): Path =
        tempDir.resolve(name).also { path ->
            RandomAccessFile(path.toFile(), "rw").use { it.setLength(size) }
        }

    @Test
    fun `returns the input untouched when it is within the threshold`() =
        runTest {
            val input = file("small.mp4", 1000)
            var started = false

            val result = fitter.fit(input) { started = true }

            assertEquals(input, result)
            assertFalse(started, "callback must not fire without compression")
            coVerify(exactly = 0) { probe.probe(any()) }
            coVerify(exactly = 0) { mergeHelper.compressVideo(any(), any<CompressionPlan>()) }
            coVerify(exactly = 0) { tempFileHelper.deleteIfExists(any()) }
        }

    @Test
    fun `compresses once and deletes the input when the first attempt fits`() =
        runTest {
            val input = file("big.mp4", 5000)
            val first = file("first.mp4", 1100)
            coEvery { probe.probe(input) } returns info
            every { planner.plan(info, 1000L) } returns plan
            coEvery { mergeHelper.compressVideo(input, plan) } returns first
            var starts = 0

            val result = fitter.fit(input) { starts++ }

            assertEquals(first, result)
            assertEquals(1, starts)
            coVerify(exactly = 1) { tempFileHelper.deleteIfExists(input) }
            coVerify(exactly = 0) { tempFileHelper.deleteIfExists(first) }
            verify(exactly = 0) { planner.shrink(any(), any(), any(), any()) }
        }

    @Test
    fun `accepts a first result between the threshold and the limit`() =
        runTest {
            val input = file("big.mp4", 5000)
            val first = file("first.mp4", 1200)
            coEvery { probe.probe(input) } returns info
            every { planner.plan(info, 1000L) } returns plan
            coEvery { mergeHelper.compressVideo(input, plan) } returns first

            assertEquals(first, fitter.fit(input))
            verify(exactly = 0) { planner.shrink(any(), any(), any(), any()) }
        }

    @Test
    fun `retries from the first result when it overshoots and deletes the first result`() =
        runTest {
            val input = file("big.mp4", 5000)
            val first = file("first.mp4", 1300)
            val second = file("second.mp4", 1150)
            coEvery { probe.probe(input) } returns info
            every { planner.plan(info, 1000L) } returns plan
            every { planner.shrink(plan, info, 1300L, 1000L) } returns retryPlan
            coEvery { mergeHelper.compressVideo(input, plan) } returns first
            coEvery { mergeHelper.compressVideo(first, retryPlan) } returns second
            var starts = 0

            val result = fitter.fit(input) { starts++ }

            assertEquals(second, result)
            assertEquals(1, starts, "callback fires once, not per attempt")
            coVerify(exactly = 1) { tempFileHelper.deleteIfExists(first) }
            coVerify(exactly = 1) { tempFileHelper.deleteIfExists(input) }
            coVerify(exactly = 0) { tempFileHelper.deleteIfExists(second) }
        }

    @Test
    fun `throws VideoTooLargeException and deletes both results when the retry still overshoots`() =
        runTest {
            val input = file("big.mp4", 5000)
            val first = file("first.mp4", 1300)
            val second = file("second.mp4", 1250)
            coEvery { probe.probe(input) } returns info
            every { planner.plan(info, 1000L) } returns plan
            every { planner.shrink(plan, info, 1300L, 1000L) } returns retryPlan
            coEvery { mergeHelper.compressVideo(input, plan) } returns first
            coEvery { mergeHelper.compressVideo(first, retryPlan) } returns second

            val exception = assertThrows<VideoTooLargeException> { fitter.fit(input) }

            assertTrue(exception.message!!.contains("two compression attempts"), exception.message)
            coVerify(exactly = 1) { tempFileHelper.deleteIfExists(first) }
            coVerify(exactly = 1) { tempFileHelper.deleteIfExists(second) }
            coVerify(exactly = 0) { tempFileHelper.deleteIfExists(input) }
        }

    @Test
    fun `leaves the input to the caller when the first encode fails`() =
        runTest {
            val input = file("big.mp4", 5000)
            coEvery { probe.probe(input) } returns info
            every { planner.plan(info, 1000L) } returns plan
            coEvery { mergeHelper.compressVideo(input, plan) } throws RuntimeException("ffmpeg exited with code 1")

            assertThrows<RuntimeException> { fitter.fit(input) }

            coVerify(exactly = 0) { tempFileHelper.deleteIfExists(any()) }
        }

    @Test
    fun `deletes the first result when the retry is cancelled`() =
        runTest {
            val input = file("big.mp4", 5000)
            val first = file("first.mp4", 1300)
            coEvery { probe.probe(input) } returns info
            every { planner.plan(info, 1000L) } returns plan
            every { planner.shrink(plan, info, 1300L, 1000L) } returns retryPlan
            coEvery { mergeHelper.compressVideo(input, plan) } returns first
            coEvery { mergeHelper.compressVideo(first, retryPlan) } throws CancellationException("export cancelled")

            assertThrows<CancellationException> { fitter.fit(input) }

            coVerify(exactly = 1) { tempFileHelper.deleteIfExists(first) }
            coVerify(exactly = 0) { tempFileHelper.deleteIfExists(input) }
        }

    @Test
    fun `TELEGRAM limits are the 45 MiB threshold and the decimal 50 MB maximum`() {
        assertEquals(47_185_920L, FitLimits.TELEGRAM.thresholdBytes)
        assertEquals(50_000_000L, FitLimits.TELEGRAM.maxBytes)
    }

    @Test
    fun `FitLimits rejects a threshold above the maximum`() {
        assertThrows<IllegalArgumentException> { FitLimits(thresholdBytes = 2000, maxBytes = 1000) }
    }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run (через build-runner):
```
JAVA_HOME=/usr/lib/jvm/zulu25 ./gradlew :frigate-analyzer-core:test --tests 'ru.zinin.frigate.analyzer.core.video.TelegramVideoFitterTest' --rerun-tasks --no-watch-fs --console=plain
```
Expected: FAIL, ошибка компиляции `Unresolved reference 'FitLimits'`.

- [ ] **Step 3: Написать FitLimits**

`modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/FitLimits.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.video

/**
 * Size limits for one delivery channel.
 *
 * @property thresholdBytes files up to this size are sent as they are; above it they are
 *   re-encoded with this value as the byte budget
 * @property maxBytes hard acceptance limit for the re-encoded result
 */
data class FitLimits(
    val thresholdBytes: Long,
    val maxBytes: Long,
) {
    init {
        require(thresholdBytes in 1..maxBytes) { "thresholdBytes must be in 1..maxBytes, got $thresholdBytes / $maxBytes" }
    }

    companion object {
        /**
         * Telegram Bot API upload limit is documented as "50 MB"; the exact byte boundary is not
         * public, so the decimal reading is used as the stricter one. 45 MiB keeps the budget
         * below both readings.
         */
        val TELEGRAM = FitLimits(thresholdBytes = 45L * 1024 * 1024, maxBytes = 50_000_000L)
    }
}
```

- [ ] **Step 4: Написать TelegramVideoFitter**

`modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/TelegramVideoFitter.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.video

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.helper.TempFileHelper
import ru.zinin.frigate.analyzer.core.helper.VideoMergeHelper
import ru.zinin.frigate.analyzer.model.exception.VideoTooLargeException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

private val logger = KotlinLogging.logger {}

/**
 * Fits an export file into the Telegram upload limit.
 *
 * Files up to [FitLimits.thresholdBytes] are returned untouched. Larger files are probed, planned
 * with the threshold as the byte budget, re-encoded and checked against [FitLimits.maxBytes]; one
 * overshoot gets a second, smaller encode of the first result; a second overshoot is
 * [VideoTooLargeException].
 *
 * File ownership: on success the input is deleted when a new file was produced; on failure or
 * cancellation only the files this class created are deleted and the input is left to the caller.
 */
@Component
class TelegramVideoFitter internal constructor(
    private val probe: VideoProbe,
    private val planner: CompressionPlanner,
    private val mergeHelper: VideoMergeHelper,
    private val tempFileHelper: TempFileHelper,
    private val limits: FitLimits,
) {
    @Autowired
    constructor(
        probe: VideoProbe,
        planner: CompressionPlanner,
        mergeHelper: VideoMergeHelper,
        tempFileHelper: TempFileHelper,
    ) : this(probe, planner, mergeHelper, tempFileHelper, FitLimits.TELEGRAM)

    /**
     * @param onCompressStart called once, before probing, when [input] is above the threshold.
     * @return [input] itself, or a new temp file that fits.
     */
    suspend fun fit(
        input: Path,
        onCompressStart: suspend () -> Unit = {},
    ): Path {
        val inputSize = fileSize(input)
        if (inputSize <= limits.thresholdBytes) {
            logger.debug { "No compression needed for $input (${mib(inputSize)})" }
            return input
        }

        onCompressStart()
        val info = probe.probe(input)
        val plan = planner.plan(info, limits.thresholdBytes)
        logger.info {
            "Compressing $input: ${mib(inputSize)}, ${info.durationSeconds}s, " +
                "${info.width}x${info.height}@${info.fps}fps, audio=${info.hasAudio} -> " +
                describe(plan, sourceHeight = info.height)
        }

        val created = mutableListOf<Path>()
        try {
            var result = encode(input, plan, attempt = 1, created)
            var resultSize = fileSize(result)
            if (resultSize > limits.maxBytes) {
                val retryPlan = planner.shrink(plan, info, resultSize, limits.thresholdBytes)
                logger.warn {
                    "Compressed file is ${mib(resultSize)}, above the ${mib(limits.maxBytes)} limit; retrying with " +
                        describe(retryPlan, sourceHeight = plan.scaleHeight ?: info.height)
                }
                result = encode(result, retryPlan, attempt = 2, created)
                resultSize = fileSize(result)
            }
            if (resultSize > limits.maxBytes) {
                throw VideoTooLargeException(
                    "Video is ${mib(resultSize)} after two compression attempts, limit is ${mib(limits.maxBytes)}",
                )
            }
            created.remove(result)
            deleteAll(created + input)
            return result
        } catch (e: Exception) {
            withContext(NonCancellable) { deleteAll(created) }
            throw e
        }
    }

    private suspend fun encode(
        source: Path,
        plan: CompressionPlan,
        attempt: Int,
        created: MutableList<Path>,
    ): Path {
        val output = mergeHelper.compressVideo(source, plan)
        created.add(output)
        logger.info { "Compression attempt $attempt: ${mib(fileSize(output))} -> $output" }
        return output
    }

    private suspend fun deleteAll(paths: List<Path>) {
        for (path in paths) {
            try {
                tempFileHelper.deleteIfExists(path)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to delete temp file: $path" }
            }
        }
    }

    private suspend fun fileSize(path: Path): Long = withContext(Dispatchers.IO) { Files.size(path) }

    private fun describe(
        plan: CompressionPlan,
        sourceHeight: Int,
    ): String =
        "height=${plan.scaleHeight ?: sourceHeight}, maxrate=${plan.videoMaxrateKbps}k, " +
            "audio=${plan.audioBitrateKbps?.let { "${it}k" } ?: "none"}, crf=${plan.crf}, preset=${plan.preset}"

    private fun mib(bytes: Long): String = "%.1f MiB".format(Locale.ROOT, bytes / MIB)

    companion object {
        private const val MIB = 1024.0 * 1024.0
    }
}
```

- [ ] **Step 5: Убедиться, что тест проходит**

Run (через build-runner): та же команда, что в Step 2.
Expected: PASS, 9 тестов.

- [ ] **Step 6: Commit**

```bash
cat > "$SCRATCH/commit.txt" <<'MSG'
feat(core): fit export files into the Telegram size limit

TelegramVideoFitter returns files up to 45 MiB untouched and otherwise
probes, plans and re-encodes them with the threshold as the budget, checks
the result against 50 000 000 bytes, retries once from the first result
with a smaller cap, and throws VideoTooLargeException after a second
overshoot. It owns the files it creates: on success the input goes away,
on failure or cancellation only its own outputs do.

Claude-Session: https://claude.ai/code/session_01EytYTJW3JyptT4D4V6aEj6
MSG
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/FitLimits.kt modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/TelegramVideoFitter.kt modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/video/TelegramVideoFitterTest.kt
git commit -F "$SCRATCH/commit.txt" -- modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/FitLimits.kt modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/video/TelegramVideoFitter.kt modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/video/TelegramVideoFitterTest.kt
```

---

### Task 6: стадия `COMPRESSING_RESULT` в telegram

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/model/VideoExportProgress.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportModels.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportExecutor.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt`
- Modify: `modules/telegram/src/main/resources/messages_ru.properties`, `modules/telegram/src/main/resources/messages_en.properties`
- Test: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportModelsTest.kt`

**Interfaces:**
- Consumes: ничего из `core`.
- Produces: `VideoExportProgress.Stage.COMPRESSING_RESULT`; `renderProgress(stage, percent, mode, compressing, compressingResult: Boolean = false, msg, lang)`; ключи `export.progress.compressing.result`, `quickexport.progress.compressing.result`.

- [ ] **Step 1: Добавить падающие тесты отрисовки**

В `ExportModelsTest.kt` добавить два теста перед закрывающей скобкой класса:

```kotlin
    @Test
    fun `renderProgress lists compressing result after annotation when it happened`() {
        val result =
            renderProgress(
                Stage.COMPRESSING_RESULT,
                mode = ExportMode.ANNOTATED,
                compressingResult = true,
                msg = msg,
                lang = "ru",
            )

        val lines = result.lines()
        assertContains(lines, "✅ Аннотация видео")
        assertContains(lines, "🔄 Сжатие результата...")
        assertTrue(lines.indexOf("✅ Аннотация видео") < lines.indexOf("🔄 Сжатие результата..."))
        assertContains(lines, "⬜ Отправка")
    }

    @Test
    fun `renderProgress omits compressing result unless it happened`() {
        val result = renderProgress(Stage.SENDING, mode = ExportMode.ANNOTATED, msg = msg, lang = "ru")

        assertFalse(result.contains("Сжатие результата"))
    }
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run (через build-runner):
```
JAVA_HOME=/usr/lib/jvm/zulu25 ./gradlew :frigate-analyzer-telegram:test --tests 'ru.zinin.frigate.analyzer.telegram.bot.handler.export.ExportModelsTest' --rerun-tasks --no-watch-fs --console=plain
```
Expected: FAIL, ошибка компиляции `Unresolved reference 'COMPRESSING_RESULT'`.

- [ ] **Step 3: Добавить стадию и тексты**

`VideoExportProgress.kt`, строка enum:

```kotlin
    enum class Stage { PREPARING, MERGING, COMPRESSING, ANNOTATING, COMPRESSING_RESULT, SENDING, DONE }
```

`messages_ru.properties`: после строки `export.progress.annotating=Аннотация видео` добавить

```properties
export.progress.compressing.result=Сжатие результата
```

и после строки `quickexport.progress.annotating.percent=\u2699\uFE0F Аннотация {0}%...` добавить (эмодзи в этих файлах записаны escape-последовательностями, не литералами)

```properties
quickexport.progress.compressing.result=\u2699\uFE0F Сжатие результата...
```

`messages_en.properties`: после строки `export.progress.annotating=Annotating video` добавить

```properties
export.progress.compressing.result=Compressing result
```

и после строки `quickexport.progress.annotating.percent=\u2699\uFE0F Annotating {0}%...` добавить

```properties
quickexport.progress.compressing.result=\u2699\uFE0F Compressing result...
```

- [ ] **Step 4: Отрисовка в `renderProgress`**

В `ExportModels.kt` сигнатуру `renderProgress` заменить на

```kotlin
internal fun renderProgress(
    stage: Stage,
    percent: Int? = null,
    mode: ExportMode = ExportMode.ORIGINAL,
    compressing: Boolean = false,
    compressingResult: Boolean = false,
    msg: MessageResolver,
    lang: String,
): String {
```

а в `buildList` после строки

```kotlin
            if (mode == ExportMode.ANNOTATED) add(Stage.ANNOTATING to msg.get("export.progress.annotating", lang))
```

добавить

```kotlin
            if (compressingResult) add(Stage.COMPRESSING_RESULT to msg.get("export.progress.compressing.result", lang))
```

- [ ] **Step 5: Флаг в `ExportExecutor`**

В `ExportExecutor.runExport`:

1. После `var hadCompressing = false` добавить `var hadCompressingResult = false`.
2. После `if (progress.stage == Stage.COMPRESSING) hadCompressing = true` добавить
   `if (progress.stage == Stage.COMPRESSING_RESULT) hadCompressingResult = true`.
3. Позиционный вызов внутри `onProgress`

```kotlin
                        renderProgress(progress.stage, progress.percent, mode, hadCompressing, msg, lang),
```

заменить на

```kotlin
                        renderProgress(
                            stage = progress.stage,
                            percent = progress.percent,
                            mode = mode,
                            compressing = hadCompressing,
                            compressingResult = hadCompressingResult,
                            msg = msg,
                            lang = lang,
                        ),
```

4. Два именованных вызова для `Stage.SENDING` и `Stage.DONE`

```kotlin
renderProgress(Stage.SENDING, mode = mode, compressing = hadCompressing, msg = msg, lang = lang)
renderProgress(Stage.DONE, mode = mode, compressing = hadCompressing, msg = msg, lang = lang)
```

дополнить аргументом `compressingResult = hadCompressingResult` после `compressing = hadCompressing`.

- [ ] **Step 6: Кнопка в `QuickExportHandler`**

В `renderProgressButton` после ветки `VideoExportProgress.Stage.ANNOTATING -> { ... }` добавить

```kotlin
            VideoExportProgress.Stage.COMPRESSING_RESULT -> {
                msg.get("quickexport.progress.compressing.result", lang)
            }
```

- [ ] **Step 7: Убедиться, что тесты telegram проходят**

Run (через build-runner):
```
JAVA_HOME=/usr/lib/jvm/zulu25 ./gradlew :frigate-analyzer-telegram:test --rerun-tasks --no-watch-fs --console=plain
```
Expected: PASS, включая `MessageKeyParityTest` (ключи ru и en совпадают) и `ExportModelsTest` (8 тестов).

- [ ] **Step 8: Commit**

```bash
cat > "$SCRATCH/commit.txt" <<'MSG'
feat(telegram): show a separate stage for compressing the annotated result

The export progress list rendered every stage before the current one as
done, so a second compression after annotation could not reuse COMPRESSING
without marking annotation as pending. COMPRESSING_RESULT sits between
ANNOTATING and SENDING and appears only when that re-encode happened.

Claude-Session: https://claude.ai/code/session_01EytYTJW3JyptT4D4V6aEj6
MSG
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/model/VideoExportProgress.kt modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportModels.kt modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportExecutor.kt modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt modules/telegram/src/main/resources/messages_ru.properties modules/telegram/src/main/resources/messages_en.properties modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportModelsTest.kt
git commit -F "$SCRATCH/commit.txt" -- modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/model/VideoExportProgress.kt modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportModels.kt modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportExecutor.kt modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt modules/telegram/src/main/resources/messages_ru.properties modules/telegram/src/main/resources/messages_en.properties modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportModelsTest.kt
```

---

### Task 7: `VideoExportServiceImpl` через fitter

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImpl.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/VideoMergeHelper.kt` (удалить старый `compressVideo(inputPath)` и константы размеров)
- Modify: `.claude/rules/telegram-export.md` (шаг потока)
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImplTest.kt`

**Interfaces:**
- Consumes: `TelegramVideoFitter.fit(input, onCompressStart)` (Task 5), `Stage.COMPRESSING_RESULT` (Task 6), `VideoTooLargeException` (Task 2).
- Produces: конструктор `VideoExportServiceImpl(recordingRepository, videoMergeHelper, videoFitter: TelegramVideoFitter, tempFileHelper, videoVisualizationService, detectProperties, detectionFilterProperties)`. Контракт `VideoExportService` не меняется.

- [ ] **Step 1: Переписать тесты сервиса**

В `VideoExportServiceImplTest.kt`:

1. Импорты: удалить `import io.mockk.every`, `import io.mockk.mockkStatic`, `import io.mockk.unmockkStatic`; добавить

```kotlin
import ru.zinin.frigate.analyzer.core.video.TelegramVideoFitter
import ru.zinin.frigate.analyzer.model.exception.VideoTooLargeException
```

2. После строки `private val tempFileHelper = mockk<TempFileHelper>()` добавить мок fitter с поведением «вернуть вход как есть» по умолчанию:

```kotlin
    private val videoFitter =
        mockk<TelegramVideoFitter>().also { fitter ->
            coEvery { fitter.fit(any(), any()) } coAnswers { firstArg<Path>() }
        }
```

3. Во всех трёх местах, где строится `VideoExportServiceImpl(` (поле `service` и два локальных экземпляра в тестах про `allowedClasses`), после строки `videoMergeHelper = videoMergeHelper,` добавить `videoFitter = videoFitter,`.

4. Тест `export original emits COMPRESSING when threshold exceeded` заменить целиком на:

```kotlin
    @Test
    fun `export original emits COMPRESSING when the fitter compresses`() =
        runTest {
            val recordingFile = createTempFile("recording1.mp4")
            val mergedFile = createTempFile("merged-large.mp4")
            val compressedFile = createTempFile("compressed.mp4")

            coEvery { recordingRepository.findByCamIdAndInstantRange(camId, start, end) } returns
                listOf(recording(recordingFile.toString()))
            coEvery { videoMergeHelper.mergeVideos(any()) } returns mergedFile
            coEvery { videoFitter.fit(mergedFile, any()) } coAnswers {
                secondArg<suspend () -> Unit>().invoke()
                compressedFile
            }

            val progress = mutableListOf<VideoExportProgress>()

            val result =
                service.exportVideo(
                    startInstant = start,
                    endInstant = end,
                    camId = camId,
                    mode = ExportMode.ORIGINAL,
                    onProgress = { progress.add(it) },
                )

            assertEquals(compressedFile, result)
            assertEquals(listOf(Stage.PREPARING, Stage.MERGING, Stage.COMPRESSING), progress.map { it.stage })
        }
```

5. Тест `export original with compress still too large throws and cleans up` заменить целиком на:

```kotlin
    @Test
    fun `export original propagates VideoTooLargeException and deletes the merged file`() =
        runTest {
            val recordingFile = createTempFile("recording1.mp4")
            val mergedFile = createTempFile("merged-large.mp4")

            coEvery { recordingRepository.findByCamIdAndInstantRange(camId, start, end) } returns
                listOf(recording(recordingFile.toString()))
            coEvery { videoMergeHelper.mergeVideos(any()) } returns mergedFile
            coEvery { videoFitter.fit(mergedFile, any()) } throws
                VideoTooLargeException("Video is 60.0 MiB after two compression attempts, limit is 47.7 MiB")
            coEvery { tempFileHelper.deleteIfExists(mergedFile) } returns true

            val exception =
                assertThrows<VideoTooLargeException> {
                    service.exportVideo(
                        startInstant = start,
                        endInstant = end,
                        camId = camId,
                        mode = ExportMode.ORIGINAL,
                    )
                }

            assertTrue(exception.message!!.contains("two compression attempts"))
            coVerify { tempFileHelper.deleteIfExists(mergedFile) }
        }
```

6. После теста `annotated mode deletes intermediate merged file on annotation error and rethrows` добавить два теста:

```kotlin
    @Test
    fun `annotated mode fits the annotated file and emits COMPRESSING_RESULT`() =
        runTest {
            val recordingFile = createTempFile("recording1.mp4")
            val mergedFile = createTempFile("merged.mp4")
            val annotatedFile = createTempFile("annotated.mp4")
            val fittedFile = createTempFile("annotated-fitted.mp4")

            coEvery { recordingRepository.findByCamIdAndInstantRange(camId, start, end) } returns
                listOf(recording(recordingFile.toString()))
            coEvery { videoMergeHelper.mergeVideos(any()) } returns mergedFile
            coEvery { tempFileHelper.deleteIfExists(mergedFile) } returns true
            stubAnnotateVideo(annotatedFile)
            coEvery { videoFitter.fit(annotatedFile, any()) } coAnswers {
                secondArg<suspend () -> Unit>().invoke()
                fittedFile
            }

            val progress = mutableListOf<VideoExportProgress>()

            val result =
                service.exportVideo(
                    startInstant = start,
                    endInstant = end,
                    camId = camId,
                    mode = ExportMode.ANNOTATED,
                    onProgress = { progress.add(it) },
                )

            assertEquals(fittedFile, result)
            assertAnnotateCalledWith(mergedFile, "person,car", "yolo26x.pt")
            assertEquals(
                listOf(Stage.PREPARING, Stage.MERGING, Stage.ANNOTATING, Stage.COMPRESSING_RESULT),
                progress.map { it.stage },
            )
        }

    @Test
    fun `annotated mode deletes the annotated file when fitting it fails`() =
        runTest {
            val recordingFile = createTempFile("recording1.mp4")
            val mergedFile = createTempFile("merged.mp4")
            val annotatedFile = createTempFile("annotated.mp4")

            coEvery { recordingRepository.findByCamIdAndInstantRange(camId, start, end) } returns
                listOf(recording(recordingFile.toString()))
            coEvery { videoMergeHelper.mergeVideos(any()) } returns mergedFile
            coEvery { tempFileHelper.deleteIfExists(any()) } returns true
            stubAnnotateVideo(annotatedFile)
            coEvery { videoFitter.fit(annotatedFile, any()) } throws RuntimeException("ffmpeg exited with code 1")

            val exception =
                assertThrows<RuntimeException> {
                    service.exportVideo(
                        startInstant = start,
                        endInstant = end,
                        camId = camId,
                        mode = ExportMode.ANNOTATED,
                    )
                }

            assertEquals("ffmpeg exited with code 1", exception.message)
            coVerify { tempFileHelper.deleteIfExists(annotatedFile) }
        }
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run (через build-runner):
```
JAVA_HOME=/usr/lib/jvm/zulu25 ./gradlew :frigate-analyzer-core:test --tests 'ru.zinin.frigate.analyzer.core.service.VideoExportServiceImplTest' --rerun-tasks --no-watch-fs --console=plain
```
Expected: FAIL, ошибка компиляции: у `VideoExportServiceImpl` нет параметра `videoFitter`.

- [ ] **Step 3: Переписать оркестрацию**

В `VideoExportServiceImpl.kt`:

1. Импорт: добавить `import ru.zinin.frigate.analyzer.core.video.TelegramVideoFitter`.
2. Конструктор: после `private val videoMergeHelper: VideoMergeHelper,` добавить `private val videoFitter: TelegramVideoFitter,`.
3. Фрагмент `exportVideo` от строки `onProgress(VideoExportProgress(Stage.MERGING))` до конца функции заменить на:

```kotlin
        onProgress(VideoExportProgress(Stage.MERGING))

        // `current` always names the newest file this export owns: the fitter deletes its input
        // only on success, annotate() deletes its input on both paths, deleteIfExists is
        // idempotent, so the catch blocks below can delete `current` without bookkeeping.
        var current = videoMergeHelper.mergeVideos(existingFiles)

        try {
            current = videoFitter.fit(current) { onProgress(VideoExportProgress(Stage.COMPRESSING)) }

            if (mode == ExportMode.ORIGINAL) {
                return current
            }

            current = annotate(current, onProgress, onJobSubmitted)
            current = videoFitter.fit(current) { onProgress(VideoExportProgress(Stage.COMPRESSING_RESULT)) }
            return current
        } catch (e: CancellationException) {
            logger.debug(e) { "Export cancelled, cleaning up: $current" }
            safeDelete(current)
            throw e
        } catch (e: Exception) {
            logger.debug(e) { "Export failed, cleaning up: $current" }
            safeDelete(current)
            throw e
        }
    }
```

4. Импорты не трогать: `VideoMergeHelper` по-прежнему нужен полю `videoMergeHelper`, `Files` — проверке существования файлов записей, `CancellationException`, `NonCancellable`, `Dispatchers`, `withContext` — блокам `catch` и `safeDelete`.

В `VideoMergeHelper.kt` удалить старую перегрузку `suspend fun compressVideo(inputPath: Path): Path` целиком и две константы `MAX_FILE_SIZE_BYTES` и `COMPRESS_THRESHOLD_BYTES` из `companion object` (остаётся только `FFMPEG_TIMEOUT_SECONDS`).

- [ ] **Step 4: Убедиться, что тесты core проходят**

Run (через build-runner):
```
JAVA_HOME=/usr/lib/jvm/zulu25 ./gradlew :frigate-analyzer-core:test --rerun-tasks --no-watch-fs --console=plain
```
Expected: PASS. Если ktlint ругается на неиспользуемые импорты в тесте, выполнить `ktlintFormat` и повторить.

- [ ] **Step 5: Документировать поток**

В `.claude/rules/telegram-export.md` в разделе «Quick Export Flow», пункт 3, после строки

```markdown
   - Exports ±1 min from `recordTimestamp` (2 min total).
```

добавить строку

```markdown
   - A merged file above 45 MiB is re-encoded by `TelegramVideoFitter` (core, `video/`) to fit
     Telegram's upload limit; in ANNOTATED mode the annotated result goes through the fitter
     again (`COMPRESSING_RESULT` stage). See "Size limit" below.
```

- [ ] **Step 6: Commit**

```bash
cat > "$SCRATCH/commit.txt" <<'MSG'
fix(core): fit every export into the Telegram limit, before and after annotation

VideoExportServiceImpl now merges, fits, annotates and fits again instead
of a CRF-only re-encode that inflated 5 MP HEVC merges 2.6x and then
failed on the 50 MB check. The old compressVideo(input) and the size
constants leave VideoMergeHelper; the limits live in FitLimits.TELEGRAM.

Claude-Session: https://claude.ai/code/session_01EytYTJW3JyptT4D4V6aEj6
MSG
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImpl.kt modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/VideoMergeHelper.kt .claude/rules/telegram-export.md modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImplTest.kt
git commit -F "$SCRATCH/commit.txt" -- modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImpl.kt modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/VideoMergeHelper.kt .claude/rules/telegram-export.md modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImplTest.kt
```

---

### Task 8: сообщение «видео слишком большое» в обоих обработчиках

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportExecutor.kt`
- Modify: `modules/telegram/src/main/resources/messages_ru.properties`, `modules/telegram/src/main/resources/messages_en.properties`
- Modify: `.claude/rules/telegram-export.md` (раздел «Size limit»)
- Test: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandlerTest.kt`
- Test: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportExecutorTest.kt`

**Interfaces:**
- Consumes: `VideoTooLargeException` (Task 2, модуль `model`, доступен `telegram` как и `DetectTimeoutException`).
- Produces: ключи `quickexport.error.too.large`, `export.error.too.large`.

- [ ] **Step 1: Падающий тест Quick Export**

В `QuickExportHandlerTest.kt` добавить импорты

```kotlin
import ru.zinin.frigate.analyzer.model.exception.VideoTooLargeException
import kotlin.test.assertFalse
```

и внутри `inner class HandleTest` после теста `handle sends error message for missing files` добавить:

```kotlin
        @Test
        fun `handle sends too large message for VideoTooLargeException`() =
            runTest {
                val handler = createHandler()
                val callback = createMessageCallback()

                val capturedRequests = mutableListOf<Request<*>>()
                coEvery { bot.execute(capture(capturedRequests)) } returns mockk(relaxed = true)
                coEvery {
                    videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any(), any())
                } throws VideoTooLargeException("Video is 60.0 MiB after two compression attempts, limit is 47.7 MiB")

                handler.handle(callback)?.join()

                val expectedTooLargeMsg = msg.get("quickexport.error.too.large", "ru")
                val unavailableMsg = msg.get("quickexport.error.unavailable", "ru")
                val sendTextRequests = capturedRequests.filterIsInstance<SendTextMessage>()
                assertTrue(
                    sendTextRequests.any { it.text.contains(expectedTooLargeMsg) },
                    "Expected 'too large' error message, but got: ${sendTextRequests.map { it.text }}",
                )
                assertFalse(
                    sendTextRequests.any { it.text.contains(unavailableMsg) },
                    "Must not fall back to 'files unavailable': ${sendTextRequests.map { it.text }}",
                )
            }
```

- [ ] **Step 2: Падающий тест `/export`**

В `ExportExecutorTest.kt` добавить импорты

```kotlin
import kotlinx.coroutines.test.advanceUntilIdle
import ru.zinin.frigate.analyzer.model.exception.VideoTooLargeException
```

и перед `private fun firstActiveExport(` добавить тест:

```kotlin
    @Test
    fun `too large export — status message edited with the too large text and no keyboard`() =
        runTest {
            val bot: TelegramBot = mockk(relaxed = true)
            val videoExportService: VideoExportService = mockk(relaxed = true)
            val properties =
                mockk<TelegramProperties>(relaxed = true).also {
                    every { it.sendVideoTimeout } returns Duration.ofSeconds(30)
                }
            val scope = newTestScope()
            val registry = ActiveExportRegistry(scope)
            val executor = ExportExecutor(bot, videoExportService, properties, msg, registry, scope)

            val chatId = ChatId(RawChatId(42L))

            val capturedRequests = mutableListOf<Request<*>>()
            coEvery { bot.execute(any<Request<*>>()) } coAnswers {
                val req = firstArg<Request<*>>()
                capturedRequests.add(req)
                if (req is SendTextMessage) {
                    // Same shape as the cancellation test above: the status message must carry a
                    // real PrivateChatImpl so that editMessageText can read its chat id.
                    mockk<PrivateContentMessage<MessageContent>>(relaxed = true).apply {
                        every { chat } returns PrivateChatImpl(id = chatId, firstName = "Test")
                    }
                } else {
                    mockk(relaxed = true)
                }
            }

            coEvery {
                videoExportService.exportVideo(any(), any(), any(), any(), any(), any())
            } throws VideoTooLargeException("Video is 60.0 MiB after two compression attempts, limit is 47.7 MiB")

            val outcome =
                ExportDialogOutcome.Success(
                    Instant.parse("2026-02-16T12:00:00Z"),
                    Instant.parse("2026-02-16T12:05:00Z"),
                    "cam1",
                    ExportMode.ORIGINAL,
                )

            val executeJob = scope.launch { executor.execute(chatId, ZoneOffset.UTC, outcome, "ru") }
            executeJob.join()
            advanceUntilIdle()

            val expected = msg.get("export.error.too.large", "ru")
            val generic = msg.get("export.error.original", "ru")
            val editRequests = capturedRequests.filterIsInstance<EditChatMessageText>()
            val errorEdit = editRequests.find { it.text.orEmpty().contains(expected) }
            assertNotNull(errorEdit, "Expected a 'too large' editMessageText, got: ${editRequests.map { it.text }}")
            assertNull(errorEdit.replyMarkup, "Error edit must clear the reply markup")
            assertTrue(
                editRequests.none { it.text.orEmpty().contains(generic) },
                "Must not show the generic export error: ${editRequests.map { it.text }}",
            )
            assertNull(firstActiveExport(registry), "registry must be released after the failure")

            scope.shutdown()
        }
```

- [ ] **Step 3: Убедиться, что тесты падают**

Run (через build-runner):
```
JAVA_HOME=/usr/lib/jvm/zulu25 ./gradlew :frigate-analyzer-telegram:test --tests 'ru.zinin.frigate.analyzer.telegram.bot.handler.quickexport.QuickExportHandlerTest' --tests 'ru.zinin.frigate.analyzer.telegram.bot.handler.export.ExportExecutorTest' --rerun-tasks --no-watch-fs --console=plain
```
Expected: FAIL. Оба новых теста падают на `msg.get(...)` с `NoSuchMessageException` (ключей ещё нет) или на assert текста.

- [ ] **Step 4: Добавить ключи**

`messages_ru.properties`: после строки `quickexport.error.unavailable=Файлы записи недоступны.` добавить

```properties
quickexport.error.too.large=Видео не помещается в лимит Telegram 50 МБ даже после сжатия. Попробуйте /export с меньшим диапазоном.
```

и после строки `export.error.original=Ошибка экспорта видео. Попробуйте меньший диапазон или другую камеру.` добавить

```properties
export.error.too.large=Видео не помещается в лимит Telegram 50 МБ даже после сжатия. Попробуйте меньший диапазон.
```

`messages_en.properties`: после строки `quickexport.error.unavailable=Recording files unavailable.` добавить

```properties
quickexport.error.too.large=The video exceeds Telegram's 50 MB limit even after compression. Try /export with a shorter range.
```

и после строки `export.error.original=Error exporting video. Try a shorter range or different camera.` добавить

```properties
export.error.too.large=The video exceeds Telegram's 50 MB limit even after compression. Try a shorter range.
```

- [ ] **Step 5: Маппинг в обработчиках**

`QuickExportHandler.kt`: добавить импорт `import ru.zinin.frigate.analyzer.model.exception.VideoTooLargeException` и в `runExport`, в блоке

```kotlin
            val errorMsg =
                when (e) {
                    is IllegalArgumentException -> msg.get("quickexport.error.not.found", lang)
                    is IllegalStateException -> msg.get("quickexport.error.unavailable", lang)
                    is DetectTimeoutException -> msg.get("quickexport.error.annotation.timeout", lang)
                    else -> msg.get("quickexport.error.generic", lang)
                }
```

добавить ветку перед `else`:

```kotlin
                    is VideoTooLargeException -> msg.get("quickexport.error.too.large", lang)
```

`ExportExecutor.kt`: добавить импорт `import ru.zinin.frigate.analyzer.model.exception.VideoTooLargeException` и в `runExport`, в блоке `catch (e: Exception)`, заменить

```kotlin
            val errorText =
                if (mode == ExportMode.ANNOTATED) {
                    msg.get("export.error.annotated", lang)
                } else {
                    msg.get("export.error.original", lang)
                }
```

на

```kotlin
            val errorText =
                when {
                    e is VideoTooLargeException -> msg.get("export.error.too.large", lang)
                    mode == ExportMode.ANNOTATED -> msg.get("export.error.annotated", lang)
                    else -> msg.get("export.error.original", lang)
                }
```

- [ ] **Step 6: Убедиться, что тесты telegram проходят**

Run (через build-runner):
```
JAVA_HOME=/usr/lib/jvm/zulu25 ./gradlew :frigate-analyzer-telegram:test --rerun-tasks --no-watch-fs --console=plain
```
Expected: PASS, включая `MessageKeyParityTest`.

- [ ] **Step 7: Документировать лимит**

В `.claude/rules/telegram-export.md` перед строкой `## Cancellation` вставить раздел:

```markdown
## Size limit

Telegram bots may upload at most 50 MB per video. `VideoExportServiceImpl` (core) runs every
export through `TelegramVideoFitter` (`core/video/`) after the merge and, in ANNOTATED mode, again
after the annotation (the vision server returns H.264, so a 30 MiB HEVC merge can come back at
70 MiB):

- up to 45 MiB the file is sent as it is, HEVC originals included;
- above that, `VideoProbe` (ffprobe) reads duration, frame size, fps and audio; `CompressionPlanner`
  turns the 45 MiB budget into a bitrate cap and picks the largest height of 1080/720/540 (never
  above the source) whose bits-per-pixel stays at or above `EXPORT_COMPRESS_MIN_BITS_PER_PIXEL`;
  `VideoMergeHelper.compressVideo` runs libx264 with CRF plus `-maxrate`/`-bufsize`; the result is
  checked against 50 000 000 bytes (`FitLimits.TELEGRAM`), one overshoot is retried from the first
  result with a 10 % smaller cap;
- a second overshoot throws `VideoTooLargeException` (model), shown as
  `quickexport.error.too.large` / `export.error.too.large`. `IllegalStateException` keeps meaning
  "recording files unavailable".

Progress stages: `COMPRESSING` before annotation, `COMPRESSING_RESULT` after it; both appear only
when a re-encode actually happened. The fitter logs the chosen plan and the result sizes at INFO.
Tunables: `FFPROBE_PATH`, `EXPORT_COMPRESS_PRESET`, `EXPORT_COMPRESS_CRF`,
`EXPORT_COMPRESS_MIN_BITS_PER_PIXEL` — see `configuration.md`, "Video Export".

```

- [ ] **Step 8: Commit**

```bash
cat > "$SCRATCH/commit.txt" <<'MSG'
feat(telegram): tell the user when an export cannot fit the Telegram limit

QuickExportHandler mapped every IllegalStateException to "recording files
unavailable", which is what the user saw when the compressed file was
simply too large. VideoTooLargeException now has its own text in both
Quick Export and /export, in Russian and English.

Claude-Session: https://claude.ai/code/session_01EytYTJW3JyptT4D4V6aEj6
MSG
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportExecutor.kt modules/telegram/src/main/resources/messages_ru.properties modules/telegram/src/main/resources/messages_en.properties .claude/rules/telegram-export.md modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandlerTest.kt modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportExecutorTest.kt
git commit -F "$SCRATCH/commit.txt" -- modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportExecutor.kt modules/telegram/src/main/resources/messages_ru.properties modules/telegram/src/main/resources/messages_en.properties .claude/rules/telegram-export.md modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandlerTest.kt modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportExecutorTest.kt
```

---

### Task 9: интеграционный тест с реальным ffmpeg и ffmpeg в CI

**Files:**
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/video/FfmpegCompressionIntegrationTest.kt`
- Modify: `.github/workflows/ci.yml`, `.github/workflows/docker-publish.yml`

**Interfaces:**
- Consumes: всё из Tasks 1–5 с реальными реализациями; `TempFileHelper(properties, clock)` плюс `init()`.
- Produces: ничего нового в коде.

- [ ] **Step 1: Написать интеграционный тест**

Создать `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/video/FfmpegCompressionIntegrationTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.video

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import ru.zinin.frigate.analyzer.core.config.properties.ExportProperties
import ru.zinin.frigate.analyzer.core.helper.TempFileHelper
import ru.zinin.frigate.analyzer.core.helper.VideoMergeHelper
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Runs the real ffmpeg/ffprobe binaries. Skipped (reported as such, not as passed) when they are
 * not installed at /usr/bin; CI installs them with apt before the build.
 */
class FfmpegCompressionIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    private val ffmpeg: Path = Path.of("/usr/bin/ffmpeg")
    private val ffprobe: Path = Path.of("/usr/bin/ffprobe")

    @BeforeEach
    fun requireTools() {
        assumeTrue(Files.isExecutable(ffmpeg), "ffmpeg not found at $ffmpeg, skipping")
        assumeTrue(Files.isExecutable(ffprobe), "ffprobe not found at $ffprobe, skipping")
    }

    @Test
    fun `fitter probes, plans and re-encodes a real video under the limit`() =
        runTest(timeout = 5.minutes) {
            val properties =
                ApplicationProperties(
                    tempFolder = tempDir,
                    ffmpegPath = ffmpeg,
                    ffprobePath = ffprobe,
                    connectionTimeout = Duration.ofSeconds(5),
                    readTimeout = Duration.ofSeconds(5),
                    writeTimeout = Duration.ofSeconds(5),
                    responseTimeout = Duration.ofSeconds(5),
                )
            val tempFileHelper = TempFileHelper(properties, Clock.systemUTC())
            tempFileHelper.init()
            val runner = FfmpegProcessRunner()
            val probe = VideoProbe(properties, runner, JsonMapper.builder().build())
            val planner = CompressionPlanner(ExportProperties())
            val mergeHelper = VideoMergeHelper(properties, tempFileHelper, runner)
            val limits = FitLimits(thresholdBytes = 1L * 1024 * 1024, maxBytes = 1_250_000L)
            val fitter = TelegramVideoFitter(probe, planner, mergeHelper, tempFileHelper, limits)

            // 20 s of synthetic 720p video with a sine tone, near-lossless so that it is well above
            // the 1 MiB threshold.
            val source = tempFileHelper.createTempFile("source-", ".mp4")
            runner.run(
                listOf(
                    ffmpeg.toString(),
                    "-hide_banner",
                    "-nostdin",
                    "-f",
                    "lavfi",
                    "-i",
                    "testsrc2=size=1280x720:rate=12.5",
                    "-f",
                    "lavfi",
                    "-i",
                    "sine=frequency=440:sample_rate=48000",
                    "-t",
                    "20",
                    "-c:v",
                    "libx264",
                    "-preset",
                    "ultrafast",
                    "-crf",
                    "5",
                    "-pix_fmt",
                    "yuv420p",
                    "-c:a",
                    "aac",
                    "-b:a",
                    "128k",
                    "-shortest",
                    "-y",
                    source.toString(),
                ),
                Duration.ofMinutes(2),
            )
            val sourceSize = Files.size(source)
            assertTrue(sourceSize > limits.thresholdBytes, "synthetic source must exceed the threshold, got $sourceSize bytes")

            val info = probe.probe(source)
            assertEquals(1280, info.width)
            assertEquals(720, info.height)
            assertEquals(12.5, info.fps, 0.01)
            assertEquals(20.0, info.durationSeconds, 0.5)
            assertTrue(info.hasAudio)

            // 1 MiB over 20 s leaves ~342 kbps for video: too thin for 720p and 540p at 0.1 bpp,
            // so the planner falls to the smallest candidate.
            val plan = planner.plan(info, limits.thresholdBytes)
            assertEquals(540, plan.scaleHeight)
            assertEquals(64, plan.audioBitrateKbps)

            var compressStarted = false
            val result = fitter.fit(source) { compressStarted = true }

            assertTrue(compressStarted)
            val resultSize = Files.size(result)
            assertTrue(resultSize <= limits.maxBytes, "result is $resultSize bytes, limit is ${limits.maxBytes}")
            assertFalse(Files.exists(source), "the fitter deletes its input after success")

            val resultInfo = probe.probe(result)
            assertEquals(540, resultInfo.height)
            assertEquals(960, resultInfo.width)
            assertTrue(resultInfo.hasAudio)
            assertEquals(20.0, resultInfo.durationSeconds, 0.5)
        }
}
```

- [ ] **Step 2: Запустить локально (ffmpeg 8.0.1 стоит в `/usr/bin`)**

Run (через build-runner):
```
JAVA_HOME=/usr/lib/jvm/zulu25 ./gradlew :frigate-analyzer-core:test --tests 'ru.zinin.frigate.analyzer.core.video.FfmpegCompressionIntegrationTest' --rerun-tasks --no-watch-fs --console=plain
```
Expected: PASS, 1 тест, время до минуты. Если тест падает на размере результата, сообщить точный размер: это сигнал, что потолок VBV не удержал бюджет, и решение (уменьшить `bufsize` до `maxrate` или расширить резерв) принимает пользователь.

- [ ] **Step 3: Добавить ffmpeg в CI**

В `.github/workflows/ci.yml` между шагом «Setup Gradle» (после строки `          cache-provider: basic`) и шагом «Build and test» вставить:

```yaml
      - name: Install ffmpeg
        run: sudo apt-get update && sudo apt-get install -y --no-install-recommends ffmpeg
```

В `.github/workflows/docker-publish.yml` тот же шаг вставить между «Setup Gradle» (после строки `          cache-provider: basic`) и «Extract version from tag».

- [ ] **Step 4: Commit**

```bash
cat > "$SCRATCH/commit.txt" <<'MSG'
test(core): exercise the size fitting with the real ffmpeg and install it in CI

A 20 s synthetic 720p clip with audio goes through probe, planner, encoder
and fitter with a 1 MiB budget and must come back at 540p under 1.25 MB.
The test is skipped where ffmpeg is missing; both workflows now apt-install
it so the encode command is verified on every PR and release.

Claude-Session: https://claude.ai/code/session_01EytYTJW3JyptT4D4V6aEj6
MSG
git add modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/video/FfmpegCompressionIntegrationTest.kt .github/workflows/ci.yml .github/workflows/docker-publish.yml
git commit -F "$SCRATCH/commit.txt" -- modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/video/FfmpegCompressionIntegrationTest.kt .github/workflows/ci.yml .github/workflows/docker-publish.yml
```

---

### Task 10: полная сборка и проверка перед ревью

**Files:** без изменений кода, только проверка.

- [ ] **Step 1: Полная сборка**

Run (через build-runner):
```
JAVA_HOME=/usr/lib/jvm/zulu25 ./gradlew build --rerun-tasks --no-watch-fs --console=plain
```
Expected: BUILD SUCCESSFUL, 0 упавших тестов, ktlint чист. Ориентир по количеству: было 848 тестов и 1 пропущенный; теперь на 50 с лишним больше, пропущенных по-прежнему 1 (интеграционный тест на этой машине выполняется, не пропускается).

- [ ] **Step 2: Проверить рабочее дерево**

```bash
git status --short
```
Expected: в индексе только `docs/deep-research-review-report.md` (как было), остальное untracked как до начала работ. Никаких незакоммиченных правок в `modules/`, `.claude/`, `.github/`.

- [ ] **Step 3: Сверить спеку и код**

Пройти по разделам спеки 4–8 и убедиться, что: команда ffmpeg совпадает с разделом 5; лимиты 47 185 920 и 50 000 000; четыре переменные окружения описаны в `configuration.md`; четыре ключа i18n есть в обоих файлах; `telegram-export.md` содержит раздел «Size limit».

- [ ] **Step 4: Дальше по процессу**

Запросить код-ревью (`superpowers:requesting-code-review`), исправить критичные замечания, повторить сборку. Перед PR удалить `docs/superpowers/` из ветки отдельным коммитом (`git rm -r docs/superpowers`) по правилу пользователя.

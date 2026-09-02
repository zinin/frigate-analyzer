# План реализации: экспорт видео в лимит Telegram

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Любой экспорт видео из Telegram-бота, «Оригинал» и «С объектами», укладывается в лимит загрузки Telegram, а если не укладывается даже после повтора, пользователь видит сообщение о размере, а не «Файлы записи недоступны».

**Architecture:** В `core` появляется пакет `core.video`: `FfmpegProcessRunner` запускает ffmpeg и ffprobe, `VideoProbe` читает параметры файла, `CompressionPlanner` считает разрешение и потолок битрейта из бюджета, `TelegramVideoFitter` укладывает файл в лимит с одной проверкой и одним повтором. `VideoExportServiceImpl` вызывает fitter после склейки и после аннотации. В `telegram` добавляются стадия `COMPRESSING_RESULT` и маппинг `VideoTooLargeException` на собственные тексты.

**Tech Stack:** Kotlin 2.4.10, Spring Boot 4.1.0, kotlinx-coroutines, Jackson 3 (`tools.jackson`), ffmpeg и ffprobe как внешние процессы, JUnit 5, mockk 1.14, kotlinx-coroutines-test, ktlint.

**Spec:** `docs/superpowers/specs/2026-09-02-export-video-size-limit-design.md`

**Статус (2026-09-02):** задачи 1–10 выполнены, внешнее ревью через `/claude-mesh:mesh-review` пройдено (см. Task 10). Осталось удалить `docs/superpowers/` отдельным коммитом, запушить ветку и открыть PR в `master`; после выкладки — замер на cam4 по разделу 12 дизайна.

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

✅ Done — see commit(s): `ccdc293`

---

### Task 2: CompressionPlanner, ExportProperties, VideoTooLargeException

✅ Done — see commit(s): `b2f56f0`

---

### Task 3: VideoProbe (ffprobe) и `FFPROBE_PATH`

✅ Done — see commit(s): `02565dd`

---

### Task 4: `VideoMergeHelper.compressVideo(input, plan)`

✅ Done — see commit(s): `bd07106`

---

### Task 5: FitLimits и TelegramVideoFitter

✅ Done — see commit(s): `df2c83f`

---

### Task 6: стадия `COMPRESSING_RESULT` в telegram

✅ Done — see commit(s): `bcad45c`

---

### Task 7: `VideoExportServiceImpl` через fitter

✅ Done — see commit(s): `7999f42`

---

### Task 8: сообщение «видео слишком большое» в обоих обработчиках

✅ Done — see commit(s): `f35425f`

---

### Task 9: интеграционный тест с реальным ffmpeg и ffmpeg в CI

✅ Done — see commit(s): `c33f8f6`

---

### Task 10: полная сборка и проверка перед ревью

✅ Done — сборка проверена (912 тестов, 0 падений, 1 skip вне ветки, ktlint чист) и внешнее ревью пройдено: коммиты `3943aa0` (13 авто-исправлений), `44e52e0` (кооперативная отмена ffmpeg), `7489874` (таймауты ORIGINAL 12 мин). Из Step 4 остаётся только `git rm -r docs/superpowers` отдельным коммитом перед PR.

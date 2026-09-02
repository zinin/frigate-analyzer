# Экспорт видео: гарантированное попадание в лимит Telegram

Дата: 2026-09-02. Ветка: `fix/export-video-size-limit`. Статус: дизайн утверждён, план не написан.

## 1. Проблема

Quick Export «Оригинал» по записи камеры cam4 упал с ошибкой
`Video too large even after compression: 294MB`, а пользователь увидел в чате
«Файлы записи недоступны». Склейка двенадцати клипов Frigate за две минуты весила 113 MiB,
«сжатие» шло 148 секунд и дало 294 MiB.

Причина. `VideoMergeHelper.compressVideo` перекодирует склейку командой
`ffmpeg -i <merged> -vcodec libx264 -crf 28 -preset fast -acodec aac`: без масштабирования,
без целевого битрейта, без учёта исходного кодека. Все камеры пишут HEVC около 5 МП
(2560×1920 и 2880×1620, 12,5 fps). H.264 при том же качестве требует в 2–2,5 раза больше бит,
чем HEVC, поэтому перекодирование раздувает файл, а код умеет только упасть по лимиту.
Даже с даунскейлом до 720p при CRF 28 склейка из инцидента дала бы около 54 MiB: замер на самом
тяжёлом клипе показал сжатие лишь в 2,1 раза.

Камеры cam1–cam3 обычно укладываются в 45 MiB за две минуты и уходят без перекодирования.
cam4 в среднем даёт 65 MB за две минуты, то есть почти каждый её экспорт попадает в сломанную
ветку. В оживлённый момент туда попадёт любая камера.

Смежная дыра: в режиме «С объектами» файл от vision-сервера уходит в Telegram без проверки
размера. Сервер отдаёт H.264, поэтому склейка HEVC в 30 MiB может вернуться файлом на 70 MiB.

## 2. Цели и границы

Цели:

- Любой экспорт, «Оригинал» и «С объектами», доходит до пользователя, если файлы записи
  существуют. Размер результата не превышает 50 MB (50 000 000 байт).
  Документация Telegram называет лимит «50 MB», точная граница в байтах неизвестна, поэтому
  берётся более строгое десятичное прочтение.
- Качество результата максимальное из того, что помещается в бюджет: разрешение выбирается
  по бюджету, битрейт ограничен сверху, простая сцена получает лучшее качество, чем сложная.
- Если файл не помещается даже после повтора, пользователь видит сообщение о размере,
  а не о недоступных файлах.
- Логи уровня INFO позволяют понять, что выбрал планировщик и что получилось.

Вне границ:

- локальный Bot API сервер (лимит 2000 MB);
- аппаратное кодирование и `libx265` на выходе: доступ контейнера к `/dev/dri` не проверен,
  а программный HEVC на i7-4820K в реальном времени не тянет;
- нарезка результата на несколько видео;
- окно Quick Export ±1 мин, максимум 5 минут для `/export`, таймауты ffmpeg 300 с,
  внешние таймауты 5 и 50 минут;
- отправка HEVC-оригинала без перекодирования, если склейка меньше 45 MiB.

## 3. Принятые решения

| Решение | Выбор | Почему |
|---|---|---|
| Гарантия размера | Кодировать под бюджет, проверять, один повтор | Даунскейл без бюджета не гарантирует лимит; нарезка меняет UX; локальный сервер — новая инфраструктура |
| Разрешение | Адаптивно из 1080/720/540 по битам на пиксель | Две минуты влезают в 1080p, пять минут — в 720p |
| Режим «С объектами» | Проверять и сжимать и после аннотации | Иначе аннотированный экспорт cam4 остаётся лотереей |
| Структура кода | Отдельный слой `fit` из четырёх единиц | Каждая единица тестируется отдельно, сервис экспорта только оркестрирует |
| Стратегия кодирования | Один проход `libx264`, CRF плюс потолок `maxrate`/`bufsize` | Двухпроходное вдвое дольше, голый `-b:v` портит простые сцены |

## 4. Архитектура

Новые единицы живут в модуле `core`, пакет `ru.zinin.frigate.analyzer.core.video`.
`VideoMergeHelper` остаётся в `core.helper`.

| Единица | Тип | Что делает | Зависимости |
|---|---|---|---|
| `FfmpegProcessRunner` | `@Component` | Запускает внешнюю команду с таймаутом, собирает до 500 строк вывода, бросает `RuntimeException` при ненулевом коде или таймауте. Это вынесенный `runFfmpeg` из `VideoMergeHelper` без изменения поведения. | — |
| `VideoProbe` | `@Component` | Вызывает ffprobe с выводом JSON и возвращает `VideoInfo`. | runner, `ApplicationProperties.ffprobePath`, Jackson |
| `CompressionPlanner` | `@Component`, без ввода-вывода | Считает `CompressionPlan` из `VideoInfo` и бюджета; `shrink` ужимает план по факту перебора. | `ExportProperties.compress` |
| `VideoMergeHelper` | `@Component` | `mergeVideos` как сейчас; `compressVideo(input, plan)` строит команду ffmpeg из плана. Сборка аргументов вынесена в чистые функции. | runner, `TempFileHelper`, `ApplicationProperties.ffmpegPath` |
| `TelegramVideoFitter` | `@Component` | `fit(input, onCompressStart)`: укладывает файл в лимит. | probe, planner, mergeHelper, `TempFileHelper` |
| `VideoTooLargeException` | класс в `model/exception` | Файл не помещается в лимит даже после повтора. | — |

Поток в `VideoExportServiceImpl.exportVideo`:

```
current = mergeHelper.mergeVideos(files)            // как сейчас, -c copy
current = fitter.fit(current) { onProgress(COMPRESSING) }
if (mode == ORIGINAL) return current
current = annotate(current)                          // как сейчас
return fitter.fit(current) { onProgress(COMPRESSING_RESULT) }
```

Блок `catch` сервиса удаляет `current` при исключении и отмене, как сегодня. `annotate`
сохраняет контракт: удаляет свой вход и при успехе, и при ошибке. Повторное удаление
безопасно, `deleteIfExists` идемпотентен.

Алгоритм `fit`:

1. Если размер входа не больше порога 45 MiB, вернуть вход как есть. Probe не вызывается,
   callback не вызывается.
2. Вызвать `onCompressStart`, затем `probe(input)`, затем `planner.plan(info, 45 MiB)`.
3. Закодировать: `mergeHelper.compressVideo(input, plan)`. Если результат не больше 50 MB,
   удалить вход, вернуть результат.
4. Иначе `planner.shrink(plan, info, размер результата, 45 MiB)`, закодировать ещё раз со
   входом в виде первого результата, первый результат удалить. Если второй результат не больше
   50 MB, удалить вход, вернуть его.
5. Иначе удалить второй результат и бросить `VideoTooLargeException`.

Владение файлами. При успехе fitter удаляет вход, если создал новый файл. При ошибке или отмене
fitter удаляет только созданные им файлы и оставляет вход вызывающему. Удаление при отмене
идёт под `NonCancellable`, как в сервисе.

## 5. Планировщик

Настройки: список высот `1080, 720, 540` (константа), минимум бит на пиксель `0.1`,
CRF `23`, preset `fast`, звук `64` кбит/с, резерв контейнера `3 %`, множитель повтора `0.9`.

Бюджет:

```
totalKbps  = targetBytes × 8 / 1000 / durationSeconds
usableKbps = totalKbps × (1 − 0.03)
videoKbps  = floor(usableKbps − (hasAudio ? 64 : 0))
```

`durationSeconds` обязана быть больше нуля, иначе `IllegalArgumentException`. Если `videoKbps`
не больше нуля, планировщик бросает `VideoTooLargeException`: бюджета нет даже на звук.

Разрешение. Кандидаты: высоты из списка плюс высота источника, только не больше высоты
источника, без повторов, по убыванию. Для кандидата `h` ширина `w` равна
`sourceWidth × h / sourceHeight`, округлённая до ближайшего чётного числа. Биты на пиксель:
`videoKbps × 1000 / (w × h × fps)`. Берётся первый кандидат, у которого значение не ниже
минимума; если таких нет, последний, то есть наименьший. Если выбрана высота источника,
`scaleHeight` равен `null`, и фильтр масштабирования не добавляется. Апскейла нет никогда.

Кодирование (`VideoMergeHelper.compressVideo`):

```
ffmpeg -hide_banner -nostdin -i <input>
  [-vf scale=-2:<scaleHeight>]
  -c:v libx264 -preset <preset> -crf <crf>
  -maxrate <videoKbps>k -bufsize <2 × videoKbps>k
  -pix_fmt yuv420p
  (-c:a aac -b:a 64k | -an)
  -movflags +faststart -y <output>
```

`-nostdin` добавляется и в команду склейки: процесс никогда не пишет ffmpeg в stdin.
`-movflags +faststart` даёт Telegram потоковое воспроизведение. `-pix_fmt yuv420p` делает
результат предсказуемым для 10-битных источников. Потолок `maxrate` с буфером `bufsize`
ограничивает размер значением `videoKbps × duration + bufsize`: для 120 секунд запас буфера
меньше 2 %, резерв контейнера 3 %, а зазор между целью 45 MiB и лимитом 50 MB составляет 6 %.

Повтор (`shrink`):

```
videoKbps' = floor(videoKbps × targetBytes / actualBytes × 0.9)
```

Разрешение выбирается заново с новым потолком, при этом высота источника для выбора равна
`plan.scaleHeight ?: info.height`, то есть высоте первого результата; если выбор совпал с ней,
`scaleHeight` равен `null`. CRF, preset и звук не меняются. Вход повтора — первый результат: он уже H.264 и меньшего разрешения, поэтому
декодируется быстро.

Примеры при 12,5 fps без звука, цель 45 MiB:

| Камера | Окно | Потолок видео | 1080p, бит/px | 720p, бит/px | Выбор |
|---|---|---|---|---|---|
| cam4 2560×1920 | 2 мин | 3,05 Мбит/с | 0,157 | 0,353 | 1440×1080 |
| cam1 2880×1620 | 2 мин | 3,05 Мбит/с | 0,118 | 0,265 | 1920×1080 |
| cam4 2560×1920 | 5 мин | 1,22 Мбит/с | 0,063 | 0,141 | 960×720 |
| cam1 2880×1620 | 5 мин | 1,22 Мбит/с | 0,047 | 0,106 | 1280×720 |

Со звуком потолок ниже на 64 кбит/с, выбор в примерах не меняется.

## 6. Интерфейсы

```kotlin
// core.video
data class VideoInfo(
    val durationSeconds: Double,
    val width: Int,
    val height: Int,
    val fps: Double,
    val hasAudio: Boolean,
)

data class CompressionPlan(
    val scaleHeight: Int?,        // null: без фильтра масштабирования
    val videoMaxrateKbps: Int,
    val audioBitrateKbps: Int?,   // null: -an
    val crf: Int,
    val preset: String,
)

class CompressionPlanner(settings: CompressProperties) {
    fun plan(info: VideoInfo, targetBytes: Long): CompressionPlan
    fun shrink(previous: CompressionPlan, info: VideoInfo, actualBytes: Long, targetBytes: Long): CompressionPlan
}

class FfmpegProcessRunner {
    /** Возвращает захваченные строки вывода (stdout и stderr вместе, не больше 500). */
    suspend fun run(command: List<String>, timeout: Duration): List<String>
}

class VideoProbe(runner: FfmpegProcessRunner, properties: ApplicationProperties) {
    suspend fun probe(path: Path): VideoInfo
}

data class FitLimits(val thresholdBytes: Long, val maxBytes: Long) {
    companion object { val TELEGRAM = FitLimits(thresholdBytes = 45L * 1024 * 1024, maxBytes = 50_000_000L) }
}

class TelegramVideoFitter(
    probe: VideoProbe,
    planner: CompressionPlanner,
    mergeHelper: VideoMergeHelper,
    tempFileHelper: TempFileHelper,
    limits: FitLimits = FitLimits.TELEGRAM,
) {
    suspend fun fit(input: Path, onCompressStart: suspend () -> Unit = {}): Path
}

// model.exception
class VideoTooLargeException(message: String) : RuntimeException(message)
```

Цель бюджета для `plan` равна `limits.thresholdBytes`. Константы `MAX_FILE_SIZE_BYTES` и
`COMPRESS_THRESHOLD_BYTES` уходят из `VideoMergeHelper` в `FitLimits.TELEGRAM`;
`FFMPEG_TIMEOUT_SECONDS` остаётся в `VideoMergeHelper`. Таймаут ffprobe: 30 секунд.

Команда ffprobe:

```
ffprobe -v error -show_entries stream=codec_type,width,height,avg_frame_rate,r_frame_rate:format=duration -of json <file>
```

Разбор: первый поток с `codec_type = video` даёт размер кадра и fps; наличие любого потока
`audio` даёт `hasAudio`; `format.duration` даёт длительность. fps берётся из `avg_frame_rate`
вида `25/2`; если там `0/0` или мусор, из `r_frame_rate`; если и там мусор, `25.0` с
предупреждением в лог. Отсутствие видеопотока или длительности даёт `RuntimeException` с текстом
про ffprobe, а не `IllegalStateException`: Quick Export маппит `IllegalStateException` на «файлы
недоступны», а здесь нужна общая ошибка экспорта.

В `VideoExportProgress.Stage` добавляется `COMPRESSING_RESULT` между `ANNOTATING` и `SENDING`.

## 7. Конфигурация

`application.yaml`:

```yaml
application:
  ffprobe-path: ${FFPROBE_PATH:/usr/bin/ffprobe}
  export:
    compress:
      preset: ${EXPORT_COMPRESS_PRESET:fast}
      crf: ${EXPORT_COMPRESS_CRF:23}
      min-bits-per-pixel: ${EXPORT_COMPRESS_MIN_BITS_PER_PIXEL:0.1}
```

`ffprobePath: Path` добавляется в `ApplicationProperties` рядом с `ffmpegPath`. Новый класс
`ExportProperties` с префиксом `application.export` и вложенным `CompressProperties`
регистрируется по образцу `DetectProperties`. Валидация: preset не пустой, CRF от 0 до 51,
минимум бит на пиксель положительный. В образе ffprobe уже есть: Alpine ставит его вместе с ffmpeg.

## 8. Ошибки, тексты, стадии, логи

Маппинг ошибок:

- `QuickExportHandler`: ветка `is VideoTooLargeException` перед `else` даёт
  `quickexport.error.too.large`. Маппинг `IllegalStateException` на «Файлы записи недоступны»
  остаётся для настоящих случаев: нет записей, нет файлов, нет камеры.
- `ExportExecutor`: `VideoTooLargeException` даёт `export.error.too.large`, остальные
  исключения — прежние тексты по режиму.

Новые ключи i18n, русский и английский:

| Ключ | Русский | English |
|---|---|---|
| `quickexport.error.too.large` | Видео не помещается в лимит Telegram 50 МБ даже после сжатия. Попробуйте /export с меньшим диапазоном. | The video exceeds Telegram's 50 MB limit even after compression. Try /export with a shorter range. |
| `export.error.too.large` | Видео не помещается в лимит Telegram 50 МБ даже после сжатия. Попробуйте меньший диапазон. | The video exceeds Telegram's 50 MB limit even after compression. Try a shorter range. |
| `export.progress.compressing.result` | Сжатие результата | Compressing result |
| `quickexport.progress.compressing.result` | ⚙️ Сжатие результата... | ⚙️ Compressing result... |

Стадии. `renderProgress` получает флаг `compressingResult` и строит список: подготовка, склейка,
сжатие (если было), аннотация (если режим с объектами), сжатие результата (если было), отправка,
готово. `ExportExecutor` запоминает второй флаг по образцу `hadCompressing`.
`QuickExportHandler.renderProgressButton` получает ветку для новой стадии.

Логи fitter:

- INFO перед кодированием: размер входа, длительность, размер кадра и fps источника, выбранное
  разрешение, потолок битрейта, CRF, preset.
- INFO после кодирования: размер результата и номер попытки.
- WARN перед повтором: размер первого результата, лимит, новый потолок и высота.
- Команда ffmpeg остаётся на DEBUG.

## 9. Тесты

Порядок TDD: тест, затем код, по каждой единице.

- `CompressionPlannerTest`: бюджет со звуком и без; четыре строки таблицы из раздела 5;
  источник 720p даёт 720 или 540; источник 480p даёт высоту источника и `scaleHeight = null`;
  округление ширины до чётного; ни один кандидат не прошёл порог — берётся наименьший;
  `shrink` уменьшает потолок и не поднимает высоту выше прежней; нулевая длительность даёт
  `IllegalArgumentException`; нулевой бюджет видео даёт `VideoTooLargeException`.
- `VideoProbeTest`: runner подменён моком с заготовленным JSON. Разбор длительности, размера,
  fps `25/2`, звука; откат `0/0` на `r_frame_rate`, затем на 25; нет видеопотока —
  исключение; команда собирается из `ffprobePath` и ожидаемых аргументов.
- `VideoMergeHelperTest`: команда сжатия из плана с фильтром и без, с `maxrate`/`bufsize`,
  CRF, preset, звуком и `-an`, `faststart`; склейка пишет concat-список с экранированием
  кавычек и зовёт runner с `-c copy`; один файл копируется без ffmpeg; при сбое выход удалён.
- `FfmpegProcessRunnerTest`: реальные процессы через `sh -c`: успех, ненулевой код с хвостом
  вывода в сообщении, таймаут с принудительным завершением.
- `TelegramVideoFitterTest`: моки probe, planner, mergeHelper; размеры задаются реальными
  файлами в `@TempDir` через `RandomAccessFile.setLength`, без `mockkStatic(Files)`. Случаи:
  ниже порога — вход возвращается, probe и callback не вызваны; выше порога — callback один
  раз, вход удалён; перебор — `shrink` и повтор со входом в виде первого результата, первый
  результат удалён; второй перебор — `VideoTooLargeException`, всё удалено; сбой кодирования —
  вход цел; отмена — выходы удалены.
- `VideoExportServiceImplTest`: два теста про сжатие переписываются на мок fitter; новые:
  в режиме ANNOTATED второй `fit` получает аннотированный файл и его результат возвращается;
  callback второго `fit` даёт `COMPRESSING_RESULT`; сбой второго `fit` удаляет аннотированный
  файл; `VideoTooLargeException` прокидывается.
- `QuickExportHandlerTest`, `ExportExecutorTest`: новый текст для `VideoTooLargeException`,
  кнопки восстановлены. `ExportModelsTest`: отрисовка списка стадий с обоими флагами.
- `FfmpegCompressionIntegrationTest` с реальным ffmpeg: генерирует 20-секундный ролик 1280×720
  со звуком через `lavfi` (`testsrc2` и `sine`) с высоким битрейтом; прогоняет probe,
  планировщик, кодирование и fitter с `FitLimits(1 MiB, 1.25 MiB)`. Проверяет параметры из
  probe, размер результата не больше `maxBytes`, высоту результата по плану. Если ffmpeg или
  ffprobe не найдены, тест пропускается через `Assumptions` и отчёт показывает skipped.

## 10. CI

В `ci.yml` и `docker-publish.yml` перед шагом «Build and test» добавляется шаг:

```yaml
- name: Install ffmpeg
  run: sudo apt-get update && sudo apt-get install -y --no-install-recommends ffmpeg
```

Оба workflow выполняют сборку с тестами, поэтому интеграционный тест идёт на каждом PR
и на каждом релизе.

## 11. Документация

- `.claude/rules/telegram-export.md`: поток экспорта с шагом `fit`, лимиты, новая ошибка,
  новая стадия.
- `.claude/rules/configuration.md`: `FFPROBE_PATH`, `EXPORT_COMPRESS_PRESET`,
  `EXPORT_COMPRESS_CRF`, `EXPORT_COMPRESS_MIN_BITS_PER_PIXEL`.

## 12. Риски и проверка после выкладки

- Скорость. Узким местом становится декодирование HEVC 5 МП. Ожидание: 60–90 секунд на
  двухминутное окно вместо 148, от 2,5 до 4 минут на пятиминутный `/export`. Второе близко
  к таймауту ffmpeg 300 секунд и внешнему таймауту 5 минут. После выкладки замерить по логам
  INFO; если тесно, поднять оба таймаута одной правкой.
- Точность потолка. VBV ограничивает размер локально, итог может превысить `maxrate × duration`
  на размер буфера. Резерв 3 %, зазор 6 % и повтор это покрывают; третьей попытки нет сознательно.
- ffprobe. Если бинарника нет, первый же экспорт с большим файлом упадёт с общей ошибкой и
  записью в лог. Проверки на старте нет: образ гарантирует наличие.
- Проверка на проде после выкладки: Quick Export «Оригинал» и «С объектами» по cam4 в
  оживлённое время, `/export` cam4 на 5 минут, сверка размеров и времени в логе.

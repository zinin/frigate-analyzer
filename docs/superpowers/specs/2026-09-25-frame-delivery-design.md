# Потолок размера кадра на пресете и распознавание выброшенных кадров

**Дата:** 2026-09-25
**Ветка:** `feature/frame-delivery`
**Предыстория.** Пресеты описаний (`provider + model + effort`) владелец переключает в `/ai`, и один
каталог обслуживает описания и LLM-судью. Размер кадра, уходящего модели, задаётся только на
уровне фичи: `APP_AI_DESCRIPTION_MAX_IMAGE_SIDE` (по умолчанию `0`, исходное разрешение) и
`APP_AI_JUDGE_MAX_IMAGE_SIDE` (`1280`). Живые замеры 2026-09-25: BYOK-модель GLM-5.3-Flash за
шлюзом LANIT (LiteLLM → nginx → vLLM), grok 1.0.41, команда в точности как у `GrokCommandBuilder`:

| Запрос | Тело запроса | Итог |
|---|---|---|
| 1 кадр 2560×1920 | ≈1,5 МБ | ответ по кадру |
| 10 кадров по 1568 px | 10,4 МБ | ответ по кадрам, 27 тыс. входных токенов |
| 10 кадров 2560×1920 | 15,8 МБ | `413 Request Entity Too Large` от nginx; grok выкинул все 10 картинок и повторил запрос без них; exit 0 и валидный JSON «Кадры недоступны для анализа», 4,3 тыс. входных токенов |

Камеры продакшена снимают 2560×1440 и 2560×1920, описания шлют до 10 кадров. Уменьшить кадры
настройкой фичи — значит уменьшить их и `claude-opus`, и любой другой модели, у которой такого
лимита нет. Выброс картинок grok делает молча: приложение получает обычный ответ, отправляет его
получателям как описание, а судья выносит по нему вердикт, не видя кадров.

GLM-5.3-Flash за LANIT здесь только пример. Задача общая: у одной модели или шлюза лимит есть, у
другой нет.

## Задача

1. Дать пресету необязательный потолок длинной стороны кадра, не меняя поведения остальных пресетов.
2. Распознавать ответ, полученный grok после выброса картинок, и не выдавать его за описание или
   вердикт.

Ограничения:

- Пресеты без нового поля работают ровно как сейчас. Код не знает конкретных моделей и шлюзов.
- Контракт `ai-description` наружу не меняется: те же `DescriptionAgent`, `JudgeAgent`,
  `DescriptionException`, те же пути ошибок в `core` и `telegram`.
- Логирование grok, stderr процесса и классификация ошибок в `GrokExceptionMapper` не трогаются.
- Ни миграций БД, ни новых переменных окружения.

## Что меняется для владельца

- У пресета в `application-docker.yaml` появляется необязательный ключ `max-image-side`. Пока его
  нет, ничего не меняется.
- Стартовая строка каталога и строка об активном пресете показывают потолок у пресета, где он
  задан: `glm-flash (grok/glm-5-3-flash/low, max-image-side=1568)`.
- Если grok выкинул картинки и на единственном повторе, уведомление несёт штатное «⚠ Описание
  недоступно» вместо текста «кадры недоступны», а судья отправляет уведомление без вердикта
  (`FAILOVER` / `INVALID_RESPONSE`). Лог объясняет причину WARN-ом с подсказкой про потолок.
- Если распознавание не находит в логе grok записей своего запуска, в логе приложения один раз за
  процесс появляется WARN, а поведение остаётся сегодняшним.

## Принятые решения

1. **Потолок живёт на пресете.** Пресет — единица конфигурации, которую выбирает владелец. Два
   пресета на одной модели повторяют значение.
2. **Семантика — потолок, а не замена.** Итоговая сторона — меньшее из ненулевых значений фичи и
   пресета; `0` значит «без предела». Пресет выражает предел модели или шлюза и может только
   ужесточить лимит фичи. Замена подняла бы кадры судьи на таком пресете с 1280 до 1568: настройка,
   заведённая ради ограничения, увеличила бы запрос.
3. **Только сторона, без числа кадров.** `core` отбирает кадры (top-N по качеству, затем сортировка
   по `frameIndex`) раньше, чем известен пресет, и ранг качества до executor-а не доходит. Потолок
   числа кадров потребовал бы нести ранг через `DescriptionRequest` и `JudgeRequest`. Измеренному
   случаю хватает стороны: 10 кадров по 1568 px проходят.
4. **Потолок не зависит от провайдера.** Кадры уменьшает `VisionCallExecutor`, общий для обоих
   провайдеров, поэтому поле годится и BYOK-модели grok, и claude за `ANTHROPIC_BASE_URL`.
5. **Распознавание читает `GROK_HOME/logs/unified.jsonl`.** grok пишет туда JSON-строку
   `"msg":"shell.turn.images_stripped"` с `sid` сессии и числом выкинутых картинок в `ctx.stripped`;
   тот же `sessionId` приходит в stdout. Отвергнутые варианты:
   - stderr при `RUST_LOG=warn`: человекочитаемый текст с ANSI-кодами (`NO_COLOR` их не снимает) и
     посторонние WARN/ERROR в том самом хвосте stderr, по которому `GrokExceptionMapper.fromFailure`
     классифицирует падения без error-JSON. Шум мог бы дать ложный `Unauthorized` и ложный алерт
     владельцу;
   - эвристика по `usage.input_tokens`: порог зависит от модели и разрешения (у GLM один кадр —
     4,1 тыс. токенов, десять выкинутых — 4,3 тыс.);
   - каталог сессии `GROK_HOME/sessions/<cwd>/<sessionId>/`: маркера выброса там нет (проверено).

   В документации и строках бинарника grok 1.0.41 не нашлось переключателя, который заставил бы
   grok падать вместо выброса.
6. **Хвост файла, а не смещение.** grok сам урезает `unified.jsonl`, перечитывая и переписывая
   файл (строки бинарника `[unified_log] trim read failed` / `trim rewrite failed`; локальный файл
   на 3,8 МБ, существующий с 2026-08-26, начинается записью 2026-09-25 10:03). Смещение, запомненное
   до запуска, после такой перезаписи указало бы в чужую строку, и ранние записи запуска выпали бы
   из чтения — вплоть до ложного «выброса не было». Записи только что завершившегося запуска —
   самые свежие в файле и переживают урезание, поэтому детектор читает хвост файла после запуска.
7. **Проверка работоспособности.** Каждый запуск, дошедший до модели, пишет в `unified.jsonl`
   строки со своим `sid`. Нет ни одной — распознавание слепо (формат сменился, лог пропал): WARN
   один раз за процесс и fail-open.
8. **Любой выброс отвергает ответ.** Число выкинутых картинок точное, ложных срабатываний нет.
   Вместо выкинутых картинок grok вставляет текст «The server could not process an image, so it
   was left out of this request.», и модель пересказывает его в описании. Частичный выброс на кадрах
   одной камеры маловероятен: 413 выкидывает все.
9. **Выброс — это `InvalidResponse` со штатным повтором.** Смысл тот же, что у гейта Claude: модель
   ответила, не видя кадров. Executor повторяет `InvalidResponse` один раз; при устойчивой причине
   (413) это один лишний текстовый вызов. Неповторяемый вариант потребовал бы нового варианта
   `DescriptionException` или флага на нём — изменение API ради одного вызова.
10. **Без переключателя в конфиге.** Ложное срабатывание требует строки с нашим `sid` и именем
    выброса; сменившийся формат обрабатывает решение 7.

## Конфигурация

```yaml
application:
  ai:
    description:
      presets:
        claude-opus: { provider: claude, model: opus }
        glm-flash:   { provider: grok,   model: glm-5-3-flash, effort: low, max-image-side: 1568 }
```

| Ключ пресета | По умолчанию | Значения | Смысл |
|---|---|---|---|
| `max-image-side` | `0` | `0` или `256..8192` | Потолок длинной стороны кадра для этого пресета. Итог — меньшее из ненулевых значений пресета и фичи (`application.ai.description.common.max-image-side` у описаний, `application.ai.judge.max-image-side` у судьи); `0` — без потолка. |

Итог для примера выше:

| Пресет | Описания (фича `0`) | Судья (фича `1280`) |
|---|---|---|
| `glm-flash` (`1568`) | 1568 | 1280 |
| `claude-opus` (не задан) | исходное разрешение | 1280 |

Legacy-путь (`APP_AI_DESCRIPTION_PROVIDER` при пустой карте) синтезирует пресет с
`max-image-side = 0`. Переменной окружения у поля нет: пресеты объявляются только в yaml.

## Модуль `ai-description`

### Потолок размера кадра

**`config/DescriptionProperties.Preset`** получает `val maxImageSide: Int = 0`, а
`Preset.validate(id)` — проверку:

```kotlin
require(maxImageSide == 0 || maxImageSide in 256..8192) {
    "preset '$id': max-image-side must be 0 (no cap) or 256..8192, was $maxImageSide"
}
```

Проверка не зависит от провайдера, границы совпадают с `CommonSection.maxImageSide` и
`JudgeProperties.maxImageSide`.

**`api/DescriptionPreset`** получает последним параметром `val maxImageSide: Int = 0` с KDoc:
«Потолок длинной стороны кадра этого пресета; `0` — без потолка. Итоговая сторона — меньшее из
ненулевых значений пресета и вызывающей фичи». Параметр последний и со значением по умолчанию,
поэтому конструкторы в тестах `telegram` и `ai-description` не меняются.

**`core/DescriptionPresetCatalogBuilder.entryOf`** копирует `preset.maxImageSide` в view.

**`core/PresetLogFormat.logSignature()`** дописывает `, max-image-side=N`, только если `N > 0`:

```kotlin
internal fun DescriptionPreset.logSignature(): String =
    listOfNotNull(provider, effectiveModel, effort.takeIf { it.isNotBlank() }).joinToString("/") +
        if (maxImageSide > 0) ", max-image-side=$maxImageSide" else ""
```

Функция обслуживает обе INFO-строки — `Description presets: …` и `Active … preset …`, — и формат
остаётся общим. Существующие тесты на точные строки не меняются: их пресеты потолка не задают.

**`core/VisionCallExecutor`.** `downscaleFrames(request)` становится
`downscaleFrames(request, entry.view.maxImageSide)`, итоговую сторону считает чистая функция —
новый член `object FrameDownscaler`:

```kotlin
/** Меньшее из ненулевых ограничений; 0 — ни одно не задано. */
internal fun effectiveMaxSide(featureCap: Int, presetCap: Int): Int =
    listOf(featureCap, presetCap).filter { it > 0 }.minOrNull() ?: 0
```

Пресет к этому моменту уже резолвлен (до семафора), новых чтений настроек нет. Остальное как
сейчас: одно уменьшение на запрос, до повторов и вне `withTimeout`; ошибка уменьшения — кадры как
есть с WARN; DEBUG-строка `Downscaled N frames … to <=X px` печатает итоговое значение. KDoc класса
говорит, что потолок складывается из фичи и пресета.

`/ai` потолок не показывает: владелец выбирает пресет не по нему, а оператор видит значение в
стартовой строке.

### Распознавание выброса в grok

**Что делает grok 1.0.41.** Если эндпоинт отвечает на запрос с картинками ошибкой, которую grok
относит к картинкам (в замере — `413 Payload Too Large`, `reason=payload_heuristic`), grok убирает
картинки, вставляет вместо них текст «The server could not process an image, so it was left out of
this request.» и повторяет запрос. Процесс завершается с exit 0, stdout несёт обычный ответ и
`sessionId`, а в `GROK_HOME/logs/unified.jsonl` остаётся строка:

```json
{"ts":"…","src":"shell","pid":901027,"ver":"1.0.41","lvl":"warn","sid":"01a0d95a-363c-7b41-9de8-9bb19b368c9c","msg":"shell.turn.images_stripped","ctx":{"sampler_request_id":"…","stripped":10,"reason":"payload_heuristic","persist_deferred":false}}
```

**`grok/GrokImageStripDetector`** — новый `@Component` с
`@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")`, как соседи;
зависит от `GrokProperties` и `ObjectMapper`:

```kotlin
class GrokImageStripDetector(properties: GrokProperties, objectMapper: ObjectMapper) {
    /** Хвост unified.jsonl после выхода процесса. Вызывается под тем же GrokHomeGuard.shared, что и запуск. */
    fun capture(): CapturedLog

    /** Разбор захваченного по sessionId из stdout. Чистая функция. */
    fun inspect(captured: CapturedLog, sessionId: String?): StripCheck

    /** WARN при первом вызове за процесс, дальше DEBUG. */
    fun reportBlind(reason: String)
}

sealed interface CapturedLog {
    data class Lines(val lines: List<String>) : CapturedLog
    data class Unavailable(val reason: String) : CapturedLog
}

sealed interface StripCheck {
    data object Clean : StripCheck
    data class Stripped(val images: Int, val reasons: Set<String>) : StripCheck
    data class Blind(val reason: String) : StripCheck
}
```

`CapturedLog` и `StripCheck` — верхнеуровневые типы в том же файле
`grok/GrokImageStripDetector.kt`. Путь — `properties.homePath.resolve("logs/unified.jsonl")`, тот же
`GROK_HOME`, что `GrokCommandBuilder` передаёт процессу.

`capture()`:

- файла нет → `Unavailable("<path> not found")`; `IOException` → `Unavailable` с сообщением;
- читаются последние `CAPTURE_TAIL_BYTES` (1 МиБ) или весь файл, если он меньше. Запуск пишет
  десятки килобайт; даже несколько параллельных запусков не вытеснят его записи из мегабайта;
- в `Lines` попадают только полные строки: если чтение началось с середины файла, первая строка
  отбрасывается; последняя без `\n` тоже (её может дописывать другой запуск).

`inspect(captured, sessionId)`:

- `sessionId` пустой или `null` → `Blind("no sessionId in grok output")`; `Unavailable` →
  `Blind(reason)`;
- строки, не содержащие `sessionId` подстрокой, отсеиваются до разбора JSON; из оставшихся
  непарсящиеся пропускаются, а учитываются только те, где `sid == sessionId`;
- среди них события `msg == "shell.turn.images_stripped"` → `Stripped(images, reasons)`: `images` —
  сумма `ctx.stripped` (нецелое или отсутствующее значение считается за 1), `reasons` — набор
  `ctx.reason`. Сумма, а не максимум: каждое событие выкидывает картинки, ещё остававшиеся в
  запросе, и одну картинку дважды не считает;
- строки нашего `sid` есть, событий выброса нет → `Clean`;
- строк нашего `sid` нет → `Blind("no entries for session <id> in the last <N> bytes of <path>")`.

**`grok/GrokBackend`.** `runGrok` возвращает результат процесса вместе с захваченным хвостом лога —
приватный `data class GrokRun(val result: GrokProcessResult, val log: CapturedLog)`:

```kotlin
private suspend fun runGrok(…): GrokRun {
    val command = commandBuilder.build(promptFile, model, effort, structuredOutput, jsonSchema, systemPrompt)
    return guard.shared {
        val result = runner.run(command)
        GrokRun(result, stripDetector.capture())
    }
}
```

`capture()` выполняется под `GrokHomeGuard.shared`: пока запуск держит shared, `GrokHomeSweeper`
(exclusive) не стартует и не удалит файл между выходом процесса и чтением. Разбор — вне guard. При
повторе без `--json-schema` второй запуск захватывает свой хвост, и проверяется запуск, чей ответ
используется.

`complete` проверяет выброс после `outputParser.parse` и до извлечения `payload`:

```kotlin
val strip = if (request.frames.isEmpty()) StripCheck.Clean else stripDetector.inspect(run.log, output.sessionId)
when (strip) {
    is StripCheck.Stripped -> throw rejectStripped(request, output, strip)
    is StripCheck.Blind -> stripDetector.reportBlind(strip.reason)
    StripCheck.Clean -> Unit
}
```

Проверка не выполняется, если в запросе нет кадров или grok вернул error-JSON либо ненулевой
exit: ответа тогда нет, и исключение уже брошено.

`GrokBackendFactory` получает детектор в конструктор и передаёт его в каждый `GrokBackend`.

### Реакция на выброс

`rejectStripped` пишет WARN и возвращает исключение:

```
Grok dropped 10 of 10 frame(s) for <requestId> (model=glm-5-3-flash, reason=payload_heuristic);
rejecting the answer: <первые 300 символов payload>
```

```kotlin
DescriptionException.InvalidResponse(
    detail = "grok dropped $images of ${request.frames.size} frame(s) before the model call " +
        "(reason=${reasons.joinToString()}); if the endpoint limits request size, set max-image-side on the preset",
)
```

Подсказка общая: backend не знает id пресета, но оператор найдёт по ней ключ из раздела
«Конфигурация». Длина фрагмента — те же 300 символов, что у `DefaultClaudeInvoker.ANSWER_LOG_MAX`.

`reportBlind` пишет WARN один раз за процесс (флаг на детекторе-синглтоне), дальше DEBUG:

```
Cannot verify frame delivery for grok runs: <reason>; image drops by grok will go unnoticed
```

Существующая DEBUG-строка `Grok call …` получает поле `strip=clean|stripped|blind`.

## Поведение потребителей

Кода в `core` и `telegram` не добавляется: `InvalidResponse` уже проходит оба пути.

- **Описания.** Executor повторяет вызов один раз; после второго выброса facade получает
  `Result.failure`, и `DescriptionEditJobRunner` рисует `DescriptionState.Failed` — «⚠ Описание
  недоступно».
- **Судья.** `NotificationJudgeService` записывает `FAILOVER` / `PUBLISH` / `INVALID_RESPONSE` и
  отправляет уведомление; строка видна в `/status` и `/verdicts`. Snooze не взводится.
- **Авторизация.** `ProviderAuthTracker` не трогается: `InvalidResponse` — не успех и не
  `Unauthorized`.
- **Лимиты.** Слот rate limiter не возвращается, как при любой неудаче.

## Деплой и документация

Миграций и переменных окружения нет; существующие `application-docker.yaml` и `.env` работают без
правок. На сервер изменение попадает с новым образом: тег → CI → `latest`.

Документация:

- **`README.md`**: раздел **Presets** — абзац про `max-image-side` с общим примером (эндпоинт
  ограничивает размер запроса или разрешение) и правилом `min(фича, пресет)`; раздел
  **Custom models (BYOK)** — фраза о том, что ответ после выброса картинок отвергается, а лог
  подсказывает потолок; таблица описаний — строка `APP_AI_DESCRIPTION_MAX_IMAGE_SIDE`, которой там
  нет.
- **`.claude/rules/ai-description.md`**: абзац **Declaration** — поле и правило; таблица слоёв —
  `DescriptionPreset` (поле), `FrameDownscaler` (итог из фичи и пресета), новая строка
  `GrokImageStripDetector`; раздел **Grok invocation** — подраздел о выбросе картинок (поведение
  grok, чтение хвоста под `GrokHomeGuard.shared` и почему не смещение, три исхода, `InvalidResponse`,
  проверка по `sid`, перепроверка при смене `GROK_VERSION`); **GROK_HOME hygiene** — `unified.jsonl`
  теперь ещё и читается; **Testing** — новые тесты.
- **`.claude/rules/configuration.md`**: строки `APP_AI_DESCRIPTION_MAX_IMAGE_SIDE` и
  `APP_AI_JUDGE_MAX_IMAGE_SIDE` — отсылка к потолку пресета.
- **`docker/deploy/application-docker.yaml.example`**: закомментированный BYOK-пресет с
  `max-image-side` и строкой о том, когда он нужен. **`docker/deploy/.env.example`**: в комментарии к
  `APP_AI_DESCRIPTION_MAX_IMAGE_SIDE` — отсылка к пресету.
- **`CLAUDE.md`** проекта: строка **Description presets** — `provider + model + effort + optional
  max-image-side`.

## Тестирование

Фейки на швах, `@TempDir`, проверки текста лога там, где он контракт.

Потолок:

- `DescriptionPresetsValidationTest`: `max-image-side` 100 и 9000 отклоняются с id пресета в
  сообщении; 0 и 1568 проходят.
- `AiDescriptionAutoConfigurationTest`: `application.ai.description.presets.g.max-image-side=1568`
  связывается и доходит до `DescriptionPreset.maxImageSide` в каталоге (relaxed binding kebab-case).
- `DescriptionPresetCatalogBuilderTest`: новый тест точной строки — суффикс `, max-image-side=N`
  только у пресета, где он задан; `the startup line names every preset with its values and the
  default` не меняется.
- `VisionCallExecutorTest`, рядом с `frames are downscaled once before the backend sees them`:
  пресет 1568 при фиче 0 → 1568; пресет 1568 при фиче 1280 → 1280; пресет 1024 при фиче 1280 → 1024.
  Случай «оба 0» покрывает `frames are left alone when the limit is disabled`.
- `effectiveMaxSide` — табличный unit-тест.

Распознавание:

- `GrokImageStripDetectorTest` (`@TempDir` в роли `GROK_HOME`), фикстуры — настоящие строки grok
  1.0.41 (`shell.turn.images_stripped`, `shell.turn.inference_done`):
  - выброс нашего `sid` → `Stripped(10, {payload_heuristic})`; два события → сумма;
  - выброс чужого `sid` при наших строках → `Clean`;
  - нет строк нашего `sid`, `sessionId == null`, файла нет → `Blind`;
  - файл больше `CAPTURE_TAIL_BYTES`: читается хвост, первая неполная строка отбрасывается, наши
    строки в хвосте находятся;
  - хвост без `\n` и мусорная строка пропускаются;
  - строка, где `sessionId` встречается не в `sid` (например, в `ctx`), не считается нашей.
- `GrokBackendTest`: фейковый runner во время `run` дописывает фикстуры в
  `<home>/logs/unified.jsonl` и отдаёт stdout с тем же `sessionId`:
  - выброс → `InvalidResponse` с `dropped 1 of 1`, prompt-файл удалён;
  - чистый запуск → ответ;
  - `Blind` → ответ, WARN один раз на два вызова;
  - повтор без `--json-schema` → проверяется второй запуск.

  Существующие тесты не меняются: их runner лог не пишет, детектор возвращает `Blind`, ответ
  проходит.
- Сквозной тест (только POSIX, по образцу `DefaultGrokProcessRunnerTest`): stub-скрипт `grok` пишет
  строку выброса со своим `sessionId` в `$GROK_HOME/logs/unified.jsonl` и печатает stdout;
  `GrokBackend` с настоящими `DefaultGrokProcessRunner` и детектором отдаёт `InvalidResponse`. Тест
  ловит расхождение между `GROK_HOME` из env команды и путём, который читает детектор.

`core` и `telegram` новых тестов не получают: их пути при `InvalidResponse` не меняются и покрыты.

Ручная проверка до merge — с согласия владельца, несколько вызовов BYOK-модели за шлюзом с лимитом
(GLM через LANIT): 10 полных кадров без потолка → `InvalidResponse` с `dropped 10 of 10`; тот же
запрос с `max-image-side: 1568` на пресете → описание.

Сборка — агентом `build-runner`; при ошибках ktlint — `./gradlew ktlintFormat` и повтор.

## Риски

1. **Распознавание привязано к внутренностям grok**: формат `unified.jsonl`, имя
   `shell.turn.images_stripped`, поля `sid` и `ctx.stripped`. Пропавший лог или сменившийся формат
   ловит `Blind` с WARN; переименование одного события вернёт сегодняшнее поведение без сигнала.
   Фикстуры сняты с 1.0.41, документация требует перепроверки при смене `GROK_VERSION`, как для
   `grok inspect`.
2. **Пин версии grok в `Dockerfile` не держит**: `ARG GROK_VERSION=1.0.13`, а в образе от
   2026-09-24 стоит 1.0.41. Версия, а с ней формат лога, может смениться при любой пересборке образа
   без правки кода. Усиливает риск 1.
3. **Лишний вызов при устойчивом выбросе**: повтор `InvalidResponse` после 413 стоит одного
   текстового вызова и нескольких секунд.
4. **Строгость**: ответ, видевший часть кадров, тоже отвергается.
5. **Второй путь потери кадров не покрыт.** В строке `shell.image_budget` grok 1.0.41 пишет
   `trigger_bytes` ≈ 47 МиБ и `evicted`: по названиям полей, при превышении бюджета grok вытесняет
   картинки сам. Наши запросы до 20 МБ до порога не доходят; проверить поведение не удалось, и
   детектор на `evicted` не смотрит.

## Вне рамок

- Потолок числа кадров на пресете (решение 3).
- Показ потолка в `/ai`.
- Адаптивный повтор с уменьшенными кадрами после выброса.
- Неповторяемый вариант исключения для устойчивого выброса.
- Аналогичный гейт для claude-пресетов за сторонним шлюзом.
- Починка пина grok в `Dockerfile` — рекомендуется следующей задачей: без неё риск 1 реален при
  каждой пересборке образа.
- Генерация заголовка сессии: каждый headless-запуск grok 1.0.41 просит вспомогательную модель
  (`grok-4.6`) сгенерировать заголовок; с ключом LANIT это отклонённый 403-запрос к шлюзу на каждый
  вызов (виден в stderr при `RUST_LOG=warn`). Возможный рычаг — переменная `GROK_TITLE_REFRESH` из
  бинарника, не проверена.

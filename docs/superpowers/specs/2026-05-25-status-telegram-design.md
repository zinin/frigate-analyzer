# `/status` REST + Telegram command — Design

**Date:** 2026-05-25
**Author:** Alexander V. Zinin (brainstormed with Claude)
**Branch:** `feat/status-command`

## Goal

Заменить REST `/frigate-analyzer/statistics` на новый `/frigate-analyzer/status`,
включив в ответ информацию о текущем сигнальном статусе камер (HEALTHY/OFFLINE),
и добавить Telegram-команду `/status` (OWNER-only) с тем же контентом в
HTML-форматированном виде, удобном для чтения с телефона.

## Why

Сейчас:

- REST `/statistics` отдаёт `recordings` (total/processed/unprocessed/byCameras/rate) и `detectServers` (status, load), но **не отдаёт** статус сигнала по камерам.
- Статус сигнала виден владельцу только через разрозненные уведомления
  «📡❌ Camera "camX" lost signal» / «📡✅ Camera "camX" is back online», которые
  SignalLossMonitorTask шлёт по событиям. Сводной картины нет — нужно скроллить чат.
- В Telegram нет команды, аналогичной REST `/statistics`.

Цель — единая точка получения снапшота системы: и в виде JSON для скриптов, и в виде
читаемого HTML-сообщения в Telegram.

## Scope

### In scope

1. **REST.** Удалить `GET /statistics`. Добавить `GET /status`, отдающий
   recordings + cameras + detectServers в JSON.
2. **Telegram.** Добавить команду `/status` (OWNER-only), форматирующую тот же
   snapshot в HTML с `<pre>`-блоками для табличных секций.
3. **Сервисный слой.** Извлечь общую логику сбора snapshot'а в `StatusService`,
   переиспользуемый и REST'ом, и Telegram-handler'ом.
4. **Snapshot статусов камер.** Добавить публичный метод
   `SignalLossMonitorTask.snapshotStates(): Map<String, CameraSignalState>` —
   defensive copy.

### Out of scope

- Pagination / фильтры REST.
- Websocket / SSE стрим статуса.
- Inline-кнопка refresh у Telegram-сообщения.
- Deprecation/redirect старого `/statistics` (single-deployment, нет внешних клиентов).
- Кэширование `/status` (вызывается редко).
- Изменения в `HelpCommandHandler` (он автоматически перечислит `/status`).

## Architecture

### Источники данных

| Источник | Поставщик | Доступность |
|---|---|---|
| recordings (total/processed/unprocessed/byCameras/rate) | `RecordingEntityRepository` (методы уже существуют) | всегда |
| detect servers (status, load) | `DetectServerLoadBalancer.getAllServersStatistics()` (метод уже существует) | всегда |
| camera signal status (HEALTHY/OFFLINE, lastSeenAt, offlineFor) | `SignalLossMonitorTask.snapshotStates()` (новый метод) | только если `application.signal-loss.enabled=true` |

### Модули и новые файлы

```
modules/
├── core/
│   ├── controller/
│   │   ├── StatisticsController.kt       (УДАЛИТЬ)
│   │   └── StatusController.kt           (НОВЫЙ)
│   ├── service/
│   │   └── StatusService.kt              (НОВЫЙ — общая логика сбора snapshot'а)
│   └── task/
│       └── SignalLossMonitorTask.kt      (ДОБАВИТЬ snapshotStates())
├── model/
│   ├── response/
│   │   ├── StatisticsResponse.kt         (УДАЛИТЬ)
│   │   └── StatusResponse.kt             (НОВЫЙ — recordings + cameras + detectServers)
│   └── dto/
│       └── CameraStatusDto.kt            (НОВЫЙ — camId, state, lastSeenAt, offlineFor)
└── telegram/
    ├── bot/handler/
    │   └── StatusCommandHandler.kt       (НОВЫЙ — OWNER only, order=6)
    ├── service/impl/
    │   └── StatusMessageFormatter.kt     (НОВЫЙ — HTML рендер с <pre>)
    └── src/main/resources/
        ├── messages_en.properties        (НОВЫЕ ключи status.*)
        └── messages_ru.properties        (НОВЫЕ ключи status.*)
```

### Поток данных

**REST:**
```
GET /frigate-analyzer/status
  → StatusController.getStatus()
     → StatusService.collect(): StatusResponse
     → JSON
```

**Telegram:**
```
User /status → FrigateAnalyzerBot.registerRoutes (OWNER auth via AuthorizationFilter)
  → StatusCommandHandler.handle(message, user)
     → StatusService.collect(): StatusResponse
     → StatusMessageFormatter.format(snapshot, user.languageCode, user.olsonCode): String
     → reply(message, html, parseMode = HTMLParseMode)
```

## DTOs

```kotlin
data class StatusResponse(
    val recordings: RecordingsStatistics,            // как сейчас
    val cameras: CamerasSection,                     // НОВОЕ
    val detectServers: List<DetectServerStatistics>, // как сейчас
)

data class CamerasSection(
    val monitoringEnabled: Boolean, // application.signal-loss.enabled
    val items: List<CameraStatusDto>, // пусто, если monitoringEnabled=false
)

data class CameraStatusDto(
    val camId: String,
    val state: CameraState,    // HEALTHY | OFFLINE
    val lastSeenAt: Instant,
    val offlineFor: Duration?, // null если HEALTHY; downtime при OFFLINE
)

enum class CameraState { HEALTHY, OFFLINE }
```

**Существующие, переиспользуемые без изменений:**
`RecordingsStatistics`, `CameraStatistics`, `DetectServerStatistics`, `ServerLoad`, `ServerStatus`.

**Маппинг `CameraSignalState` → `CameraStatusDto`:**

| In-memory state | state | offlineFor |
|---|---|---|
| `Healthy(lastSeenAt)` | `HEALTHY` | `null` |
| `SignalLost(lastSeenAt, _)` | `OFFLINE` | `Duration.between(lastSeenAt, now)` |

`SignalLost.notificationSent` НЕ выставляется наружу — это implementation detail late-alert flow.

**Сортировка (детерминирована, важно для тестов):**

| Список | Порядок |
|---|---|
| `cameras.items` | `OFFLINE` first → внутри по `camId` asc |
| `recordings.byCameras` | по `camId` asc (как сейчас в SQL) |
| `detectServers` | `DEAD` first → внутри по `id` asc |

## Сервисный слой

### `StatusService`

```kotlin
@Service
class StatusService(
    private val recordingRepository: RecordingEntityRepository,
    private val detectServerLoadBalancer: DetectServerLoadBalancer,
    private val signalLossMonitorTask: ObjectProvider<SignalLossMonitorTask>,
    private val clock: Clock,
) {
    suspend fun collect(): StatusResponse {
        // 1. recordings: 5 запросов через RecordingEntityRepository (как сейчас в StatisticsController)
        // 2. detectServers: detectServerLoadBalancer.getAllServersStatistics(), затем sort DEAD first
        // 3. cameras:
        //    val monitor = signalLossMonitorTask.ifAvailable
        //    val monitoringEnabled = monitor != null
        //    val items = monitor?.snapshotStates()?.let { map → mapToDtos(now, map) } ?: emptyList()
        return StatusResponse(...)
    }
}
```

Сбор recordings, detectServers, cameras делаются последовательно — нет смысла гнать
parallel-coroutines: cameras это in-memory map (микросекунды), detectServers — тоже
in-memory, recordings — несколько простых SQL без транзакции.

### `SignalLossMonitorTask.snapshotStates()`

```kotlin
fun snapshotStates(): Map<String, CameraSignalState> = state.toMap()
```

`state: ConcurrentHashMap` — `toMap()` возвращает immutable snapshot. Безопасно вызывать с любых
потоков. Сам `tick()` остаётся `@Scheduled fixedDelay`-сериализованным.

## REST endpoint

```kotlin
@Tag(name = "StatusController", description = "API for retrieving system status")
@RequestMapping("/status")
@RestController
class StatusController(
    private val statusService: StatusService,
) {
    @Operation(summary = "Get system status", method = "GET")
    @ApiResponse(
        responseCode = "200",
        content = [Content(schema = Schema(implementation = StatusResponse::class))],
    )
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun getStatus(): StatusResponse = statusService.collect()
}
```

Сериализация: `Instant` → ISO-8601 string (Jackson default), `Duration` → ISO-8601 (`PT7M`).

## Telegram-команда

### `StatusCommandHandler`

```kotlin
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class StatusCommandHandler(
    private val statusService: StatusService,
    private val formatter: StatusMessageFormatter,
) : CommandHandler {
    override val command: String = "status"
    override val requiredRole: UserRole = UserRole.OWNER
    override val ownerOnly: Boolean = true
    override val order: Int = 6

    override suspend fun BehaviourContext.handle(
        message: CommonMessage<TextContent>,
        user: TelegramUserDto?,
    ) {
        val snapshot = statusService.collect()
        val text = formatter.format(
            snapshot = snapshot,
            language = user?.languageCode ?: "en",
            zone = user?.olsonCode?.let { ZoneId.of(it) } ?: ZoneOffset.UTC,
            now = Instant.now(),
        )
        reply(message, text, parseMode = HTMLParseMode)
    }
}
```

Авторизация выполняется централизованно в `FrigateAnalyzerBot.registerRoutes()` через
`AuthorizationFilter` — handler в неё не лезет, просто декларирует `requiredRole = OWNER`.

### `StatusMessageFormatter`

HTML формат (плейсхолдеры в фигурных скобках — ключи i18n, см. раздел i18n):

```
📊 <b>{status.title}</b>

📹 <b>{status.section.recordings}</b>
<pre>Total:        12 450
Processed:    12 380 (99.4%)
Unprocessed:      70
Rate (5 min):   2.3 rec/min</pre>

📹 <b>{status.section.byCamera}</b>
<pre>cam   |  rec  | proc | det
cam1  | 4 120 | 4 120 | 280
cam2  | 4 200 | 4 180 | 195
cam3  | 4 130 | 4 080 | 412</pre>

📷 <b>{status.section.cameras}</b>
<pre>🔴 cam3  offline 7 min  (last 15:31:22)
🟢 cam1  online       (2s ago)
🟢 cam2  online       (4s ago)</pre>

🖥️ <b>{status.section.servers}</b>
<pre>srv-a ALIVE  frame 2/4  ext 0/2  vis 0/1  vvis 0/1
srv-b ALIVE  frame 1/4  ext 1/2  vis 0/1  vvis 0/1
srv-c DEAD</pre>
```

**Детали:**

- Все динамические значения (`camId`, server `id`) HTML-escaped (`&`, `<`, `>`, `"`).
- Ширина колонок в `<pre>` блоках — `max(label.length)`. padRight для меток, padLeft для чисел. Tab-символы не использовать.
- Длительности — через `SignalLossMessageFormatter.formatDuration()` (s/min/h/d).
- Last-seen — абсолют в `user.olsonCode` `HH:mm:ss` + relative `(7 min ago)` — те же
  семантика и формат, что у `notification.signal.loss.last_recording`.
- Лимит Telegram = 4096 символов. Реалистичная оценка (20 камер + 5 серверов) ~1500. Если
  итог при реализации потребует — добавить hard limits по строкам с маркером `… +N more`;
  не делать преждевременно.

### Disabled monitoring

При `signal-loss.enabled=false`:

```
📷 <b>Cameras</b>
<pre>Monitoring disabled (signal-loss.enabled=false)</pre>
```

### Empty observed

При `signal-loss.enabled=true`, но snapshot пуст (ещё не было tick'а или активных камер):

```
📷 <b>Cameras</b>
<pre>No cameras observed yet</pre>
```

## i18n

Новые ключи в `messages_en.properties` (английский каноничный набор):

```properties
# /status
command.status.description=System status
status.title=Frigate Analyzer status

# Section titles
status.section.recordings=Recordings
status.section.byCamera=By camera
status.section.cameras=Cameras
status.section.servers=Detect servers

# Recordings labels (used as plain text — padding/alignment done in code)
status.recordings.label.total=Total
status.recordings.label.processed=Processed
status.recordings.label.unprocessed=Unprocessed
status.recordings.label.rate=Rate (5 min)

# Recordings values
status.recordings.value.processed={0} ({1}%)
status.recordings.value.rate={0} rec/min

# By-camera table headers (used as plain text — padding/alignment done in code)
status.byCamera.header.cam=cam
status.byCamera.header.rec=rec
status.byCamera.header.proc=proc
status.byCamera.header.det=det

# Camera status lines (emoji prepended in code; {0}=camId, {1}=relative ago, {2}=duration, {3}=last seen HH:mm:ss)
# Padding/alignment between camId and remainder applied in code AFTER MessageFormat.
status.cameras.line.online=online ({1} ago)
status.cameras.line.offline=offline {2} (last {3})
status.cameras.empty=No cameras observed yet
status.cameras.disabled=Monitoring disabled (signal-loss.enabled=false)

# Server status lines (emoji prepended in code; {0}=id; for alive: {1..8}=frame/ext/vis/vvis current,max)
status.servers.line.alive=ALIVE  frame {1}/{2}  ext {3}/{4}  vis {5}/{6}  vvis {7}/{8}
status.servers.line.dead=DEAD
```

Параллельный набор в `messages_ru.properties` (русские переводы тех же ключей).

**Важно:** padding и выравнивание колонок (например, `cam1  ` vs `cam12 `) выполняются
в коде `StatusMessageFormatter` ПОСЛЕ подстановки MessageFormat. Сами ключи отдают
"строительные блоки" без выравнивающих пробелов. Это позволяет ширине колонок
адаптироваться под фактический набор камер/серверов.

## Spring activation

| Бин | `@ConditionalOnProperty` |
|---|---|
| `StatusController` | нет (всегда) |
| `StatusService` | нет (всегда) |
| `StatusCommandHandler` | `application.telegram.enabled=true` |
| `StatusMessageFormatter` | `application.telegram.enabled=true` |

## Edge cases

| Случай | Поведение |
|---|---|
| `signal-loss.enabled=false` | `CamerasSection(monitoringEnabled=false, items=emptyList())`; бот рисует "Monitoring disabled" |
| `state` map пуст (signal-loss on, но первый tick ещё не прошёл) | `items=emptyList()`; бот рисует "No cameras observed yet" |
| В startup grace | `state` уже наполняется в первый tick — спец-индикатора не делаем |
| `recordings.total = 0` | бот рисует нули; никакого спец-сообщения |
| `cam_id` содержит `<`/`&` | HTML-escape в форматтере |
| OFFLINE'ная камера выпала из `activeWindow` | остаётся в state map (`SignalLostMonitorTask.tick()` чистит только Healthy); в `/status` всё ещё видна как OFFLINE |
| `user.olsonCode` отсутствует | fallback `ZoneOffset.UTC` (как в других местах проекта) |
| `user.languageCode` отсутствует | fallback `"en"` |

## Testing

| Тест | Что покрываем |
|---|---|
| `core/src/test/.../service/StatusServiceTest.kt` (new) | сбор snapshot'а; signal-loss on/off; пустая state map; маппинг Healthy/SignalLost; сортировка OFFLINE first / DEAD first |
| `core/src/test/.../controller/StatusControllerTest.kt` (new) | WebFlux slice — GET `/status` возвращает 200 + правильный JSON; ISO-8601 для `Instant`/`Duration` |
| `core/src/test/.../task/SignalLossMonitorTaskTest.kt` (existing) | добавить тест: `snapshotStates()` возвращает defensive copy (изменения внутреннего state не видны в полученной мапе) |
| `telegram/src/test/.../handler/StatusCommandHandlerTest.kt` (new) | owner-only auth (router-level); выбор `languageCode` и `olsonCode` пользователя; exception → `common.error.generic` |
| `telegram/src/test/.../service/impl/StatusMessageFormatterTest.kt` (new) | HTML escape `cam_id` со спецсимволами; padding в `<pre>`; форматирование durations; disabled monitoring; пустой items; правильные locale (ru/en); правильный TZ; offline-first / alive-first сортировка |

Тесты с реальной БД — через существующий test-конфиг проекта.

## Migration

- Никаких изменений схемы БД.
- Никаких новых environment variables.
- Удаление `/statistics` — 404 после деплоя для старых вызовов (single-deployment, OK).
- При первом запуске после деплоя `/status` команды у владельца появится автоматически — `FrigateAnalyzerBot` пересоздаёт `setMyCommands` на старте.

## Open questions

Нет (все решено в брейнсторминге).

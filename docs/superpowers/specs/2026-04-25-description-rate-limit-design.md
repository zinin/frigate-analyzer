# Rate limiter для AI-описания обнаружений — Design

**Дата:** 2026-04-25
**Статус:** draft — под реализацию
**Ветка:** `feature/description-rate-limit`

## 1. Постановка задачи

Когда у одной камеры происходит длительное событие (например, человек ходит
перед камерой 20 минут, проезжает машина и она же возвращается, и т.п.),
пайплайн порождает множество обработанных recordings — каждое с обнаружениями.
Сейчас для каждого такого recording мы:

1. Запускаем корутину с вызовом Claude (через `ClaudeDescriptionAgent`).
2. В Telegram сразу отправляем уведомление с placeholder-ом «⏳ описание
   готовится…» в caption и/или вторым сообщением (для нескольких фото —
   reply-text с expandable blockquote).
3. Когда Claude отвечает, `DescriptionEditJobRunner` редактирует сообщение,
   вписывая короткое и подробное описания.

Проблема: при «затяжном» событии мы тратим квоту Claude Code subscription
сериями десятков запросов за короткое время и засоряем Telegram-чат
сообщениями вида «описание готовится…», которые позже редактируются (или
проваливаются по таймауту).

Цель: ограничить число вызовов AI-описания фиксированным rate-лимитом (по
умолчанию **10 запросов в час**, конфигурируемо). При превышении лимита
обнаружение всё равно попадает в Telegram, но **без AI-описания и без
заглушки про него** — обычное уведомление как при `application.ai.description.enabled=false`.

## 2. Решения, принятые в brainstorming

| Вопрос | Решение | Причина |
|---|---|---|
| Поведение при превышении лимита | Skip: вызов Claude не запускается, формат сообщения переключается на «без описания» | Согласуется с уже встроенным fallback-ом при ошибках Claude; не растягивает Telegram-сообщения placeholder-ами |
| Что делать с placeholder-ами при skip | Не отправлять — сообщение должно выглядеть как при выключенном AI: ни «⏳ описание готовится» в caption, ни второго сообщения | Прямое требование пользователя: «не писать что описание идёт через LLM, и не отправлять второе сообщение» |
| Granularity | Глобальный счётчик (один на сервис) | Простота; защита бюджета Claude в первую очередь, не дифференциация по камерам |
| Алгоритм окна | Sliding window поверх `ArrayDeque<Instant>` под `Mutex` | Точно соответствует семантике «не больше N в последние W»; защищает от rebound на границе fixed-window |
| Persistence | In-memory only | Лимит «10/час» — защита от одного активного события (минуты), а не от долгосрочного abuse; сервис рестартует редко; in-memory тривиально тестируется |
| Default behaviour | enabled by default, max=10, window=1h | Пользователь явно назвал «10 в час»; включено сразу при `application.ai.description.enabled=true`, отключается явным флагом |
| Семантика инкремента | Счётчик растёт когда `tryAcquire()` возвращает true (т.е. при выдаче разрешения), не decrement-ится при ошибке Claude | Failed Claude-вызов всё равно расходует ресурсы CC subscription и пайплайна |
| Внутренние retry в `ClaudeDescriptionAgent` | Через rate limiter не идут | Один логический описательный запрос = одна квота |
| Точка вставки rate-лимитера | `TelegramNotificationServiceImpl.sendRecordingNotification`, перед `descriptionSupplier?.invoke()` (строка 52) | Единственное место где принимается решение «запускать корутину Claude или нет» — выше других веток (Sender, EditJobRunner) |
| Использование времени | `java.time.Clock` через Spring DI (бин уже есть в `common.config.ClockConfig`) | Стандарт проекта; легко подменяется `Clock.fixed(...)` в unit-тестах |
| Логирование skip | WARN в логе с recordingId и текущим состоянием окна | WARN заметнее в логах при разборе «почему нет описаний»; ожидаемое, но требующее внимания событие |

## 3. Архитектура и компоненты

Один новый класс. Точка интеграции — одна. Никаких миграций БД, новых
зависимостей, новых модулей.

```
modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/
├── api/
├── claude/
├── config/                          ← правка: расширяем DescriptionProperties.CommonSection
└── ratelimit/                       ← НОВЫЙ ПАКЕТ
    └── DescriptionRateLimiter.kt    ← НОВЫЙ КЛАСС
```

### 3.1 Класс `DescriptionRateLimiter`

```kotlin
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class DescriptionRateLimiter(
    private val clock: Clock,
    private val descriptionProperties: DescriptionProperties,
) {
    private val rateLimit = descriptionProperties.common.rateLimit
    private val mutex = Mutex()
    private val timestamps = ArrayDeque<Instant>(rateLimit.maxRequests)

    /**
     * Returns true if the request is allowed (and consumes a slot).
     * Returns false if the rate limit window is full.
     * When rate-limit.enabled=false, always returns true without taking the mutex.
     *
     * Caller is responsible for logging on `false` return — caller knows the
     * recordingId, the limiter intentionally stays domain-agnostic.
     */
    suspend fun tryAcquire(): Boolean {
        if (!rateLimit.enabled) return true

        return mutex.withLock {
            val now = clock.instant()
            val cutoff = now.minus(rateLimit.window)

            // Drop timestamps that fell out of the sliding window.
            while (timestamps.isNotEmpty() && !timestamps.first().isAfter(cutoff)) {
                timestamps.removeFirst()
            }

            if (timestamps.size < rateLimit.maxRequests) {
                timestamps.addLast(now)
                true
            } else {
                false
            }
        }
    }
}
```

**Поведение:**
- `enabled=false` → fast-path без mutex (ноль накладных расходов).
- `enabled=true` → берём mutex, чистим хвост deque (всё что `<= cutoff`),
  сравниваем размер с лимитом, при разрешении добавляем `now`.
- Counter не decrement-ится: failed Claude-вызов всё равно занимает слот.
- Внутренние retry в `ClaudeDescriptionAgent.executeWithRetry()` (transport
  errors, invalid JSON) идут под одним вызовом `describe()` и НЕ проходят
  через rate limiter повторно — один логический описательный запрос =
  одна квота.

**Зависимости:**
- `java.time.Clock` — Spring найдёт бин из `common.config.ClockConfig` по
  типу. Импортируется только `java.time.Clock`, без явного импорта
  `ClockConfig`. ai-description модуль формально не зависит от common —
  Clock приходит как стандартный JDK-тип через DI.
- `DescriptionProperties` — расширяем (см. §4).

**Условность бина:**
- `@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")`
  — синглтон создаётся когда AI-описание в принципе включено. Если AI-
  описание выключено, `descriptionSupplier` в `RecordingProcessingFacade`
  будет `null`, и до точки rate-лимитера дело не дойдёт.

### 3.2 Точка интеграции

**Файл:** `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt`

**Изменение метода `sendRecordingNotification` (строка 52):**

```kotlin
// БЫЛО:
val descriptionHandle = descriptionSupplier?.invoke()

// СТАНЕТ:
val descriptionHandle =
    if (descriptionSupplier != null && (rateLimiter.getIfAvailable()?.tryAcquire() != false)) {
        descriptionSupplier.invoke()
    } else {
        logger.warn {
            "AI description rate limit reached, skipping description for recording ${recording.id}"
        }
        null
    }
```

Логика выражения `(rateLimiter.getIfAvailable()?.tryAcquire() != false)`:
- `getIfAvailable() == null` (AI выключен — бина нет) → `?.tryAcquire()` = `null` → `null != false` = **true** (но `descriptionSupplier == null` в этой же ветке `&&`, поэтому до этого условия не доходим);
- `tryAcquire() == true` (есть слот) → `true != false` = **true** → запускаем supplier;
- `tryAcquire() == false` (лимит) → `false != false` = **false** → не запускаем.

WARN-лог пишется только в третьем случае: когда AI включён, был бы вызов,
но лимитер отказал. Лог делаем здесь (а не в самом `DescriptionRateLimiter`),
потому что call site знает `recording.id`, а лимитер по принципу SRP не
должен зависеть от доменных типов уведомления.

**Конструктор сервиса:** добавляется `ObjectProvider<DescriptionRateLimiter>`.
Используем `ObjectProvider` (а не прямой инжект), потому что сервис
`TelegramNotificationServiceImpl` создаётся при `application.telegram.enabled=true`,
а лимитер — при `application.ai.description.enabled=true`. Когда AI-описание
выключено, лимитера в контексте нет, но и `descriptionSupplier` тоже null —
мы до `tryAcquire()` не дойдём (короткий-circuit `&&`).

```kotlin
class TelegramNotificationServiceImpl(
    ...existing deps...,
    private val rateLimiter: ObjectProvider<DescriptionRateLimiter>,
)
```

**Каскадное поведение при `tryAcquire() == false`:**

После того как `descriptionHandle = null`:

| Дальнейший шаг | Поведение |
|---|---|
| `RecordingNotificationTask` создаётся с `descriptionHandle = null` | OK |
| `TelegramNotificationSender.kt:84` — `if (task.descriptionHandle != null) descriptionFormatter.getIfAvailable() else null` | `formatter = null` |
| `TelegramNotificationSender.kt:90-102` (no photo) | Простой текст без placeholder-ов (как при выключенном AI) |
| `TelegramNotificationSender.kt:105-122` (one photo) | `captionInitialPlaceholder()` НЕ применяется — отправляется caption без хвоста про описание |
| `TelegramNotificationSender.kt:124-134` (multi photo) | Альбом отправляется без сопроводительного второго reply-сообщения с placeholder-деталями |
| `TelegramNotificationSender.kt:137-144` — `if (formatter != null && target != null) editJobRunner.launchEditJob(...)` | НЕ запускается |
| Вызов Claude (`descriptionScope.async { agent.describe(...) }`) | НЕ происходит — supplier не invoke-ится |

Таким образом единственная точечная правка в `TelegramNotificationServiceImpl`
автоматически отключает все четыре канала, требующие отдельного отключения
по требованию пользователя:
1. Заглушка про описание в caption
2. Второе сообщение с placeholder-деталями
3. EditJob, ожидающий результата
4. Сам вызов Claude

## 4. Конфигурация

### 4.1 Расширение `DescriptionProperties`

```kotlin
// modules/ai-description/.../config/DescriptionProperties.kt

data class CommonSection(
    @field:Pattern(regexp = "ru|en", message = "must be 'ru' or 'en'")
    val language: String,
    @field:Min(50) @field:Max(500)
    val shortMaxLength: Int,
    @field:Min(200) @field:Max(3500)
    val detailedMaxLength: Int,
    @field:Min(1) @field:Max(50)
    val maxFrames: Int,
    val queueTimeout: Duration,
    val timeout: Duration,
    @field:Min(1) @field:Max(10)
    val maxConcurrent: Int,
    @field:Valid
    val rateLimit: RateLimit,             // ← НОВОЕ
) {
    init {
        require(queueTimeout.toMillis() > 0) { "queue-timeout must be positive" }
        require(timeout.toMillis() > 0) { "timeout must be positive" }
    }
}

data class RateLimit(                     // ← НОВЫЙ data class
    val enabled: Boolean,
    @field:Min(1) @field:Max(10000)
    val maxRequests: Int,
    val window: Duration,
) {
    init {
        require(window.toMillis() > 0) { "rate-limit.window must be positive" }
    }
}
```

Валидация: `maxRequests` ограничен сверху 10000 (защита от опечатки —
больше не имеет смысла для нашего use-case), `window` обязан быть строго
положительным.

### 4.2 `application.yaml`

```yaml
application:
  ai:
    description:
      enabled: ${APP_AI_DESCRIPTION_ENABLED:false}
      provider: ${APP_AI_DESCRIPTION_PROVIDER:claude}
      common:
        language: ${APP_AI_DESCRIPTION_LANGUAGE:en}
        short-max-length: ${APP_AI_DESCRIPTION_SHORT_MAX:200}
        detailed-max-length: ${APP_AI_DESCRIPTION_DETAILED_MAX:1500}
        max-frames: ${APP_AI_DESCRIPTION_MAX_FRAMES:10}
        queue-timeout: ${APP_AI_DESCRIPTION_QUEUE_TIMEOUT:30s}
        timeout: ${APP_AI_DESCRIPTION_TIMEOUT:60s}
        max-concurrent: ${APP_AI_DESCRIPTION_MAX_CONCURRENT:2}
        rate-limit:                                                   # ← НОВЫЙ БЛОК
          enabled: ${APP_AI_DESCRIPTION_RATE_LIMIT_ENABLED:true}
          max-requests: ${APP_AI_DESCRIPTION_RATE_LIMIT_MAX:10}
          window: ${APP_AI_DESCRIPTION_RATE_LIMIT_WINDOW:1h}
      claude:
        ...
```

Дефолты (`enabled=true`, `max-requests=10`, `window=1h`) совпадают с
brainstorming-решением.

### 4.3 Документация конфигурации

Добавить в `.claude/rules/configuration.md` секцию про новые env-переменные.
Обновить README/CHANGELOG если в проекте принят такой формат (проверим
при имплементации).

## 5. Поведение и диагностика

### 5.1 Логирование

Один WARN на каждый skip — пишется в `TelegramNotificationServiceImpl` (см. §3.2),
потому что call site знает `recording.id`, а лимитер по принципу SRP остаётся
доменно-независимым:

```
WARN  AI description rate limit reached, skipping description for recording <recordingId>
```

Намеренно не сообщаем в логе `maxRequests`/`window`: эти параметры пользователь
видит в `application.yaml` или env-переменных. WARN-лог — это сигнал «лимит
работает», а не отчёт о конфигурации.

### 5.2 Что НЕ делаем (out of scope)

Эти возможности не реализуем сейчас, оставляем для отдельного решения если
понадобится:

- **Метрики Micrometer / Prometheus.** Сейчас в проекте нет принятого
  паттерна публикации метрик через Spring Actuator (`@Counted`,
  `MeterRegistry`). Если возникнет потребность — добавим отдельной задачей.
- **Уведомления администратора в Telegram при длительном превышении лимита.**
  Например «за последний час было пропущено 100 описаний». Текущий
  WARN-лог достаточен.
- **Per-camera лимит / двухуровневая защита.** Brainstorming показал что в
  нашем сценарии глобального лимита достаточно.
- **Persistence в БД.** In-memory переживает любой реальный кейс «активный
  человек/машина 20 минут».
- **Динамическое изменение лимита на лету.** Изменение `application.yaml`
  требует рестарта — стандарт для всех остальных параметров проекта.

## 6. Тестирование

### 6.1 Unit-тесты `DescriptionRateLimiter`

Файл: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/ratelimit/DescriptionRateLimiterTest.kt`

`Clock.fixed(Instant.parse("2026-04-25T12:00:00Z"), ZoneOffset.UTC)` для
детерминированного времени; для проверок «прошло время» — отдельный
Clock на каждую фазу теста (`Clock.fixed(...)` immutable).

| Сценарий | Setup | Ожидание |
|---|---|---|
| `enabled=false` всегда `true` | `RateLimit(enabled=false, maxRequests=10, window=1h)` + 100 вызовов | Все возвращают `true`, deque пуст |
| Под лимитом проходит | max=3, делаем 3 вызова с разными timestamps в одной секунде | Все 3 → `true` |
| Лимит блокирует | max=3, делаем 4-й вызов в той же секунде | 4-й → `false` |
| Окно сдвигается — освобождает слоты | max=3, заполняем в `t=12:00:00`, потом `Clock.fixed(t+1h+1ms)` — 4-й вызов | 4-й → `true`, в deque только новый timestamp |
| Граничное условие: `t+window-1ms` | max=1, заполняем в `t`, потом `t+1h-1ms` | 2-й → `false` (старая запись всё ещё в окне) |
| Граничное условие: ровно `t+window` | max=1, заполняем в `t`, потом `t+1h` | 2-й → `true` (cutoff = t, `!t.isAfter(t) == true` → drop, слот свободен) |
| Граничное условие: `t+window+1ms` | max=1, заполняем в `t`, потом `t+1h+1ms` | 2-й → `true` (старый сдропан) |
| Concurrency: 11 параллельных корутин, max=10 | `runBlocking { coroutineScope { launch x 11 } }` | Ровно 10 → `true`, ровно 1 → `false` |
| Чистка хвоста не оставляет мусор | max=10, 100 вызовов с заведомо устаревшими timestamps | После каждого вызова `timestamps.size <= maxRequests` |
| Конфиг-валидация: `window=0s` | Создаём `RateLimit(true, 10, Duration.ZERO)` | `IllegalArgumentException` в `init {}` |
| Конфиг-валидация: `maxRequests=0` | Создаём `RateLimit(true, 0, 1h)` | Validator `@Min(1)` ловит при ConfigurationProperties binding |

Тесты используют unit-стиль (без `@SpringBootTest`), т.к. класс не
требует контекста.

### 6.2 Интеграционный тест

Один тест в стиле существующих `TelegramNotificationServiceImplTest`:

| Сценарий | Setup | Ожидание |
|---|---|---|
| Skip при превышении не запускает supplier и не передаёт handle | mock `descriptionSupplier`, mock `rateLimiter.tryAcquire()` returns `false` | `supplier` НЕ вызывается; `RecordingNotificationTask.descriptionHandle == null` |
| Под лимитом supplier вызывается ровно один раз для всех получателей | mock `tryAcquire()` returns `true`, 3 user-zone | `supplier.invoke()` вызвано 1 раз; во всех 3 task-ах одинаковый handle |
| AI выключен → rate limiter не запрашивается | Без бина `DescriptionRateLimiter` (моделируем `ObjectProvider.getIfAvailable() == null`) | `descriptionSupplier == null` уже сверху → ветка не активируется; testverify-ит что `getIfAvailable` вызвался 0 или 1 раз без ошибок |

### 6.3 Smoke-сценарий вручную

В режиме разработчика с `APP_AI_DESCRIPTION_RATE_LIMIT_MAX=2`,
`APP_AI_DESCRIPTION_RATE_LIMIT_WINDOW=5m`:

1. Сгенерировать 3 recordings подряд за 30 секунд.
2. Проверить:
   - Первые 2 уведомления имеют caption-placeholder и edit с описанием.
   - Третье уведомление — простой текст без хвоста и без второго сообщения,
     editJob не запущен (нет `editMessageCaption` в логах для этого ID).
   - В логах WARN с recordingId третьего.

## 7. Изменения по файлам

| Файл | Действие | Описание |
|---|---|---|
| `modules/ai-description/.../ratelimit/DescriptionRateLimiter.kt` | NEW | Класс выше |
| `modules/ai-description/.../config/DescriptionProperties.kt` | EDIT | Добавить `RateLimit` data class и поле `rateLimit: RateLimit` в `CommonSection` |
| `modules/core/src/main/resources/application.yaml` | EDIT | Добавить блок `rate-limit` под `application.ai.description.common` |
| `modules/telegram/.../TelegramNotificationServiceImpl.kt` | EDIT | Добавить `ObjectProvider<DescriptionRateLimiter>`, обернуть `descriptionSupplier?.invoke()` |
| `modules/ai-description/src/test/.../ratelimit/DescriptionRateLimiterTest.kt` | NEW | Unit-тесты по таблице §6.1 |
| `modules/telegram/src/test/.../TelegramNotificationServiceImplTest.kt` | EDIT | 2 новых теста по §6.2 |
| `.claude/rules/configuration.md` | EDIT | Документировать новые env-переменные |

Никаких миграций БД, gradle-зависимостей, новых модулей.

## 8. Риски и обратная совместимость

- **Существующие установки с включённым AI получают rate-лимитер
  включённым по умолчанию (10/час).** Это намеренный выбор по brainstorming-у:
  не оставить пользователей без защиты. Если у кого-то была настройка с
  большим объёмом описаний — поднимут `APP_AI_DESCRIPTION_RATE_LIMIT_MAX`
  явно. WARN-лог при первом срабатывании сразу подскажет переменную.
- **Spring Boot binding для nested data class.** `RateLimit` — `data class`
  с фиксированными полями (нужны дефолты только в `application.yaml`,
  не в самом классе, т.к. `@ConfigurationProperties` создаёт инстанс через
  binder с явными значениями из YAML). Это совпадает со стилем существующего
  `CommonSection`, так что сюрпризов не будет.
- **Mutex contention.** `tryAcquire()` под `Mutex` — последовательно по
  всему сервису. На практике частота вызовов ограничена частотой
  recordings (десятки в минуту максимум, обычно — единицы), а внутри
  лимитера выполняется только проверка размера deque и dequeue/enqueue
  одного `Instant`. Никаких блокирующих операций. Contention не значим.
- **Точка инжекта через `ObjectProvider`** — небольшая ценовая
  дополнительность по сравнению с прямым инжектом, но позволяет
  `TelegramNotificationServiceImpl` (создаётся при telegram-enabled)
  сосуществовать с лимитером (создаётся при ai-description-enabled) в
  любых комбинациях флагов.

# Export Cancellation — Design

**Date:** 2026-04-17
**Branch:** `fix/quick-export-annotation-timeout`
**Context:** Vision Server API v2.2.0 добавил `POST /jobs/{job_id}/cancel` (см. OpenAPI-контракт в обсуждении). Цель — дать пользователю возможность отменить уже запущенный экспорт видео из Telegram: и быстрый экспорт (`qe:/qea:` в нотификациях), и диалоговый (`/export`).

---

## 1. Goals & Non-Goals

### Goals
- Пользователь может отменить уже запущенный экспорт на **любом этапе** (`PREPARING → MERGING → COMPRESSING → ANNOTATING → SENDING`), в обоих режимах (`ORIGINAL`, `ANNOTATED`) и в обоих точках запуска (`QuickExport`, `/export`).
- Если экспорт аннотированный и job уже отдан на vision server — корректно дёрнуть `POST /jobs/{id}/cancel`, чтобы не тратить GPU-ресурсы впустую.
- Единый UI-паттерн отмены в обоих местах: отдельная inline-кнопка «✖ Отмена».
- Двухшаговая обратная связь: мгновенный UI-feedback («Отменяется...») → финал («Экспорт отменён»).

### Non-Goals
- Persistence реестра активных экспортов (переживание рестарта приложения). При рестарте активные экспорты «теряются» — UI-кнопка «Отмена» на старых сообщениях ответит «Экспорт уже завершён или недоступен». Оркестрация orphan jobs на vision server — ответственность сервера (server-side TTL).
- Продвинутые случаи многопользовательских групп. Бот работает в DM-режиме; chatId == userId.
- Отмена не-аннотированных экспортов **на самом vision server** — сервер туда не вовлечён вообще (ORIGINAL идёт только через склейку/ffmpeg).

---

## 2. Архитектура: компоненты

### 2.1 Новые компоненты

| Компонент | Модуль | Роль |
|---|---|---|
| `ActiveExportRegistry` | `telegram/bot/handler/export/` | In-memory реестр активных экспортов. Заменяет `ActiveExportTracker`. Единственная точка истины о том, что сейчас выполняется и что можно отменить. |
| `CancelExportHandler` | `telegram/bot/handler/cancel/` | Обрабатывает callback `xc:{exportId}`. Атомарно переводит запись в CANCELLING, редактирует клавиатуру, отменяет корутину, дёргает vision server cancel (если есть `(server, jobId)`). Также обрабатывает noop-prefix `np:` — silently `bot.answer(callback)` без действий. |
| `CancellableJob` (SAM) | `telegram/service/model/` | Shared contract для публикации возможности отмены аннотированного job'а из core-слоя в telegram-слой, без протечки `AcquiredServer` наружу. |
| `ExportCoroutineScope` | `telegram/bot/handler/export/` | Shared scope bean (замена private `CoroutineScope` в `QuickExportHandler`). Используется QuickExport, Export и CancelExport хендлерами. Graceful shutdown через `@PreDestroy`. |
| `DetectService.cancelJob(server, jobId)` | `core/service/` | Новый suspend-метод. `POST /jobs/{jobId}/cancel` с коротким таймаутом. Толерантен к 409/5xx/timeout (логируем, не бросаем наверх). |

### 2.2 Удаляемые компоненты

| Компонент | Причина |
|---|---|
| `ActiveExportTracker` + `ActiveExportTrackerTest` | Функциональность (per-chat dedup для `/export`) поглощается `ActiveExportRegistry.tryStartDialogExport`. |
| Private поле `activeExports: MutableSet<UUID>` в `QuickExportHandler` | Функциональность (per-recording dedup) поглощается `ActiveExportRegistry.tryStartQuickExport`. |

### 2.3 Изменения сигнатур

`VideoVisualizationService.annotateVideo`:
```kotlin
suspend fun annotateVideo(
    ...,
    onProgress: suspend (JobStatusResponse) -> Unit = {},
    onJobSubmitted: suspend (CancellableJob) -> Unit = {},   // NEW
): Path
```
Callback вызывается ровно один раз сразу после успешного `submitWithRetry()`, обёрнут в `withContext(NonCancellable)` — чтобы одномоментная отмена параллельно с submit не потеряла возможность позже сообщить cancel vision-серверу.

`VideoExportService.exportVideo(...)` и `exportByRecordingId(...)`:
```kotlin
suspend fun exportVideo(
    ...,
    onProgress: suspend (VideoExportProgress) -> Unit = {},
    onJobSubmitted: suspend (CancellableJob) -> Unit = {},   // NEW
): Path
```
Прокидывается в `annotateVideo` только для `mode == ANNOTATED`. Для `ORIGINAL` callback не вызывается (vision server не задействован).

### 2.4 Routing

`FrigateAnalyzerBot` уже содержит `onDataCallbackQuery` с фильтром по префиксам `qe:` / `qea:`. Добавляется второй `onDataCallbackQuery` с фильтром по `xc:` и `np:` → диспатч в `CancelExportHandler`. Никакой другой логики роутинга не меняется.

---

## 3. Data Flow

### 3.1 Запуск экспорта (общий скелет)

```
1. Handler принимает клик / команду → chatId, mode, (recordingId или диапазон)
2. exportId = UUID.randomUUID()
3. job = exportScope.launch(start = LAZY) { … тело экспорта … }
4. registry.tryStart*(exportId, chatId, mode, …, job):
     - Duplicate* → job.cancel(); отвечаем пользователю «уже выполняется»; return
     - Success → job.start()
5. В теле экспорта:
     - onProgress обновляет UI (проверка state == CANCELLING → skip)
     - onJobSubmitted = { cancellable → registry.attachCancellable(exportId, cancellable) }
     - finally:
         * if state == CANCELLING → финальный "Отменён" рендер
         * else (success / ошибка) → текущая логика
         * registry.release(exportId)
```

### 3.2 Отмена

```
Click "xc:{exportId}"
  → CancelExportHandler.handle()
  → auth, chat match, parse
  → registry.markCancelling(exportId) → Entry?
      null → bot.answer("уже завершён" или "уже отменяется")
      Entry {
        bot.answer(callback)
        editMessageReplyMarkup → "⏹ Отменяется..." (noop-callback)
        entry.job.cancel(CancellationException("user cancelled"))
        entry.cancellable?.let {
            exportScope.launch { it.cancel() }   // fire-and-forget, c /cancel таймаутом внутри
        }
      }
  // Exit. Финальный UI ("Отменён") делает finally-ветка тела экспорта, а не этот handler.
```

### 3.3 UI по фазам

**QuickExport (редактируем только `replyMarkup`, текст сообщения — оригинальная нотификация):**

| Фаза | Ряды клавиатуры |
|---|---|
| Initial (из notification) | `[📹 Оригинал]  [📹 С объектами]` — неизменно |
| ACTIVE | Ряд 1: `[⚙ Аннотация 45%…]` (callback `np:{exportId}` — silently ack) <br> Ряд 2: `[✖ Отмена]` → `xc:{exportId}` |
| CANCELLING | Один ряд: `[⏹ Отменяется…]` (callback `np:{exportId}`) |
| Финал — успех | Initial keyboard восстанавливается (видео уже отправлено отдельным сообщением). |
| Финал — ошибка | Initial keyboard + отдельное текстовое сообщение с причиной. |
| Финал — отмена | Initial keyboard + отдельное сообщение «❌ Экспорт отменён». |

**/export (редактируем текст статус-сообщения и клавиатуру):**

| Фаза | Текст | Клавиатура |
|---|---|---|
| PREPARING..SENDING | Текущий `renderProgress(stage, ...)` | `[✖ Отмена]` → `xc:{exportId}` |
| CANCELLING | Текущий текст (не трогаем) | `[⏹ Отменяется…]` (callback `np:{exportId}`) |
| DONE / ошибка / отмена | Финальный текст | `replyMarkup = null` (клавиатура удаляется) |

---

## 4. State Machine

`ActiveExportRegistry.Entry.state`:

```
ACTIVE ──markCancelling──> CANCELLING ──finally корутины──> (remove из registry)
   │                           │
   └── нормальное завершение ──┴──> (remove из registry)
```

- `markCancelling` — атомарный переход `ACTIVE → CANCELLING`. Успех возвращает снапшот `Entry`, повторный вызов возвращает `null`.
- `release(exportId)` — **всегда** вызывается ровно один раз из `finally` тела корутины экспорта; идемпотентен для повторных вызовов.
- `attachCancellable(exportId, cancellable)` — выполняется под `withContext(NonCancellable)` внутри `annotateVideo`, чтобы даже при одновременной cancel извне публикация состоялась.

Race: click «Отмена» между `submitWithRetry` и `attachCancellable`. `NonCancellable` гарантирует, что callback отработает. `attachCancellable` пишет Volatile-поле Entry — CancelHandler, если сработал раньше, уже `job.cancel()` сделал; эффекту это не мешает, но vision cancel может не успеть (cancellable ещё null в момент чтения). Худший случай — orphan job на сервере (тот же риск, что есть сегодня). Принято осознанно, улучшение остаётся вне скоупа.

---

## 5. Error Handling

### 5.1 `DetectService.cancelJob`

```kotlin
try {
    withTimeout(cancelTimeout) {
        POST /jobs/{jobId}/cancel
    }
} catch (CancellationException) → throw
catch (409) → log INFO "already terminal"
catch (others, timeout) → log WARN
```

Все не-cancellation исключения проглатываются. Нет понятия «отмена отмены» — отмена на клиентской стороне уже произошла через `job.cancel()`.

### 5.2 Финальная ветка UI

Корутина тела экспорта:
```kotlin
} catch (e: CancellationException) {
    if (registry.get(exportId)?.state == ACTIVE) {
        // Это не пользовательская отмена (shutdown scope). Просто rethrow без UI.
        throw e
    }
    // state == CANCELLING → пользовательская отмена.
    renderCancelledFinal()   // editMessageText / sendTextMessage + editMessageReplyMarkup
} catch (e: Exception) {
    // существующая логика (IllegalArgument / IllegalState / DetectTimeout / generic)
} finally {
    registry.release(exportId)
}
```

### 5.3 Граничные случаи

| Случай | Поведение |
|---|---|
| Двойной клик «Отмена» | Второй клик → `markCancelling` = null → «Отмена уже выполняется» |
| Рестарт приложения после старта экспорта | Registry пуст → «Экспорт уже завершён или недоступен». Orphan job на сервере — без изменений. |
| Клик «Отмена» после завершения | Registry пуст → «Экспорт уже завершён или недоступен». |
| chatId ≠ entry.chatId | «Экспорт уже завершён или недоступен» (без раскрытия, что он существует у кого-то ещё). |
| Unauthorized | «Нет доступа» (переиспользуем `common.error.unauthorized`). |
| `/cancel` 409 / 5xx / timeout | Логируем; UI-рендер «Отменён» одинаков в любом случае. Пользователь не узнаёт про сбой на vision-сервере — сервер сам через TTL добьёт job. |
| Cancel в Phase 3 (download) | `CancellationException` проходит через `DataBufferUtils.write`; catch в `downloadJobResult` чистит temp-файл. |
| Cancel в SENDING (upload в Telegram) | `CancellationException` прерывает multipart upload; `cleanupExportFile` в finally отрабатывает. |
| `registry.release` вызван дважды | `ConcurrentHashMap.remove` идемпотентен. |

---

## 6. Configuration

В `VideoVisualizeConfig`:
```kotlin
val cancelTimeout: Duration = Duration.ofSeconds(10)
```
Env var: `DETECT_VIDEO_VISUALIZE_CANCEL_TIMEOUT`. Документируется в `.claude/rules/configuration.md`.

Outer таймауты QuickExport (50 мин для ANNOTATED) остаются как есть. Отмена не зависит от внешнего таймаута: `job.cancel()` прерывает withTimeoutOrNull.

---

## 7. i18n

В `modules/telegram/src/main/resources/messages_{ru,en}.properties`:

```properties
# Общие
cancel.error.format           = Некорректный параметр отмены / Invalid cancel parameter
cancel.error.not.active       = Экспорт уже завершён или недоступен / Export is already finished or unavailable
cancel.error.already.cancelling = Отмена уже выполняется / Cancellation already in progress

# QuickExport
quickexport.button.cancel     = ✖️ Отмена / ✖️ Cancel
quickexport.progress.cancelling = ⏹️ Отменяется... / ⏹️ Cancelling...
quickexport.cancelled         = ❌ Экспорт отменён / ❌ Export cancelled

# /export
export.button.cancel          = ✖️ Отмена / ✖️ Cancel
export.progress.cancelling    = ⏹️ Отменяется... / ⏹️ Cancelling...
export.cancelled              = ❌ Экспорт отменён / ❌ Export cancelled
export.error.concurrent       = Уже идёт экспорт. / An export is already running.
```

Единая семантика: без отдельного сообщения «vision server не ответил» — пользователю неважно, сработал ли cancel на стороне сервера (логи покажут реализатору).

---

## 8. Testing Strategy

| Тест | Покрытие |
|---|---|
| `ActiveExportRegistryTest` (new) | `tryStart*` happy + dup; concurrent races; `markCancelling` CAS; `release` идемпотентен; namespace-независимость QuickExport vs `/export` |
| `CancelExportHandlerTest` (new) | happy path; not found; already cancelling; chat mismatch; unauthorized; malformed callback data; Telegram API ошибки при edit keyboard |
| `DetectServiceTest` (extend) | `cancelJob` 200; 409 logged INFO; 5xx logged WARN без throw; timeout logged WARN без throw; CancellationException rethrown |
| `VideoVisualizationServiceTest` (extend) | `onJobSubmitted` вызван один раз после submit; не вызван при провале submit; выполнен под NonCancellable (тест с одновременной отменой родительской корутины) |
| `VideoExportServiceImplTest` (extend) | `onJobSubmitted` прокинут в `annotateVideo` для ANNOTATED; не вызывается для ORIGINAL |
| `QuickExportHandlerTest` (extend) | клик Cancel → UI «Отменяется…»; финал «Отменён» + восстановление initial keyboard; dedup через registry вместо локального Set |
| `ExportExecutorTest` (new/extend) | клик Cancel → editMessageText «Отменён» + replyMarkup=null; dedup через registry; LAZY launch + join |

Ручная e2e валидация (записать в плане как item для реализатора): запустить annotated QuickExport в dev-окружении, нажать «Отмена» на этапе ANNOTATING, проверить что vision server получает `POST /jobs/{id}/cancel` и job уходит в `cancelled`.

---

## 9. Observability

| Log level | Событие |
|---|---|
| INFO | Cancel инициирован пользователем: `exportId=, chatId=, mode=, recordingId=` |
| INFO | `Cancel request accepted for job <jobId> on <serverId>` |
| INFO | `Cancel: job <jobId> already terminal (409)` |
| WARN | `Cancel failed for job <jobId> on <serverId>: <status>` |
| WARN | `Failed to update keyboard to cancelling state` |
| WARN | `Vision server cancel failed exportId=<>` (в `exportScope.launch` пойманная ошибка) |

---

## 10. Documentation Updates

- `.claude/rules/telegram.md` — дописать секцию «Cancellation» в Quick Export (новая кнопка, префикс `xc:`, поведение при рестарте).
- `.claude/rules/configuration.md` — добавить `DETECT_VIDEO_VISUALIZE_CANCEL_TIMEOUT` (default 10s).

---

## 11. Out of Scope (явно)

- Persistence реестра.
- Отмена ORIGINAL-экспорта на уровне ffmpeg-сервера (ffmpeg-процессы локальны, `CancellationException` достаточен для их прерывания через структурную concurrency существующего pipeline).
- Возможность возобновить отменённый экспорт.
- Метрики Prometheus по отменам (можно добавить отдельно).

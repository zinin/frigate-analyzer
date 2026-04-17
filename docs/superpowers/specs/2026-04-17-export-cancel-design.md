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
- **Мгновенная отмена ffmpeg-подпроцесса на этапах MERGING/COMPRESSING.** `VideoMergeHelper` использует блокирующий `process.waitFor(...)` — `CancellationException` разматывается только после того, как ffmpeg сам завершится (merge ~секунды, compress до ~минут). Пользователь видит «⏹ Отменяется…» мгновенно, но фактическое «❌ Отменён» появится после того, как текущая ffmpeg-операция закончится. Cancellation-aware wrapper (`runInterruptible` + `process.destroyForcibly()`) — отдельная задача вне scope.

---

## 2. Архитектура: компоненты

### 2.1 Новые компоненты

| Компонент | Модуль | Роль |
|---|---|---|
| `ActiveExportRegistry` | `telegram/bot/handler/export/` | In-memory реестр активных экспортов **в фазе execution**. Единственная точка истины о том, что сейчас выполняется и что можно отменить. Дедуп: `byRecordingId` для QuickExport, `byChat` для `/export` execution phase. |
| `ActiveExportTracker` (keep) | `telegram/bot/handler/export/` | **Не удаляется.** Продолжает отвечать за dialog-phase chat lock в `/export`: `tryAcquire(chatId)` вызывается до `runDialog()` и гарантирует, что два параллельных `/export`-диалога в одном DM не начнут перехватывать ответы друг у друга. Lock освобождается в `finally` `ExportCommandHandler.handle(...)` — то есть сразу после того, как `ExportExecutor.execute(...)` вернёт управление (fire-and-forget: запуск LAZY-job через `job.start()` без `job.join()`). Дальнейший execution-phase lifecycle (включая дедуп `byChat`) подхватывает registry. **Критически важно:** `execute()` НЕ должен блокировать `handle()` до завершения экспорта — иначе tracker удерживался бы до 50 минут и двухуровневая модель локов схлопнулась бы в один. |
| `CancelExportHandler` | `telegram/bot/handler/cancel/` | Обрабатывает callback `xc:{exportId}`. Атомарно переводит запись в CANCELLING, редактирует клавиатуру, отменяет корутину, дёргает vision server cancel (если есть `(server, jobId)`). Также обрабатывает noop-prefix `np:` — silently `bot.answer(callback)` без действий. |
| `CancellableJob` (SAM) | `telegram/service/model/` | Shared contract для публикации возможности отмены аннотированного job'а из core-слоя в telegram-слой, без протечки `AcquiredServer` наружу. |
| `ExportCoroutineScope` | `telegram/bot/handler/export/` | Shared scope bean (замена private `CoroutineScope` в `QuickExportHandler`). Используется QuickExport, Export и CancelExport хендлерами. Graceful shutdown через `@PreDestroy`. |
| `DetectService.cancelJob(server, jobId)` | `core/service/` | Новый suspend-метод. `POST /jobs/{jobId}/cancel` с коротким таймаутом. Толерантен к 409/5xx/timeout (логируем, не бросаем наверх). |

### 2.2 Удаляемые компоненты

| Компонент | Причина |
|---|---|
| Private поле `activeExports: MutableSet<UUID>` в `QuickExportHandler` | Функциональность (per-recording dedup) поглощается `ActiveExportRegistry.tryStartQuickExport`. |

`ActiveExportTracker` **сохраняется** (в противоположность ранней версии дизайна): он отвечает за dialog-phase lock в `/export`, где у registry нет хукa (диалог выполняется до генерации `exportId`). Registry покрывает только execution-phase lifecycle (submit → poll → download → send → cancel).

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

Дополнительно, `ActiveExportRegistry.attachCancellable` выполняет **check-on-attach**: если на момент публикации callback'а запись уже в состоянии `CANCELLING`, он немедленно запускает `cancellable.cancel()` в `ExportCoroutineScope` — это закрывает узкое окно между моментом клика «Отмена» и фактическим появлением `cancellable` в registry.

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
| DONE / ошибка / отмена | Финальный текст (для отмены — `❌ Экспорт отменён`) | `replyMarkup = null` (клавиатура удаляется) |
| Early-return по timeout (processing / send) | Финальный текст ошибки | `replyMarkup = null` — обязательно, иначе старая кнопка «✖ Отмена» остаётся активной на финальном экране |

---

## 4. State Machine

`ActiveExportRegistry.Entry.state`:

```
ACTIVE ──markCancelling──> CANCELLING ──finally корутины──> (remove из registry)
   │                           │
   └── нормальное завершение ──┴──> (remove из registry)
```

- `markCancelling` — атомарный переход `ACTIVE → CANCELLING` через `ConcurrentHashMap.computeIfPresent` + `synchronized(entry)` (чтобы race с `release()` не вернул уже-удалённую запись). Успех возвращает снапшот `Entry`, повторный вызов или вызов для уже удалённого exportId возвращает `null`.
- `release(exportId)` — **сначала** `byExportId.remove(exportId)` (без `synchronized(entry)`), затем под `synchronized(entry)` очистка вторичных индексов (`byRecordingId`, `byChat`). Такой порядок исключает deadlock-окно с `markCancelling`: если бы `release` сначала брал `synchronized(entry)`, а внутри — `byExportId.remove` (бёрет bucket lock CHM), а `markCancelling` в параллели делал `computeIfPresent` (держит bucket lock) → `synchronized(entry)` (ждёт entry monitor) — получалась бы обратная последовательность захвата двух мониторов (dual-lock deadlock). Убираем entry monitor из процедуры `byExportId.remove` — после успешного remove'а concurrent `computeIfPresent` видит `null` и безопасно возвращается. Безопасно для двойного вызова (`remove` вернёт `null` во второй раз). Дополнительно: вызывается через `Job.invokeOnCompletion` (как дополнение к `finally` — на случай LAZY-корутины, отменённой до первого suspension point, где `finally` не сработает).
- `attachCancellable(exportId, cancellable)` — выполняется под `withContext(NonCancellable)` внутри `annotateVideo`, плюс check-on-attach: если `entry.state == CANCELLING` на момент вызова, немедленный `exportScope.launch { cancellable.cancel() }` — закрывает race-окно.
- `tryStartQuickExport` / `tryStartDialogExport` — синхронизованы через `synchronized(startLock)` (private object) для атомарности `putIfAbsent(index)` + `byExportId[...] = Entry`. Альтернативно — `release` содержит fallback-цикл, сканирующий вторичные индексы на висячие значения `exportId`. Стартов мало относительно длительности экспорта, contention незначителен.

Race: click «Отмена» между `submitWithRetry` и `attachCancellable`. `NonCancellable` гарантирует, что callback отработает. `attachCancellable` с check-on-attach ретраем закрывает окно: если CancelHandler уже сработал и выставил CANCELLING, `attachCancellable` сам вызовет `cancellable.cancel()` — vision server получит `/cancel`. Узкий риск остаётся только если CancelHandler прочитал `cancellable == null` и не успел выставить CANCELLING до `attachCancellable` — но это невозможно, так как `markCancelling` — первое, что делает handler. См. § 11 Known Limitations.

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
    withContext(NonCancellable) {
        renderCancelledFinal()   // editMessageText / sendTextMessage + editMessageReplyMarkup
    }
} catch (e: Exception) {
    // существующая логика (IllegalArgument / IllegalState / DetectTimeout / generic)
} finally {
    withContext(NonCancellable) {
        videoExportService.cleanupExportFile(videoPath)   // suspend cleanup в отменённой корутине
    }
    registry.release(exportId)
}
```

**Почему `withContext(NonCancellable)` обязателен.** Любой suspend-вызов (в том числе `bot.editMessageText`, `bot.sendTextMessage`, `tempFileHelper.deleteIfExists`, `videoExportService.cleanupExportFile`) в уже отменённой корутине **мгновенно** бросает `CancellationException` без фактической работы. Без обёртки финальный UI «❌ Отменён» не доходит до пользователя, и temp-файл осиротевает. `NonCancellable` переводит корутину в непрерываемое состояние на время обёрнутого блока — suspend-вызовы работают нормально, а по выходу cancellation восстанавливается.

### 5.3 Граничные случаи

| Случай | Поведение |
|---|---|
| Двойной клик «Отмена» | Второй клик → `markCancelling` = null → «Отмена уже выполняется» |
| Рестарт приложения после старта экспорта | Registry пуст → «Экспорт уже завершён или недоступен». Orphan job на сервере — без изменений. |
| Клик «Отмена» после завершения | Registry пуст → «Экспорт уже завершён или недоступен». |
| chatId ≠ entry.chatId | «Экспорт уже завершён или недоступен» (без раскрытия, что он существует у кого-то ещё). |
| Unauthorized | «Нет доступа» (переиспользуем `common.error.unauthorized`). |
| `/cancel` 409 / 5xx / timeout | Логируем; UI-рендер «Отменён» одинаков в любом случае. Пользователь не узнаёт про сбой на vision-сервере — сервер сам через TTL добьёт job. |
| Cancel в Phase 3 (download) | `CancellationException` проходит через `DataBufferUtils.write`; `downloadJobResult` обрабатывает её **явно** отдельным `catch (CancellationException) { withContext(NonCancellable) { cleanup }; throw }` до `catch (Exception)` — иначе temp-файл осиротеет (в корутинах `catch (Exception)` не ловит `CancellationException`, а `deleteIfExists` — suspend, поэтому без `NonCancellable` cleanup сам бросит CancellationException). |
| Cancel в SENDING (upload в Telegram) | `CancellationException` прерывает multipart upload; `cleanupExportFile` (suspend) вызывается из `finally` под `withContext(NonCancellable)` — иначе в уже отменённой корутине suspend-вызов мгновенно бросит CancellationException без реальной работы. |
| Cancel в `VideoExportServiceImpl.annotate(...)` после merge/compress | Отдельный `catch (CancellationException) { withContext(NonCancellable) { tempFileHelper.deleteIfExists(originalPath) }; throw }` до `catch (Exception)` — иначе merged/compressed файл осиротеет. |
| Cancel на MERGING/COMPRESSING | `CancellationException` доходит до ffmpeg-вызова, но `process.waitFor(...)` не прерывается синхронно. UI показывает «Отменяется…», финальный «Отменён» появится после того, как текущая ffmpeg-операция сама завершится. Cм. § 1 Non-Goals. |
| `registry.release` вызван дважды (finally + invokeOnCompletion) | `synchronized(entry)` + `ConcurrentHashMap.remove` идемпотентен. |
| LAZY-корутина отменена до первого suspension point | Тело с `finally { release() }` не стартует. Страховка через `job.invokeOnCompletion { registry.release(exportId) }`, регистрируется сразу после создания job'а. |

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
export.cancelled.by.user      = ❌ Экспорт отменён / ❌ Export cancelled
export.error.concurrent       = Уже идёт экспорт. / An export is already running.
```

Единая семантика: без отдельного сообщения «vision server не ответил» — пользователю неважно, сработал ли cancel на стороне сервера (логи покажут реализатору).

**Naming asymmetry by design.** `quickexport.cancelled` и `export.cancelled.by.user` — не симметричны намеренно: ключ `export.cancelled` уже занят dialog-cancel path (отмена до старта экспорта) и имеет другое значение. Суффикс `.by.user` добавлен, чтобы отличать "отменено пользователем во время выполнения" от существующего "отменено через dialog". `quickexport.cancelled` конфликта не имеет, поэтому суффикс не нужен.

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

## 11. Known Limitations

Явные, осознанно принятые ограничения текущей реализации.

1. **Orphan jobs на vision server при рестарте приложения.** Реестр активных экспортов in-memory; рестарт стирает всё. Job'ы на vision server продолжат выполняться и будут убиты серверным TTL.
2. **ffmpeg cancellation — best-effort.** На этапах MERGING/COMPRESSING `CancellationException` не прерывает ffmpeg мгновенно из-за блокирующего `process.waitFor(...)`. UI перейдёт в «⏹ Отменяется…», но финальный «❌ Отменён» появится после того, как текущая ffmpeg-операция завершится сама. См. § 1 Non-Goals.
3. **Узкий race `submitWithRetry` → `attachCancellable`.** Дизайн закрывает это check-on-attach ретраем: если CancelHandler успел выставить `CANCELLING` до публикации `cancellable`, `attachCancellable` сам запускает `cancellable.cancel()`. Остаточный риск — только между моментом успешного submit и первым чтением `state` внутри `attachCancellable`; это микросекундное окно, в худшем случае → orphan job, убивается серверным TTL.
4. **Legacy Telegram-сообщения без `exportId` в callback data.** После деплоя старые нотификации с `qe:`/`qea:` продолжают работать (запускают новый экспорт), но не имеют кнопки «Отмена» в старом сообщении — только у нового сообщения-статуса. Документируется в `.claude/rules/telegram.md`.

## 12. Out of Scope (явно)

- Persistence реестра.
- Cancellation-aware ffmpeg wrapper (`runInterruptible` + `destroyForcibly()`).
- Возможность возобновить отменённый экспорт.
- Метрики Prometheus по отменам.
- Temporary UI-блокировка кнопок после cancel (disabled «Отменено» на N секунд).

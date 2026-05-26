# Дизайн: обновление диаграммы «How It Works» в README

**Дата:** 2026-05-26
**Ветка:** `docs/refresh-howitworks-diagram`
**Скоуп:** обновление одной секции `README.md` — `## How It Works`. Без правок остального README, кода и `.claude/rules/`.

## Контекст

Текущая mermaid-диаграмма впервые появилась в коммите `9abb8d3` (2026-03-03) и с тех пор не менялась. За эти ~3 месяца проект получил крупные подсистемы, которые в схеме не отражены:

| Что добавлено в код, но отсутствует в текущей диаграмме |
|---|
| Multi-server load balancing для детекции (vision-api-server как внешний сервис с приоритетной балансировкой) |
| Two-stage detection — initial scan + recheck с более точной моделью (есть только короткое упоминание в подписи Consumers) |
| Object tracking — cross-recording IoU для подавления дубликатов |
| Signal-loss monitor — отдельная подсистема, polls БД, шлёт алерты |
| AI description — async через Claude Code CLI, редактирует уже отправленное сообщение |
| Двунаправленный Telegram: `/export` диалог, Quick Export inline-кнопки, обращение к ffmpeg / Vision API для аннотации |

Текущая схема даёт линейный поток `Recordings → Watcher → Producers→Consumers → DB → Visualize → Telegram`. Реальная система имеет минимум три независимых подсистемы (основной pipeline, signal-loss monitor, Telegram-интеракции) плюс внешний vision-api-server, к которому обращаются четыре разных стадии.

## Решения по дизайну

Принятые в ходе brainstorming:

1. **Одна расширенная диаграмма**, а не несколько мини-схем.
2. **Vision API Server — один внешний блок** с подписью «multi-instance, priority LB». Не рисуем LB отдельно и не показываем N инстансов — это перегрузило бы диаграмму.
3. **Двунаправленный Telegram**: показываем не только исходящие уведомления, но и команды от пользователя (`/export`, Quick Export) с обратным потоком через export jobs.
4. **Группировка через `subgraph`** для smyslovых блоков: Detection Pipeline, Telegram. Это даёт визуальную иерархию.

## Соответствие коду

| Узел диаграммы | Где живёт |
|---|---|
| File Watcher | `core/task/WatchRecordsTask`, `WatchRecordsLoop` (пишет recording-rows в БД) |
| Producers (6x) | `core/pipeline/frame/FrameExtractorProducer` (poll БД, запрос extract в Vision API) |
| Consumers (auto) | `core/pipeline/frame/FrameAnalyzerConsumer` (запрос detect в Vision API) |
| Vision API Server (external) | `vision-api-server` репо + балансировка в `loadbalancer/` |
| Visualize (local) + Save to DB | `core/facade/RecordingProcessingFacade.processAndNotify` — последовательность `frameVisualizationService.visualizeFrames` (локально, Java2D) + `recordingEntityService.saveProcessingResult` |
| Object Tracker (IoU) | `service/ObjectTrackerService` → `service/impl/NotificationDecisionServiceImpl` (gatekeeper, решает — слать уведомление или подавить) |
| PostgreSQL | recordings, detections, object_tracks |
| AI Description | `ai-description/` + `telegram/queue/DescriptionEditJobRunner` (триггерится из `telegramNotificationService.sendRecordingNotification` после фильтра подписчиков и `DescriptionRateLimiter.tryAcquire`) |
| Signal-loss Monitor | `core/task/SignalLossMonitorTask`, `SignalLossDecider` (polls только `recordings`-таблицу) |
| Telegram Bot | `telegram/` (bot core, очередь сообщений, авторизация) |
| Export / Annotate jobs | `telegram/handler/export/`, `telegram/handler/quickexport/` — исполнитель `core/service/VideoExportServiceImpl` |

## Ключевые потоки на диаграмме

1. **Основной pipeline** (как в `RecordingProcessingFacade.processAndNotify` — порядок важен):
   `Watcher → DB (recording-row) → Producers (poll) → Consumers (detect) → Facade.visualize (local) + Facade.save → Object Tracker (decide) → Bot`

2. **Vision API связан с тремя стадиями** (пунктирные стрелки):
   - Producers — extract frames
   - Consumers — detect (включая two-stage recheck)
   - Export jobs — video annotate для Quick Export «Annotated»

   (Уведомления визуализируются локально через `LocalVisualizationService` (Java2D) внутри Facade — Vision API там не задействован.)

3. **Параллельная ветка signal-loss:** `DB → SignalLossMonitor → Bot` — отдельное сообщение в Telegram, не edit существующего.

4. **Параллельная ветка AI description:** `Bot → AI Description (Claude CLI) → Bot (edit message)` — async из `TelegramNotificationSender` после rate-limit-проверки, редактирует уже отправленное уведомление.

5. **Обратная связь от пользователя** (через subgraph Telegram):
   `User ↔ Bot → Export Jobs` → `ffmpeg merge` (для Original) или Vision API video-annotate (для Annotated) → `Bot → User`.

## Mermaid-код

```mermaid
graph TD
    A["Frigate NVR<br/>Recordings (.mp4)"] --> B["File Watcher"]
    B --> DB

    V["<b>Vision API Server</b><br/>(external)<br/>multi-instance, priority LB"]

    DB -. "poll unprocessed" .-> P

    subgraph PIPE ["Detection Pipeline"]
        direction LR
        P["<b>Producers (6x)</b><br/>Extract key frames"] -- "Channel" --> Q["<b>Consumers (auto)</b><br/>Detect • Filter • Re-check"]
    end

    P -. "extract" .-> V
    Q -. "detect" .-> V

    Q --> FAC["Visualize (local) + Save to DB"]
    FAC --> DB[("PostgreSQL")]
    FAC --> OT["Object Tracker<br/>(cross-recording IoU)"]
    OT --> BOT

    DB --> SL["Signal-loss Monitor<br/>(polls recordings)"]
    SL --> BOT

    BOT -. "async describe" .-> AI["AI Description<br/>(Claude Code CLI)"]
    AI -. "edit message" .-> BOT

    subgraph TG ["Telegram"]
        direction TB
        BOT["<b>Bot</b><br/>notifications + commands"]
        EX["Export / Annotate jobs<br/>/export • Quick Export"]
        U["User"]
        BOT <--> U
        BOT --> EX
    end

    EX -. "ffmpeg merge" .-> BOT
    EX -. "video annotate" .-> V
```

Принятые правки относительно первой версии (по итогам external review iter-1):
- Watcher теперь пишет в DB, Producers её опрашивают — отражено явной парой `B → DB` и `DB -. poll .-> P` (Watcher и Producer не связаны прямым каналом — общаются через БД).
- Введён узел `FAC ("Visualize (local) + Save to DB")` между Consumers и Object Tracker — отражает реальный порядок шагов в `RecordingProcessingFacade.processAndNotify`.
- Object Tracker стал gatekeeper-ом между save и Bot (как в `NotificationDecisionService`), а не «перед DB».
- Стрелка визуализации к Vision API убрана — визуализация фреймов выполняется локально.
- AI-ветка перенесена с Object Tracker на Bot — `DescriptionEditJobRunner` запускается из telegram-слоя, а не из facade.
- Двунаправленные пунктирные стрелки `<-. label .->` заменены на однонаправленные `-. label .->` — синтаксис недокументирован и риск рендера на GitHub.

## Out of Scope

- Изменение текстового пояснения под диаграммой (фраза про vision-api-server остаётся как есть).
- Любые другие правки в README (Features, Configuration, Telegram Bot, и т.д.).
- Изменения в `.claude/rules/` и других docs.
- Изменения в коде.

## Критерии готовности

- `README.md` отрендеренный на GitHub корректно показывает обновлённую диаграмму.
- Все упомянутые в Features подсистемы (multi-server LB, two-stage detection, object tracking, signal-loss, AI description, Quick Export) присутствуют на диаграмме или явно подписаны.
- Mermaid синтаксис валиден (проверяется визуально в IDE / GitHub preview).

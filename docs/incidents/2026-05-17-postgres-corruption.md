# Incident: остановка pipeline из-за повреждения PostgreSQL

- **Дата инцидента:** 2026-05-17 12:24:22 (момент падения), обнаружено и устранено 2026-05-18
- **Симптом:** перестали приходить уведомления о записях в Telegram-бот
- **Длительность простоя:** ~30 часов
- **Влияние:** все 3 камеры — никаких уведомлений; за период простоя ~1700 видеозаписей не были обработаны (наверстаются `FirstTimeScanTask` после рестарта)

---

## TL;DR

Не было связано с `NOTIFICATIONS_TRACK_TTL=PT12H` — это совпадение по времени. В PostgreSQL **физически повредился B-tree индекс `pk_recordings`**: `INSERT INTO recordings` начал падать с `XX002: right sibling's left-link doesn't match`. Это исключение поймал `SimpleAsyncUncaughtExceptionHandler` поверх `@Async`-метода `WatchRecordsTask.run()`, его поток `task-1` тихо умер и больше не перезапускался — Spring `@Async` не предусматривает retry. Новые `.mp4` от Frigate перестали попадать в БД, `FrameExtractorProducer` не находил unprocessed записей, очередь уведомлений опустела.

Дополнительно обнаружено повреждение таблицы `detections`: tuples с битыми ссылками на `pg_xact` и `pg_subtrans` (статусы транзакций потеряны раньше, чем их успели заморозить). Повреждение размазано по таблице, локальной починке не поддавалось.

Починка: `REINDEX TABLE recordings` восстановил основную таблицу; повреждённая `detections` переименована в `detections_corrupt_archive`, на её месте создана чистая с идентичной структурой. Приложение исторические детекции не использует (`DetectionEntityService.findByRecordingId` нигде не вызывается из live-кода), поэтому потеря 293K строк безопасна.

После рестарта контейнера за первые 2 минуты прошло 8 уведомлений `notify: person` — pipeline работает.

---

## Хронология

| Время (UTC, msk?) | Событие |
|---|---|
| 2026-05-16 ~13:17 | Рестарт контейнера с новыми `NOTIFICATIONS_TRACK_TTL=PT12H`, `NOTIFICATIONS_TRACK_CLEANUP_RETENTION=PT48H` |
| 2026-05-16 — 17 12:24 | Pipeline работает нормально: 125 решений (`1 notify + 124 suppress all_repeated`) |
| 2026-05-17 12:24:22 | Первое исключение `XX002: right sibling's left-link doesn't match ... in index "pk_recordings"` |
| 2026-05-17 12:24:22+ | `SimpleAsyncUncaughtExceptionHandler` залогировал исключение, поток `task-1` (WatchRecordsTask) умер |
| 2026-05-17 12:24:50 | Последнее `Finalizing recording ...` — допроцессились ещё in-flight записи, потом тишина |
| 2026-05-17 12:27:15 | `SignalLossMonitorTask` разослал `Signal lost` по cam1/cam2/cam3 (lastSeen ~12:24:03Z) — recovery так и не пришёл |
| 2026-05-17 12:27 — 05-18 18:30 | ~30 часов простоя. В логах только VPS health flapping и шум от HTTP-сканеров |
| 2026-05-18 ~21:00 | User обратился: «не приходят уведомления, я недавно поднял TTL» |
| 2026-05-18 21:25–21:35 | Расследование: исключён TTL как причина, найден corrupted-индекс в логе |
| 2026-05-18 ~21:40 | User остановил приложение |
| 2026-05-18 21:40–22:00 | `REINDEX recordings`, точечная находка corrupt-tuple в `detections (5325,36)`, попытки чистого `REINDEX detections` через `VACUUM FREEZE` упали по `pg_xact/pg_subtrans` |
| 2026-05-18 22:00–22:10 | Стратегия пересмотрена: убедились через `grep`, что историческая `detections` приложением не читается → rename в архив + чистая новая таблица |
| 2026-05-18 22:15 | Рестарт контейнера, pipeline ожил, уведомления пошли |

---

## Что наблюдалось в логах

### 1. Исключение в `WatchRecordsTask`

```
2026-05-17T12:24:22,713 ERROR [task-1] o.s.a.i.SimpleAsyncUncaughtExceptionHandler :40 -
  Unexpected exception occurred invoking async method:
  public void ru.zinin.frigate.analyzer.core.task.WatchRecordsTask.run()
org.springframework.dao.DataAccessResourceFailureException: executeMany;
  SQL [INSERT INTO "recordings" ("id", "creation_timestamp", "file_path", ...) VALUES (...)];
  right sibling's left-link doesn't match: block 899 links to 18058 instead of expected 11782
  in index "pk_recordings"
  ...
Caused by: io.r2dbc.postgresql.ExceptionFactory$PostgresqlNonTransientResourceException:
  [XX002] right sibling's left-link doesn't match: ...
```

`XX002` — это `INDEX_CORRUPTED` в PostgreSQL: B-tree-инвариант «правый сосед страницы X должен ссылаться обратно на X» нарушен. Конкретно `block 899` ссылается на `18058` в качестве правого соседа, а тот через свой `left-link` указывает на `11782`. Структура сломана физически.

### 2. После этого — полная тишина `task-1`

```
$ grep "task-1" frigate-analyzer.log | tail
2026-05-17T12:00:17,640  INFO ... WatchRecordsTask: Registered ...
...
2026-05-17T12:24:22,713 ERROR [task-1] SimpleAsyncUncaughtExceptionHandler ...
# ничего после этой строки
```

Все `Decision:` решения тоже останавливаются: до момента остановки за период их было 125, после неё — ноль.

### 3. Signal-loss каскад

```
2026-05-17T12:27:15,174  INFO SignalLossMonitorTask :126 - Signal lost: camera=cam1, lastSeen=2026-05-17T12:24:03Z, gap=PT3M12s
2026-05-17T12:27:15,178  INFO SignalLossMonitorTask :126 - Signal lost: camera=cam2, lastSeen=2026-05-17T12:24:03Z, gap=PT3M12s
2026-05-17T12:27:15,181  INFO SignalLossMonitorTask :126 - Signal lost: camera=cam3, lastSeen=2026-05-17T12:24:02Z, gap=PT3M13s
```

`SignalLossMonitorTask` использует `repository.findLastRecordingPerCamera(...)` — он смотрит в БД, не в файловую систему. БД не пополняется → все 3 камеры тут же «считаются» отвалившимися. Это маскировало корневую причину: пользователь видел signal-loss alerts и думал, что проблема с Frigate/камерами, а не с writer-ом.

---

## Корневая причина — два слоя

### Слой 1: повреждение PostgreSQL

**Что повреждено и как именно:**

1. **`pk_recordings` (B-tree)** — нарушена сцепка соседей: `block 899 → right=18058, но 18058 → left=11782 ≠ 899`. Это структурное повреждение страниц индекса.

2. **`detections` (heap-страницы + потерянные XID-статусы)** — в нескольких блоках есть tuples со ссылками на уже не существующие commit-log файлы:

   ```
   ERROR: could not access status of transaction 1112175536
   DETAIL: Could not open file "pg_subtrans/424A": No such file or directory.
   ERROR: could not access status of transaction 1871757364
   DETAIL: Could not open file "pg_subtrans/6F90": No such file or directory.
   ERROR: could not access status of transaction 4090932037
   DETAIL: Could not open file "pg_xact/0F3D": No such file or directory.
   ```

   Это значит: страницы tuples не были «frozen» до того, как `autovacuum` обрезал `pg_subtrans`/`pg_xact`. Нормальный VACUUM/REINDEX/FREEZE на них падает, потому что не может определить visibility.

**Один из corrupt-tuples** (`ctid (5325,36)`) был замечен с `id IS NULL` при том, что `id` — PRIMARY KEY с `NOT NULL`. То есть страница в этом месте физически содержала мусор, который PG не отверг при загрузке (т.к. CRC опционален, и был отключён).

**Вероятные первопричины** (по убыванию правдоподобия):
1. **Аварийное завершение PG** (kill -9, OOM-kill, потеря питания, hard reboot) во время WAL-flush. Если WAL не успел записать commit-status некоторых субтранзакций, а потом autovacuum их обрезал — получаем точно ту картину.
2. **Сбой дисковой подсистемы** (bad sectors, ZFS/MD inconsistency, hypervisor snapshot без `fsfreeze`).
3. **Прерванный pg_upgrade или restore из backup** — менее вероятно, т.к. PG бы вообще не стартанул.
4. **glibc collation drift после `apt upgrade`** — для наших таблиц маловероятно, потому что повреждённые индексы — по `uuid` и `timestamptz`, не по `text`.

Проверить стоит:
```bash
journalctl -u postgresql -p err --since "-60 days" | grep -iE "crash|signal|killed|oom"
dmesg -T | grep -iE "i/o error|ata|memory error|oom"
smartctl -a /dev/<диск с PGDATA>
```

### Слой 2: архитектурный bug в `WatchRecordsTask`

`modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTask.kt`:

```kotlin
@Async
fun run() {
    val watchService = FileSystems.getDefault().newWatchService()
    registerAllDirs(folderPath, watchService)
    ...
    while (!stopped.get()) {
        val key = watchService.poll(POLL_PERIOD, TimeUnit.MILLISECONDS)
        // обработка с runBlocking { ... insert into recordings ... }
        // <-- НЕТ try/catch вокруг цикла
    }
}
```

Spring `@Async` без `AsyncUncaughtExceptionHandler` retry-логики **просто логирует и забывает**. Поток `task-1` умирает на первом же unchecked-исключении из тела цикла. Нет:
- алёрта в Telegram
- автоматического перезапуска
- health-индикатора, который позволил бы внешним мониторингам это заметить

Это превратило транзиентную ошибку БД (можно было пересоздать индекс CONCURRENTLY) в 30-часовой простой с маскирующим signal-loss alert.

---

## Что было сделано (шаг за шагом)

### Шаг 0: расследование без вмешательства

1. Подтвердил, что контейнер `deploy-frigate-analyzer-1` запущен и healthy (`Up 47 hours`) — значит, конфиг валиден, приложение не падало целиком.
2. Прочитал `application.yaml` и `ObjectTrackerProperties.kt` — конфиг с TTL=12H проходит валидацию (`cleanupRetention >= ttl ✓`).
3. Посмотрел `Decision:` в логе: 125 решений до 17 мая 12:23, ноль после. То есть pipeline ВООБЩЕ остановился, а не «давит уведомления».
4. Нашёл момент падения: `2026-05-17T12:24:22,713 ERROR ... WatchRecordsTask`.
5. Проследил, что `task-1` после этой строки в логах больше не появляется — поток мёртв.
6. Убедился, что новые `.mp4`-файлы пишутся в `/mnt/data/frigate/recordings/2026-05-18/...` через `docker exec deploy-frigate-analyzer-1 ls` — Frigate жив, проблема только в analyzer-writer.

### Шаг 1: остановка приложения

User самостоятельно сделал `docker compose down`.

### Шаг 2: подключение к PG и оценка масштаба

Подключение через `host.docker.internal` → `127.0.0.1:5432`, юзер `frigate_analyzer_rw` (он же owner всех таблиц — может делать `REINDEX` без `sudo -u postgres`).

Размеры таблиц/индексов:

| Таблица | Размер | Индексы |
|---|---|---|
| recordings | 721 MB | 1.17 GB суммарно (6 индексов) |
| detections | 78 MB | 88 MB суммарно (5 индексов) |
| object_tracks | 8 KB | мизер |
| остальные | 8 KB | мизер |

### Шаг 3: REINDEX recordings — это всё, что нужно для возобновления pipeline

```sql
REINDEX (VERBOSE) TABLE recordings;
-- Время: 50.9 сек
-- Перестроены: pg_toast_25709_index, pk_recordings, idx_recordings_creation_timestamp,
--              idx_recordings_file_creation_timestamp, idx_recordings_record_timestamp,
--              udx_export_recordings_file_path (581 MB → 40 сек), idx_recordings_process_timestamp
```

Все индексы перестроены без ошибок. `pk_recordings` восстановлен — `WatchRecordsTask` теперь сможет делать INSERT.

### Шаг 4: проверка остальных таблиц

```sql
REINDEX TABLE object_tracks;     -- OK
REINDEX TABLE telegram_users;    -- OK
REINDEX TABLE app_settings;      -- OK
REINDEX TABLE detections;        -- ❌ ОШИБКА
```

`detections` упал:

```
WARNING:  concurrent delete in progress within table "detections"
ERROR:  could not access status of transaction 1112175536
DETAIL:  Could not open file "pg_subtrans/424A": No such file or directory.
CONTEXT:  while checking uniqueness of tuple (5325,36) in relation "detections"
```

### Шаг 5: попытка точечно починить `detections`

1. Нашёл corrupt-tuple:
   ```sql
   SELECT ctid, xmin, xmax, recording_id, detection_timestamp
   FROM detections WHERE ctid='(5325,36)';
   -- (5325,36) | xmin=1871757364 | xmax=1112175536 | recording_id=f14fe47a-... | detection_timestamp=NULL
   --                                                                         ^ NOT NULL по схеме!
   ```
   То есть страница содержит мусор: `detection_timestamp NULL` нарушает constraint, который PG не успел проверить из-за повреждённой проверки visibility.

2. `DELETE FROM detections WHERE ctid='(5325,36)'` — прошёл (вернул `id=NULL, recording_id=f14fe47a-..., detection_timestamp=NULL`, что подтверждает мусор).

3. Повторный `REINDEX TABLE detections` — упал снова, теперь по `xmin` той же строки (после DELETE PG ещё пытается прочитать старую версию, чтобы проверить, виден ли DELETE):
   ```
   ERROR: could not access status of transaction 1871757364
   Could not open file "pg_subtrans/6F90": No such file or directory.
   ```

4. `SET vacuum_freeze_min_age = 0; VACUUM FREEZE detections;` — упал на ДРУГОМ блоке:
   ```
   ERROR: uncommitted xmin 11435995 needs to be frozen
   CONTEXT: while scanning block 5092 of relation "public.detections"
   ```
   Повреждение **не локально**.

5. Скан таблицы по блокам через `count(*) WHERE ctid IN block`:
   ```
   Total heap blocks: 9967
   Corrupt blocks: 1 (block 5325)
   ```
   Но это **обман**: count через ctid-фильтр читает только line pointers и не всегда триггерит проверку visibility, поэтому многие битые блоки прошли тихо. Реальное число повреждённых tuples неизвестно, но точно > 1 (видно по ошибкам на других этапах). И главное:

6. `NOT EXISTS`-проверка через seqscan по всей таблице — падает ещё на `pg_xact`:
   ```
   ERROR: could not access status of transaction 4090932037
   Could not open file "pg_xact/0F3D": No such file or directory.
   ```
   `pg_xact` (commit log) — это уже более низкий уровень, чем `pg_subtrans`. Часть строк указывает на commit-статусы, которые тоже потеряны. Точечная починка невозможна.

### Шаг 6: стратегическое решение по `detections`

Проверка: грепом по `modules/{core,telegram}` — `findByRecordingId` **не вызывается ни откуда** в production-коде. Старые детекции приложению не нужны. Текущий `NotificationDecisionService` получает свежие detections **по параметру**, а не вычитывает старые из БД.

→ Самый безопасный путь: **rename → recreate**, чтобы не потерять данные и при этом получить чистую таблицу.

```sql
BEGIN;

ALTER INDEX pk_detections                       RENAME TO pk_detections_corrupt_archive;
ALTER INDEX idx_detections_creation_timestamp   RENAME TO idx_detections_creation_timestamp_corrupt_archive;
ALTER INDEX idx_detections_recording_id         RENAME TO idx_detections_recording_id_corrupt_archive;
ALTER INDEX idx_detections_detection_timestamp  RENAME TO idx_detections_detection_timestamp_corrupt_archive;
ALTER INDEX idx_detections_recording_frame      RENAME TO idx_detections_recording_frame_corrupt_archive;
ALTER TABLE detections RENAME CONSTRAINT fk_detections_recording TO fk_detections_recording_corrupt_archive;
ALTER TABLE detections RENAME TO detections_corrupt_archive;

CREATE TABLE detections (
    id                  uuid                     NOT NULL,
    creation_timestamp  timestamp with time zone NOT NULL,
    recording_id        uuid                     NOT NULL,
    detection_timestamp timestamp with time zone NOT NULL,
    frame_index         integer                  NOT NULL,
    model               varchar(255)             NOT NULL,
    class_id            integer                  NOT NULL,
    class_name          varchar(255)             NOT NULL,
    confidence          real                     NOT NULL,
    x1 real NOT NULL, y1 real NOT NULL, x2 real NOT NULL, y2 real NOT NULL
);
ALTER TABLE detections ADD CONSTRAINT pk_detections PRIMARY KEY (id);
CREATE INDEX idx_detections_creation_timestamp  ON detections USING btree (creation_timestamp);
CREATE INDEX idx_detections_recording_id        ON detections USING btree (recording_id);
CREATE INDEX idx_detections_detection_timestamp ON detections USING btree (detection_timestamp);
CREATE INDEX idx_detections_recording_frame     ON detections USING btree (recording_id, frame_index);
ALTER TABLE detections ADD CONSTRAINT fk_detections_recording
    FOREIGN KEY (recording_id) REFERENCES recordings(id) ON DELETE CASCADE;

COMMIT;
```

Структура новой таблицы — побайтово идентична Liquibase changeset `20251230-01` (`CREATE TABLE IF NOT EXISTS detections ...`). Liquibase больше ничего не сделает, потому что changeset уже отмечен применённым в `databasechangelog`.

### Шаг 7: ANALYZE

```sql
ANALYZE recordings;     -- 712 ms
ANALYZE detections;     -- 0.6 ms (пустая)
ANALYZE object_tracks;  -- 16 ms
```

### Шаг 8: рестарт контейнера

После `docker compose up` пользователем:
- `WatchRecordsTask` стартанул, зарегистрировал watch-keys
- За первые 2 минуты:
  - 8 решений `notify: cam=cam{2,3} newClasses=[person]`
  - 15 решений `suppress (all_repeated)` — антиспам трекер работает
  - 0 новых ERROR в логах

Pipeline полностью функционален.

---

## Текущее состояние

```
| Таблица                       | Размер | Состояние                                     |
|-------------------------------|--------|-----------------------------------------------|
| recordings                    | 721 MB | ✅ Все индексы пересобраны                   |
| detections                    | 0 B    | ✅ Чистая, готова принимать INSERT           |
| detections_corrupt_archive    | 78 MB  | ⚠️  Архив старых данных, не используется    |
| object_tracks                 | 8 KB   | ✅                                            |
| telegram_users, app_settings  | 8 KB   | ✅                                            |
```

В архивной таблице ~293K старых детекций (за 2025-09-28 … 2026-05-06). На неё нет ссылок ни из кода, ни из FK. Можно удалить через `DROP TABLE detections_corrupt_archive;` после того, как pipeline проработает несколько дней без проблем.

---

## Что не делать впредь — и что доделать

### Архитектурный bug (открыт, нужно зафиксить отдельным коммитом)

`WatchRecordsTask.run()` падает молча. Минимальный fix:

```kotlin
@Async
fun run() {
    val folderPath = recordsWatcherProperties.folder
    logger.info { "Starting watch records in folder: $folderPath" }
    while (!stopped.get()) {
        try {
            runWatchLoop()  // содержит текущий внутренний цикл
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.error(t) { "WatchRecordsTask iteration failed, restarting after backoff" }
            Thread.sleep(5_000)
        }
    }
}
```

Лучше — переложить на `@Scheduled(fixedDelay)` с supervisor-логикой, или сделать `SmartLifecycle`-компонент, который умеет себя поднимать и репортить health через actuator.

Дополнительно: завести алёрт в Telegram при ошибке любого critical task — сейчас signal-loss alert маскирует реальную причину (это «камеры отвалились», а на деле — наш writer мёртв).

### Здоровье PG — превентивные меры

1. **Включить data checksums** (`pg_checksums --enable`, требует остановки PG). Сейчас они отключены, иначе corrupt-страницы были бы выявлены при чтении. Минимальный CPU-overhead (~2-3%).
2. **Алёрт на ошибки PostgreSQL**: tail `postgresql.log` через journald/promtail и алёрт по `XX002`, `could not access status of transaction`, `uncommitted xmin needs to be frozen`.
3. **Регулярный `pg_amcheck`** (доступен с PG 14). Можно установить `apt install postgresql-17-amcheck` и cron-задачей раз в неделю:
   ```bash
   pg_amcheck -h 127.0.0.1 -U postgres -d frigate_analyzer --all
   ```
4. **Backup-стратегия**: убедиться, что есть полный `pg_basebackup` + WAL архивы за разумный период, чтобы можно было откатиться до момента ДО повреждения, а не разбираться post-mortem.
5. **Расследовать первопричину**: `journalctl -u postgresql -p err --since "-90 days" | grep -iE "killed|signal 9|oom"`, `dmesg -T | grep -iE "i/o error|ata"`, `smartctl -a /dev/<PGDATA disk>`. Если найдётся OOM/kill-9/I/O — это и есть триггер.

### Про `NOTIFICATIONS_TRACK_TTL=PT12H`

Пока не отменяй, но наблюдай. За первые 2 минуты после рестарта: 8 `notify` / 15 `suppress` — здоровое соотношение, не похоже что трекер «душит» всё подряд. Если со временем начнёт быть много `suppress (all_repeated)` при очевидно новых событиях — снизь TTL до `PT2H`-`PT4H` либо подними `NOTIFICATIONS_TRACK_IOU_THRESHOLD` до `0.5`.

---

## Артефакты и команды для повтора при необходимости

Все DDL-команды собраны в одном месте на случай рецидива:

```sql
-- 1) recordings: переиндексация
REINDEX (VERBOSE) TABLE recordings;

-- 2) Точечно найти corrupt tuples (если повреждение локальное):
DO $$
DECLARE total_blocks int; block_num int; rows_in_block int;
BEGIN
    SELECT (pg_relation_size('detections') / 8192)::int INTO total_blocks;
    FOR block_num IN 0..total_blocks-1 LOOP
        BEGIN
            SELECT count(*) INTO rows_in_block FROM detections
            WHERE ctid >= ('(' || block_num || ',0)')::tid
              AND ctid <  ('(' || (block_num+1) || ',0)')::tid;
        EXCEPTION WHEN OTHERS THEN
            RAISE NOTICE 'Corrupt block: %', block_num;
        END;
    END LOOP;
END $$;

-- 3) Архивация повреждённой таблицы (см. шаг 6 выше) — полный DDL в репозитории.

-- 4) Когда уверен, что архив больше не нужен:
DROP TABLE detections_corrupt_archive;
```

---

## Чему научил этот инцидент

1. **Корреляция ≠ причина.** TTL поменяли за день до симптома → user связал. На самом деле — несвязанные события. Систематическая отладка (тайминги в логах, точка остановки `task-1`) увела от ложной гипотезы за 10 минут.
2. **Маскирующие алёрты опасны.** `Signal lost: camera=cam{1,2,3}` выглядел как «Frigate отвалился», а на деле — наш writer мёртв. Алёрт должен говорить «WatchRecordsTask died», а не «signal lost».
3. **`@Async` без supervisor — мина замедленного действия.** Любая транзиентная ошибка в bare-цикле = молчаливая смерть на месяцы.
4. **PG checksums + pg_amcheck — must-have** для long-running prod-баз. Без них corrupt-страницы обнаруживаются по факту падения, а не в момент возникновения.

# Incident: повторное повреждение PostgreSQL → диагностирован битый SATA-link диска `/dev/sda`

- **Дата инцидента:** 2026-05-23 12:14:27 UTC (15:14 MSK) — момент падения writer; обнаружено пользователем ~22:00 MSK
- **Симптом:** на `/statistics` `unprocessed=0` и `processingRatePerMinute=0.0` при активно работающем Frigate (новые `.mp4` пишутся на диск). Telegram-уведомления о новых событиях не идут.
- **Длительность простоя writer-а на момент диагностики:** ~7 часов
- **Влияние:** все 3 камеры — никаких уведомлений по новым событиям; ~7500 видеозаписей не были проиндексированы (этот хвост решено НЕ догонять — оставлен `DISABLE_FIRST_SCAN=true`)
- **Окончательная починка:** **отложена до физического ремонта SATA-кабеля sda**. На момент закрытия отчёта контейнер остановлен (`docker compose down`), БД не трогали.

---

## TL;DR

Это **второе срабатывание ровно того же сценария, что в [incident 2026-05-17](2026-05-17-postgres-corruption.md)**: `WatchRecordsTask` под `@Async` упал на ошибке `INSERT INTO recordings`, поток `task-1` тихо умер, новые `.mp4` перестали попадать в БД. Ошибка PG в этот раз другого класса — `XX000 could not open file "base/25697/34076.1" (target block 1503595372): previous segment is only 14451 blocks` — но **повреждён тот же объект**: `pk_recordings` (B-tree primary key indexa `recordings`).

Корневая причина первого слоя установлена окончательно: **битый SATA-link на диске `/dev/sda`** (Samsung SSD 860 EVO 500GB, SN `S3YANB0K310215F`). У него `UDMA_CRC_Error_Count = 1,525,536`, в kernel-логе регулярные `ata1: SError: { UnrecovData CommWake Handshk }` + `hard resetting link`, btrfs накопил на `/dev/sda3` **1,717,946 write_io_errs / 380,384 corruption_errs**. На втором диске того же контроллера (`/dev/sdb`, парный 860 EVO SN `S3YANB0K310168P`) и на остальных (`sdg`, `sdh`, `sdf`) — **0/0/0/0**. SMART здоровья самого SSD идеальный (`Reallocated_Sector_Ct=0`, `Uncorrectable_Error_Cnt=0`, `Runtime_Bad_Block=0`). Значит — кабель данных или порт `ata1`, не сам диск и не питание.

PG `data_checksums = off`, поэтому страницы, испорченные на стороне FS, доверчиво принимаются как валидные → в `pk_recordings` периодически оседают мусорные pointer'ы → следующий INSERT падает. Архитектурный bug `WatchRecordsTask` (открытый со времён майского инцидента) превращает транзиентный сбой БД в многочасовой простой writer-а.

Майский REINDEX починил симптом, но не причину — `pk_recordings` снова сломался через 6 дней работы на повреждённом железе.

---

## Хронология

| Время (MSK) | Событие |
|---|---|
| 2026-05-17 12:24 UTC | Первый инцидент — XX002 corruption `pk_recordings`, см. [предыдущий отчёт](2026-05-17-postgres-corruption.md) |
| 2026-05-18 ~21:40 | REINDEX recordings, rename `detections` → `detections_corrupt_archive`, рестарт. Pipeline жив. |
| 2026-05-20 22:35 | Система перезагружена (uptime до момента инцидента = 2 дня 23 ч). PG `postgresql@17-main` запущен. |
| 2026-05-20 22:36 | Контейнер `deploy-frigate-analyzer-1` поднят — uptime 22 ч на момент диагностики. |
| 2026-05-21 — 23 | В dmesg регулярно (минимум 4 эпизода): `ata1: SError: { UnrecovData CommWake Handshk } / hard resetting link / SATA link up 3.0 Gbps`. btrfs набирает `csum failed` и `write_io_errs` на `sda3`. |
| **2026-05-23 12:14:27 UTC** | Первое (и единственное) исключение `XX000 ... target block 1503595372 ... previous segment is only 14451 blocks` при `INSERT INTO recordings`. `SimpleAsyncUncaughtExceptionHandler` залогировал, поток `task-1` умер. |
| 2026-05-23 12:14:27 — 12:15:01 | `FrameAnalyzerConsumer` доделывает in-flight записи (6 штук). Дальше — тишина. |
| 2026-05-23 12:14:52 | Последнее `FrameExtractorProducer: Found N unprocessed`. После — pipeline в idle (нет работы, потому что writer не пишет новые recordings). |
| 2026-05-23 ~22:00 MSK | User обратился: «`unprocessed:0`, должно быть больше нуля в моменте». |
| 2026-05-23 22:20 MSK | Найдено: `task-1` мёртв с 12:14:27 UTC. Контейнер остановлен (`docker compose down`). |
| 2026-05-23 22:25 MSK | Диагностика выявила реальную причину в `dmesg`: `BTRFS error (device sdb3): bdev /dev/sda3 errs: wr 1717946, rd 232, flush 0, corrupt 380384, gen 0`. |
| 2026-05-23 22:30 MSK | Решено: пока **ничего не чинить**, дождаться физического переподключения SATA-кабеля. |

---

## Что наблюдалось

### 1. Исключение в логах приложения

`docker/deploy/logs/frigate-analyzer.log:2203`:

```
2026-05-23T12:14:27,154 ERROR [task-1] o.s.a.i.SimpleAsyncUncaughtExceptionHandler :40 -
  Unexpected exception occurred invoking async method:
  public void ru.zinin.frigate.analyzer.core.task.WatchRecordsTask.run()
org.springframework.dao.DataAccessResourceFailureException: executeMany;
  SQL [INSERT INTO "recordings" ("id", "creation_timestamp", "file_path", ...) VALUES (...)];
  could not open file "base/25697/34076.1" (target block 1503595372):
  previous segment is only 14451 blocks
Caused by: io.r2dbc.postgresql.ExceptionFactory$PostgresqlNonTransientResourceException:
  [XX000] could not open file "base/25697/34076.1" ...
```

PG-error `XX000` означает: индекс хранит pointer на блок №1.5 миллиарда, но физически в файле `base/25697/34076.1` (это 2-й сегмент relfilenode-а) всего **14451 блок**. То есть в одной из страниц `pk_recordings` лежит мусорный right-link/down-link.

### 2. Идентификация повреждённого объекта в PG

```
SELECT c.oid, c.relname, c.relkind, pg_size_pretty(pg_relation_size(c.oid)) AS size, c.relpages,
       i.indrelid::regclass AS parent_table
FROM pg_class c LEFT JOIN pg_index i ON i.indexrelid = c.oid
WHERE c.relfilenode = 34076;
```
→
```
25714 | pk_recordings | i | 113 MB | 14451 | recordings
```

`relpages = 14451` буквально совпадает с цифрой "previous segment is only 14451 blocks" в исключении. Это primary key `recordings(id)` — **тот же индекс, что чинили REINDEX-ом 18 мая**.

`SHOW data_checksums` → `off`. PG не сверяет CRC страниц при чтении, поэтому мусор от FS принимается без вопросов.

### 3. Признаки кончины потока `task-1`

```bash
$ grep "task-1" frigate-analyzer.log | tail -3
2026-05-23T12:14:27,135 INFO [task-1] WatchRecordsTask :100 - New file created: ...14.15.mp4
2026-05-23T12:14:27,154 ERROR [task-1] SimpleAsyncUncaughtExceptionHandler ... # тут поток умер
# ничего после
```

```bash
$ grep "Found .* unprocessed" frigate-analyzer.log | tail -3
12:14:42 ... Found 1 unprocessed
12:14:47 ... Found 3 unprocessed
12:14:52 ... Found 2 unprocessed
# после — пусто; БД не пополняется → нечего обрабатывать
```

### 4. Физика — `dmesg` и SMART

`dmesg -T | grep -iE "BTRFS"` показал волну `csum failed root 5 ino ... mirror 1` и `parent transid verify failed`, причём одно и то же значение `csum 0x8941f998` появлялось многократно в разных файлах — это сигнатура мусорного pattern'а с битой стороны mirror'а.

```
BTRFS error (device sdb3): bdev /dev/sda3 errs: 
  wr 1717946, rd 232, flush 0, corrupt 380384, gen 0
BTRFS error (device sdb3): parent transid verify failed on logical 74487676928
  mirror 1 wanted 1341873 found 1341641
```

`dmesg -T | grep ata1` — регулярные эпизоды:

```
ata1: SError: { UnrecovData CommWake Handshk }
ata1.00: exception Emask 0x10 SAct 0x2000 SErr 0x440100 action 0x6 frozen
ata1.00: irq_stat 0x08000000, interface fatal error
ata1.00: failed command: WRITE FPDMA QUEUED
ata1: hard resetting link
ata1: SATA link up 3.0 Gbps (SStatus 123 SControl 320)
ata1.00: configured for UDMA/133
```

SMART сравнительная таблица:

| Disk | Port | UDMA CRC errors | POR Reset | Power-on h | Reallocated | Uncorrectable | SMART health |
|---|---|---:|---:|---:|---:|---:|---|
| **sda** (`S3YANB0K310215F`) | **ata1** | **1,525,536** | 2985 | 29613 | 0 | 0 | PASSED |
| sdb (`S3YANB0K310168P`) | ata2 | **0** | 2977 | 29613 | 0 | 0 | PASSED |
| sdg | ata7 | **0** | 20 | 29357 | 0 | 0 | PASSED |
| sdh | ata8 | **0** | 20 | 29357 | 0 | 0 | PASSED |
| sdf | ata6 | **0** | — | 11856 | 0 | 0 | PASSED |

Парный с sda диск sdb (тот же бэкплейн, тот же контроллер, та же модель, тот же возраст) — **полностью чистый**. PSU и контроллер исключены.

POR_Recovery почти равный на sda/sdb говорит, что **не питание** — оно для пары общее. Уникален для sda именно UDMA CRC. Это **data-line** (кабель/порт). Power-кабель тоже стоит заменить заодно, но он не главный подозреваемый.

### 5. btrfs context

```
$ btrfs filesystem df /
Data, RAID10: total=172.00GiB, used=111.15GiB
Metadata, RAID10: total=2.00GiB, used=1.02GiB
System, RAID10: total=32.00MiB, used=48.00KiB

$ btrfs filesystem show /
  devid 1 size 461.71GiB used 87.02GiB path /dev/sdb3
  devid 2 size 461.71GiB used 87.02GiB path /dev/sda3   ← виновник
  devid 3 size 465.76GiB used 87.02GiB path /dev/sdg
  devid 4 size 465.76GiB used 87.02GiB path /dev/sdh

$ btrfs device stats /
[/dev/sdb3].write_io_errs    0   read_io_errs 0   corruption_errs 0
[/dev/sda3].write_io_errs    1717946   read_io_errs 232   corruption_errs 380384
[/dev/sdg].write_io_errs     0   read_io_errs 0   corruption_errs 0
[/dev/sdh].write_io_errs     0   read_io_errs 0   corruption_errs 0

$ btrfs scrub status /
no stats available  ← scrub НИ РАЗУ не запускался за 3.4 года FS
```

`/boot` (отдельный btrfs `b2f100e9-...`, raid1 mirror 2 устройств `sdb2 + sda2`) аналогично:

```
[/dev/sdb2].write_io_errs 0     corruption_errs 0
[/dev/sda2].write_io_errs 42    corruption_errs 3063
```

`/dev/sda1` (vfat /boot/efi mirror) не используется, ошибок неизвестно.

---

## Корневая причина — три слоя

### Слой 1: hardware — битый SATA-link sda (root cause)

Физическая проблема на линии передачи данных между контроллером (`pci-0000:00:1f.2-ata-1.0`) и диском sda. По профилю ошибок — **либо SATA data-кабель**, **либо порт ata1 на материнской плате**, **либо разъём data на самом диске**. Питание исключено сравнением POR_Recovery с sdb.

Когда драйвер ata1 теряет write-команды или получает мусорные ack — btrfs ловит `write_io_err` (1.7M накопилось). Параллельно при чтении страниц со стороны sda3 в момент до `repair_io_failure` btrfs может вернуть слою выше **мусорные байты** (это и есть наш `corruption_errs = 380K`).

### Слой 2: data-pipeline — PG доверяет FS

`data_checksums = off` на PG-кластере — настройка по умолчанию для PG, поставленного через `apt`. Это означает, что страницы не имеют PG-CRC, и PG **не проверяет**, что прочитанные с диска байты — это валидная страница. Мусор от FS интерпретируется как валидные heap-/index-структуры.

`btrfs scrub` за 3.4 года ни разу не запускался → латентные corruption накапливались годами незамеченно. У btrfs есть данные с другой стороны mirror'а — он бы исправил, если бы scrub его об этом попросил.

### Слой 3: приложение — `WatchRecordsTask` без supervisor

`modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTask.kt:77-145`:

```kotlin
@Async
fun run() {
    ...
    while (!stopped.get()) {
        val key = watchService.poll(POLL_PERIOD, TimeUnit.MILLISECONDS)
        // обработка с runBlocking { recordingEntityHelper.createRecording(...) }
        // НЕТ try/catch вокруг тела цикла
    }
}
```

Spring `@Async` без `AsyncUncaughtExceptionHandler` retry-логики просто логирует и забывает. Поток `task-1` умирает на первом же unchecked exception из цикла, и больше никогда не перезапускается. Нет:
- retry / backoff
- алёрта в Telegram при смерти critical task
- health-индикатора, который позволил бы внешним мониторингам это заметить (Spring actuator `/health` остаётся `UP`, потому что HTTP жив)
- defensive isolation между `WatchRecordsTask` и остальным pipeline-ом

Это **тот же открытый bug**, что описан в [майском отчёте](2026-05-17-postgres-corruption.md#архитектурный-bug-открыт-нужно-зафиксить-отдельным-коммитом). За 6 дней не зафикшен — и стрельнул второй раз.

### Сводка слоёв

| Уровень | Почему сломалось | Что обязательно сделать |
|---|---|---|
| Hardware | Битый SATA-кабель/порт ata1 | **Физически заменить кабель** (data + power для верности), пронаблюдать `CRC_Error_Count` сутки |
| Filesystem | btrfs не сверял (scrub не запускался) | Регулярный `btrfs scrub` (cron еженедельно) |
| Database | PG доверяет FS (`data_checksums=off`) | `pg_checksums --enable` на остановленном кластере; `pg_amcheck` cron |
| Application | `@Async` writer умирает молча | Supervisor wrapper, health indicator, Telegram alert на смерть |
| Monitoring | Healthcheck смотрит только HTTP | Алёрт на смерть `task-1` / на reactor errors / на PG XX000-XX002 |

Без починки слоя 1 — все вышестоящие фиксы только маскируют постоянно генерируемый мусор.

---

## Что было сделано (Phase 1 systematic-debugging)

1. По симптому `unprocessed=0` + `rate=0` локализовали мёртвый поток `task-1` → нашли исключение в логе.
2. По `relfilenode=34076` через `pg_class` идентифицировали `pk_recordings`.
3. Контейнер остановили (`docker compose down`), чтобы не накапливать дальнейших corruption-событий в БД.
4. По `dmesg` нашли волну btrfs `csum failed` и `ata1: SError ... hard resetting link`.
5. Сравнили SMART всех 5 дисков — выявили `UDMA_CRC_Error_Count = 1.5M` уникально на sda. Локализовали проблему до **физической линии sda**.
6. Идентифицировали диск для физического поиска: **Samsung SSD 860 EVO 500GB, SN `S3YANB0K310215F`, WWN `0x5002538e4024437c`, порт `pci-0000:00:1f.2-ata-1.0`**.

## Что было НАМЕРЕННО не сделано

По решению пользователя — **никаких изменений до физической починки железа**:
- `btrfs device remove /dev/sda3 /` — НЕ выполнено (требует `btrfs balance` convert RAID10→RAID1 перед этим, ~30 мин; решено отложить)
- `REINDEX (CONCURRENTLY) INDEX pk_recordings` — НЕ выполнено
- `pg_amcheck --all` — НЕ выполнено (требует установки `postgresql-17-amcheck`)
- `pg_checksums --enable` — НЕ выполнено
- `btrfs scrub start /` — НЕ выполнено
- Архитектурный фикс `WatchRecordsTask` — отложен (см. [docs/tasks/watch-records-task-supervisor-prompt.md](../tasks/watch-records-task-supervisor-prompt.md))
- `DISABLE_FIRST_SCAN=true` оставлено — backfill ~7500 пропущенных файлов делать не будем

---

## Текущее состояние на момент закрытия отчёта

| Компонент | Состояние |
|---|---|
| `deploy-frigate-analyzer-1` контейнер | **Остановлен** (`docker compose down`) |
| `deploy-frigate-analyzer-liquibase-1` | Остановлен |
| `postgresql@17-main` | Работает (uptime 2д 23ч) |
| `pk_recordings` (B-tree) | **Повреждён**, INSERT в `recordings` будет падать XX000 |
| `recordings` (heap) | Статус неизвестен — `pg_amcheck` не прогонялся |
| `detections` (heap) | Статус неизвестен; чистая таблица после майского rename, 700K строк |
| Frigate (запись `.mp4` на /mnt/data) | Работает (`sdf1`, отдельный диск без ошибок) |
| `/dev/sda3` в btrfs pool `/` | **Активен**, продолжает копить write_io_err при попытках записи |
| `/dev/sda2` в btrfs pool `/boot` | Активен, бьётся редко (boot почти не пишется) |
| Detect-server `mypc` (`<lan-detect-host>:3001`) | DEAD с момента старта контейнера — невыключен в конфиге, не критично |
| Detect-server `vps` (`<vps-host>:3001`) | До остановки: flapping 93×DEAD/93×ALIVE за сутки — сетевые таймауты, не критично |

---

## План восстановления (после физической починки кабеля)

В порядке выполнения:

1. **Физически:** выключить, заменить SATA-data-кабель (и для верности — power-кабель) к sda. Можно попробовать другой порт SATA. Включить, проверить, что `CRC_Error_Count` за час-сутки не растёт:
   ```bash
   sudo smartctl -A /dev/sda | grep CRC_Error
   ```

2. **`/` pool:** на лету (PG и контейнер можно НЕ останавливать):
   ```bash
   sudo btrfs balance start -dconvert=raid1 -mconvert=raid1 -sconvert=raid1 /
   sudo btrfs device remove /dev/sda3 /
   sudo btrfs device add /dev/sda3 /
   sudo btrfs balance start /                # перебалансировать
   sudo btrfs scrub start /                  # пройтись по всем данным, восстановить через mirror
   sudo btrfs scrub status /                 # мониторить
   ```

   Альтернатива (если хочется оставить RAID10): `btrfs device remove → physically reconnect → btrfs device add → balance` без convert; работает на 4 → 3 → 4 устройствах **с downtime degraded-mount**.

3. **`/boot` pool:** `btrfs device remove /dev/sda2 /boot` (станет single — OK для /boot), потом `btrfs device add /dev/sda2 /boot`, `btrfs balance -mconvert=raid1 -dconvert=raid1 /boot`.

4. **PG:** остановить → `pg_checksums --enable -D /var/lib/postgresql/17/main` (~10-20 мин на 224 GiB) → запустить:
   ```bash
   sudo systemctl stop postgresql@17-main
   sudo -u postgres /usr/lib/postgresql/17/bin/pg_checksums --enable \
       -D /var/lib/postgresql/17/main
   sudo systemctl start postgresql@17-main
   ```

5. **Установить amcheck, прогнать на всю БД:**
   ```bash
   sudo apt install postgresql-17-amcheck
   psql -d frigate_analyzer -c "CREATE EXTENSION IF NOT EXISTS amcheck"
   pg_amcheck -h 127.0.0.1 -d frigate_analyzer --all --heapallindexed --parent-check
   ```

6. **REINDEX повреждённых объектов:**
   ```sql
   REINDEX (CONCURRENTLY) INDEX pk_recordings;
   -- + других, что покажет amcheck
   ```

7. **Контейнер поднять:** `cd docker/deploy && ./deploy-up.sh`. Проверить в логах: появилось `Starting watch records`, через минуту — `New file created` / `Recording id:`.

8. **Cron'ы добавить:**
   - Еженедельный `btrfs scrub start /`
   - Еженедельный `pg_amcheck --all` с алёртом в Telegram при ошибках
   - Алёрт по `journalctl -u postgresql -p err` на `XX000`, `XX002`, `could not access status of transaction`, `uncommitted xmin needs to be frozen`

9. **Архитектурный фикс `WatchRecordsTask`:** см. отдельный prompt [docs/tasks/watch-records-task-supervisor-prompt.md](../tasks/watch-records-task-supervisor-prompt.md).

---

## Чему научил этот инцидент

1. **Открытый bug не починили — он вернулся.** Майский ретро прямо назвал `WatchRecordsTask` "открытым архитектурным багом, нужно зафиксить отдельным коммитом". За 6 дней не сделали — сценарий повторился 1-в-1 с той же длительностью простоя (~7 ч вместо ~30 ч только потому, что обнаружили быстрее).
2. **REINDEX лечит симптом, не причину.** В мае посчитали — pg_amcheck не запустили, "вероятные первопричины" перечислили списком и закрыли. Реальная причина (битый кабель) была видна в SMART и dmesg **уже тогда**. Если бы запустили `smartctl -A`, нашли бы 1.5M CRC errors сразу.
3. **`apt install postgresql` оставляет `data_checksums=off`.** Это сильный default для new-кластеров — должен быть включён сразу.
4. **`btrfs scrub` не работает без cron'а.** За 3.4 года FS — ни одного scrub'а. Латентная corruption невидима, пока не упрётся в чтение в горячем коде.
5. **Healthcheck в docker-compose проверяет HTTP, а не функцию.** Контейнер 22 ч "healthy", но 7 ч из них реально мёртв в части ключевой функции. Healthcheck должен включать "writer alive within last N minutes".
6. **`@Async` для критичных long-running задач — антипаттерн.** Использовать `SmartLifecycle` / `@Scheduled(fixedDelay)` с явным supervisor'ом.
7. **Маскирующие алёрты по-прежнему опасны.** `SignalLossMonitorTask` смотрит в БД, БД не обновляется → "все 3 камеры пропали". В мае это сбило с толку, в этот раз TTL подавил signal-loss и симптом дошёл до пользователя только через косвенный признак (`unprocessed=0` на статистике).

---

## Артефакты для повтора диагностики

```bash
# 1. Найти живые писатели
docker exec deploy-frigate-analyzer-1 ps -e | grep java
grep -E "task-1|SimpleAsyncUncaughtExceptionHandler" docker/deploy/logs/frigate-analyzer.log | tail

# 2. Идентифицировать упавший PG-объект по сообщению "base/<dboid>/<relfilenode>"
psql -d frigate_analyzer -c "
  SELECT c.relname, c.relkind, c.relfilenode, i.indrelid::regclass AS parent
  FROM pg_class c LEFT JOIN pg_index i ON i.indexrelid=c.oid
  WHERE c.relfilenode = <relfilenode>"

# 3. Дисковая диагностика
for d in /dev/sd[a-h]; do
  echo "=== $d ==="
  sudo smartctl -A "$d" | grep -E "CRC_Error|Reallocated|Uncorrect|Bad_Block|POR_Rec"
done

sudo btrfs device stats /
sudo btrfs scrub status /
dmesg -T | grep -iE "ata[0-9]+:|BTRFS.*error" | tail -100
```

```bash
# 4. Установить amcheck (когда дойдут руки):
sudo apt install postgresql-17-amcheck
psql -d frigate_analyzer -c "CREATE EXTENSION amcheck;"
pg_amcheck -h 127.0.0.1 -d frigate_analyzer --all --heapallindexed
```

# Дизайн: ускорение регистрации каталогов в WatchService

**Дата:** 2026-08-02
**Ветка:** `perf/watch-records-registration`
**База:** `master` = `553f988`

## Проблема

На проде `WatchRecordsLoop.registerAllDirs()` выполняется **9 минут 9 секунд** при каждом старте приложения.

Подтверждено логами прод-хоста после деплоя `v0.9.1`:

```
16:18:04,135  WatchRecordsTask :124 - Starting watch records in folder: /mnt/data/frigate/recordings
16:27:12,960  WatchRecordsLoop :153 - Registered 167 directories, skipped 11638 old directories.
16:27:12,962  WatchRecordsTask :253 - Watch service created; registered 167 directories.
```

### Последствия

1. **Контейнер держится `unhealthy` ~7 минут.** Пока `lastSuccessfulRegistrationAt == null`, `WatchRecordsTask.computeHealth` отдаёт `OUT_OF_SERVICE` первые 2 минуты (BRANCH 3), затем — после истечения `STARTUP_GRACE=2m` — **`DOWN`** (BRANCH 2). Оба состояния Spring Boot маппит в HTTP 503, docker healthcheck (`start_period=60s`, `interval=30s`, `retries=3`) метит контейнер `unhealthy`.
2. **Ложные алерты о потере сигнала.** Пока WatchService не зарегистрирован, новые `.mp4` не подхватываются, в БД нет свежих записей, и `SignalLossMonitorTask` объявляет потерю сигнала по всем камерам. На проде recovery пришёл через 22 секунды после завершения регистрации — то есть алерты были спровоцированы самой регистрацией.
3. **Каждый `ClosedWatchServiceException` повторяет всё заново.** Супервизор пересоздаёт WatchService, `registeredDirs` очищается и регистрируется с нуля — ещё 9 минут слепоты. Причём во второй раз срабатывает уже BRANCH 3.5 (`registeredDirs.isEmpty()` после успешного старта) — тоже `DOWN`.

### Измерения с прода

`/mnt/data` — HDD 5.5 TB, занято 73%. Структура Frigate: `recordings/YYYY-MM-DD/HH/camera/MM.SS.mp4`.

| Метрика | Значение |
|---|---|
| каталогов дат в корне | 124 |
| камер | 3 |
| файлов на камеро-час | ~360 (сегменты по 10 секунд) |
| каталогов всего | 11 805 (167 + 11 638 из лога) |
| файлов всего (оценка) | 124 × 24 × 3 × 360 ≈ **3.2 млн** |
| время регистрации | **549 с** |
| полезной работы | 167 регистраций |

549 с / 3.2 млн ≈ **170 мкс на элемент** — нормальная цифра для HDD с холодным dentry-кэшем.

Арифметика сходится: 167 = корень + вчера (1 + 24 + 24×3 = 97) + сегодня до 16:18 (1 + 17 + 17×3 = 69).

## Корневая причина

`modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsLoop.kt:131-155` — проверено по фактическому коду:

```kotlin
Files.walk(start).use { stream ->
    stream
        .filter { Files.isDirectory(it) }
        .forEach { dir ->
            if (isWithinWatchPeriod(dir, ...)) registeredDirs.computeIfAbsent(dir) { ... }
            else skipped++
        }
}
```

Три дефекта, каждый умножает работу:

1. **`Files.walk` возвращает плоский `Stream<Path>` и не управляем.** Фильтры ниже по цепочке на него не влияют — он спускается во всё дерево целиком: ~12 000 `opendir`, ~3.2 млн `readdir`-записей, `stat` на каждую, чтобы решить, спускаться ли внутрь.
2. **Двойной `stat`.** `.filter { Files.isDirectory(it) }` делает второй системный вызов на те же 3.2 млн путей. Обход уже знал ответ, но `Files.walk` отдаёт голые `Path` без атрибутов.
3. **Фильтр по дате применяется последним.** `isWithinWatchPeriod` работает чисто в памяти и отсеивает ~11.8 тыс. каталогов — но уже после того, как файловая система перелопачена полностью. Единственная проверка, способная сэкономить работу, стоит в конце цепочки.

Итого ~6.4 млн системных вызовов ради 167 регистраций.

## Проверенные факты о JDK

Перед проектированием поведение `Files.walkFileTree` проверено экспериментально (JDK 25, Linux). Результаты зафиксированы здесь, потому что два из них контринтуитивны.

**Факт 1. `root.relativize(root)` — пустой путь, и его `getNameCount()` возвращает `1`, а не `0`.**
Наивный расчёт глубины через `root.relativize(dir).nameCount` классифицировал бы корень как каталог даты. Требуется явный guard на пустой путь.

**Факт 2. Prune по дате + остановка на глубине камеры даёт `visitFile == 0`.**
На дереве из 4 дат (2 в периоде) × 2 часа × 2 камеры × 3 файла: `preVisitDirectory` = 17, `postVisitDirectory` = 7, отсечено поддеревьев = 2, **посещено файлов = 0**. Ни один `.mp4` не трогается. `postVisitDirectory` не вызывается для каталогов, из которых вернули `SKIP_SUBTREE`.

**Факт 3. Каталог открывается ДО вызова `preVisitDirectory`.**
Нечитаемый каталог (`chmod 000`) приходит в `visitFileFailed(AccessDeniedException)`, а не в `preVisitDirectory`. Из этого следует два вывода:
- `SKIP_SUBTREE` **не отменяет** `opendir` самого отсекаемого каталога — только `readdir` его содержимого;
- `SimpleFileVisitor.visitFileFailed` по умолчанию **пробрасывает** исключение, то есть один нечитаемый каталог оборвал бы всю регистрацию.

**Факт 4. Глубина, отсчитанная от корня, корректна и когда `start` не корень.**
При `walkFileTree(root/2026-08-02)` глубины выходят 1 (дата) / 2 (час) / 3 (камера), файлов посещено 0. Вызов из `runIteration` не требует отдельной ветки.

## Принятые решения

| Решение | Выбор | Отклонённые альтернативы |
|---|---|---|
| Стратегия обхода | `walkFileTree` + prune по дате + остановка на глубине камеры | (а) только prune по дате — оставляет ~52 000 файлов и растёт линейно по `WATCH_PERIOD`; (б) три вложенных `newDirectoryStream` — та же скорость, но раскладка зашивается в структуру управления, а вызов из `runIteration` требует диспетчера по глубине `start` |
| Развязка health от полной регистрации | **Не делаем** | После фикса регистрация занимает миллисекунды: BRANCH 2 (grace 2 минуты) недостижима, а BRANCH 3.5 закрывается сама — корень регистрируется первым колбэком, `registeredDirs` перестаёт быть пустым немедленно |
| Окно первого скана | Отдельная переменная `FIRST_SCAN_PERIOD`, дефолт `= WATCH_PERIOD` | Жёсткая привязка к `WATCH_PERIOD`; удаление `FirstTimeScanTask` целиком |
| Верификация | Счётчики в возвращаемом значении + тесты на временном дереве | Синтетический бенчмарк на dev — абсолютные числа на SSD не переносятся на прод-HDD, а счётчик даёт точный инвариант |
| `show-details` | Переменная окружения в основном `application.yaml`, дефолт `always` | Правка операторского `application-docker.yaml` — его нет в репозитории |

### Почему глубина камеры — приемлемое знание о раскладке

Единственная константа с предположением о структуре — `CAMERA_DEPTH = 3`. Это не новый риск: `RecordingFileHelper.parse` уже жёстко зашивает ту же раскладку через `path.parent.parent.parent` и `require(path.nameCount >= 6)`. Если Frigate сменит структуру каталогов, парсер отвалится на первом же файле независимо от того, что написано в обходе.

Дополнительно предположение ослаблено: проверка даты применяется на **каждом** уровне, а не только на глубине 1. Она чисто строковая и стоит ноль. Поэтому глубина остаётся ровно одним рычагом — она управляет только спуском, а не отсевом, и корректность от неё не зависит.

## Компонент 1: `RecordingsTree.kt` (новый файл)

`modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/RecordingsTree.kt`

Выделенная единица с одной задачей: по пути под корнем записей сказать, какая у него дата, попадает ли он в окно, можно ли отсечь его поддерево и на какой он глубине. Потребителей двое — `WatchRecordsLoop` и `FirstTimeScanTask`.

`extractDateFromPath` и `isWithinWatchPeriod` **переезжают** сюда из `WatchRecordsLoop.kt` без изменений тела. Пакет тот же, видимость `internal`, поэтому импорты не меняются нигде, включая тесты.

```kotlin
/** Глубина каталога камеры относительно корня записей: recordings/YYYY-MM-DD/HH/camera. */
internal const val CAMERA_DEPTH: Int = 3

private val DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

internal fun watchCutoff(watchPeriod: Duration, clock: Clock): LocalDate =
    LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(watchPeriod.toDays())

internal fun extractDateFromPath(path: Path, rootFolder: Path): LocalDate? { /* без изменений */ }

/** Fail-OPEN: дата не извлеклась — считаем путь внутри периода. Поведение не меняется. */
internal fun isWithinWatchPeriod(path: Path, rootFolder: Path, watchPeriod: Duration, clock: Clock): Boolean {
    val date = extractDateFromPath(path, rootFolder) ?: return true
    return !date.isBefore(watchCutoff(watchPeriod, clock))
}

/**
 * Fail-CLOSED зеркало [isWithinWatchPeriod]: поддерево отсекается ТОЛЬКО когда дата
 * успешно извлечена И она вне окна. Отдельная функция, а не `!isWithinWatchPeriod(...)`:
 * тождество сегодня выполняется, но отрицание читается как «не в периоде → режь»,
 * и именно эта формулировка провоцирует отрезать корень при рефакторинге.
 */
internal fun isPrunableDate(path: Path, rootFolder: Path, cutoff: LocalDate): Boolean {
    val date = extractDateFromPath(path, rootFolder) ?: return false
    return date.isBefore(cutoff)
}

/**
 * Глубина относительно корня: 0 = корень, 1 = дата, 2 = час, 3 = камера.
 * Возвращает -1 для путей вне корня — правила глубины к ним не применяются,
 * обход деградирует к полному, то есть к сегодняшнему поведению.
 *
 * ВНИМАНИЕ: rootFolder.relativize(rootFolder) — ПУСТОЙ путь, и его nameCount равен 1,
 * а не 0 (проверено). Без guard'а корень был бы классифицирован как каталог даты.
 */
internal fun depthFromRoot(path: Path, rootFolder: Path): Int {
    if (!path.startsWith(rootFolder)) return -1
    val rel = rootFolder.relativize(path)
    return if (rel.toString().isEmpty()) 0 else rel.nameCount
}
```

## Компонент 2: `registerAllDirs`

`WatchRecordsLoop.kt`. Возвращаемый тип меняется с `Int` на `RegistrationResult`. Это безопасно: **оба call-site игнорируют возврат** — `WatchRecordsTask.kt:244` и `WatchRecordsLoop.kt:83`.

```kotlin
data class RegistrationResult(
    /** Сколько каталогов реально добавлено в registeredDirs. */
    val registered: Int,
    /** Сколько поддеревьев отсечено по дате. */
    val prunedSubtrees: Int,
    /** Сколько записей файловой системы посещено. Главный инвариант тестов. */
    val visitedEntries: Int,
)
```

```kotlin
fun registerAllDirs(
    start: Path,
    watchService: WatchService,
    registeredDirs: ConcurrentMap<Path, WatchKey>,
): RegistrationResult {
    val root = recordsWatcherProperties.folder
    // Cutoff вычисляется ОДИН раз на весь обход: иначе обход, пересекающий полночь,
    // применял бы к разным веткам разные границы.
    val cutoff = watchCutoff(recordsWatcherProperties.watchPeriod, clock)
    var registered = 0
    var pruned = 0
    var visited = 0
    val startedAt = System.nanoTime()

    Files.walkFileTree(
        start,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                visited++
                if (isPrunableDate(dir, root, cutoff)) {
                    pruned++
                    return FileVisitResult.SKIP_SUBTREE
                }
                registeredDirs.computeIfAbsent(dir) {
                    val k = dir.register(watchService, ENTRY_CREATE)
                    registered++
                    k
                }
                // Ниже камеры только файлы. WatchService вешается на каталог камеры —
                // именно он присылает ENTRY_CREATE на новые .mp4.
                return if (depthFromRoot(dir, root) >= CAMERA_DEPTH) {
                    FileVisitResult.SKIP_SUBTREE
                } else {
                    FileVisitResult.CONTINUE
                }
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                visited++
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                visited++
                logger.warn(exc) { "Registration: skipping unreadable entry $file" }
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) logger.warn(exc) { "Registration: error after visiting $dir" }
                return FileVisitResult.CONTINUE
            }
        },
    )

    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
    logger.info {
        "Registered $registered dirs, pruned $pruned date subtrees, " +
            "visited $visited entries in ${elapsedMs}ms"
    }
    return RegistrationResult(registered, pruned, visited)
}
```

### Стоимость на проде после правки

| | до | после |
|---|---|---|
| посещено записей ФС | ~3.2 млн | ~320 |
| `stat` на элемент | 2 | 1 |
| посещено `.mp4` | ~3.2 млн | **0** |
| время | 549 с | миллисекунды |

Разбивка «после»: 1 (корень) + 124 (записи дат) + 2×24 (часы двух свежих дат) + 48×3 (камеры) ≈ 320.

**Остаточная стоимость, принятая осознанно.** Согласно Факту 3, `SKIP_SUBTREE` не отменяет `opendir` самого отсекаемого каталога. Значит 122 старые даты и 144 каталога камер всё же открываются и сразу закрываются — без `readdir`. На холодном HDD это сотни миллисекунд в худшем случае. Величина растёт линейно по `record.retain.days`, а не по числу файлов, и остаётся на три порядка меньше исходных 549 с. Избавиться можно только отказавшись от единого обхода в пользу ручного `newDirectoryStream` на верхнем уровне — ровно та связанность с раскладкой, которая отклонена выше.

### Изменения поведения

1. **Нечитаемый подкаталог больше не роняет регистрацию.** Сейчас `Files.walk` бросает `UncheckedIOException` при потреблении стрима → `registerAllDirs` падает → `ensureWatchService` считает это ошибкой регистрации → backoff → приложение вообще не следит за записями. С `visitFileFailed → CONTINUE` каталог логируется на WARN и пропускается. Строго лучше, но это новое поведение.
2. **Строка лога меняет форму и смысл счётчика.** Было `Registered 167 directories, skipped 11638 old directories`, стало `Registered 167 dirs, pruned 122 date subtrees, visited 320 entries in 41ms`. Считаются отсечённые **поддеревья**, а не каталоги поштучно, поэтому число падает с 11638 до 122 — формулировка изменена, чтобы это не читалось как регрессия.
3. **Порядок регистрации.** Корень регистрируется первым колбэком обхода, поэтому `registeredDirs` перестаёт быть пустым немедленно. Это закрывает окно BRANCH 3.5 при пересоздании WatchService.

`cleanupExpiredDirs` не меняется — он итерирует map, а не файловую систему.

## Компонент 3: `FirstTimeScanTask`

Тот же `Files.walk` по всем 3.2 млн файлов плюс `createRecording` на каждый. На проде выключен (`DISABLE_FIRST_SCAN=true`), но код остаётся миной. Три правки.

### 3.1 Ограничение окна

`Files.walk` заменяется на `Files.walkFileTree` с тем же `isPrunableDate` в `preVisitDirectory`, но **без** ограничения `CAMERA_DEPTH` — файлы здесь и есть цель, поэтому спуск идёт до конца. Пути собирает `visitFile`; `visitFileFailed` логирует на WARN и возвращает `CONTINUE`, как в `registerAllDirs`.

Граница — `watchCutoff(recordsWatcherProperties.firstScanPeriod, clock)`, вычисляется один раз на обход. Отсев идёт по датам, поэтому окно всегда целые дни: `P0D` = только сегодня, `P1D` = сегодня и вчера.

Пути собираются в `List<Path>` за один обход, затем обрабатываются потоком. При дефолтном `P1D`, трёх камерах и десятисекундных сегментах это ~52 000 путей (~10 МБ) — приемлемо. Число собранных файлов логируется до начала обработки, чтобы масштаб был виден в логе.

Масштаб стоит понимать: каждый проиндексированный файл попадает в `recordings`, а оттуда в YOLO-конвейер. `FIRST_SCAN_PERIOD` — как раз ручка, чтобы при включении первого скана получить осмысленный объём.

### 3.2 Изоляция ошибок

Текущий `.catch {}` навешен на цепочку Flow и **завершает поток**, а не пропускает элемент. Первый же файл с дублем `file_path` или нераспознаваемым именем молча убивает весь скан и печатает «Finish first time scan task».

Обработка переезжает внутрь `flatMapMerge`: сбой одного файла логируется на WARN, увеличивает счётчик `failed` и не эмитит ничего; скан продолжается. `CancellationException` пробрасывается — как в `WatchRecordsLoop.runIteration`. Внешний `.catch` остаётся последней сеткой.

Логирование каждого id на INFO заменяется на счётчики и одну итоговую строку — 52 000 строк лога бесполезны.

### 3.3 Тестируемость

Логика выносится в `internal suspend fun scan(): ScanResult`, `run()` остаётся тонкой обёрткой, которая её запускает в детач-скоупе. Тот же раскол, что уже применён в паре `WatchRecordsLoop` (логика) / `WatchRecordsTask` (запуск и супервизия).

```kotlin
internal data class ScanResult(
    val indexed: Int,
    val failed: Int,
    val prunedSubtrees: Int,
    val visitedEntries: Int,
)
```

Счётчики `indexed` / `failed` — `AtomicInteger`, потому что `flatMapMerge(concurrency = 8)` работает конкурентно.

В конструктор добавляется `clock: Clock` (бин уже существует, используется в `WatchRecordsLoop`).

**Не входит:** `@Async` поверх `CoroutineScope(Dispatchers.Default).launch` (избыточно, но безвредно) и отсутствие отмены скоупа при shutdown. К этой задаче не относится.

## Компонент 4: конфигурация

### `RecordsWatcherProperties`

```kotlin
data class RecordsWatcherProperties(
    val disableFirstScan: Boolean = false,
    @field:NotNull val folder: Path,
    @field:NotNull val watchPeriod: Duration = Duration.ofDays(1),
    @field:NotNull val firstScanPeriod: Duration = watchPeriod,
    @field:NotNull val cleanupInterval: Duration = Duration.ofHours(1),
) {
    init {
        require(watchPeriod.toDays() >= 1) { "watchPeriod must be at least 1 day, got: $watchPeriod" }
        require(!firstScanPeriod.isNegative) { "firstScanPeriod must not be negative, got: $firstScanPeriod" }
        // строка про cleanupInterval — без изменений
    }
}
```

Нижняя граница у `firstScanPeriod` — `P0D`, а не `P1D` как у `watchPeriod`: иначе «просканировать только сегодня» невыразимо.

### `modules/core/src/main/resources/application.yaml`

```yaml
  records-watcher:
    disable-first-scan: ${DISABLE_FIRST_SCAN:false}
    folder: ${FRIGATE_RECORDS_FOLDER:/mnt/data/frigate/recordings}
    watch-period: ${WATCH_PERIOD:P1D}
    # Defaults to watch-period: the startup backfill covers exactly the window the watcher watches.
    # The default references the RESOLVED property, not $WATCH_PERIOD — same form as reappear-gap
    # above; reading the variable instead would silently fall back to P1D whenever watch-period is
    # set by anything other than that one env var.
    first-scan-period: ${FIRST_SCAN_PERIOD:${application.records-watcher.watch-period}}
    cleanup-interval: ${WATCH_CLEANUP_INTERVAL:PT1H}
```

Форма дефолта — не `${FIRST_SCAN_PERIOD:${WATCH_PERIOD:P1D}}`. Проект уже наступал на эти грабли и зафиксировал вывод прямо в `application.yaml:62-66`: вложенная **переменная окружения** следует только за самой собой, и если `watch-period` задан профильным yaml, CLI-аргументом или relaxed-именем `APPLICATION_RECORDSWATCHER_WATCHPERIOD`, дефолт молча свалится к литералу `P1D`. Ссылка на разрешённое свойство `${application.records-watcher.watch-period}` следует за значением из любого источника. Тот же приём применён для `reappear-gap` (строка 67) и для `claude.working-directory`.

Дублирующая страховка — Kotlin-дефолт `firstScanPeriod: Duration = watchPeriod` в самом data-классе, ровно как `reappearGap: Duration = ttl` в `ObjectTrackerProperties`.

Плюс блок, которого в файле сейчас нет вовсе:

```yaml
management:
  endpoint:
    health:
      show-details: ${HEALTH_SHOW_DETAILS:always}
```

Дефолт `always`, а не `never`: проект однодеплойный (зафиксировано в `.claude/rules/pipeline.md`), поэтому на проде не требуется ручных действий. `computeHealth` уже собирает `reason`, `registeredDirs`, `lastSuccessfulRegistrationAt` и `lastFailure` — сейчас всё это молча выбрасывается, из-за чего причину девятиминутного `DOWN` пришлось восстанавливать по логам.

`modules/core/src/test/resources/application.yaml` не трогаем: у `firstScanPeriod` есть Kotlin-дефолт, биндинг отработает без строки.

### `docker/deploy/.env.example`

Добавляется секция watcher'а. Соблюдается принятая в файле конвенция: комментарий **над** строкой, не inline — парсеры `env_file` не считают `#` inline-комментарием и запекли бы текст в значение.

```
# --- Records watcher ---
# Skip the one-off startup scan that indexes recordings already present on disk.
# DISABLE_FIRST_SCAN=false
# How far back the watcher registers directories. Day granularity: P1D = today + yesterday.
# WATCH_PERIOD=P1D
# How far back the startup scan indexes files. Defaults to WATCH_PERIOD. P0D = today only.
# FIRST_SCAN_PERIOD=P1D
# Expose /actuator/health details (reason, registeredDirs, timestamps): always | never | when-authorized
# HEALTH_SHOW_DETAILS=always
```

`DISABLE_FIRST_SCAN` и `WATCH_PERIOD` документируются заодно — сейчас их в `.env.example` нет вообще.

## Тестирование

### Каноническая фикстура

Часы зафиксированы на `2026-05-23T12:00:00Z` (как в существующем `WatchRecordsLoopTest`), `watchPeriod = P1D` → cutoff `2026-05-22`.

```
<tmp>/                      корень
├── 2026-05-23/             сегодня        — в окне
├── 2026-05-22/             ровно cutoff   — в окне
├── 2026-05-21/             день до cutoff — вне окна
└── 2025-01-01/             далеко вне     — вне окна
        каждая дата: часы 00, 01
        каждый час:  cam1, cam2
        каждая камера: 00.10.mp4, 00.20.mp4, 00.30.mp4
```

Ожидаемые значения для `registerAllDirs(root)` — совпадают с результатом эксперимента на такой же структуре:

| | значение | разбивка |
|---|---|---|
| `registered` | **15** | корень 1 + даты 2 + часы 4 + камеры 8 |
| `prunedSubtrees` | **2** | `2026-05-21`, `2025-01-01` |
| `visitedEntries` | **17** | 15 зарегистрированных + 2 отсечённые даты; файлов 0 |

### `RecordingsTreeTest.kt` (новый)

Сюда переезжают существующие тесты чистых функций из `WatchRecordsLoopTest.kt` (`extractDateFromPath` ×5, `isWithinWatchPeriod` ×5) — они тестируют файл, который переехал. Добавляются:

1. `isPrunableDate` возвращает `false` для пути без даты (корень) — fail-closed
2. `isPrunableDate` возвращает `false` для даты в окне
3. `isPrunableDate` возвращает `true` для даты до cutoff
4. `depthFromRoot(root, root) == 0` — регрессия на пустой путь с `nameCount == 1`
5. `depthFromRoot` даёт 1 / 2 / 3 для даты / часа / камеры
6. `depthFromRoot` возвращает `-1` для пути вне корня

### `WatchRecordsLoopTest.kt` (дополняется)

Существующие тесты `runIteration` остаются. Дерево строится через `Files.createTempDirectory` с очисткой в `finally` — как в текущих тестах файла. Добавляется группа на `registerAllDirs`, который сегодня не вызывает ни один тест:

1. **Регистрируются ровно нужные каталоги** — сверка `registeredDirs.keys` точным множеством: корень, 2 даты в окне, их 4 часа, их 8 камер
2. **Ни один `.mp4` не посещён** — `visitedEntries == 17`; главный инвариант, не зависящий от железа
3. **Поддеревья вне окна отсечены** — `prunedSubtrees == 2`, ни один путь из-под `2026-05-21` и `2025-01-01` не зарегистрирован
4. **Ниже камеры не спускаемся** — вложенный каталог, созданный внутри `cam1`, не зарегистрирован, `visitedEntries` остаётся 17
5. **Корень зарегистрирован, хотя даты у него нет** — guard на fail-open
6. **`start` ниже корня** (вызов как из `runIteration`): `registerAllDirs(root/2026-05-23)` → `registered == 7`, `prunedSubtrees == 0`, `visitedEntries == 7`, корень не зарегистрирован, файлы не посещены
7. **Идемпотентность** — повторный вызов даёт `registered == 0` при тех же `prunedSubtrees == 2` и `visitedEntries == 17`
8. **Нечитаемый подкаталог не роняет обход** — `chmod 000` на `2026-05-23/00`, обход завершается, `2026-05-23/01` и его камеры зарегистрированы. Тест обёрнут `Assumptions.assumeTrue`: под root'ом `chmod 000` не ограничивает доступ, и проверка была бы ложноположительной. Гард — попытка `Files.newDirectoryStream` на подготовленном каталоге

### `FirstTimeScanTaskTest.kt` (новый)

Тестируется `scan()` напрямую. `RecordingFileHelper` и `RecordingEntityHelper` мокаются, как в существующих тестах.

1. **Индексируются только файлы в окне** — на канонической фикстуре `createRecording` вызван ровно 24 раза (2 даты × 2 часа × 2 камеры × 3 файла), и ни разу с путём под `2026-05-21` или `2025-01-01`
2. **Сбой одного файла не останавливает скан** — `parse` бросает на одном конкретном пути; остальные 23 проиндексированы, `ScanResult.failed == 1`, `indexed == 23`
3. **`P0D` индексирует только сегодняшнюю дату** — 12 файлов

### `RecordsWatcherPropertiesBindingTest.kt` (новый)

Строится по образцу существующего `ObjectTrackerPropertiesBindingTest` — он биндит продовый `src/main/resources/application.yaml` через `Binder` и синтетический `StandardEnvironment`. Это единственный способ проверить продовый yaml: тестовый classpath несёт собственный `application.yaml`, который его затеняет, поэтому все placeholder'ы продового файла иначе впервые вычисляются только на проде.

1. При пустом окружении `firstScanPeriod == watchPeriod == P1D`
2. `WATCH_PERIOD=P3D` через env → `firstScanPeriod == P3D`
3. `application.records-watcher.watch-period=P3D` как свойство (профильный yaml / CLI) → `firstScanPeriod == P3D` — случай, который отвергнутая форма с вложенной переменной провалила бы
4. `APPLICATION_RECORDSWATCHER_WATCHPERIOD=P3D` (relaxed-имя) → `firstScanPeriod == P3D`
5. Явный `FIRST_SCAN_PERIOD=P0D` перебивает дефолт
6. `management.endpoint.health.show-details` из продового yaml резолвится в `always` при пустом окружении и в `never` при `HEALTH_SHOW_DETAILS=never`

### Проверка перед сборкой

`WatchRecordsTaskTest.kt` мокает `loop.registerAllDirs(...)` на строках 436 и 467 — обе через `throws`, возвращаемое значение не стабится, смена типа их не задевает. При реализации убедиться, что нигде в тестах нет `every { loop.registerAllDirs(...) } returns <Int>`.

## Критерии готовности

- `visitedEntries == 17` на канонической фикстуре — закреплено тестом
- ни один `.mp4` не посещается при регистрации — закреплено тестом
- `depthFromRoot(root, root) == 0` — закреплено тестом
- на проде новая строка лога показывает `visited` в сотнях и время в миллисекундах вместо 9 мин 9 с
- `/actuator/health` отдаёт `reason` и `registeredDirs` вместо голого статуса
- `./gradlew build` зелёный, `ktlintCheck` чистый

## Файлы

| Файл | Действие |
|---|---|
| `core/task/RecordingsTree.kt` | новый; хелперы дат, prune-предикат, глубина |
| `core/task/WatchRecordsLoop.kt` | `registerAllDirs` переписан, `RegistrationResult`, хелперы дат вынесены |
| `core/task/FirstTimeScanTask.kt` | окно, изоляция ошибок, вынесенный `scan()` |
| `core/config/properties/RecordsWatcherProperties.kt` | `firstScanPeriod` + валидация |
| `core/src/main/resources/application.yaml` | `first-scan-period`, блок `management` |
| `docker/deploy/.env.example` | секция watcher'а |
| `core/src/test/.../config/properties/RecordsWatcherPropertiesBindingTest.kt` | новый; биндинг продового yaml по образцу `ObjectTrackerPropertiesBindingTest` |
| `core/src/test/.../RecordingsTreeTest.kt` | новый; переехавшие + новые тесты чистых функций |
| `core/src/test/.../WatchRecordsLoopTest.kt` | группа тестов `registerAllDirs` |
| `core/src/test/.../FirstTimeScanTaskTest.kt` | новый |
| `.claude/rules/pipeline.md` | раздел «Selective watching» — prune, глубина, счётчики, окно первого скана |
| `.claude/rules/configuration.md` | `FIRST_SCAN_PERIOD`, `HEALTH_SHOW_DETAILS`, `DISABLE_FIRST_SCAN` |

## Вне работы

- Вариант 4 — развязка health от полной регистрации (`onRegistrationSuccess` после корня, фоновая дорегистрация)
- `start_period` в docker healthcheck
- Отменяемость скоупа `FirstTimeScanTask` при shutdown и избыточный `@Async`
- Агрегация и кулдаун Telegram-уведомлений
- Нестабильность detect-сервера `vps`
- Сокращение `record.retain.days` и длины сегмента в самом Frigate, перенос архива на SSD

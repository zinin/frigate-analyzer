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
3. **Каждый `ClosedWatchServiceException` повторяет всё заново.** Супервизор пересоздаёт WatchService, `registeredDirs` очищается и регистрируется с нуля — ещё 9 минут слепоты. BRANCH 3.5 (`registeredDirs.isEmpty()` после успешного старта) успевает отдать `DOWN` только в окне backoff перед перерегистрацией: сам обход заполняет map немедленно (`Files.walk` отдаёт `start` первым элементом стрима), и остаток девяти минут `DOWN` держит BRANCH 6 (`consecutiveFailures > 0`) — та же 503, но другая ветка и другая причина в `reason`.

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
Нечитаемый каталог (`chmod 000`) приходит в `visitFileFailed(AccessDeniedException)`, а не в `preVisitDirectory`. Механизм (OpenJDK `FileTreeWalker.visit`): чтение атрибутов каталога с mode 000 успешно — `stat` требует лишь search-права на родителя, — но следом walker открывает `DirectoryStream`, и именно этот отказ маршрутизируется в `visitFileFailed`, не доходя ни до `preVisitDirectory`, ни до `dir.register(...)`; `postVisitDirectory` вызывается только для каталогов, чей стрим успел открыться. Из этого следует два вывода:
- `SKIP_SUBTREE` **не отменяет** `opendir` самого отсекаемого каталога — только `readdir` его содержимого;
- `SimpleFileVisitor.visitFileFailed` по умолчанию **пробрасывает** исключение, то есть один нечитаемый каталог оборвал бы всю регистрацию.

**Факт 4. Глубина, отсчитанная от корня, корректна и когда `start` не корень.**
При `walkFileTree(root/2026-08-02)` глубины выходят 1 (дата) / 2 (час) / 3 (камера), файлов посещено 0. Вызов из `runIteration` не требует отдельной ветки.

## Принятые решения

| Решение | Выбор | Отклонённые альтернативы |
|---|---|---|
| Стратегия обхода | `walkFileTree` + prune по дате + остановка на глубине камеры | (а) только prune по дате — оставляет ~52 000 файлов и растёт линейно по `WATCH_PERIOD`; (б) три вложенных `newDirectoryStream` — та же скорость, но раскладка зашивается в структуру управления, а вызов из `runIteration` требует диспетчера по глубине `start` |
| Развязка health от полной регистрации | **Не делаем** | После фикса регистрация занимает миллисекунды: BRANCH 2 (grace 2 минуты) недостижима, а BRANCH 3.5 закрывается сама — корень регистрируется первым колбэком, `registeredDirs` перестаёт быть пустым немедленно |
| Окно первого скана | Отдельная переменная `FIRST_SCAN_PERIOD`, дефолт `= WATCH_PERIOD`; только целые сутки (`require`, субдневные значения падают на старте вместо молчаливого усечения `toDays()`) | Жёсткая привязка к `WATCH_PERIOD`; удаление `FirstTimeScanTask` целиком; часовая гранулярность окна (несоразмерное расширение скоупа) |
| Дефолт `DISABLE_FIRST_SCAN` | **`true`** — скан становится opt-in бэкфиллом (первичная установка, восстановление индекса); включение — осознанное действие | Оставить `false`: починенный скан впервые довёл бы до конца никем не наблюдавшийся ~52k-бэкфилл на свежем деплое, одновременно со стартом watcher'а; `false` противоречит единственной реальной эксплуатации |
| Верификация | Счётчики в возвращаемом значении + тесты на временном дереве | Синтетический бенчмарк на dev — абсолютные числа на SSD не переносятся на прод-HDD, а счётчик даёт точный инвариант |
| `show-details` | Переменная окружения в основном `application.yaml`, дефолт `always` | Правка операторского `application-docker.yaml` — его нет в репозитории |

### Почему глубина камеры — приемлемое знание о раскладке

Единственная константа с предположением о структуре — `CAMERA_DEPTH = 3`. Это не новый риск: `RecordingFileHelper.parse` уже жёстко зашивает ту же раскладку через `path.parent.parent.parent` и `require(path.nameCount >= 6)`. Если Frigate сменит структуру каталогов, парсер отвалится на первом же файле независимо от того, что написано в обходе.

Дополнительно: проверка даты применяется на **каждом** уровне, а не только на глубине 1 — она чисто строковая и стоит ноль, поэтому **отсев** по дате от глубины не зависит. Но **спуск** зависит, а вместе с ним — регистрация каталогов камер, единственного источника `ENTRY_CREATE` на новые `.mp4`: если камеры лежат не на глубине 3 (например, `FRIGATE_RECORDS_FOLDER` указывает на уровень выше корня записей), обход остановится раньше, и приём записей молча прекратится — сегодняшний `Files.walk` в той же ситуации лишь тратил время. Поэтому в обход встроен дешёвый детектор: дата, извлечённая из пути, у которого первый сегмент относительно корня — не дата (`isDateAtUnexpectedDepth`), означает сдвинутый корень и логируется одним WARN с упоминанием `FRIGATE_RECORDS_FOLDER`. Молчаливая деградация становится громкой.

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

/**
 * Ищет date-like сегмент ОТ ЛИСТА К КОРНЮ и берёт первый найденный. Унаследованный контракт:
 * date-like имя камеры или каталога ниже даты даст неверный ответ — менять только вместе с
 * RecordingFileHelper.parse. Date-like ID камер объявлены неподдерживаемой конфигурацией.
 */
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
 *
 * Симлинки: walkFileTree без FOLLOW_LINKS не спускается в симлинк-каталоги (они приходят
 * в visitFile), поэтому правила глубины к ним не применяются вовсе.
 */
internal fun depthFromRoot(path: Path, rootFolder: Path): Int {
    if (!path.startsWith(rootFolder)) return -1
    val rel = rootFolder.relativize(path)
    return if (rel.toString().isEmpty()) 0 else rel.nameCount
}

/**
 * Детектор сдвинутого корня: дата извлекается, но первый сегмент относительно корня — не дата.
 * В раскладке Frigate дата ВСЕГДА первый сегмент под корнем; нарушение означает, что
 * FRIGATE_RECORDS_FOLDER указывает на уровень выше корня записей. Реализация через
 * extractDateFromPath, а не через DATE_PATTERN напрямую — функция обязана работать и до
 * переезда DATE_PATTERN (он private в файле-источнике).
 */
internal fun isDateAtUnexpectedDepth(path: Path, rootFolder: Path): Boolean {
    if (!path.startsWith(rootFolder) || path == rootFolder) return false
    val rel = rootFolder.relativize(path)
    val firstSegmentIsDate = extractDateFromPath(rootFolder.resolve(rel.getName(0)), rootFolder) != null
    return !firstSegmentIsDate && extractDateFromPath(path, rootFolder) != null
}
```

## Компонент 2: `registerAllDirs`

`WatchRecordsLoop.kt`. Возвращаемый тип меняется с `Int` на `RegistrationResult`. Это безопасно: **оба call-site игнорируют возврат** — `WatchRecordsTask.kt:244` и `WatchRecordsLoop.kt:83`.

```kotlin
data class RegistrationResult(
    /** Сколько каталогов реально добавлено в registeredDirs (новые вставки; при повторном обходе уже зарегистрированных — 0). */
    val registered: Int,
    /** Сколько поддеревьев отсечено по дате. Только по дате: остановки на глубине камеры сюда не входят. */
    val prunedSubtrees: Int,
    /** Сколько записей файловой системы посещено всего: каталоги + файлы + сбойные записи. */
    val visitedEntries: Int,
    /**
     * Сколько записей пришло в visitFile — файлы и симлинки ВЫШЕ уровня камеры.
     * Главный инвариант: 0 на дереве Frigate. Ниже камеры обход не спускается,
     * поэтому ни один .mp4 не перечисляется — и это видно прямо в строке лога, без арифметики.
     */
    val visitedFiles: Int,
    /** Сколько записей не удалось прочитать (visitFileFailed). Ненулевое значение — частичная слепота, видимая в логе. */
    val failed: Int,
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
    // применял бы к разным веткам разные границы. Проверка окна намеренно продублирована
    // с предпроверкой в runIteration — registerAllDirs обязан быть корректным при любом start.
    val cutoff = watchCutoff(recordsWatcherProperties.watchPeriod, clock)
    var registered = 0
    var pruned = 0
    var visited = 0
    var visitedFiles = 0
    var failed = 0
    var misplacedDateReported = false
    val startedAt = System.nanoTime()

    Files.walkFileTree(
        start,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                visited++
                // Глубже камеры не следим ни при каком пути вызова: runIteration может передать
                // start глубже CAMERA_DEPTH — раньше такой каталог получал watch key, который
                // стартовый обход после пересоздания WatchService никогда бы не восстановил.
                if (depthFromRoot(dir, root) > CAMERA_DEPTH) return FileVisitResult.SKIP_SUBTREE
                if (isPrunableDate(dir, root, cutoff)) {
                    pruned++
                    return FileVisitResult.SKIP_SUBTREE
                }
                if (!misplacedDateReported && isDateAtUnexpectedDepth(dir, root)) {
                    misplacedDateReported = true
                    logger.warn { "Registration: date directory at unexpected depth: $dir — check FRIGATE_RECORDS_FOLDER" }
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
                // start-файл или start-симлинк (обход без FOLLOW_LINKS классифицирует симлинк
                // как файл): пустая «успешная» регистрация оставила бы залипший DOWN без ретрая.
                if (file == start) throw NotDirectoryException(start.toString())
                visited++
                visitedFiles++
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                // Сбой САМОГО start (несуществующий/нечитаемый корень) фатален и retryable —
                // как у Files.walk сегодня: ensureWatchService уходит в backoff и повторяет.
                if (file == start) throw exc
                visited++
                failed++
                if (failed <= FAILURE_LOG_LIMIT) {
                    logger.warn { "Registration: skipping unreadable entry $file (${exc.message})" }
                    logger.debug(exc) { "Registration: failure details for $file" }
                }
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) {
                    logger.warn { "Registration: error after visiting $dir (${exc.message})" }
                    logger.debug(exc) { "Registration: failure details for $dir" }
                }
                return FileVisitResult.CONTINUE
            }
        },
    )

    if (failed > FAILURE_LOG_LIMIT) {
        logger.warn { "Registration: ${failed - FAILURE_LOG_LIMIT} more unreadable entries suppressed" }
    }
    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
    logger.info {
        "Registered $registered dirs, pruned $pruned date subtrees, " +
            "visited $visited entries ($visitedFiles files, $failed failed) in ${elapsedMs}ms"
    }
    return RegistrationResult(registered, pruned, visited, visitedFiles, failed)
}
```

Константа рядом с `CAMERA_DEPTH` в `WatchRecordsLoop.kt`: `private const val FAILURE_LOG_LIMIT = 100` — при массовом сбое (частично отвалившийся mount) первые 100 записей логируются поимённо, остальные сворачиваются в одну итоговую строку, чтобы не устроить DoS собственному логу.

### Стоимость на проде после правки

| | до | после |
|---|---|---|
| посещено записей ФС | ~3.2 млн | ~320 |
| `stat` на элемент | 2 | 1 |
| посещено `.mp4` | ~3.2 млн | **0** |
| время | 549 с | миллисекунды |

Разбивка «после»: 1 (корень) + 124 (записи дат) + 2×24 (часы двух свежих дат) + 48×3 (камеры) ≈ 320.

**Остаточная стоимость, принятая осознанно.** Согласно Факту 3, `SKIP_SUBTREE` не отменяет `opendir` самого отсекаемого каталога. Значит 122 старые даты и 144 каталога камер всё же открываются и сразу закрываются — без `readdir`. Абсолютная величина не измерена; она растёт линейно по `record.retain.days`, а не по числу файлов, и остаётся на три порядка меньше исходных 549 с. Избавиться можно двумя способами, оба отклонены: (а) ручной `newDirectoryStream` на верхнем уровне — ровно та связанность с раскладкой, которая отклонена выше; (б) `maxDepth = CAMERA_DEPTH` в `walkFileTree` — тогда каталоги камер приходили бы в `visitFile` неоткрытыми (замерено: 9 `preVisitDirectory` + 8 `visitFile` вместо 17 + 0 на канонической фикстуре), но регистрация размазалась бы на два колбэка, для вызова из `runIteration` понадобилась бы арифметика `maxDepth = CAMERA_DEPTH - depthFromRoot(start)`, и исчез бы наблюдаемый инвариант `visitedFiles == 0` — камеры считались бы «файлами». Условие пересмотра: рост `record.retain.days` на порядок.

### Изменения поведения

1. **Политика ошибок обхода разделяется: корень строгий, поддеревья мягкие и видимые.** Сбой **самого `start`** остаётся фатальным и retryable, как сегодня: несуществующий или нечитаемый `start` пробрасывается из `visitFileFailed`, а `start`-файл или `start`-симлинк (обход без `FOLLOW_LINKS` классифицирует симлинк как файл) даёт `NotDirectoryException` из `visitFile` — `ensureWatchService` уходит в backoff и повторяет; залипшего DOWN с пустым `registeredDirs` не бывает. Сбой чтения **подкаталога** логируется на WARN (сообщение без stack trace; полный trace на DEBUG; после `FAILURE_LOG_LIMIT = 100` записей — одна суммарная строка) и пропускается — это **принятая наблюдаемая деградация**, а не «строго лучше»: непрочитанное поддерево не получает watch key до следующей полной перерегистрации, зато один необратимо битый каталог больше не загоняет супервизор в вечный retry-loop, в котором приложение не следит ни за чем. Счётчик `failed` виден в строке лога рядом с `visitedFiles`. **Граница гарантии:** изолируется только отказ *чтения* каталога (атрибуты, открытие стрима). Исключение из `dir.register(...)` — inotify `ENOSPC`/`EACCES` — бросается из тела `preVisitDirectory`, и `walkFileTree` пробрасывает его наружу, роняя обход целиком, как и сегодня: `walkFileTree` не маршрутизирует исключения самого посетителя в `visitFileFailed` (pre-existing контракт). Отдельная оговорка для вызова из `runIteration`: частичный отказ там оставляет полузарегистрированное поддерево без повтора — событие `ENTRY_CREATE` уже потреблено, механизма отката нет (тоже pre-existing).
2. **`postVisitDirectory` с ошибкой больше не роняет обход.** Дефолтный `SimpleFileVisitor` пробрасывает ненулевой `exc`; override логирует WARN и продолжает — то же семейство изменений, что и п. 1.
3. **Строка лога меняет форму и смысл счётчика.** Было `Registered 167 directories, skipped 11638 old directories`, стало `Registered 167 dirs, pruned 122 date subtrees, visited 320 entries (0 files) in 41ms`. Считаются отсечённые **поддеревья**, а не каталоги поштучно, поэтому число падает с 11638 до 122 — формулировка изменена, чтобы это не читалось как регрессия; пояснение для оператора фиксируется в `pipeline.md`. Перед деплоем убедиться, что на старый формат строки не завязан внешний лог-парсинг.
4. **Порядок регистрации не меняется.** Корень (при `start == folder`) регистрируется первым колбэком — как и раньше: `Files.walk` отдаёт `start` первым элементом стрима. Немедленная непустота `registeredDirs` — свойство call-site'а (`ensureWatchService` всегда передаёт корень), а не новой реализации.
5. **Симлинки перестают регистрироваться.** `Files.walk` не спускался в симлинк-каталоги, но `Files.isDirectory` в фильтре следовал за ссылкой — сегодня каталог-симлинк получает watch key. `walkFileTree` без `FOLLOW_LINKS` отдаёт симлинк в `visitFile` — регистрации не будет; в `scan()` симлинк-файлы (`attrs.isRegularFile == false` при NOFOLLOW-чтении) не индексируются. Симлинки в дереве записей объявляются неподдерживаемой конфигурацией; если перенос архива на SSD (см. «Вне работы») будет делаться симлинками, потребуется отдельная ревизия с `FOLLOW_LINKS`.
6. **Каталоги глубже камеры не получают watch key и из `runIteration`.** Раньше событие о вложенном каталоге внутри камеры регистрировало его (`registerAllDirs(start = вложенный)`), хотя стартовый обход такой ключ после пересоздания WatchService никогда бы не восстановил. Теперь guard по глубине действует в обоих путях; файлы в таких каталогах всё равно не парсятся `RecordingFileHelper`.

`cleanupExpiredDirs` не меняется — он итерирует map, а не файловую систему.

## Компонент 3: `FirstTimeScanTask`

Тот же `Files.walk` по всем 3.2 млн файлов плюс `createRecording` на каждый. На проде выключен (`DISABLE_FIRST_SCAN=true`), но код остаётся миной. Три правки в самом скане — плюс дефолт `DISABLE_FIRST_SCAN` переключается на `true` (Компонент 4): скан становится opt-in бэкфиллом, потому что починенный (наконец доживающий до конца) и мгновенный скан при включённом дефолте впервые исполнил бы никем не наблюдавшийся ~52k-бэкфилл на свежем деплое.

### 3.1 Ограничение окна

`Files.walk` заменяется на `Files.walkFileTree` с тем же `isPrunableDate` в `preVisitDirectory`, но **без** ограничения `CAMERA_DEPTH` — файлы здесь и есть цель, поэтому спуск идёт до конца. `visitFile` собирает пары «путь + `creationTime`» (`ScanFile`): атрибуты уже получены самим обходом, и второй `stat` на каждый файл — ровно дефект № 2 из разбора корневой причины — не делается. Берутся только обычные файлы с расширением `.mp4`; посторонние файлы (thumbnail, tmp) пропускаются молча, не накручивая `failed`. `visitFileFailed` логирует на WARN и возвращает `CONTINUE`, как в `registerAllDirs`.

Граница — `watchCutoff(recordsWatcherProperties.firstScanPeriod, clock)`, вычисляется один раз на обход. Отсев идёт по датам, поэтому окно всегда целые дни: `P0D` = только сегодня, `P1D` = сегодня и вчера.

Файлы собираются в `List<ScanFile>` за один обход, затем обрабатываются потоком. При дефолтном `P1D`, трёх камерах и десятисекундных сегментах это ~52 000 записей (~10 МБ) — приемлемо. Число собранных файлов логируется до начала обработки, чтобы масштаб был виден в логе.

Масштаб стоит понимать: каждый проиндексированный файл попадает в `recordings`, а оттуда в YOLO-конвейер. `FIRST_SCAN_PERIOD` — как раз ручка, чтобы при включении первого скана получить осмысленный объём.

### 3.2 Изоляция ошибок

Текущий `.catch {}` навешен на цепочку Flow и **завершает поток**, а не пропускает элемент. Первый же сбойный файл молча убивает весь скан и печатает «Finish first time scan task». Реальные причины сбоя: нераспознаваемое имя (`parse` бросает), файл, удалённый Frigate за время обхода, и гоночный `IllegalStateException` из `RecordingEntityHelper` после трёх попыток. Дубль `file_path` скан НЕ убивает: `RecordingEntityServiceImpl.createRecording` (строки 34-37) сначала делает `findByFilePath` и возвращает существующий id с WARN «Recording already exists». Следствие: повторный скан по уже проиндексированному окну доживёт до конца и напечатает ~52 000 таких WARN — известная цена; `indexed` при этом означает «успешно create-or-find», а не «новых строк в БД».

Обработка переезжает внутрь `flatMapMerge`: сбой одного файла логируется на WARN, увеличивает счётчик `failed` и не эмитит ничего; скан продолжается. `CancellationException` пробрасывается — как в `WatchRecordsLoop.runIteration`. Внешний `.catch` остаётся последней сеткой — **с явным re-throw `CancellationException`**: `Flow.catch` ловит и его, и без проброса заявленная отменяемость `scan()` была бы фикцией.

Логирование каждого id на INFO заменяется на счётчики и одну итоговую строку — 52 000 строк лога бесполезны.

### 3.3 Тестируемость

Логика выносится в `internal suspend fun scan(): ScanResult`, `run()` остаётся тонкой обёрткой, которая её запускает в детач-скоупе. Тот же раскол, что уже применён в паре `WatchRecordsLoop` (логика) / `WatchRecordsTask` (запуск и супервизия).

```kotlin
internal data class ScanResult(
    /** Успешно обработанные файлы: create-or-find, НЕ «новых строк в БД» — дубль возвращает существующий id. */
    val indexed: Int,
    val failed: Int,
    val prunedSubtrees: Int,
    /** Все посещённые записи ФС: каталоги + файлы (в отличие от registerAllDirs файлы здесь и есть цель). */
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
    // true: скан — opt-in бэкфилл. Дефолт приведён к единственной реальной эксплуатации;
    // починенный (наконец доживающий до конца) скан при включённом дефолте впервые исполнил бы
    // никем не наблюдавшийся ~52k-бэкфилл на свежем деплое, одновременно со стартом watcher'а.
    val disableFirstScan: Boolean = true,
    @field:NotNull val folder: Path,
    @field:NotNull val watchPeriod: Duration = Duration.ofDays(1),
    @field:NotNull val cleanupInterval: Duration = Duration.ofHours(1),
    // Последним параметром, а не рядом с watchPeriod: вставка в середину сломала бы будущие
    // позиционные вызовы. Kotlin-дефолт может ссылаться на любой более ранний параметр.
    @field:NotNull val firstScanPeriod: Duration = watchPeriod,
) {
    init {
        require(watchPeriod.toDays() >= 1) { "watchPeriod must be at least 1 day, got: $watchPeriod" }
        require(!firstScanPeriod.isNegative) { "firstScanPeriod must not be negative, got: $firstScanPeriod" }
        // Целые сутки: toDays() молча усекает, и PT12H превратился бы в «только сегодня».
        require(firstScanPeriod == Duration.ofDays(firstScanPeriod.toDays())) {
            "firstScanPeriod must be whole days (P0D, P1D, ...), got: $firstScanPeriod"
        }
        // строка про cleanupInterval — без изменений
    }
}
```

Нижняя граница у `firstScanPeriod` — `P0D`, а не `P1D` как у `watchPeriod`: иначе «просканировать только сегодня» невыразимо.

Гранулярность обоих окон — целые сутки **в UTC**: Frigate именует каталоги дат по UTC, и `watchCutoff` считает «сегодня» тоже по UTC — это документированное предположение. Для `FIRST_SCAN_PERIOD=P0D` оно существенно: на хосте в зоне с отрицательным смещением «сегодня по UTC» около полуночи не совпадает с локальным днём. Прод-арифметика из раздела «Измерения» (17 часов к 16:18) с UTC согласуется.

### `modules/core/src/main/resources/application.yaml`

```yaml
  records-watcher:
    # Disabled by default: the startup scan is an opt-in backfill (first install, index recovery).
    # A fixed scan that actually finishes would otherwise run a never-observed ~52k backfill on a
    # fresh deployment with default settings.
    disable-first-scan: ${DISABLE_FIRST_SCAN:true}
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

Осознанная цена: `always` отдаёт эти детали — структуру каталогов (`registeredDirs`), текст последней ошибки (`lastFailure`, до 500 символов, может содержать пути и ошибки БД), счётчики и таймстемпы — любому, кто дотянется до опубликованного `HOST_PORT`; «однодеплойный» не означает «порт недоступен снаружи». Риск принят для домашнего деплоя за закрытым периметром; отключается ручкой `HEALTH_SHOW_DETAILS=never` (или `when-authorized`), перечень раскрываемого фиксируется в комментарии `.env.example`.

`modules/core/src/test/resources/application.yaml` не трогаем: у `firstScanPeriod` есть Kotlin-дефолт, биндинг отработает без строки.

### `docker/deploy/.env.example`

Добавляется секция watcher'а. Соблюдается принятая в файле конвенция: комментарий **над** строкой, не inline — парсеры `env_file` не считают `#` inline-комментарием и запекли бы текст в значение.

```
# --- Records watcher ---
# The one-off startup scan that indexes recordings already on disk is DISABLED by default.
# Set to false to run the backfill once (first install, index recovery) — read FIRST_SCAN_PERIOD first.
# DISABLE_FIRST_SCAN=true
# How far back the watcher registers directories. Whole days in UTC, at least P1D.
# WATCH_PERIOD=P1D
# How far back the startup scan indexes files. Defaults to WATCH_PERIOD — raising WATCH_PERIOD
# silently widens the startup backfill in the same proportion. Whole days in UTC; P0D = today only
# (WATCH_PERIOD itself must stay >= P1D). Every indexed file becomes a recording and enters the
# detection pipeline, so keep it small.
# FIRST_SCAN_PERIOD=P1D
# How often expired watch keys are cleaned up (ISO-8601 duration).
# WATCH_CLEANUP_INTERVAL=PT1H
# Expose /actuator/health details: always | never | when-authorized. `always` shows reason,
# registered directory paths, timestamps and the text of the last failure to anyone who can
# reach the published port. Spring's relaxed binding also honours
# MANAGEMENT_ENDPOINT_HEALTH_SHOWDETAILS — set only one of the two.
# HEALTH_SHOW_DETAILS=always
```

`DISABLE_FIRST_SCAN`, `WATCH_PERIOD` и `WATCH_CLEANUP_INTERVAL` документируются заодно — сейчас их в `.env.example` нет вообще, и после правки секция описывает весь операторский контракт watcher'а.

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

Сюда переезжают существующие тесты чистых функций из `WatchRecordsLoopTest.kt` (`extractDateFromPath` ×6, `isWithinWatchPeriod` ×6 — всего 12) — они тестируют файл, который переехал. Добавляются:

1. `isPrunableDate` возвращает `false` для пути без даты (корень) — fail-closed
2. `isPrunableDate` возвращает `false` для даты в окне
3. `isPrunableDate` возвращает `true` для даты до cutoff
4. `depthFromRoot(root, root) == 0` — регрессия на пустой путь с `nameCount == 1`
5. `depthFromRoot` даёт 1 / 2 / 3 для даты / часа / камеры
6. `depthFromRoot` возвращает `-1` для пути вне корня
7. `isDateAtUnexpectedDepth`: `false` для корня, даты на глубине 1 и её подкаталогов; `true` для даты, чей первый сегмент относительно корня — не дата (корень задан уровнем выше); `false` для пути вне корня
8. Тождество `isPrunableDate` ↔ `!isWithinWatchPeriod` для датированных путей — на широком наборе дат вокруг cutoff (параметризованно), чтобы будущая замена на `!`-форму гарантированно ломала тест

### `WatchRecordsLoopTest.kt` (дополняется)

Существующие тесты `runIteration` остаются. Дерево строится через `Files.createTempDirectory` с очисткой в `finally` — как в текущих тестах файла. Добавляется группа на `registerAllDirs`, который сегодня не вызывает ни один тест:

1. **Регистрируются ровно нужные каталоги** — сверка `registeredDirs.keys` точным множеством: корень, 2 даты в окне, их 4 часа, их 8 камер
2. **Ни один файл не посещён** — `visitedEntries == 17` и `visitedFiles == 0`; главный инвариант, не зависящий от железа
3. **Поддеревья вне окна отсечены** — `prunedSubtrees == 2`, ни один путь из-под `2026-05-21` и `2025-01-01` не зарегистрирован
4. **Ниже камеры не спускаемся** — вложенный каталог, созданный внутри `cam1`, не зарегистрирован, `visitedEntries` остаётся 17
5. **Корень зарегистрирован, хотя даты у него нет** — guard на fail-open
6. **`start` ниже корня** (вызов как из `runIteration`): `registerAllDirs(root/2026-05-23)` → `registered == 7`, `prunedSubtrees == 0`, `visitedEntries == 7`, корень не зарегистрирован, файлы не посещены
7. **Идемпотентность** — повторный вызов даёт `registered == 0` при тех же `prunedSubtrees == 2`, `visitedEntries == 17`, `visitedFiles == 0`
8. **Нечитаемый подкаталог не роняет обход** — `chmod 000` на `2026-05-23/00`, обход завершается, `2026-05-23/01` и его камеры зарегистрированы; `failed == 1`, `visitedEntries == 15` (12 зарегистрированных + 2 отсечённые + 1 сбойная). Тест обёрнут `Assumptions.assumeTrue`: под root'ом `chmod 000` не ограничивает доступ, и проверка была бы ложноположительной. Гард — попытка `Files.newDirectoryStream` на подготовленном каталоге. Комментарий в тесте фиксирует: в CI под root assumption всегда false и тест скипается — путь `visitFileFailed` регрессионно защищён только на машинах с обычным UID
9. **Посторонний файл на уровне даты посещается, но не регистрируется** — stray-файл в каталоге даты: `visitedFiles == 1`, `visitedEntries == 18`, `registered == 15`, файл не в map
10. **`start` глубже камеры не регистрируется** (вызов как из `runIteration` для вложенного каталога): `registered == 0`, `visitedEntries == 1`, map пуста
11. **`start` вне корня деградирует к полному обходу** — `depthFromRoot == -1`, правила глубины неактивны: все каталоги регистрируются, файлы посещаются (`visitedFiles > 0`)
12. **Несуществующий `start` бросает** — `NoSuchFileException` пробрасывается, map пуста; ровно тот сценарий («опечатка в `FRIGATE_RECORDS_FOLDER`, NFS не смонтирован»), на котором супервизор обязан уйти в backoff-retry
13. **`start`-симлинк бросает** — `Files.createSymbolicLink` на каталог даты, `registerAllDirs(link)` → `NotDirectoryException`, map пуста; пустая «успешная» регистрация исключена

### `FirstTimeScanTaskTest.kt` (новый)

Тестируется `scan()` напрямую. `RecordingFileHelper` и `RecordingEntityHelper` мокаются, как в существующих тестах.

1. **Индексируются только файлы в окне** — на канонической фикстуре `createRecording` вызван ровно 24 раза (2 даты × 2 часа × 2 камеры × 3 файла), и ни разу с путём под `2026-05-21` или `2025-01-01`; `visitedEntries == 41` (разбивка в приложении плана)
2. **Сбой одного файла не останавливает скан** — `parse` бросает на одном конкретном пути; остальные 23 проиндексированы, `ScanResult.failed == 1`, `indexed == 23`
3. **`P0D` индексирует только сегодняшнюю дату** — 12 файлов, `visitedEntries == 23`

### `RecordsWatcherPropertiesBindingTest.kt` (новый)

Строится по образцу существующего `ObjectTrackerPropertiesBindingTest` — он биндит продовый `src/main/resources/application.yaml` через `Binder` и синтетический `StandardEnvironment`. Это единственный способ проверить продовый yaml: тестовый classpath несёт собственный `application.yaml`, который его затеняет, поэтому все placeholder'ы продового файла иначе впервые вычисляются только на проде.

1. При пустом окружении `firstScanPeriod == watchPeriod == P1D`
2. `WATCH_PERIOD=P3D` через env → `firstScanPeriod == P3D`
3. `application.records-watcher.watch-period=P3D` как свойство (профильный yaml / CLI) → `firstScanPeriod == P3D` — случай, который отвергнутая форма с вложенной переменной провалила бы
4. `APPLICATION_RECORDSWATCHER_WATCHPERIOD=P3D` (relaxed-имя) → `firstScanPeriod == P3D`
5. Явный `FIRST_SCAN_PERIOD=P0D` перебивает дефолт
6. `management.endpoint.health.show-details` из продового yaml резолвится в `always` при пустом окружении и в `never` при `HEALTH_SHOW_DETAILS=never`
7. При пустом окружении `disableFirstScan == true` — скан выключен по умолчанию
8. Субдневный `firstScanPeriod` (`PT12H`) отвергается конструктором с сообщением про целые сутки — вместо молчаливого усечения

### Проверка перед сборкой

`WatchRecordsTaskTest.kt` мокает `loop.registerAllDirs(...)` на строках 436 и 467 — обе через `throws`, возвращаемое значение не стабится, смена типа их не задевает. При реализации убедиться, что нигде в тестах нет `every { loop.registerAllDirs(...) } returns <Int>`.

## Критерии готовности

- `visitedEntries == 17`, `visitedFiles == 0`, `failed == 0` на канонической фикстуре — закреплено тестами
- ни один `.mp4` не посещается при регистрации — наблюдаемо напрямую: `(0 files, 0 failed)` в строке лога
- несуществующий/нечитаемый корень и корень-симлинк бросают → супервизор повторяет с backoff — закреплено тестами
- `depthFromRoot(root, root) == 0` — закреплено тестом
- на проде зафиксировать фактические числа из строки лога первого старта; ожидание — `visited` в сотнях, `0 files`, время на порядки меньше 549 с. Точная цифра — не критерий: 549 с замерены на обычном рестарте контейнера, состояние dentry-кэша в момент замера не фиксировалось, поэтому «миллисекунды» — прогноз
- `/actuator/health` отдаёт `reason` и `registeredDirs` вместо голого статуса
- `./gradlew build` зелёный, `ktlintCheck` чистый

## Файлы

| Файл | Действие |
|---|---|
| `core/task/RecordingsTree.kt` | новый; хелперы дат, prune-предикат, глубина, детектор сдвинутого корня |
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
- Отменяемость скоупа `FirstTimeScanTask` при shutdown и избыточный `@Async` — TODO фиксируется в `pipeline.md`; `scan()` вынесен и тестируется напрямую именно ради изоляции от этой проблемы
- Асимметрия вычисления cutoff: `cleanupExpiredDirs` по-прежнему зовёт `isWithinWatchPeriod` на каждый элемент map — наблюдаемо только при пересечении полуночи; заодно: `registerAllDirs` идемпотентна по эффекту, но не по стоимости (каждый вызов заново открывает отсекаемые каталоги дат)
- `putIfAbsent` вместо `computeIfAbsent` (I/O под бин-локом `ConcurrentHashMap`) — pre-existing, без измеренной проблемы
- Поддержка симлинков в дереве записей и date-like ID камер — объявлены неподдерживаемыми конфигурациями
- Агрегация и кулдаун Telegram-уведомлений
- Нестабильность detect-сервера `vps`
- Сокращение `record.retain.days` и длины сегмента в самом Frigate, перенос архива на SSD

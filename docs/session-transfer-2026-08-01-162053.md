## CONTEXT TRANSFER

This is a continuation of a previous session. Read this context carefully before proceeding.

## CRITICAL: DO NOT START WORKING

After loading all context below, you MUST:
1. Read all mentioned files
2. Confirm you understood the context (brief summary)
3. **WAIT for explicit user instructions** before taking ANY action

**DO NOT:**
- Start making changes
- Run commands (except reading files)
- Assume what to do next

**The user will tell you exactly what to do.**

## PROJECT

- **Directory:** `/home/zinin/github/frigate-analyzer`
- **Project:** Frigate Analyzer — анализ записей с камер Frigate через YOLO-детекцию, уведомления в Telegram. Kotlin 2.4.10, Spring Boot 4.1.0, WebFlux, R2DBC/PostgreSQL, Java 25.
- **Важно:** это **прод**. Развёрнут через `docker/deploy/deploy-up.sh` (docker compose pull + up), образ `avzinin/frigate-analyzer:latest`.

## ORIGINAL TASK

Пользователь сообщил: после 23:00 походил под камерами — уведомление в Telegram не пришло, хотя настройки владельца предполагают уведомления с 23:00 до 08:00. Требовалось найти корневую причину (использовалась методика `superpowers:systematic-debugging`) и починить.

## CURRENT STATE

**Completed:**
- [x] Корневая причина найдена и доказана данными (не гипотеза — количественное подтверждение)
- [x] Диагноз воспроизведён вживую: пользователь дважды прошёл под камерами (23:16 и 23:50), оба прохода подавлены
- [x] Реализован фикс: детект «возвращения объекта после отсутствия»
- [x] 9 новых юнит-тестов, все зелёные
- [x] Сборка зелёная (в контейнере с JDK 25), ktlint чистый
- [x] Ветка `fix/tracker-reappearance-notifications` от `master`, коммит `d46d7bb`, запушена
- [x] PR создан: **https://github.com/zinin/frigate-analyzer/pull/39**

**In progress / Remaining:**
- [ ] Ревью кода (пользователь планировал запустить ревью на другом компьютере)
- [ ] Дождаться зелёного CI — локально не прогнались 35 интеграционных тестов
- [ ] После мержа: добавить `NOTIFICATIONS_TRACK_REAPPEAR_GAP=PT1H` в `docker/deploy/.env` (файл НЕ в git, лежит на прод-хосте)
- [ ] Выкат: образ публикуется только по тегу `v*` (последний — `v0.9.0` на HEAD master). Нужен тег `v0.9.1` → CI соберёт и опубликует → `deploy-up.sh`
- [ ] Проверить поведение после выката вживую (окно уведомлений 23:00–08:00)

**Status:** `BUILD SUCCESSFUL`, ktlint чистый. `ObjectTrackerServiceImplTest` — 17 тестов / 0 падений, `NotificationDecisionServiceImplTest` — 19 тестов / 0 падений. Изменения закоммичены и запушены, PR открыт. Рабочее дерево чистое, кроме предсуществующей правки пользователя в `docker/deploy/docker-compose.yml` (раскомментирован маунт `log4j2.yaml`) — она НЕ моя и НЕ закоммичена.

## KEY FILES

Read these files first to understand the context:

- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/ObjectTrackerServiceImpl.kt` — ядро правки: расчёт `absence` и признак возвращения (строки ~96–150)
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/config/ObjectTrackerProperties.kt` — новый параметр `reappearGap` + инварианты валидации
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationDecisionServiceImpl.kt` — новая ветка решения `REAPPEARED`
- `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/DetectionDelta.kt` — поля `reappearedTracksCount` / `reappearedClasses`
- `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/NotificationDecision.kt` — новая причина `REAPPEARED`
- `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/ObjectTrackerServiceImplTest.kt` — 6 новых тестов
- `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationDecisionServiceImplTest.kt` — 3 новых теста
- `.claude/rules/configuration.md` — раздел про подбор `REAPPEAR_GAP`
- `modules/core/src/main/resources/application.yaml` — строка ~62, вложенный плейсхолдер по умолчанию

## SESSION KNOWLEDGE

### Корневая причина (доказана, не предполагается)

`NOTIFICATIONS_TRACK_TTL=PT12H` в `docker/deploy/.env` (дефолт проекта — 30 минут).

Механизм: TTL — это **скользящее окно по `last_seen_at`**, а `ObjectTrackRepository.updateOnMatch` продлевает его через `GREATEST` при каждом совпадении. Ограничения на абсолютный возраст трека нет. Треки не истекают, а накапливаются — `findActive` возвращал **126–247 активных треков на камеру** (в логах видно как `stale=247`). Кадр насыщается: любая новая детекция перекрывается с каким-нибудь треком выше `iouThreshold=0.3`, `newTracksCount` всегда 0 → `ALL_REPEATED` → уведомление подавлено.

**Расписание работало корректно** — это была ложная зацепка в начале. В логах ровно в 20:00:46 UTC (= 23:00:46 MSK) решения переключились с `out_of_schedule` на обычную оценку. Настройки в БД: окно `23:00-08:00`, зона `Europe/Moscow`, `enabled=true`, глобальный флаг `true`, у обоих пользователей `notifications_recording_enabled=t`. Контейнер живёт в UTC — логи в UTC, локальное время MSK (+3).

### Контрфактическое доказательство

Четыре трека cam3, поглотившие проход в 23:16, реально наблюдались последний раз задолго до него (IoU считался прямо в SQL):

| трек (bbox) | создан | реально виден до прохода | разрыв |
|---|---|---|---|
| `1276,250 1340,452` | 10:09 | 14:49 | 8.5 ч |
| `980,229 1058,456` | 14:27 | 14:32 | 8.7 ч |
| `1840,386 1967,722` | 14:34 | 14:55 | 8.3 ч |
| `1991,311 2121,672` | 10:09 | 20:12 | 3.1 ч |

Все разрывы укладываются в 12-часовое окно, но все превышают дефолтные 30 минут.

### Почему нельзя было просто укоротить TTL

Пользователь явно отказался: «если я уменьшу ttl мне начинается сыпаться спам от статичных объектов. надо починить правильно». Длинный TTL — это то, что глушит статику. Профиль прошлой ночи (00:00–08:00): cam3 `bicycle` — 4034 детекции в 2220 записях (стабильный ложноположительный детект), cam2 `car` — 2735 детекций в 1479 записях (припаркованная машина), `person` — **ноль**.

### Ключевое наблюдение, на котором построен фикс

Записи идут **непрерывно**, а не по движению: 2873–2876 записей на камеру за 8 часов, средний интервал 10 с, **максимальный разрыв 48 с**, ни одного разрыва больше 30 минут. Поэтому статика физически не может «пропасть» надолго, и разделение по отсутствию надёжно:

| | худший разрыв |
|---|---|
| статика — припаркованная машина cam2 | 8.4 мин |
| статика — ложный `bicycle` cam3 | 32.7 мин (один раз за 8 ч) |
| **порог `PT1H`** | |
| реальные проходы человека | 3.1–8.7 ч |

Порог `PT1H` выбран пользователем из вариантов PT1H / PT2H / PT30M.

### Решения по дизайну и их обоснование

- **Обратная совместимость:** `reappearGap` по умолчанию равен `ttl` — это no-op, так как `ttl` также ограничивает глубину `findActive`, и ни один возвращённый трек не может достичь такого разрыва. В `application.yaml` сделано вложенным плейсхолдером: `${NOTIFICATIONS_TRACK_REAPPEAR_GAP:${NOTIFICATIONS_TRACK_TTL:PT30M}}`.
- **`reappearedTracksCount` — намеренно подмножество `matchedTracksCount`.** Если бы возвращения вычитались из `matched`, запись только с возвращениями дала бы `new==matched==stale==0` и была бы ошибочно классифицирована как `NO_VALID_DETECTIONS`.
- **Возвращение переиспользует существующую строку трека** (`updateOnMatch`), новая не создаётся — таблица не растёт.
- **Записи не по порядку:** `absence` считается как `Duration.between(match.lastSeenAt, recordingTimestamp)` и для более старых записей отрицателен, поэтому возвращением никогда не считается. Есть тест.
- **Приоритет причин:** `NEW_OBJECTS` → `REAPPEARED` → `ALL_REPEATED`. Глобальный флаг и расписание гейтят раньше, как и было. Есть тест на то, что расписание по-прежнему гейтит возвращение.

### Что было проверено и оказалось НЕ проблемой

- Расписание, зона, глобальный флаг, флаги пользователей — всё корректно
- Задача очистки `ObjectTracksCleanupTask` работает штатно (retention настроен на `PT48H` через `NOTIFICATIONS_TRACK_CLEANUP_RETENTION`); сначала показалось багом, но это осознанная настройка
- Путь доставки в Telegram исправен — уведомления приходили 30–31 июля (5 штук, все по редким классам car/cat/truck, ни одного по `person`)
- Определять движение по кадрам внутри записи **нельзя**: кадров всего 2 на запись, и bbox между ними практически не меняется даже у идущего человека

### Гочи окружения

- **JDK 25 локально нет** — только zulu17 и zulu21. Проект требует 25 (`build.gradle.kts:84`), `settings.gradle.kts` не подключает foojay-resolver, автоскачивание не работает. Сборка делалась в контейнере:
  ```
  docker run --rm --user "$(id -u):$(id -g)" \
    -v /home/zinin/github/frigate-analyzer:/app \
    -v /home/zinin/.gradle:/gradle-home \
    -e GRADLE_USER_HOME=/gradle-home -e HOME=/tmp -w /app \
    azul/zulu-openjdk-alpine:25 \
    sh ./gradlew build --no-daemon --console=plain
  ```
- **Оставшийся gradle-демон блокирует кэш.** Если сборка падает с `Timeout waiting to lock journal cache` — остановить демон на хосте (`pkill -f GradleDaemon`) перед запуском контейнера.
- **35 интеграционных тестов `:frigate-analyzer-core:test` в контейнере не проходят** — `IntegrationTestBase` поднимает Postgres через Testcontainers (`ComposeContainer`), а docker-сокет внутрь не проброшен. Падают с `NoClassDefFoundError`/`ExceptionInInitializerError` на `Unsafe`. **Это окружение, не код** — правка не трогает репозитории и запросы. Для локальной проверки использовать `./gradlew build -x :frigate-analyzer-core:test`. Полный прогон — в CI.
- Первая версия тестов падала на предсуществующем инварианте `cleanupRetention >= ttl`: при `ttl=12h` нужно явно задавать `cleanupRetention`. В тестах заведено общее свойство `longTtlProps`.
- `ObjectTrackEntity.lastSeenAt` объявлен nullable (`Instant?`), хотя `findActive` фильтрует по нему — в коде стоит `?.let`.
- Доступ к БД для диагностики: `cd docker/deploy && set -a && . ./.env && set +a && PGPASSWORD="$DB_PASS" psql -h localhost -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME"`. Осторожно: рабочий каталог в сессии сбрасывался, из-за чего `. ./.env` не находился — использовать абсолютные пути.

### Известное следствие фикса (обсуждено с пользователем, принято)

Троттлинга уведомлений в системе нет. По фактическим отсутствиям регионов на проходе в 23:50 (0.1 / 1.0 / 3.5 / 1.0 / 3.4 / 5.2 / 7.0 / 1.0 ч) фикс дал бы **~4–6 уведомлений за один проход** через кадр — по одному на запись. При `PT2H` было бы 3. Это уже так работает и для новых объектов (кот 31.07 прислал 3 уведомления за 16 секунд). Возможный последующий шаг — агрегация/кулдаун уведомлений на камеру, но это отдельная задача, намеренно не смешанная с исправлением корневой причины.

## NEXT STEPS

Пользователь переключается на другой компьютер, чтобы посмотреть код и запустить ревью, потому что это прод. Дальше по плану:

1. Ревью PR #39
2. Зелёный CI (там прогонятся интеграционные тесты, недоступные локально)
3. Мерж в `master`
4. Добавить `NOTIFICATIONS_TRACK_REAPPEAR_GAP=PT1H` в `docker/deploy/.env` на прод-хосте
5. Тег `v0.9.1` → CI опубликует образ → `deploy-up.sh`
6. Проверить вживую в окне 23:00–08:00: в логах должно появиться `Decision: notify (reappeared)`

## INSTRUCTIONS

1. Read the key files listed above
2. Understand the context and current state
3. Provide a brief summary of what you understood
4. **STOP and WAIT** — do NOT proceed with any work
5. Ask: "What would you like me to work on?"

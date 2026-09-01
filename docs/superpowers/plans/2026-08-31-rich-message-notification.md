# Единое rich-сообщение для уведомления о детекции — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** одно событие детекции порождает одно сообщение в Telegram вместо двух.

**Architecture:** отправка переходит с `SendPhoto`/`sendMediaGroup`/`sendTextMessage` на единственный
`sendRichMessage` с HTML-телом, коллажем кадров и inline-клавиатурой. Метаданные едут в задаче структурой
и верстаются таблицей в новом `RichNotificationRenderer`. Правка после ответа модели становится одной
`editMessageText(rich_message)` вместо двух вызовов. Кадры грузит только первый получатель, остальные
ссылаются на его `file_id`.

**Tech Stack:** Kotlin 2.4.10, Spring Boot 4.1.0, ktgbotapi 36.1.0, Ktor 3.5.2, Coroutines 1.11.0,
JUnit 5 + MockK, Java 25.

**Spec:** `docs/superpowers/specs/2026-08-31-rich-message-notification-design.md`

## Global Constraints

- `ktgbotapi` = `36.1.0`, `ktor` = `3.5.2`, `coroutines` = `1.11.0` — версии держатся в lockstep, см. комментарии в `gradle/libs.versions.toml`.
- Конструкции Bot API 10.3 не отправляются: никаких `<tg-button>`, `<tg-document>`, `<blockquote expandable>` внутри rich-сообщения. Библиотека падает на разборе ответа с такой сущностью, а сообщение при этом доставляется.
- Лимиты rich-сообщения: 32768 символов текста, не более 50 медиа.
- Медиа в правке переобъявляются всегда. Без массива `media` Telegram отвечает `400 RICH_MESSAGE_PHOTO_INVALID`, даже если HTML ссылается на прежние `tg://photo?id=`.
- Идентификатор медиа формируется единственным местом — `RichNotificationRenderer.mediaId(index)`. HTML и массив `media` обязаны использовать одни и те же строки.
- TDD: сначала падающий тест, потом реализация.
- `./gradlew` напрямую не запускается — сборка через агент `claude-forge:build-runner` (правило проекта в `CLAUDE.md`). При ошибках ktlint: `./gradlew ktlintFormat`, затем повторная сборка.
- После создания или изменения любого файла — `git add <файл>`.

---

### Task 1: Апгрейд ktgbotapi до 36.1.0

✅ Done — see commit(s): `d3a1e35`

Итог: `ktgbotapi 36.1.0`, `ktor 3.5.2`, `coroutines 1.11.0` в lockstep; правок кода не потребовалось,
ломающие изменения 36.0.0 наш код не задели. BUILD SUCCESSFUL, 808 тестов.

---

### Task 2: Модель данных, состояние описания и рендерер HTML

✅ Done — see commit(s): `53b1be5`, `95a37ef`

Созданы `RecordingNotificationData`, `DescriptionState`, `RichNotificationRenderer`, восемь i18n-ключей
в обоих бандлах, двенадцать тестов рендерера.

**Interfaces (что Task 4 потребляет отсюда — сигнатуры фиксированы, не менять):**

- `RecordingNotificationData(camId, fileName, detectionsCount: Int, analyzedFramesCount: Int, analyzeTimeSeconds: Int, recordTimestamp: String, processTimestamp: String)` — пакет `telegram.service.model`
- `DescriptionState` — `Absent` / `Pending` / `Ready(result: DescriptionResult)` / `Failed`, пакет `telegram.service.model`
- `RichNotificationRenderer.render(data: RecordingNotificationData, description: DescriptionState, frameCount: Int, language: String): String`
- `RichNotificationRenderer.mediaId(index: Int): String` → `"f$index"`, и `RichNotificationRenderer.MAX_MEDIA = 50` — оба на companion
- Сверх исходного плана: `MAX_SHORT_LENGTH = 2_000` ограничивает текст модели `short`

**Что Task 4 обязан соблюсти:** рендерер клампит число кадров до `MAX_MEDIA` молча, поэтому отправитель
должен приложить ровно `min(frameCount, MAX_MEDIA)` элементов `media` — иначе `<img src="tg://photo?id=fN">`
и массив `media` разъедутся и Telegram отвергнет сообщение.

---

### Task 3: Держатель file_id, общий на запись

✅ Done — see commit(s): `d9ceb5d`

### Task 4: Перевод отправки и правки на rich-сообщение

✅ Done — see commit(s): `cec612b`, `4302a52`, `d1688c1`, `024a06b`

### Task 5: Удаление форматтера и мёртвых ключей

✅ Done — see commit(s): `4cbf63e`

### Task 6: Документация

✅ Done — see commit(s): `915f5a8`, `7d567d2`

## Проверка после реализации

Ручная, на живом боте — автоматикой не закрывается:

1. Уведомление с несколькими кадрами: одно сообщение, коллаж, таблица, клавиатура экспорта.
2. Появление описания: текст заменяет плейсхолдеры, кадры остаются на месте.
3. Двое подписчиков: второму кадры уходят без повторной загрузки (в логах нет второй загрузки, сообщение приходит целым). **Это единственная проверка кросс-чатового `file_id`** — предположение, которое не удалось проверить в исследовании.
4. Вид на телефоне: таблица на узком экране.
5. Запись без кадров: одно сообщение без медиа, описание не запрашивается.

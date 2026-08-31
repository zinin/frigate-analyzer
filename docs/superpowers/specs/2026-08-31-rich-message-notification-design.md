# Единое rich-сообщение для уведомления о детекции

**Дата:** 2026-08-31
**Ветка:** `feature/rich-message-notification`
**Предыстория:** `docs/telegram-rich-message-migration.md` — исследование Bot API и живая проверка на тестовом боте 2026-08-31.

## Задача

Одно событие детекции должно порождать одно сообщение в Telegram. Сегодня их два, потому что
подпись к фото ограничена 1024 символами, а альбом не умеет нести форматированный текст. Bot API 10.2
снял оба ограничения: rich-сообщение вмещает 32768 символов, до 50 медиа, таблицы, раскрывающиеся
блоки и inline-клавиатуру. `ktgbotapi 36.1.0` поддерживает 10.2 и принимает загрузку свежих байтов
через `attach://`.

Проверено вживую, а не выведено из changelog: отправка с multipart-загрузкой кадров, доставка на
аккаунт без Premium, клавиатура на rich-сообщении, правка через `editMessageText(rich_message)`,
разбор ответа библиотекой 36.1.0.

## Что меняется для получателя

| | Сейчас | После |
|---|---|---|
| Сообщений на запись | 2 (при одном кадре и при альбоме) | 1 |
| Метаданные | Восемь строк с эмодзи | Таблица |
| Кадры | Фото или альбом | Коллаж (от двух кадров) |
| Подробное описание | Раскрывающаяся цитата отдельным сообщением | `<details>` внутри того же сообщения |
| Клавиатура экспорта | На фото или на втором сообщении | На единственном сообщении |

## Принятые решения

**Полная замена, без флага.** Старые ветки отправки удаляются. Сервер Telegram уже на 10.2, рендер
проверен на обычном аккаунте, а старая схема — обход лимитов, которых больше нет. Флаг стоил бы
поддержки двух путей и `EditTarget`, обслуживающего обе схемы. Цена решения: откат возможен только релизом.

**Метаданные передаются структурой.** `RecordingNotificationTask.message: String` сегодня несёт
готовую строку, собранную в `TelegramNotificationServiceImpl.formatRecordingMessage`. Таблицу из неё
не построить, поэтому задача получает структуру полей, а вёрстка уезжает в рендерер.

**Кадры показываются коллажем.** Коллаж повторяет привычный альбом: все кадры видны сразу, без свайпа.
Слайдшоу компактнее, но прячет девять кадров из десяти — для тревожного уведомления это потеря.
Простой поток `<img>` растягивает сообщение на всю высоту экрана уже на трёх кадрах.

**`file_id` переиспользуются между получателями.** Первый получатель грузит байты, остальные ссылаются
на полученные идентификаторы. Экономит N−1 загрузок на запись.

**Кнопки остаются в `reply_markup`.** Bot API 10.3 умеет кнопки внутри HTML, но `ktgbotapi 36.1.0`
падает на разборе ответа с такой сущностью, причём сообщение при этом доставляется — бот видит ошибку
отправки для успешно ушедшего сообщения. Проверено.

## Компоненты

### Новые

**`service/model/RecordingNotificationData.kt`** — метаданные записи:

```kotlin
data class RecordingNotificationData(
    val camId: String,
    val fileName: String,
    val detectionsCount: Int,
    val analyzedFramesCount: Int,
    val analyzeTimeSeconds: Int,
    val recordTimestamp: String,   // отформатировано в зоне и локали получателя
    val processTimestamp: String,  // "N/A", когда запись ещё не обработана — как и сегодня, без локализации
)
```

Даты форматирует сервис — он знает зону и язык пользователя. Рендерер получает готовые строки.

**`service/model/DescriptionState.kt`** — состояние AI-описания:

```kotlin
sealed interface DescriptionState {
    data object Absent : DescriptionState                       // выключено или кадров нет
    data object Pending : DescriptionState                       // плейсхолдер до ответа модели
    data class Ready(val result: DescriptionResult) : DescriptionState
    data object Failed : DescriptionState                        // текст fallback
}
```

**`service/impl/RichNotificationRenderer.kt`** — единственное место, где собирается HTML. Безусловный
`@Component`: сообщение строится всегда, а описание — лишь одно из его состояний.

```kotlin
fun render(
    data: RecordingNotificationData,
    description: DescriptionState,
    frameCount: Int,
    language: String,
): String
```

**`queue/SharedFrameIds.kt`** — держатель идентификаторов кадров, один на запись:

```kotlin
class SharedFrameIds {
    private val ref = AtomicReference<List<FileId>?>(null)
    fun get(): List<FileId>? = ref.get()
    fun putIfAbsent(ids: List<FileId>) = ref.compareAndSet(null, ids)
    fun invalidate() = ref.set(null)
}
```

### Изменяемые

| Класс | Изменение |
|---|---|
| `queue/NotificationTask.kt` | `message: String` → `data: RecordingNotificationData`; добавляется `frameIds: SharedFrameIds` |
| `queue/TelegramNotificationSender.kt` | Три ветки отправки схлопываются в одну |
| `queue/DescriptionEditJobRunner.kt` | `EditTarget` → `(chatId, messageId, data, fileIds, exportKeyboard, language)`; одна правка вместо двух |
| `service/impl/TelegramNotificationServiceImpl.kt` | `formatRecordingMessage` → `buildNotificationData`; создаёт `SharedFrameIds` рядом с `descriptionHandle` |

### Удаляемые

- `service/impl/DescriptionMessageFormatter.kt` и его тест. Экранирование и энтити-безопасная обрезка
  переезжают в рендерер. Заменить один класс другим нельзя: форматтер условен по
  `application.ai.description.enabled`, а рендерер нужен всегда.
- В отправителе: `sendEmptyText`, `sendSinglePhoto`, `sendMediaGroupMessages`, `toCaption`,
  константы `MAX_CAPTION_LENGTH` и `MAX_MEDIA_GROUP_SIZE`.
- В раннере правок: `editMediaGroup`, `editSinglePhotoCaption`, `editSinglePhotoDetails`.
- `ObjectProvider<DescriptionMessageFormatter>` как признак «описание включено». Признаком становится
  `task.descriptionHandle != null`. Нынешний комментарий в коде — «formatter != null acts as the
  description-enabled gate downstream» — прямо называет эту связь хрупкой.

## Разметка сообщения

```html
<h2>📹 Обработка записи завершена</h2>
<table bordered striped compact>
  <tr><td>Камера</td><td>driveway</td></tr>
  <tr><td>Файл</td><td>2026-08-31_21-15-03.mp4</td></tr>
  <tr><td>Обнаружений</td><td>3</td></tr>
  <tr><td>Кадров проанализировано</td><td>12</td></tr>
  <tr><td>Время обработки, сек</td><td>4</td></tr>
  <tr><td>Запись</td><td>31 августа 2026 г., 21:15</td></tr>
  <tr><td>Обработка</td><td>31 августа 2026 г., 21:20</td></tr>
</table>
<p>Человек подошёл к воротам и оставил коробку.</p>
<tg-collage><img src="tg://photo?id=f0"/><img src="tg://photo?id=f1"/></tg-collage>
<details><summary>Подробное описание</summary>…</details>
```

- Шапки у таблицы нет: две колонки читаются без подписей «Параметр» и «Значение».
- Эмодзи уезжают из строк в заголовок. По значку на строку — визуальный шум внутри таблицы.
- Коллаж собирается от двух кадров. Один кадр остаётся обычным `<img>`.
- Подписи под коллажем нет: время записи уже в таблице.
- Краткое описание — абзац без префикса, как сегодня в подписи к фото. В состоянии `Pending` на его
  месте стоит плейсхолдер, в `Absent` абзаца нет вовсе.
- Блок `<details>` отсутствует в состоянии `Absent`.

## Поток данных

### Отправка

```kotlin
val html = renderer.render(task.data, descriptionState, task.visualizedFrames.size, lang)
val media = task.frameIds.get()
    ?.mapIndexed { i, id -> InputRichMessageMedia("f$i", TelegramMediaPhoto(id)) }
    ?: task.visualizedFrames.mapIndexed { i, f ->
        InputRichMessageMedia("f$i", TelegramMediaPhoto(f.visualizedBytes.asMultipartFile("frame_${f.frameIndex}.jpg")))
    }
val sent = RetryHelper.retryIndefinitely("Send rich notification", task.chatId) {
    bot.sendRichMessage(chatId, InputRichMessageHTML(html, media = media), replyMarkup = exportKeyboard)
}
task.frameIds.putIfAbsent(sent.photoFileIds())
```

Идентификаторы достаются из ответа: блоки `RichBlockPhoto` в порядке `<img>`, из каждого берётся
самый крупный `PhotoSize`.

### Переиспользование file_id

Очередь разбирает задачи одним потребителем — `for (task in channel) { sender.send(task) }`. Порядок
задач определяет, кто грузит байты, поэтому ни `Deferred`, ни таймаут не нужны. `AtomicReference`
защищает не от гонки, а от будущего распараллеливания очереди: в худшем случае произойдёт вторая
загрузка вместо зависания на ожидании.

Застрявший на бесконечных ретраях первый получатель блокирует очередь и сегодня — новая схема этого
не ухудшает.

### Правка после AI-описания

```kotlin
bot.execute(EditChatMessageRichText(
    target.chatId,
    target.messageId,
    InputRichMessageHTML(
        renderer.render(target.data, state, target.fileIds.size, target.language),
        media = target.fileIds.mapIndexed { i, id -> InputRichMessageMedia("f$i", TelegramMediaPhoto(id)) },
    ),
    replyMarkup = target.exportKeyboard,
))
```

Медиа переобъявляются обязательно: без массива `media` Telegram отвечает `400 RICH_MESSAGE_PHOTO_INVALID`,
даже когда HTML ссылается на прежние `tg://photo?id=`. Проверено.

Результат правки библиотека типизирует как `TextContent`, хотя приходит `RichMessageContent`. Значение
не используется, каст не нужен.

Обработка `MessageIsNotModifiedException`, `MessageToEditNotFoundException` и бэкофф на пять попыток
сохраняются без изменений.

## Ошибки и отказы

| Ситуация | Поведение |
|---|---|
| Отправка по `file_id` отклонена | Кэш сбрасывается через `invalidate()`, кадры грузятся заново — один раз на задачу |
| Итоговый HTML длиннее 32768 символов | Энтити-безопасно подрезается `detailed`; остальное сообщение не трогается |
| Кадров больше 50 | Список срезается до 50 **до вызова рендерера**, поэтому `<img>` и `media` остаются согласованы (наш потолок — 10, это защита от дрейфа настройки) |
| Кадров нет | Сообщение без коллажа и без медиа, состояние описания `Absent` |
| Модель не ответила | Состояние `Failed`, в оба блока подставляется текст fallback |

## i18n

Семь ключей вида `notification.recording.camera=🎥 Камера: {0}` заменяются подписями ячеек
`notification.recording.label.camera=Камера` — в `messages_ru.properties` и `messages_en.properties`.
Единица измерения переезжает в подпись — `notification.recording.label.analyze.time=Время обработки, сек`, —
поэтому в ячейку значения попадает голое число и форматирование значений не требуется.
Ключ `notification.recording.title` остаётся заголовком. Добавляется `ai.description.details.summary`
для заголовка раскрывающегося блока. Ключ `notification.recording.export.prompt` удаляется: подсказка
существовала только потому, что у альбома кнопка жила в отдельном сообщении.

## Тесты

Порядок по правилам проекта: сначала тест, потом реализация.

**`RichNotificationRendererTest`** (новый, вместо `DescriptionMessageFormatterTest`): экранирование
`<`, `>`, `&` в имени камеры, имени файла и обоих текстах описания; подрезка `detailed` при
переполнении 32768; четыре состояния описания; коллаж от двух кадров и `<img>` при одном; отсутствие
медиа-блока при нуле кадров.

**`TelegramNotificationSenderTest`** (переписывается): один путь отправки вместо трёх; media собран из
multipart у первого получателя; media собран из `file_id` у второго; повторная загрузка после
`invalidate()`; клавиатура на сообщении; `EditTarget` несёт идентификаторы кадров.

**`DescriptionEditJobRunner`**: одна правка с переобъявлением медиа. Тесты на исключения и бэкофф
остаются нетронутыми.

**`TelegramNotificationServiceImpl`**: `buildNotificationData` отдаёт структуру; все задачи одной
записи получают один и тот же `SharedFrameIds`.

## Зависимости

`gradle/libs.versions.toml`: `ktgbotapi` `35.1.0` → `36.1.0`, `ktor` `3.5.1` → `3.5.2` с обновлением
комментария про lockstep. `coroutines` остаются на `1.11.0`, оговорка про BOM Spring Boot в силе.

Ломающие изменения 36.0.0 по нашим точкам вызова проверены и не задевают код: позиционного создания
`Common*ContentMessageImpl` нет, `ReplyParameters(message.metaInfo)` в `StatusCommandHandler`
продолжает разрешаться, новое поведение `reply(to = …)` касается только ephemeral-сообщений.
Мажорный апгрейд задевает весь бот, поэтому полная сборка проекта входит в объём работы.

## Документация

`.claude/rules/telegram.md` и `.claude/rules/ai-description.md` описывают новую схему из одного
сообщения. `docs/telegram-rich-message-migration.md` помечается как реализованный.

## Риски

| Риск | Что делаем |
|---|---|
| `file_id` может не переиспользоваться в чужом чате — проверено только внутри одного | Фолбэк на повторную загрузку; убрать, когда схема отработает в проде |
| Рендер на мобильном не проверялся, особенно таблица на узком экране | Проверить руками после выката |
| Двойная отправка, если Telegram примет сообщение, а разбор ответа упадёт | Конструкции 10.3 не отправляются |
| Мажорный апгрейд библиотеки задевает команды и экспорт | Полная сборка проекта |

## Границы

Не входит: слайдшоу и блоки 10.3, кнопки внутри HTML, `SimpleTextNotificationTask` (уведомления о
потере сигнала), всё, что связано с экспортом видео.

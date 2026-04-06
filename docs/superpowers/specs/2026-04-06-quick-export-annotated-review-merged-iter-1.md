# Merged Design Review — Iteration 1

## codex-executor (gpt-5.4)

- [CRITICAL-1] Inline-кнопку нельзя сделать неактивной — в Telegram нет disabled-state. Нужно явно определить поведение повторного нажатия.
- [IMPORTANT-1] Логика прогресса и таймаутов уже есть в ExportExecutor, план дублирует.
- [IMPORTANT-2] Противоречие throttling: "3-5 секунд" vs "порог 5%".
- [IMPORTANT-3] SENDING не приходит из сервисного слоя — выставляется вручную.
- [IMPORTANT-4] План тестов слишком узкий.
- [QUESTION-1] Concurrency для двух режимов и нескольких пользователей.
- [SUGGESTION-1] Callback prefix qe:/qea: → qe:o:/qe:a:.
- [SUGGESTION-2] Кнопка "Видео" → "Оригинал".

---

## gemini-executor

- [CRITICAL-1] TelegramNotificationSender.kt должен быть изменён для двух кнопок.
- [IMPORTANT-1] Потокобезопасность activeExports — MutableSet не потокобезопасен.
- [IMPORTANT-2] Гарантированное восстановление клавиатуры (try-finally).
- [SUGGESTION-1] "Видео" → "Оригинал".
- [SUGGESTION-2] После экспорта — ✅ вместо обычной кнопки.
- [SUGGESTION-3] Коллизия qe:/qea: — безопасна при проверке с двоеточием.
- [QUESTION-1] Механизм rate limiting — Flow operators.

---

## ccs-executor (glm, glm-5.1)

- [CRITICAL-1] qea: не попадёт в фильтр startsWith("qe:") — рекомендует qe:o:/qe:a:.
- [CRITICAL-2] activeExports блокирует экспорт другого типа для той же записи.
- [IMPORTANT-1] exportByRecordingId захардкожен в ORIGINAL.
- [IMPORTANT-2] Отсутствует схема throttling.
- [IMPORTANT-3] SENDING stage бесполезен — слишком быстро.
- [IMPORTANT-4] 20-мин таймаут — нужно intermediate уведомление.
- [SUGGESTION-1/2/3/4] Названия кнопок, callback data лимит, rate limiting абстрактен, шум логов.
- [QUESTION-1/2/3] Lock per recording vs per user, прогресс для ORIGINAL, горизонт vs вертикаль.

---

## ccs-executor (albb-glm, GLM-5)

- [CRITICAL-1] activeExports keyed by UUID — два режима блокируют друг друга.
- [CRITICAL-2] PREPARING stage никогда не виден.
- [IMPORTANT-1] parseRecordingId порядок removePrefix — подтверждён правильным.
- [IMPORTANT-2] Нет внутреннего таймаута аннотации.
- [IMPORTANT-3] Telegram 429 — нет time-based throttling.
- [SUGGESTION-1/2/3] Кнопка после завершения, error recovery, прогресс для ORIGINAL.
- [QUESTION-1/2] mode не будет hardcoded; ActiveExportTracker vs activeExports.

---

## ccs-executor (albb-qwen, qwen3-coder-plus)

- [CRITICAL-1] Недостаточная спецификация архитектуры.
- [CRITICAL-2] Отсутствие обработки критических ситуаций.
- [IMPORTANT-1] Префиксы qe:/qea: слишком короткие.
- [IMPORTANT-2] Отсутствие детализации обновления прогресса.
- [SUGGESTION-1/2] ConcurrentHashMap, отмена экспорта.
- [QUESTION-1/2] Rate limiting механизм, одновременные нажатия.

---

## ccs-executor (albb-kimi, kimi-k2.5)

- [CRITICAL-1] Race condition при одновременном нажатии — activeExports без учёта сообщения.
- [CRITICAL-2] Коллизия фильтра qe:/qea:.
- [IMPORTANT-1] Rate limiting только для ANNOTATING.
- [IMPORTANT-2] Префиксы — рекомендует qe:o:/qe:a:.
- [IMPORTANT-3] Блокировка по recordingId без userId.
- [IMPORTANT-4] Обратная совместимость со старыми callback.
- [SUGGESTION-1-5] Процент сжатия, "Готово", "Оригинал", устаревшие сообщения, метрики.
- [QUESTION-1/2] Синхронизация таймаутов, поведение PREPARING.

---

## ccs-executor (albb-minimax, MiniMax-M2.5)

- [CRITICAL-1] Throttling: time-based vs threshold-based несоответствие.
- [CRITICAL-2] activeExports без учёта режима.
- [CRITICAL-3] Восстановление клавиатуры после таймаута — проверить две кнопки.
- [IMPORTANT-1] Порядок callback prefix — подтверждён.
- [IMPORTANT-2] Пропущен import VideoExportProgress.Stage.
- [SUGGESTION-1/2] Консистентность с ExportExecutor, DONE stage не используется.

# План: Локализация захардкоженных русских строк

**Goal:** Устранить захардкоженные русскоязычные UI-строки, перенеся их в существующую i18n-инфраструктуру (`MessageResolver` + `messages_*.properties`).

**Architecture:** Используем готовый `MessageResolver` (Spring `ReloadableResourceBundleMessageSource`). В `StartupTelegramNotifier` получаем язык владельца через новый метод `TelegramUserService.getOwnerLanguage()` с fallback на `"en"`. В `LanguageCommandHandler` имена/кнопки языков выносим в одинаковые ключи в обеих локалях.

**Tech Stack:** Kotlin 2.3.21, Spring Boot 4.0.6, MessageResolver (telegram-модуль), R2DBC.

---

## Контекст

Пользователь обнаружил жёстко зашитую русскую строку `"🟢 Frigate Analyzer запущен"` в `modules/core/.../StartupTelegramNotifier.kt:50` и попросил проверить остальные места. Инвентаризация нашла **4 USER_FACING строки в 2 файлах** (см. ниже). Остальные русские строки — комментарии разработчика и AI-промпты, вне scope.

| Файл | Строка | Текст | Контекст |
|------|--------|-------|----------|
| `core/.../StartupTelegramNotifier.kt` | 50 | `"🟢 Frigate Analyzer запущен"` | Уведомление owner-а при старте |
| `telegram/.../LanguageCommandHandler.kt` | 50 | `"🇷🇺 Русский"` | Кнопка выбора языка |
| `telegram/.../LanguageCommandHandler.kt` | 51 | `"🇬🇧 English"` | Кнопка выбора языка |
| `telegram/.../LanguageCommandHandler.kt` | 78 | `if (newLang == "ru") "Русский" else "English"` | Имя выбранного языка |

## Решения

- `StartupTelegramNotifier`: язык владельца берётся из БД через новый `TelegramUserService.getOwnerLanguage()`, fallback `"en"`.
- Английские лейблы (`Version:`, `Commit:`, `Build time:`, `Started:`) **не трогаем**.
- Имена языков (`Русский`, `English`) — значение одинаково в обеих локалях.

---

## Подготовка: создать feature-ветку

✅ Done — see commit: `1e54d78` (feature-ветка `feat/i18n-hardcoded-ru-strings`, план скопирован в `docs/superpowers/plans/`).

## Задача 1: Добавить ключи в i18n ресурсы

✅ Done — see commit: `5bd3a30`. Добавлены ключи `startup.notification.message`, `language.button.{ru,en}`, `language.name.{ru,en}` в оба `messages_ru.properties` и `messages_en.properties`.

## Задача 2: Добавить `getOwnerLanguage()` в TelegramUserService

✅ Done — see commit: `5bd3a30`. Метод добавлен в interface, реализован как `findByUsernameIgnoreCase(telegramProperties.owner)?.languageCode` с `@Transactional(readOnly = true)`. 3 unit-теста в `TelegramUserServiceImplTest`.

## Задача 3: Локализовать LanguageCommandHandler

✅ Done — see commit: `5bd3a30`. Строки 50, 51, 78 заменены на `msg.get(...)`. `LanguageCommandHandlerTest` не менялся (проверяет только метаданные).

## Задача 4: Локализовать StartupTelegramNotifier

✅ Done — see commit: `5bd3a30`. Конструктор принимает `TelegramUserService` + `MessageResolver`. Логика перенесена внутрь `scope.launch` (под `withTimeout`). Тест расширен моками + новый тест fallback на `"en"`.

## Задача 5: Финальная верификация и code review

✅ Done — see commits: `5bd3a30` (initial review + build), `91866ea` (plan trim), `e5852d0` (multi-agent external review decisions applied).

External review (6 ревьюеров: claude + codex + ccs-glm + ollama-kimi/deepseek/minimax; codex CLI install сломан, остальные 5 отдали полный отчёт):
- 2 decisions применены: parity-тест на 5 новых i18n-ключей в `MessageResolverTest`; `getOwnerLanguage()` теперь фильтрует по `UserStatus.ACTIVE` для consistency с sibling-кодом + тест на INVITED-owner.
- 1 disputed оставлен как есть (withTimeout shared budget — явная plan-level trade-off).
- 11 issues отклонены после верификации против кода.

Build clean: 440 тестов прошли, `ktlintCheck` зелёный.

- [ ] **Шаг 6: Перед PR — удалить план из docs/superpowers/**

По глобальным preferences пользователя — план-документ не должен быть в diff PR:

```bash
git rm docs/superpowers/plans/2026-05-26-localize-hardcoded-ru-strings.md
git commit -m "chore: drop plan doc before opening PR"
```

План остаётся доступен через `git log -- docs/superpowers/plans/` в истории ветки.

---

## Verification (как проверить руками)

1. **Юнит-тесты:** `./gradlew :frigate-analyzer-telegram:test :frigate-analyzer-core:test` — должны пройти.
2. **Smoke-тест локализации i18n-ключей:**
   ```bash
   grep -E "^(startup\.notification|language\.(button|name))" \
     modules/telegram/src/main/resources/messages_*.properties
   ```
   Ожидается 10 строк (5 в каждом файле).
3. **Smoke-тест приложения** (если есть локальное окружение):
   - Запустить `./gradlew :frigate-analyzer-core:bootRun` с `TELEGRAM_OWNER`.
   - Owner должен получить "🟢 Frigate Analyzer запущен" (если `languageCode=ru`) или "started" (если `en`/null).
   - `/language` в чате — кнопки одинаковые независимо от локали; после смены приходит подтверждение на новом языке.

## Что НЕ входит в скоуп

- Русские комментарии разработчика и AI-промпты — не UI-текст.
- Английские лейблы `Version:`, `Commit:`, `Build time:`, `Started:` — оставлены как есть.
- Перевод других строк, если найдутся несоответствия `messages_ru` vs `messages_en` — отдельная задача.

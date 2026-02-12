---
paths: "modules/telegram/**"
---

# Telegram Bot Integration

Library: `dev.inmo:tgbotapi` (ktgbotapi)

## Documentation

- Official: https://docs.inmo.dev/tgbotapi/index.html
- Telegram Bot API: https://core.telegram.org/bots/api
- Context7: `/insanusmokrassar/ktgbotapi` for examples

## Components

| Component | Location | Purpose |
|-----------|----------|---------|
| FrigateAnalyzerBot | `telegram/bot/` | Main bot, long polling, commands |
| TelegramUserService | `telegram/service/` | Manages users (invite, activate, remove) |
| TelegramNotificationService | `telegram/service/` | Sends notifications |
| AuthorizationFilter | `telegram/filter/` | Role-based auth (OWNER, USER) |
| TelegramProperties | `telegram/config/` | Spring Boot config |

## User Management

- Owner defined in config (`TELEGRAM_OWNER`)
- Users stored in `telegram_users` table
- Owner invites via `/adduser @username`
- Users activate via `/start`

## Bot Commands

| Command | Access | Description |
|---------|--------|-------------|
| /start | All | Activate subscription |
| /help | USER, OWNER | List commands |
| /adduser | OWNER | Invite user |
| /removeuser | OWNER | Remove user |
| /users | OWNER | List all users |

## Authorization

AuthorizationFilter returns UserRole (OWNER, USER) or null for unauthorized.

## Configuration

| Variable | Purpose |
|----------|---------|
| `TELEGRAM_ENABLED` | Enable/disable bot (default: true) |
| `TELEGRAM_BOT_TOKEN` | Bot token |
| `TELEGRAM_OWNER` | Owner username (without @) |
| `TELEGRAM_QUEUE_CAPACITY` | Notification queue size (default: 100) |

Disable for development: `java -Dapplication.telegram.enabled=false ...`

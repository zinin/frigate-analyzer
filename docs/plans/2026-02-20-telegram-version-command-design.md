# Design: Telegram Bot /version Command

## Context
Add a `/version` command to the Telegram bot to display application version information (Git commit, build time, etc.). This mirrors the existing functionality in `VersionController`.

## Goals
- Allow authorized users to check the currently running version via Telegram.
- Include Git commit hash, commit time, build version, and build time.

## Architecture

### Module: `frigate-analyzer-telegram`

#### `FrigateAnalyzerBot`
- Inject `ObjectProvider<BuildProperties>` and `ObjectProvider<GitProperties>` to retrieve build info.
- Register `/version` command in `DEFAULT_COMMANDS`.
- Implement `handleVersion` method:
    - Check user authorization (must be `ACTIVE` or `OWNER`).
    - Format response string similar to `VersionController`.
    - Send response.

## Data Flow
User sends `/version` -> Bot checks auth -> Bot retrieves build/git properties -> Bot sends formatted text response.

## Security
- Command is available to any user with `ACTIVE` status (same as `/export` and `/timezone`).
- Unauthorized users receive the standard unauthorized message.

## Implementation Details

### Dependencies
- `org.springframework.boot.info.BuildProperties`
- `org.springframework.boot.info.GitProperties`
- `org.springframework.beans.factory.ObjectProvider`

### Code Changes
1.  **`FrigateAnalyzerBot.kt`**:
    - Add `ObjectProvider` dependencies to constructor.
    - Add `BotCommand("version", "Версия приложения")` to `DEFAULT_COMMANDS`.
    - Add `onCommand("version")` handler in `start()`.
    - Add `handleVersion` function.

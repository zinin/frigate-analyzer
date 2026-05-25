package ru.zinin.frigate.analyzer.telegram.filter

import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.model.UserRole

sealed class AuthResult {
    /**
     * [user]'s `status` is `ACTIVE`. `user.chatId` is normally non-null (enforced by
     * `TelegramUserService.activateUser`), but callers should still treat it defensively:
     * pathological rows (manual DB edit, partial snapshot restore) with `status=ACTIVE` and
     * `chatId=null` are logged with a warning in `AuthorizationFilter` and still returned as
     * `Active` — blocking would create a dead-end since `/start` cannot re-activate an
     * already-`ACTIVE` row.
     */
    data class Active(
        val role: UserRole,
        val user: TelegramUserDto,
    ) : AuthResult()

    data object NeedsActivation : AuthResult()

    data object Unauthorized : AuthResult()
}

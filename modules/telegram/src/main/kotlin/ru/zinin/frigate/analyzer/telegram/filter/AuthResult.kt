package ru.zinin.frigate.analyzer.telegram.filter

import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.model.UserRole

sealed class AuthResult {
    data class Active(
        val role: UserRole,
        val user: TelegramUserDto,
    ) : AuthResult()

    data object NeedsActivation : AuthResult()

    data object Unauthorized : AuthResult()
}

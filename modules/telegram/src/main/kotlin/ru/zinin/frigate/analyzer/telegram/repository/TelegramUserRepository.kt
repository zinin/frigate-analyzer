package ru.zinin.frigate.analyzer.telegram.repository

import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import ru.zinin.frigate.analyzer.telegram.entity.TelegramUserEntity
import java.time.Instant
import java.util.UUID

@Repository
interface TelegramUserRepository : CoroutineCrudRepository<TelegramUserEntity, UUID> {
    suspend fun findByUsername(username: String): TelegramUserEntity?

    suspend fun findByUsernameAndStatus(
        username: String,
        status: String,
    ): TelegramUserEntity?

    @Query("SELECT * FROM telegram_users WHERE status = :status")
    suspend fun findAllByStatus(
        @Param("status") status: String,
    ): List<TelegramUserEntity>

    @Query("SELECT chat_id FROM telegram_users WHERE status = 'ACTIVE' AND chat_id IS NOT NULL")
    suspend fun findAllActiveChatIds(): List<Long>

    @Modifying
    @Query(
        """
        UPDATE telegram_users
        SET chat_id = :chatId,
            user_id = :userId,
            first_name = :firstName,
            last_name = :lastName,
            status = 'ACTIVE',
            activation_timestamp = :activationTimestamp
        WHERE username = :username AND status = 'INVITED'
        """,
    )
    suspend fun activate(
        @Param("username") username: String,
        @Param("chatId") chatId: Long,
        @Param("userId") userId: Long,
        @Param("firstName") firstName: String?,
        @Param("lastName") lastName: String?,
        @Param("activationTimestamp") activationTimestamp: Instant,
    ): Long

    suspend fun deleteByUsername(username: String): Long
}

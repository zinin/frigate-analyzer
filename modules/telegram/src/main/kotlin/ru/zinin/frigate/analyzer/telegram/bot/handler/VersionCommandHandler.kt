package ru.zinin.frigate.analyzer.telegram.bot.handler

import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.info.GitProperties
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.model.UserRole

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class VersionCommandHandler(
    private val buildProperties: ObjectProvider<BuildProperties>,
    private val gitProperties: ObjectProvider<GitProperties>,
) : CommandHandler {
    override val command: String = "version"
    override val description: String = "Версия приложения"
    override val requiredRole: UserRole = UserRole.USER
    override val order: Int = 5

    override suspend fun BehaviourContext.handle(
        message: CommonMessage<TextContent>,
        role: UserRole?,
    ) {
        val git = gitProperties.ifAvailable
        val build = buildProperties.ifAvailable

        val text =
            buildString {
                if (git != null) {
                    appendLine("Git version: ${git.commitId}")
                    appendLine("Git commit time: ${git.commitTime}")
                } else {
                    appendLine("Git info not available")
                }

                if (build != null) {
                    appendLine("Build version: ${build.version}")
                    appendLine("Build time: ${build.time}")
                } else {
                    appendLine("Build info not available")
                }
            }.trimEnd()

        reply(message, text)
    }
}

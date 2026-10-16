package morningpilates.telegram.profile

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import morningpilates.flow.session.FlowStorage
import morningpilates.profile.ProfileField
import morningpilates.profile.ProfileRepository
import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.api.deleteMessageOrRemoveButtons
import morningpilates.telegram.training.view.TrainingViewStorage
import morningpilates.telegram.training.view.closeTrainingView
import morningpilates.user.TelegramUserId
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val DIARY_BUSY =
    "Завершите текущее действие дневника или отмените его через /cancel, затем откройте /profile."
private const val PROFILE_BUSY = "Сначала завершите редактирование профиля или отмените его через /cancel."
private const val STORAGE_ERROR = "Не удалось обратиться к базе данных. Попробуйте ещё раз или используйте /profile."

/** One process owns the dialogs; the lock serializes callbacks, text and cancellation. */
class ProfileInteraction(
    private val repository: ProfileRepository,
    private val flows: FlowStorage,
    private val views: TrainingViewStorage,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val screens = ConcurrentHashMap<TelegramUserId, ProfileScreen>()
    private val launch = UUID.randomUUID().toString().take(8)

    fun isEditing(userId: TelegramUserId): Boolean = screens[userId]?.content?.mode == ProfileMode.Edit

    internal fun isFromPreviousLaunch(data: String): Boolean {
        val token = data.substringAfterLast(':')
        return token.matches(Regex("[0-9a-f]{8}\\.[0-9a-f]{16}")) && !token.startsWith("$launch.")
    }

    @Synchronized
    fun blockDiary(
        bot: Bot,
        userId: TelegramUserId,
        chatId: Long,
    ): Boolean {
        if (isEditing(userId)) {
            bot.sendMessage(ChatId.fromId(chatId), text = PROFILE_BUSY)
            return true
        }
        close(bot, userId)
        return false
    }

    @Synchronized
    fun close(
        bot: Bot,
        userId: TelegramUserId,
    ): Boolean {
        val screen = screens.remove(userId) ?: return false
        bot.deleteMessageOrRemoveButtons(TelegramChatId(screen.chatId), TelegramMessageId(screen.messageId))
        return true
    }

    @Synchronized
    internal fun open(
        bot: Bot,
        userId: TelegramUserId,
        chatId: Long,
    ): Boolean {
        if (diaryBusy(userId)) {
            bot.sendMessage(ChatId.fromId(chatId), text = DIARY_BUSY)
            return false
        }
        bot.closeTrainingView(userId, views)
        return showProfile(bot, userId, chatId, ProfileMode.View, committed = true)
    }

    @Synchronized
    internal fun callback(
        bot: Bot,
        userId: TelegramUserId,
        chatId: Long,
        messageId: Long,
        data: String,
    ) {
        val screen = screens[userId]?.takeIf { it.authorizes(chatId, messageId, data) } ?: return
        val action = data.removePrefix("profile:").substringBefore(':')
        when {
            action == "edit" -> {
                if (diaryBusy(userId)) {
                    bot.sendMessage(ChatId.fromId(chatId), text = DIARY_BUSY)
                } else {
                    showProfile(bot, userId, chatId, ProfileMode.Edit)
                }
            }

            action == "finish" || action == "back" -> {
                val mode = if (action == "finish") ProfileMode.View else ProfileMode.Edit
                val content = screen.content.copy(mode = mode, field = null, warning = null)
                display(bot, userId, chatId, content, committed = true)
            }

            action == "clear" || action.startsWith("value-") -> {
                save(bot, userId, screen, if (action == "clear") null else action.removePrefix("value-"))
            }

            else -> {
                val content = screen.content.copy(field = ProfileField.valueOf(action), warning = null)
                display(bot, userId, chatId, content)
            }
        }
    }

    @Synchronized
    internal fun text(
        bot: Bot,
        userId: TelegramUserId,
        chatId: Long,
        input: String,
        messageId: Long,
    ) {
        val screen =
            screens[userId]?.takeIf {
                it.chatId == chatId && it.content.mode == ProfileMode.Edit && it.content.field != null
            } ?: return
        if (save(bot, userId, screen, input)) bot.deleteMessage(ChatId.fromId(chatId), messageId)
    }

    private fun diaryBusy(userId: TelegramUserId): Boolean =
        flows.get(userId) != null || views.get(userId)?.dateSearch?.isWaitingForDate == true

    private fun showProfile(
        bot: Bot,
        userId: TelegramUserId,
        chatId: Long,
        mode: ProfileMode,
        committed: Boolean = false,
    ): Boolean =
        repository.find(userId).fold(
            onSuccess = { display(bot, userId, chatId, ProfileContent(it, mode), committed) },
            onFailure = {
                val screen = screens[userId]
                if (screen == null) {
                    bot.sendMessage(ChatId.fromId(chatId), text = STORAGE_ERROR)
                } else {
                    display(bot, userId, chatId, screen.content.copy(warning = STORAGE_ERROR))
                }
                false
            },
        )

    private fun save(
        bot: Bot,
        userId: TelegramUserId,
        screen: ProfileScreen,
        input: String?,
    ): Boolean {
        val profile =
            repository.find(userId).getOrElse {
                return display(bot, userId, screen.chatId, screen.content.copy(warning = STORAGE_ERROR))
            }
        val updated =
            runCatching {
                profile.withInput(checkNotNull(screen.content.field), input, LocalDate.now(clock))
            }
        return updated.fold(
            onSuccess = { value ->
                repository.save(userId, value).fold(
                    onSuccess = {
                        display(bot, userId, screen.chatId, ProfileContent(value, ProfileMode.Edit), committed = true)
                        true
                    },
                    onFailure = {
                        display(bot, userId, screen.chatId, screen.content.copy(warning = STORAGE_ERROR))
                    },
                )
            },
            onFailure = { error ->
                if (error !is IllegalArgumentException) throw error
                display(bot, userId, screen.chatId, screen.content.copy(warning = checkNotNull(error.message)))
            },
        )
    }

    private fun display(
        bot: Bot,
        userId: TelegramUserId,
        chatId: Long,
        content: ProfileContent,
        committed: Boolean = false,
    ): Boolean {
        val previous = screens[userId]
        val token = "$launch.${UUID.randomUUID().toString().replace("-", "").take(16)}"
        // A committed save or accepted reset must invalidate old actions even if Telegram cannot update the card.
        if (committed && previous != null) screens[userId] = previous.copy(token = token, content = content)
        val delivered = bot.deliverProfileScreen(chatId, previous, content, token, LocalDate.now(clock)) ?: return false
        screens[userId] = delivered
        return true
    }
}

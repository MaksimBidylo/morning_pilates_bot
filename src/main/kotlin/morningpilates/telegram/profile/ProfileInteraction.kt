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

private data class ProfileScreen(
    val chatId: Long,
    val messageId: Long,
    val token: String,
    val field: ProfileField?,
)

/** One process owns the dialogs; the lock serializes callbacks, text and cancellation. */
class ProfileInteraction(
    private val repository: ProfileRepository,
    private val flows: FlowStorage,
    private val views: TrainingViewStorage,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val screens = ConcurrentHashMap<TelegramUserId, ProfileScreen>()
    private val launch = UUID.randomUUID().toString().take(8)

    fun isEditing(userId: TelegramUserId): Boolean = screens[userId]?.field != null

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
    ) {
        if (diaryBusy(userId)) {
            bot.sendMessage(ChatId.fromId(chatId), text = DIARY_BUSY)
            return
        }
        bot.closeTrainingView(userId, views)
        showProfile(bot, userId, chatId)
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
        if (action == "close") {
            close(bot, userId)
        } else if (action == "cancel") {
            close(bot, userId)
            showProfile(bot, userId, chatId)
        } else if (screen.field == null) {
            if (diaryBusy(userId)) {
                bot.sendMessage(ChatId.fromId(chatId), text = DIARY_BUSY)
                return
            }
            val field = ProfileField.valueOf(action)
            display(bot, userId, chatId, "${field.title()}\n\n${field.prompt()}", field)
        } else {
            save(bot, userId, screen, if (action == "clear") null else action.removePrefix("value-"))
        }
    }

    @Synchronized
    internal fun text(
        bot: Bot,
        userId: TelegramUserId,
        chatId: Long,
        input: String,
    ) {
        val screen = screens[userId]?.takeIf { it.chatId == chatId && it.field != null } ?: return
        save(bot, userId, screen, input)
    }

    private fun diaryBusy(userId: TelegramUserId): Boolean =
        flows.get(userId) != null || views.get(userId)?.dateSearch?.isWaitingForDate == true

    private fun showProfile(
        bot: Bot,
        userId: TelegramUserId,
        chatId: Long,
    ) {
        repository.find(userId).fold(
            onSuccess = { display(bot, userId, chatId, it.text(LocalDate.now(clock)), null) },
            onFailure = { bot.sendMessage(ChatId.fromId(chatId), text = STORAGE_ERROR) },
        )
    }

    private fun save(
        bot: Bot,
        userId: TelegramUserId,
        screen: ProfileScreen,
        input: String?,
    ) {
        val profile =
            repository.find(userId).getOrElse {
                bot.sendMessage(ChatId.fromId(screen.chatId), text = STORAGE_ERROR)
                return
            }
        val updated =
            try {
                profile.withInput(checkNotNull(screen.field), input, LocalDate.now(clock))
            } catch (error: IllegalArgumentException) {
                bot.sendMessage(ChatId.fromId(screen.chatId), text = checkNotNull(error.message))
                return
            }
        repository.save(userId, updated).fold(
            onSuccess = {
                // Invalidate the submitted action before delivery: failed Telegram calls cannot authorize it again.
                close(bot, userId)
                display(bot, userId, screen.chatId, updated.text(LocalDate.now(clock)), null)
            },
            onFailure = { bot.sendMessage(ChatId.fromId(screen.chatId), text = STORAGE_ERROR) },
        )
    }

    private fun display(
        bot: Bot,
        userId: TelegramUserId,
        chatId: Long,
        text: String,
        field: ProfileField?,
    ) {
        val token = "$launch.${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val sent =
            bot.sendMessage(ChatId.fromId(chatId), text = text, replyMarkup = profileKeyboard(field, token)).getOrNull()
                ?: return
        val previous = screens.put(userId, ProfileScreen(chatId, sent.messageId, token, field))
        if (previous != null) {
            bot.deleteMessageOrRemoveButtons(TelegramChatId(previous.chatId), TelegramMessageId(previous.messageId))
        }
    }
}

private fun ProfileScreen.authorizes(
    chatId: Long,
    messageId: Long,
    data: String,
): Boolean =
    this.chatId == chatId && this.messageId == messageId &&
        profileActions(field).any { (_, action) -> data == "profile:$action:$token" }

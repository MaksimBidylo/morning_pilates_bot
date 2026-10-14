package morningpilates.telegram

import morningpilates.user.TelegramUserId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@JvmInline
value class TrainingViewToken(
    val value: UUID,
)

data class ActiveTrainingView(
    val chatId: TelegramChatId,
    val messageId: TelegramMessageId,
    val token: TrainingViewToken,
)

class TrainingViewStorage {
    private val views = ConcurrentHashMap<TelegramUserId, ActiveTrainingView>()

    fun get(userId: TelegramUserId): ActiveTrainingView? = views[userId]

    fun put(
        userId: TelegramUserId,
        view: ActiveTrainingView,
    ) {
        views[userId] = view
    }

    fun remove(
        userId: TelegramUserId,
        expected: ActiveTrainingView,
    ): Boolean = views.remove(userId, expected)
}

package morningpilates.telegram

import morningpilates.user.TelegramUserId
import java.util.concurrent.ConcurrentHashMap

class TrainingMessageSessionStorage {
    private val sessions = ConcurrentHashMap<TelegramUserId, TrainingMessageSession>()

    fun get(userId: TelegramUserId): TrainingMessageSession? = sessions[userId]

    fun put(
        userId: TelegramUserId,
        session: TrainingMessageSession,
    ) {
        sessions[userId] = session
    }
}

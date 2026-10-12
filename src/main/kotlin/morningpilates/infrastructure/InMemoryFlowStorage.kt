package morningpilates.infrastructure

import morningpilates.flow.model.UserSession
import morningpilates.flow.session.FlowStorage
import morningpilates.user.TelegramUserId
import java.util.concurrent.ConcurrentHashMap

class InMemoryFlowStorage : FlowStorage {
    private val sessions = ConcurrentHashMap<TelegramUserId, UserSession>()

    override fun get(userId: TelegramUserId): UserSession? = sessions[userId]

    override fun start(
        userId: TelegramUserId,
        session: UserSession,
    ): Boolean = sessions.putIfAbsent(userId, session) == null

    override fun update(
        userId: TelegramUserId,
        expected: UserSession,
        updated: UserSession,
    ): Boolean = sessions.replace(userId, expected, updated)

    override fun remove(
        userId: TelegramUserId,
        expected: UserSession,
    ): Boolean = sessions.remove(userId, expected)
}

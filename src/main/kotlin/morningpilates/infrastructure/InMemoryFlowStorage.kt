package morningpilates.infrastructure

import morningpilates.flow.FlowStorage
import morningpilates.flow.UserSession
import java.util.concurrent.ConcurrentHashMap

class InMemoryFlowStorage : FlowStorage {
    private val sessions = ConcurrentHashMap<Long, UserSession>()

    override fun get(userId: Long): UserSession? = sessions[userId]

    override fun start(userId: Long, session: UserSession): Boolean =
        sessions.putIfAbsent(userId, session) == null

    override fun update(userId: Long, expected: UserSession, updated: UserSession): Boolean = TODO()

    override fun remove(userId: Long, expected: UserSession): Boolean = TODO()
}

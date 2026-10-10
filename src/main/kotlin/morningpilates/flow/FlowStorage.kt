package morningpilates.flow

interface FlowStorage {
    fun get(userId: Long): UserSession?

    fun start(
        userId: Long,
        session: UserSession,
    ): Boolean

    fun update(
        userId: Long,
        expected: UserSession,
        updated: UserSession,
    ): Boolean

    fun remove(
        userId: Long,
        expected: UserSession,
    ): Boolean
}

package morningpilates.flow.session

import morningpilates.flow.model.UserSession
import morningpilates.user.TelegramUserId

interface FlowStorage {
    fun get(userId: TelegramUserId): UserSession?

    /** Runs a short action only for this exact session snapshot, atomically with session mutations. */
    fun withCurrentSession(
        userId: TelegramUserId,
        expected: UserSession,
        action: () -> Boolean,
    ): Boolean

    fun start(
        userId: TelegramUserId,
        session: UserSession,
    ): Boolean

    fun update(
        userId: TelegramUserId,
        expected: UserSession,
        updated: UserSession,
    ): Boolean

    fun remove(
        userId: TelegramUserId,
        expected: UserSession,
    ): Boolean
}

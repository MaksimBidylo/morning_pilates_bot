package morningpilates.flow.session

import morningpilates.flow.model.UserSession
import morningpilates.user.TelegramUserId

interface FlowStorage {
    fun get(userId: TelegramUserId): UserSession?

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

package morningpilates.user

import java.util.UUID

interface UserRepository {
    fun getOrCreate(telegramUserId: TelegramUserId): UUID
}

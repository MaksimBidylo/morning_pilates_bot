package morningpilates.profile

import morningpilates.user.TelegramUserId

interface ProfileRepository {
    fun find(userId: TelegramUserId): Result<AthleteProfile>

    fun save(
        userId: TelegramUserId,
        profile: AthleteProfile,
    ): Result<Unit>
}

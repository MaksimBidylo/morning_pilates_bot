package morningpilates.profile

import morningpilates.user.TelegramUserId

class FakeProfileRepository : ProfileRepository {
    val profiles = mutableMapOf<TelegramUserId, AthleteProfile>()
    var readFailure = false
    var saveFailure = false
    var saves = 0

    override fun find(userId: TelegramUserId): Result<AthleteProfile> =
        if (readFailure) {
            Result.failure(IllegalStateException("Unavailable"))
        } else {
            Result.success(profiles[userId] ?: AthleteProfile())
        }

    override fun save(
        userId: TelegramUserId,
        profile: AthleteProfile,
    ): Result<Unit> =
        if (saveFailure) {
            Result.failure(IllegalStateException("Unavailable"))
        } else {
            saves++
            profiles[userId] = profile
            Result.success(Unit)
        }
}

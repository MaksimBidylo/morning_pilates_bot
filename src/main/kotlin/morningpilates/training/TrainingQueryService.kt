package morningpilates.training

import morningpilates.user.TelegramUserId

class TrainingQueryService(
    private val repository: TrainingRepository,
) {
    fun findLatest(telegramUserId: TelegramUserId): Result<SavedTraining?> = repository.findLatest(telegramUserId)
}

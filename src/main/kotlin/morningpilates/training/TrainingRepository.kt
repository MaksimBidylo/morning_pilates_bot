package morningpilates.training

import morningpilates.user.TelegramUserId

interface TrainingRepository {
    fun findLatest(telegramUserId: TelegramUserId): Result<SavedTraining?>

    fun save(
        telegramUserId: TelegramUserId,
        trainingId: TrainingId,
        details: TrainingDetails,
    ): Result<Unit>
}

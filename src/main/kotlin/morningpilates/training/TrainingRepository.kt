package morningpilates.training

import morningpilates.user.TelegramUserId

interface TrainingRepository {
    fun save(
        telegramUserId: TelegramUserId,
        trainingId: TrainingId,
        details: TrainingDetails,
    ): Result<Unit>
}

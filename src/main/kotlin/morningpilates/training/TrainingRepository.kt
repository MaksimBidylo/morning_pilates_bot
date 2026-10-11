package morningpilates.training

import morningpilates.user.TelegramUserId

interface TrainingRepository {
    fun save(
        telegramUserId: TelegramUserId,
        details: TrainingDetails,
    ): Result<Unit>
}

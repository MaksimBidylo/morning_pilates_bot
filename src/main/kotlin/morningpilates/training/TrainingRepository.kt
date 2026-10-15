package morningpilates.training

import morningpilates.user.TelegramUserId
import java.time.LocalDate

interface TrainingRepository {
    fun findLatest(telegramUserId: TelegramUserId): Result<SavedTraining?>

    fun findByDate(
        telegramUserId: TelegramUserId,
        date: LocalDate,
    ): Result<List<SavedTraining>>

    fun delete(
        telegramUserId: TelegramUserId,
        trainingId: TrainingId,
    ): Result<Boolean>

    fun save(
        telegramUserId: TelegramUserId,
        trainingId: TrainingId,
        details: TrainingDetails,
    ): Result<Unit>
}

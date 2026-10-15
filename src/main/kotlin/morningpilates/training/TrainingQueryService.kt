package morningpilates.training

import morningpilates.user.TelegramUserId
import java.time.LocalDate

class TrainingQueryService(
    private val repository: TrainingRepository,
) {
    fun findLatest(telegramUserId: TelegramUserId): Result<SavedTraining?> = repository.findLatest(telegramUserId)

    fun findByDate(
        telegramUserId: TelegramUserId,
        date: LocalDate,
    ): Result<List<SavedTraining>> = repository.findByDate(telegramUserId, date)
}

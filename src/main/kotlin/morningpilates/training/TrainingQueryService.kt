package morningpilates.training

import morningpilates.user.TelegramUserId
import java.time.LocalDate

class TrainingQueryService(
    private val repository: TrainingRepository,
) {
    fun findLatest(telegramUserId: TelegramUserId): Result<SavedTraining?> = repository.findLatest(telegramUserId)

    // RED #94: delegation is intentionally absent until GREEN.
    fun findByDate(
        telegramUserId: TelegramUserId,
        date: LocalDate,
    ): Result<List<SavedTraining>> = Result.success(emptyList())
}

package morningpilates.training

import morningpilates.diagnostics.RequestDiagnostics
import morningpilates.user.TelegramUserId
import java.time.LocalDate

class TrainingQueryService(
    private val repository: TrainingRepository,
) {
    fun findLatest(telegramUserId: TelegramUserId): Result<SavedTraining?> =
        repository.findLatest(telegramUserId).onFailure(RequestDiagnostics::failure)

    fun findByDate(
        telegramUserId: TelegramUserId,
        date: LocalDate,
    ): Result<List<SavedTraining>> = repository.findByDate(telegramUserId, date).onFailure(RequestDiagnostics::failure)
}

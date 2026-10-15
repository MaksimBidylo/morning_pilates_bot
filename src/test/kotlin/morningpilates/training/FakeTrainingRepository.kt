package morningpilates.training

import morningpilates.user.TelegramUserId
import java.time.LocalDate

class FakeTrainingRepository : TrainingRepository {
    data class SaveCall(
        val telegramUserId: TelegramUserId,
        val trainingId: TrainingId,
        val details: TrainingDetails,
    )

    data class FindByDateCall(
        val telegramUserId: TelegramUserId,
        val date: LocalDate,
    )

    val findByDateCalls = mutableListOf<FindByDateCall>()
    val findByDateResults = mutableMapOf<FindByDateCall, Result<List<SavedTraining>>>()

    var saveResult: Result<Unit> = Result.success(Unit)
    val saveCalls = mutableListOf<SaveCall>()
    val findLatestCalls = mutableListOf<TelegramUserId>()
    val findLatestResults = mutableMapOf<TelegramUserId, Result<SavedTraining?>>()

    override fun findLatest(telegramUserId: TelegramUserId): Result<SavedTraining?> {
        findLatestCalls += telegramUserId
        return findLatestResults[telegramUserId]
            ?: Result.failure(UnsupportedOperationException("Reading saved trainings is not configured in this fake"))
    }

    override fun findByDate(
        telegramUserId: TelegramUserId,
        date: LocalDate,
    ): Result<List<SavedTraining>> {
        val call = FindByDateCall(telegramUserId, date)
        findByDateCalls += call
        return findByDateResults[call]
            ?: Result.failure(UnsupportedOperationException("Reading trainings by date is not configured in this fake"))
    }

    // Temporary RED stub: repository deletion is not implemented yet.
    override fun delete(
        telegramUserId: TelegramUserId,
        trainingId: TrainingId,
    ): Result<Boolean> = Result.success(false)

    override fun save(
        telegramUserId: TelegramUserId,
        trainingId: TrainingId,
        details: TrainingDetails,
    ): Result<Unit> {
        saveCalls += SaveCall(telegramUserId, trainingId, details.copy(techniques = details.techniques.toList()))
        return saveResult
    }
}

package morningpilates.training

import morningpilates.user.TelegramUserId

class FakeTrainingRepository : TrainingRepository {
    data class SaveCall(
        val telegramUserId: TelegramUserId,
        val trainingId: TrainingId,
        val details: TrainingDetails,
    )

    var saveResult: Result<Unit> = Result.success(Unit)
    val saveCalls = mutableListOf<SaveCall>()

    override fun save(
        telegramUserId: TelegramUserId,
        trainingId: TrainingId,
        details: TrainingDetails,
    ): Result<Unit> {
        saveCalls += SaveCall(telegramUserId, trainingId, details.copy(techniques = details.techniques.toList()))
        return saveResult
    }
}

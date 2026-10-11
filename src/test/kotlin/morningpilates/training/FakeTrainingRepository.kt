package morningpilates.training

import morningpilates.user.TelegramUserId

class FakeTrainingRepository : TrainingRepository {
    data class SaveCall(
        val telegramUserId: TelegramUserId,
        val details: TrainingDetails,
    )

    var saveResult: Result<Unit> = Result.success(Unit)
    val saveCalls = mutableListOf<SaveCall>()

    override fun save(
        telegramUserId: TelegramUserId,
        details: TrainingDetails,
    ): Result<Unit> {
        saveCalls += SaveCall(telegramUserId, details.copy(techniques = details.techniques.toList()))
        return saveResult
    }
}

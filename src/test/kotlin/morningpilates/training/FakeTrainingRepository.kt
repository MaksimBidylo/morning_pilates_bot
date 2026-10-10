package morningpilates.training

class FakeTrainingRepository : TrainingRepository {
    var saveResult: Result<Unit> = Result.success(Unit)
    val saveCalls = mutableListOf<TrainingDetails>()

    override fun save(details: TrainingDetails): Result<Unit> {
        saveCalls += details.copy(techniques = details.techniques.toList())
        return saveResult
    }
}

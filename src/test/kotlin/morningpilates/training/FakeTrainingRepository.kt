package morningpilates.training

class FakeTrainingRepository : TrainingRepository {
    val saveCalls = mutableListOf<TrainingDetails>()

    override fun save(details: TrainingDetails): Result<Unit> {
        saveCalls += details.copy(techniques = details.techniques.toList())
        return Result.success(Unit)
    }
}

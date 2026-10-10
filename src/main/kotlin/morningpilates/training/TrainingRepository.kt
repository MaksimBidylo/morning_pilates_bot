package morningpilates.training

interface TrainingRepository {
    fun save(details: TrainingDetails): Result<Unit>
}

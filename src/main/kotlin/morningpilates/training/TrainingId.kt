package morningpilates.training

import morningpilates.flow.model.SessionId
import java.util.UUID

@JvmInline
value class TrainingId(
    val value: UUID,
)

fun SessionId.toTrainingId(): TrainingId = TrainingId(value)

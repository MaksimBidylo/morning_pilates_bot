package morningpilates.training

import morningpilates.flow.SessionId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class TrainingIdTest {
    @Test
    fun `preserves an explicitly supplied UUID`() {
        val value = UUID.randomUUID()

        val trainingId = TrainingId(value)

        assertEquals(value, trainingId.value)
    }

    @Test
    fun `session converts to a training identifier without changing its UUID`() {
        val sessionId = SessionId.new()

        val trainingId: TrainingId = sessionId.toTrainingId()

        assertEquals(sessionId.value, trainingId.value)
    }
}

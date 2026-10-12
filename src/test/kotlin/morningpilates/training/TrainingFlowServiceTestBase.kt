package morningpilates.training

import morningpilates.flow.model.FlowResult
import morningpilates.flow.model.SessionId
import morningpilates.user.TelegramUserId
import kotlin.test.assertContains
import kotlin.test.assertIs

abstract class TrainingFlowServiceTestBase {
    protected val sessionId = SessionId.new()
    protected val telegramUserId = TelegramUserId(7_000_000_123L)
    protected val trainingRepository = FakeTrainingRepository()
    protected val service = TrainingFlowService(trainingRepository)

    protected fun assertRejected(
        result: FlowResult,
        expectedReason: String,
    ) {
        val rejected = assertIs<FlowResult.Rejected>(result)
        assertContains(rejected.reason, expectedReason)
    }
}

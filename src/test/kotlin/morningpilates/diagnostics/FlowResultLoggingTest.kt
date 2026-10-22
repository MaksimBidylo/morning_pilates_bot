package morningpilates.diagnostics

import kotlinx.coroutines.runBlocking
import morningpilates.flow.model.FlowEvent
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.telegram.training.creation.startSessionAwaitingConfirmation
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TrainingFlowService
import morningpilates.user.TelegramUserId
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlowResultLoggingTest {
    @Test
    fun `failed save remains technical when flow returns a normal confirmation transition`() =
        runBlocking {
            val repository =
                FakeTrainingRepository().apply {
                    saveResult = Result.failure(SQLException("private note and database password", "08006"))
                }
            val storage = InMemoryFlowStorage()
            val userId = TelegramUserId(123456789)
            startSessionAwaitingConfirmation(storage, userId)
            val manager = FlowSessionManager(storage, listOf(TrainingFlowService(repository)))
            LogCapture().use { logs ->
                RequestDiagnostics.process("callback") {
                    manager.handle(FlowEvent.UserInput(userId, "Да"))
                }
                assertEquals(1, repository.saveCalls.size)
                assertTrue(logs.completions.single().contains("result=technical_failure"))
                assertFalse(logs.rendered.contains("private note"))
            }
        }
}

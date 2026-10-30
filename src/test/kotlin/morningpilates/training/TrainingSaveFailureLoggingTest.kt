package morningpilates.training

import morningpilates.flow.model.FlowEvent.Continue
import morningpilates.flow.model.FlowResult
import morningpilates.logging.renderedLogs
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.slf4j.LoggerFactory
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val CANARY = "CANARY_144_STORAGE_NOT_A_REAL_SECRET"
private const val EVENT = "event=storage_failure operation=training_save stage=persist handling=retry_transition"

class TrainingSaveFailureLoggingTest : TrainingFlowServiceTestBase() {
    @ParameterizedTest
    @MethodSource("failures")
    fun `failed save logs once with safe diagnostics and returns to confirmation`(
        failure: Throwable,
        sqlState: String,
    ) {
        val details =
            trainingDetails(note = CANARY).copy(techniques = listOf(TechniqueDraft(CANARY, CANARY)))
        trainingRepository.saveResult = Result.failure(failure)
        val output =
            renderedLogs {
                val result = service.handle(TrainingFlowState.Saving(details), Continue(telegramUserId, sessionId))
                assertEquals(FlowResult.Transition(TrainingFlowState.WaitingConfirmation(details)), result)
            }
        assertEquals(
            listOf(FakeTrainingRepository.SaveCall(telegramUserId, sessionId.toTrainingId(), details)),
            trainingRepository.saveCalls,
        )
        val record = output.lineSequence().filter { it.isNotBlank() }.single()
        assertTrue("ERROR" in record && EVENT in record && "TrainingFlowService" in record)
        assertTrue("exception_type=${failure.javaClass.name}" in record)
        assertTrue("exception_type=java.sql.SQLException sql_state=$sqlState" in record || failure !is SQLException)
        assertTrue("sql_state=$sqlState" in record)
        assertFalse(CANARY in output)
        assertFalse(telegramUserId.value.toString() in output || sessionId.value.toString() in output)
        assertFalse("https://" in output || "SELECT" in output || "Suppressed" in output)
        assertFalse("UnsupportedOperationException" in output)
    }

    @Test
    fun `successful save completes without a storage failure record`() {
        val details = trainingDetails(note = CANARY)
        val output =
            renderedLogs {
                val result = service.handle(TrainingFlowState.Saving(details), Continue(telegramUserId, sessionId))
                assertEquals(FlowResult.Completed, result)
            }
        assertEquals(1, trainingRepository.saveCalls.size)
        assertFalse("storage_failure" in output || CANARY in output)
    }

    @Test
    fun `storage event allowlist rejects altered templates and other loggers`() {
        val failure = IllegalStateException(CANARY)
        val output =
            renderedLogs {
                val logger = LoggerFactory.getLogger(TrainingFlowService::class.java)
                logger.error(EVENT, failure)
                logger.error("$EVENT $CANARY", failure)
                logger.error("event={}", "storage_failure", failure)
                LoggerFactory.getLogger("morningpilates.training.Imposter").error(EVENT, failure)
                LoggerFactory.getLogger("org.jooq.tools.LoggerListener").error(EVENT, failure)
            }
        assertEquals(1, output.lineSequence().count { "event=storage_failure" in it })
        assertFalse(CANARY in output)
    }

    companion object {
        @JvmStatic
        fun failures(): List<Array<Any>> {
            val unsafe = "https://secret.invalid/$CANARY SELECT '$CANARY'"
            val nested =
                SQLException(unsafe, "08006").apply {
                    addSuppressed(UnsupportedOperationException(unsafe))
                }
            return listOf(
                arrayOf(SQLException(unsafe, "08006"), "08006"),
                arrayOf(IllegalStateException(unsafe, nested), "08006"),
                arrayOf(SQLException(unsafe, CANARY), "none"),
                arrayOf(SQLException(unsafe, "08006\n$CANARY"), "none"),
                arrayOf(SQLException(unsafe, "08a06"), "none"),
                arrayOf(SQLException(unsafe, "0800"), "none"),
                arrayOf(SQLException(unsafe, null as String?), "none"),
                arrayOf(IllegalStateException(unsafe), "none"),
            )
        }
    }
}

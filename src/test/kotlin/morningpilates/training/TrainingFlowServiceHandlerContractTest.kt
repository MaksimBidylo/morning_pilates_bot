package morningpilates.training

import morningpilates.flow.FlowEvent.UserInput
import morningpilates.flow.TestFlowState.INITIAL
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrainingFlowServiceHandlerContractTest : TrainingFlowServiceTestBase() {
    @Test
    fun `states from another flow are not supported`() {
        // Given
        val state = INITIAL

        // When
        val supported = service.supports(state)

        // Then
        assertFalse(supported)
        assertTrue(trainingRepository.saveCalls.isEmpty())
    }

    @Test
    fun `handling a state from another flow fails before calling the repository`() {
        // Given
        val state = INITIAL
        val event = UserInput("Да")

        // When
        val exception =
            assertFailsWith<IllegalArgumentException> {
                service.handle(state, event)
            }

        // Then
        assertContains(
            assertNotNull(exception.message),
            "Обработчик тренировок поддерживает только TrainingFlowState",
        )
        assertTrue(trainingRepository.saveCalls.isEmpty())
    }
}

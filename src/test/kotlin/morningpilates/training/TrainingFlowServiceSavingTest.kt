package morningpilates.training

import morningpilates.flow.FlowEvent.Continue
import morningpilates.flow.FlowEvent.UserInput
import morningpilates.flow.FlowResult
import morningpilates.flow.SessionId
import morningpilates.training.FakeTrainingRepository.SaveCall
import morningpilates.training.TrainingFlowState.Saving
import morningpilates.training.TrainingFlowState.WaitingConfirmation
import morningpilates.user.TelegramUserId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TrainingFlowServiceSavingTest : TrainingFlowServiceTestBase() {
    @ParameterizedTest(name = "saves once and completes: withOptionalDetails={0}")
    @ValueSource(booleans = [false, true])
    fun `internal continuation saves training for its user once and completes the flow`(withOptionalDetails: Boolean) {
        // Given
        val details =
            if (withOptionalDetails) trainingDetails(note = "Заметка к тренировке") else minimalTrainingDetails()
        val expectedDetails = details.snapshot()
        val state = Saving(details)
        val savingSessionId = SessionId.new()
        val savingUserId = TelegramUserId(7_000_000_456L)

        // When
        val result = service.handle(state, Continue(savingUserId, savingSessionId))

        // Then
        assertEquals(FlowResult.Completed, result)
        assertEquals(1, trainingRepository.saveCalls.size)
        assertEquals(
            SaveCall(savingUserId, savingSessionId.toTrainingId(), expectedDetails),
            trainingRepository.saveCalls.single(),
        )
        assertEquals(expectedDetails, state.details)
    }

    @ParameterizedTest(name = "returns to confirmation without retrying: withOptionalDetails={0}")
    @ValueSource(booleans = [false, true])
    fun `a failed save returns training to confirmation without retrying`(withOptionalDetails: Boolean) {
        // Given
        val details =
            if (withOptionalDetails) trainingDetails(note = "Заметка к тренировке") else minimalTrainingDetails()
        val expectedDetails = details.snapshot()
        val state = Saving(details)
        val savingSessionId = SessionId.new()
        val savingUserId = TelegramUserId(7_000_000_456L)

        trainingRepository.saveResult = Result.failure(IllegalStateException("Ошибка записи"))

        // When
        val result = service.handle(state, Continue(savingUserId, savingSessionId))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingConfirmation(expectedDetails), transition.nextState)
        assertEquals(1, trainingRepository.saveCalls.size)
        assertEquals(
            SaveCall(savingUserId, savingSessionId.toTrainingId(), expectedDetails),
            trainingRepository.saveCalls.single(),
        )
        assertEquals(expectedDetails, state.details)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Да", "ДА", " да ", "\tДа\n",
            "Нет", "НЕТ", " нет ", "\tНеТ\n", "/cancel",
            "Произвольный текст", "", " ", "\t", "\n", " \t\r\n ",
        ],
    )
    fun `user input while saving is rejected without calling the repository`(text: String) {
        // Given
        val details = trainingDetails(note = "Заметка к тренировке")
        val expectedDetails = details.snapshot()
        val state = Saving(details)

        // When
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        val rejected = assertIs<FlowResult.Rejected>(result)
        assertTrue(rejected.reason.isNotBlank())
        assertEquals(0, trainingRepository.saveCalls.size)
        assertEquals(expectedDetails, state.details)
    }
}

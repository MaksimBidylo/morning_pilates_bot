package morningpilates.training

import morningpilates.flow.FlowResult
import morningpilates.training.TrainingFlowState.Saving
import morningpilates.training.TrainingFlowState.WaitingConfirmation
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TrainingFlowServiceConfirmationInputTest : TrainingFlowServiceTestBase() {
    companion object {
        private const val EXPECTED_INVALID_CHOICE_REASON = "Выберите «Да» или «Нет»"
    }

    @Test
    fun `confirming a fully populated training requests saving with all details`() {
        // Given
        val details = populatedTrainingDetails()
        val expectedDetails = details.snapshot()
        val state = WaitingConfirmation(details)

        // When
        val result = service.handle(state, "Да")

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(Saving(expectedDetails), transition.nextState)
        assertEquals(expectedDetails, state.details)
        assertEquals(0, trainingRepository.saveCalls.size)
    }

    @Test
    fun `declining confirmation cancels without changing training details`() {
        // Given
        val details = populatedTrainingDetails()
        val expectedDetails = details.snapshot()
        val state = WaitingConfirmation(details)

        // When
        val result = service.handle(state, "Нет")

        // Then
        assertEquals(FlowResult.Cancelled, result)
        assertEquals(expectedDetails, state.details)
        assertEquals(0, trainingRepository.saveCalls.size)
    }

    @ParameterizedTest
    @ValueSource(strings = ["да", "ДА", "дА", " да ", "\tДа\t", "\nДа\n"])
    fun `yes variants request saving and preserve all training details`(text: String) {
        // Given
        val details = trainingDetails(note = "Заметка к тренировке")
        val expectedDetails = details.snapshot()
        val state = WaitingConfirmation(details)

        // When
        val result = service.handle(state, text)

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(Saving(expectedDetails), transition.nextState)
        assertEquals(expectedDetails, state.details)
        assertEquals(0, trainingRepository.saveCalls.size)
    }

    @ParameterizedTest
    @ValueSource(strings = ["нет", "НЕТ", "нЕт", " нет ", "\tНеТ\n", "\nНет\n"])
    fun `no variants cancel without changing training details`(text: String) {
        // Given
        val details = trainingDetails(note = "Заметка к тренировке")
        val expectedDetails = details.snapshot()
        val state = WaitingConfirmation(details)

        // When
        val result = service.handle(state, text)

        // Then
        assertEquals(FlowResult.Cancelled, result)
        assertEquals(expectedDetails, state.details)
        assertEquals(0, trainingRepository.saveCalls.size)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", " ", "\t", "\n", " \t\r\n ",
            "может быть", "возможно", "давай", "нет, подожди",
            "123", "yes", "no", "неточно", "Да, наверное", "Да Нет", "д а",
        ],
    )
    fun `unsupported answers are rejected without changing training details`(text: String) {
        // Given
        val details = trainingDetails(note = "Заметка к тренировке")
        val expectedDetails = details.snapshot()
        val state = WaitingConfirmation(details)

        // When
        val result = service.handle(state, text)

        // Then
        val rejected = assertIs<FlowResult.Rejected>(result)
        assertEquals(EXPECTED_INVALID_CHOICE_REASON, rejected.reason)
        assertEquals(expectedDetails, state.details)
        assertEquals(0, trainingRepository.saveCalls.size)
    }

    @Test
    fun `a training with only date and time can be confirmed`() {
        // Given
        val details = TrainingDetails(
            date = LocalDate.of(2026, 10, 9),
            time = LocalTime.of(19, 30),
            techniques = mutableListOf(),
            note = null,
        )
        val expectedDetails = details.snapshot()
        val state = WaitingConfirmation(details)

        // When
        val result = service.handle(state, "Да")

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(Saving(expectedDetails), transition.nextState)
        assertEquals(expectedDetails, state.details)
        assertEquals(0, trainingRepository.saveCalls.size)
    }
}

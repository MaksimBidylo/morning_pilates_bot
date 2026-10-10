package morningpilates.training

import morningpilates.flow.FlowEvent.UserInput
import morningpilates.flow.FlowResult
import morningpilates.training.TrainingFlowState.WaitingAddTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TrainingFlowServiceTechniqueNameInputTest : TrainingFlowServiceTestBase() {
    companion object {
        private const val EXPECTED_EMPTY_TECHNIQUE_NAME_REASON = "Введите название техники"
    }

    private val initialDetails = TrainingDetails(
        date = LocalDate.of(2026, 10, 9),
        time = LocalTime.of(19, 30),
        techniques = mutableListOf(),
    )

    @Test
    fun `valid technique name requests a description choice without adding a technique`() {
        // Given
        val expectedDetails = initialDetails.snapshot()
        val state = WaitingTechniqueName(initialDetails)
        val text = "Armbar from mount"

        // When
        val result = service.handle(state, UserInput(text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(
            WaitingAddTechniqueDescription(expectedDetails, text),
            transition.nextState,
        )
        assertEquals(expectedDetails, state.details)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Рычаг локтя",
            "Armbar from Mount",
            "Armbar из маунта",
            "Вариант №2: контроль руки",
            "Armbar  from mount",
        ],
    )
    fun `technique names preserve case internal spaces and punctuation`(text: String) {
        // Given
        val expectedDetails = initialDetails.snapshot()
        val state = WaitingTechniqueName(initialDetails)

        // When
        val result = service.handle(state, UserInput(text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(
            WaitingAddTechniqueDescription(expectedDetails, text),
            transition.nextState,
        )
        assertEquals(expectedDetails, state.details)
    }

    @ParameterizedTest
    @ValueSource(strings = [" Armbar from mount ", "\tArmbar from mount\t", "\nArmbar from mount\r\n"])
    fun `surrounding whitespace is removed from a technique name`(text: String) {
        // Given
        val expectedDetails = initialDetails.snapshot()
        val state = WaitingTechniqueName(initialDetails)

        // When
        val result = service.handle(state, UserInput(text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(
            WaitingAddTechniqueDescription(expectedDetails, "Armbar from mount"),
            transition.nextState,
        )
        assertEquals(expectedDetails, state.details)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t", "\n", " \t\r\n "])
    fun `blank technique names are rejected without changing training details`(text: String) {
        // Given
        val details = initialDetails.copy(
            techniques = mutableListOf(
                TechniqueDraft(name = "Armbar", description = "Описание техники"),
            ),
            note = "Заметка к тренировке",
        )
        val expectedDetails = details.snapshot()
        val state = WaitingTechniqueName(details)

        // When
        val result = service.handle(state, UserInput(text))

        // Then
        val rejected = assertIs<FlowResult.Rejected>(result)
        assertEquals(EXPECTED_EMPTY_TECHNIQUE_NAME_REASON, rejected.reason)
        assertEquals(expectedDetails, state.details)
    }

    @Test
    fun `next technique name preserves existing techniques their order and training note`() {
        // Given
        val details = initialDetails.copy(
            techniques = mutableListOf(
                TechniqueDraft(name = "Armbar", description = "Рычаг локтя из маунта"),
                TechniqueDraft(name = "Triangle", description = "Треугольник из гарда"),
            ),
            note = "Заметка к тренировке",
        )
        val expectedDetails = details.snapshot()
        val state = WaitingTechniqueName(details)
        val text = "Kimura"

        // When
        val result = service.handle(state, UserInput(text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(
            WaitingAddTechniqueDescription(expectedDetails, text),
            transition.nextState,
        )
        assertEquals(expectedDetails, state.details)
    }

    @Test
    fun `duplicate technique name is accepted without editing the existing technique`() {
        // Given
        val details = initialDetails.copy(
            techniques = mutableListOf(
                TechniqueDraft(name = "Armbar", description = "Рычаг локтя из маунта"),
            ),
        )
        val expectedDetails = details.snapshot()
        val state = WaitingTechniqueName(details)
        val text = "Armbar"

        // When
        val result = service.handle(state, UserInput(text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(
            WaitingAddTechniqueDescription(expectedDetails, text),
            transition.nextState,
        )
        assertEquals(expectedDetails, state.details)
    }

    @ParameterizedTest
    @ValueSource(strings = ["Да", "Нет"])
    fun `yes and no are accepted as technique names`(text: String) {
        // Given
        val expectedDetails = initialDetails.snapshot()
        val state = WaitingTechniqueName(initialDetails)

        // When
        val result = service.handle(state, UserInput(text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(
            WaitingAddTechniqueDescription(expectedDetails, text),
            transition.nextState,
        )
        assertEquals(expectedDetails, state.details)
    }
}

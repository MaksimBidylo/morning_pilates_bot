package morningpilates.training

import morningpilates.flow.model.FlowEvent.UserInput
import morningpilates.flow.model.FlowResult
import morningpilates.training.TrainingFlowState.WaitingAddNote
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TrainingFlowServiceAddTechniqueInputTest : TrainingFlowServiceTestBase() {
    companion object {
        private const val EXPECTED_INVALID_CHOICE_REASON = "Выберите «Да» или «Нет»"
    }

    private val populatedDetails =
        TrainingDetails(
            date = LocalDate.of(2026, 10, 9),
            time = LocalTime.of(19, 30),
            techniques =
                listOf(
                    TechniqueDraft(name = "Техника", description = "Описание техники"),
                ),
            note = "Заметка к тренировке",
        )

    @Test
    fun `choosing to add a technique requests its name and preserves training details`() {
        // Given
        val details =
            TrainingDetails(
                date = LocalDate.of(2026, 10, 9),
                time = LocalTime.of(19, 30),
                techniques = emptyList(),
                note = null,
            )
        val state = WaitingAddTechnique(details)
        val text = "Да"

        // When
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingTechniqueName(details), transition.nextState)
    }

    @Test
    fun `skipping techniques requests a note and preserves training details`() {
        // Given
        val details =
            TrainingDetails(
                date = LocalDate.of(2026, 10, 9),
                time = LocalTime.of(19, 30),
                techniques = emptyList(),
                note = null,
            )
        val state = WaitingAddTechnique(details)
        val text = "Нет"

        // When
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingAddNote(details), transition.nextState)
    }

    @ParameterizedTest
    @ValueSource(strings = ["да", "ДА", "дА", " Да ", " дА ", "\tДа\n"])
    fun `yes answer variants request a technique name and preserve training details`(text: String) {
        // Given
        val state = WaitingAddTechnique(populatedDetails)

        // When
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingTechniqueName(populatedDetails), transition.nextState)
    }

    @ParameterizedTest
    @ValueSource(strings = ["нет", "НЕТ", "нЕт", " нет ", "\nНет\t"])
    fun `no answer variants request a note and preserve training details`(text: String) {
        // Given
        val state = WaitingAddTechnique(populatedDetails)

        // When
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingAddNote(populatedDetails), transition.nextState)
    }

    @ParameterizedTest
    @ValueSource(strings = ["возможно", "123", "yes", "no"])
    fun `unknown answers are rejected with a choice prompt`(text: String) {
        // Given
        val state = WaitingAddTechnique(populatedDetails)

        // When
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        assertRejected(result, EXPECTED_INVALID_CHOICE_REASON)
    }

    @ParameterizedTest
    @ValueSource(strings = ["давай", "неточно", "Да, наверное", "Да Нет", "д а"])
    fun `answers must match a complete word`(text: String) {
        // Given
        val state = WaitingAddTechnique(populatedDetails)

        // When
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        assertRejected(result, EXPECTED_INVALID_CHOICE_REASON)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t", "\n", " \t\r\n "])
    fun `blank answers are rejected with a choice prompt`(text: String) {
        // Given
        val state = WaitingAddTechnique(populatedDetails)

        // When
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        assertRejected(result, EXPECTED_INVALID_CHOICE_REASON)
    }
}

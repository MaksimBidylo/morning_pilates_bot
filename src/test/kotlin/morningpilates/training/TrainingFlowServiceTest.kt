package morningpilates.training

import morningpilates.flow.FlowResult
import morningpilates.training.TrainingFlowState.WaitingAddNote
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingAddTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TrainingFlowServiceTest {

    companion object {
        private const val EXPECTED_INVALID_DATE_REASON = "Некорректная дата"
        private const val EXPECTED_INVALID_TIME_REASON = "Некорректное время"
        private const val EXPECTED_INVALID_CHOICE_REASON = "Выберите «Да» или «Нет»"
        private const val EXPECTED_EMPTY_TECHNIQUE_NAME_REASON = "Введите название техники"
    }

    private val service = TrainingFlowService()

    private fun assertRejected(result: FlowResult, expectedReason: String) {
        val rejected = assertIs<FlowResult.Rejected>(result)
        assertContains(rejected.reason, expectedReason)
    }

    @Nested
    inner class DateInput {
        @Test
        fun `valid date transitions from waiting date to waiting start time`() {
            // Given
            val state = WaitingDate
            val text = "2026-10-09"

            // When
            val result = service.handle(state, text)

            // Then
            val transition = assertIs<FlowResult.Transition>(result)
            assertEquals(
                WaitingStartTime(LocalDate.of(2026, 10, 9)),
                transition.nextState,
            )
        }

        @Test
        fun `invalid date is rejected without throwing an exception`() {
            // Given
            val state = WaitingDate
            val text = "не дата"

            // When
            val result = service.handle(state, text)

            // Then
            assertRejected(result, EXPECTED_INVALID_DATE_REASON)
        }

        @Test
        fun `nonexistent calendar date is rejected without correction`() {
            // Given
            val state = WaitingDate
            val text = "2026-02-30"

            // When
            val result = service.handle(state, text)

            // Then
            assertRejected(result, EXPECTED_INVALID_DATE_REASON)
        }
    }

    @Nested
    inner class StartTimeInput {
        @Test
        fun `valid start time transitions to adding technique and preserves date`() {
            // Given
            val date = LocalDate.of(2026, 10, 9)
            val state = WaitingStartTime(date)
            val text = "19:30"

            // When
            val result = service.handle(state, text)

            // Then
            val transition = assertIs<FlowResult.Transition>(result)
            assertEquals(
                WaitingAddTechnique(
                    TrainingDetails(
                        date = date,
                        time = LocalTime.of(19, 30),
                        techniques = emptyList(),
                        note = null,
                    ),
                ),
                transition.nextState,
            )
        }

        @Test
        fun `invalid start time is rejected without throwing an exception`() {
            // Given
            val state = WaitingStartTime(LocalDate.of(2026, 10, 9))
            val text = "не время"

            // When
            val result = service.handle(state, text)

            // Then
            assertRejected(result, EXPECTED_INVALID_TIME_REASON)
        }

        @Test
        fun `out of range hour is rejected without correction`() {
            // Given
            val state = WaitingStartTime(LocalDate.of(2026, 10, 9))
            val text = "25:00"

            // When
            val result = service.handle(state, text)

            // Then
            assertRejected(result, EXPECTED_INVALID_TIME_REASON)
        }
    }

    @Nested
    inner class AddTechniqueInput {
        private val populatedDetails = TrainingDetails(
            date = LocalDate.of(2026, 10, 9),
            time = LocalTime.of(19, 30),
            techniques = listOf(
                TechniqueDraft(name = "Техника", description = "Описание техники"),
            ),
            note = "Заметка к тренировке",
        )

        @Test
        fun `choosing to add a technique requests its name and preserves training details`() {
            // Given
            val details = TrainingDetails(
                date = LocalDate.of(2026, 10, 9),
                time = LocalTime.of(19, 30),
                techniques = emptyList(),
                note = null,
            )
            val state = WaitingAddTechnique(details)
            val text = "Да"

            // When
            val result = service.handle(state, text)

            // Then
            val transition = assertIs<FlowResult.Transition>(result)
            assertEquals(WaitingTechniqueName(details), transition.nextState)
        }

        @Test
        fun `skipping techniques requests a note and preserves training details`() {
            // Given
            val details = TrainingDetails(
                date = LocalDate.of(2026, 10, 9),
                time = LocalTime.of(19, 30),
                techniques = emptyList(),
                note = null,
            )
            val state = WaitingAddTechnique(details)
            val text = "Нет"

            // When
            val result = service.handle(state, text)

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
            val result = service.handle(state, text)

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
            val result = service.handle(state, text)

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
            val result = service.handle(state, text)

            // Then
            assertRejected(result, EXPECTED_INVALID_CHOICE_REASON)
        }

        @ParameterizedTest
        @ValueSource(strings = ["давай", "неточно", "Да, наверное", "Да Нет", "д а"])
        fun `answers must match a complete word`(text: String) {
            // Given
            val state = WaitingAddTechnique(populatedDetails)

            // When
            val result = service.handle(state, text)

            // Then
            assertRejected(result, EXPECTED_INVALID_CHOICE_REASON)
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "   ", "\t", "\n", " \t\r\n "])
        fun `blank answers are rejected with a choice prompt`(text: String) {
            // Given
            val state = WaitingAddTechnique(populatedDetails)

            // When
            val result = service.handle(state, text)

            // Then
            assertRejected(result, EXPECTED_INVALID_CHOICE_REASON)
        }
    }

    @Nested
    inner class TechniqueNameInput {
        private val initialDetails = TrainingDetails(
            date = LocalDate.of(2026, 10, 9),
            time = LocalTime.of(19, 30),
            techniques = mutableListOf(),
        )

        private fun TrainingDetails.snapshot(): TrainingDetails =
            copy(techniques = techniques.toList())

        @Test
        fun `valid technique name requests a description choice without adding a technique`() {
            // Given
            val expectedDetails = initialDetails.snapshot()
            val state = WaitingTechniqueName(initialDetails)
            val text = "Armbar from mount"

            // When
            val result = service.handle(state, text)

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
            val result = service.handle(state, text)

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
            val result = service.handle(state, text)

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
            val result = service.handle(state, text)

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
            val result = service.handle(state, text)

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
            val result = service.handle(state, text)

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
            val result = service.handle(state, text)

            // Then
            val transition = assertIs<FlowResult.Transition>(result)
            assertEquals(
                WaitingAddTechniqueDescription(expectedDetails, text),
                transition.nextState,
                )
            assertEquals(expectedDetails, state.details)
        }
    }
}

package morningpilates.training

import morningpilates.training.FakeTrainingRepository.FindByDateCall
import morningpilates.user.TelegramUserId
import org.junit.jupiter.api.Assertions.assertAll
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TrainingQueryServiceTest {
    private val repository = FakeTrainingRepository()
    private val service = TrainingQueryService(repository)
    private val user = TelegramUserId(7_000_000_123L)
    private val date = LocalDate.of(2026, 10, 12)

    @Test
    fun `date query delegates once and preserves the repository list without filtering or sorting`() {
        val expected =
            listOf(
                SavedTraining(
                    TrainingId(UUID.fromString("00000000-0000-0000-0000-000000000002")),
                    minimalTrainingDetails().copy(date = date, time = LocalTime.of(7, 0)),
                ),
                SavedTraining(
                    TrainingId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                    populatedTrainingDetails().copy(date = date.plusDays(1), time = LocalTime.of(19, 0)),
                ),
            )

        assertDelegated(Result.success(expected))
    }

    @Test
    fun `empty repository result remains successful and is still delegated once`() {
        assertDelegated(Result.success(emptyList()))
    }

    @Test
    fun `repository failure remains the same failure instead of an empty list`() {
        val error = IllegalStateException("Database unavailable")

        val actual = assertDelegated(Result.failure(error))

        assertSame(error, actual.exceptionOrNull())
    }

    private fun assertDelegated(expected: Result<List<SavedTraining>>): Result<List<SavedTraining>> {
        val call = FindByDateCall(user, date)
        repository.findByDateResults[call] = expected
        repository.findByDateResults[FindByDateCall(TelegramUserId(84L), date)] = Result.success(emptyList())
        repository.findByDateResults[FindByDateCall(user, date.minusDays(1))] = Result.success(emptyList())

        val actual = service.findByDate(user, date)

        assertAll(
            { assertEquals(listOf(call), repository.findByDateCalls) },
            { assertEquals(expected, actual) },
            { assertTrue(repository.saveCalls.isEmpty()) },
            { assertTrue(repository.findLatestCalls.isEmpty()) },
        )
        return actual
    }
}

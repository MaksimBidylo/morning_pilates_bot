package morningpilates.infrastructure.persistence

import morningpilates.infrastructure.security.TelegramIdProtector
import morningpilates.training.SavedTraining
import morningpilates.training.TechniqueDraft
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingId
import morningpilates.user.TelegramUserId
import org.jooq.ExecuteListener
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("integration")
@Testcontainers
class PostgresTrainingsByDateQueryCountIntegrationTest {
    companion object {
        private val USER_ID = TelegramUserId(7_000_000_123L)
        private val DATE = LocalDate.of(2026, 10, 12)

        @Container
        @JvmField
        val postgres = postgresTestContainer()
    }

    private val protector =
        TelegramIdProtector(
            encryptionKey = ByteArray(32) { it.toByte() },
            lookupKey = ByteArray(32) { (it + 32).toByte() },
        )

    @BeforeEach
    fun resetTestSchema() {
        postgres.resetPublicSchema()
    }

    @ParameterizedTest(name = "date query for {0} trainings uses at most two SQL executions")
    @ValueSource(ints = [0, 1, 3, 7])
    fun `SQL executions stay bounded while all trainings and techniques are returned`(trainingCount: Int) {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val setupRepository = PostgresTrainingRepository(database.dsl, protector)
            val expected = (0 until trainingCount).map(::training)
            expected.forEach { setupRepository.save(USER_ID, it.id, it.details).getOrThrow() }
            val statements = mutableListOf<String>()
            // Only the repository under measurement uses this listener: migration and setup use the original DSL.
            val measuredDsl =
                DSL.using(
                    database.dsl.configuration().derive(
                        ExecuteListener.onExecuteEnd { statements += checkNotNull(it.sql()) },
                    ),
                )
            val repository = PostgresTrainingRepository(measuredDsl, protector)

            val actual = repository.findByDate(USER_ID, DATE).getOrThrow()

            println("findByDate: trainings=$trainingCount, SQL executions=${statements.size}")
            assertAll(
                { assertEquals(expected.asReversed(), actual) },
                {
                    if (trainingCount == 0) {
                        assertEquals(1, statements.size, "An empty diary must not query techniques")
                    } else {
                        assertTrue(
                            statements.size in 1..2,
                            "$trainingCount trainings used ${statements.size} SQL executions instead of at most two",
                        )
                    }
                },
            )
        }
    }

    @Test
    fun `technique query failure returns failure instead of partial training data`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val repository = PostgresTrainingRepository(database.dsl, protector)
            val saved = training(0)
            repository.save(USER_ID, saved.id, saved.details).getOrThrow()
            // The first query can still read trainings; only technique loading fails in this isolated container.
            database.dsl.execute("ALTER TABLE training_techniques RENAME TO unavailable_techniques")

            val result = repository.findByDate(USER_ID, DATE)

            assertTrue(result.isFailure, "Technique loading failure must not produce a partial successful list")
            assertNotNull(result.exceptionOrNull())
        }
    }

    private fun training(index: Int): SavedTraining =
        SavedTraining(
            TrainingId(UUID(0L, index.toLong() + 1)),
            TrainingDetails(
                DATE,
                LocalTime.of(8 + index, 0),
                techniques =
                    if (index % 2 == 0) {
                        listOf(TechniqueDraft("Armbar", "Training $index"))
                    } else {
                        emptyList()
                    },
                note = "Training $index",
            ),
        )
}

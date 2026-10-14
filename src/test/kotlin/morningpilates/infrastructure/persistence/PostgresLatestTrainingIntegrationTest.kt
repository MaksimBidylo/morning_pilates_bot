package morningpilates.infrastructure.persistence

import morningpilates.infrastructure.security.TelegramIdProtector
import morningpilates.training.SavedTraining
import morningpilates.training.TechniqueDraft
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingId
import morningpilates.training.TrainingRepository
import morningpilates.user.TelegramUserId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("integration")
@Testcontainers
class PostgresLatestTrainingIntegrationTest {
    companion object {
        private val USER_ID = TelegramUserId(7_000_000_123L)
        private val OTHER_USER_ID = TelegramUserId(7_000_000_124L)
        private val DATE = LocalDate.of(2026, 10, 12)
        private val TIME = LocalTime.of(18, 0)

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

    @Test
    fun `empty diary returns successful absence without creating a user`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val result = repository(database.dsl).findLatest(USER_ID)

            assertNull(result.getOrThrow())
            assertEquals(0, database.dsl.fetchOne("SELECT count(*) FROM users")?.get(0, Int::class.java))
        }
    }

    @Test
    fun `latest training date wins over insertion order and start time`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val repository = repository(database.dsl)
            val expected =
                SavedTraining(
                    TrainingId(UUID.randomUUID()),
                    TrainingDetails(LocalDate.of(2026, 10, 14), LocalTime.of(9, 0)),
                )
            val trainings =
                listOf(
                    SavedTraining(TrainingId(UUID.randomUUID()), TrainingDetails(DATE, TIME)),
                    expected,
                    SavedTraining(
                        TrainingId(UUID.randomUUID()),
                        TrainingDetails(LocalDate.of(2026, 10, 10), LocalTime.of(20, 0)),
                    ),
                )
            trainings.forEach { repository.save(USER_ID, it.id, it.details).getOrThrow() }

            assertEquals(expected, repository.findLatest(USER_ID).getOrThrow())
        }
    }

    @Test
    fun `latest start time wins within the same date`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val repository = repository(database.dsl)
            val expected = SavedTraining(TrainingId(UUID.randomUUID()), TrainingDetails(DATE, LocalTime.of(19, 0)))
            val trainings =
                listOf(
                    SavedTraining(TrainingId(UUID.randomUUID()), TrainingDetails(DATE, LocalTime.of(9, 0))),
                    expected,
                    SavedTraining(TrainingId(UUID.randomUUID()), TrainingDetails(DATE, LocalTime.of(14, 0))),
                )
            trainings.forEach { repository.save(USER_ID, it.id, it.details).getOrThrow() }

            assertEquals(expected, repository.findLatest(USER_ID).getOrThrow())
        }
    }

    @Test
    fun `later created at wins when training date and start time are equal`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val repository = repository(database.dsl)
            val newerId = TrainingId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
            val olderId = TrainingId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
            val expected = SavedTraining(newerId, TrainingDetails(DATE, TIME, note = "Позже добавленная тренировка"))
            repository.save(USER_ID, expected.id, expected.details).getOrThrow()
            repository.save(USER_ID, olderId, TrainingDetails(DATE, TIME, note = "Ранее добавленная")).getOrThrow()
            val trainings = DSL.table(DSL.name("trainings"))
            val createdAt = DSL.field(DSL.name("created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
            val id = DSL.field(DSL.name("id"), SQLDataType.UUID)
            assertEquals(
                1,
                database.dsl
                    .update(trainings)
                    .set(createdAt, OffsetDateTime.parse("2026-10-14T10:00:00Z"))
                    .where(id.eq(newerId.value))
                    .execute(),
            )
            assertEquals(
                1,
                database.dsl
                    .update(trainings)
                    .set(createdAt, OffsetDateTime.parse("2026-10-14T09:00:00Z"))
                    .where(id.eq(olderId.value))
                    .execute(),
            )

            assertEquals(expected, repository.findLatest(USER_ID).getOrThrow())
        }
    }

    @Test
    fun `latest training belongs only to the requested user`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val repository = repository(database.dsl)
            val expected =
                SavedTraining(
                    TrainingId(UUID.randomUUID()),
                    TrainingDetails(LocalDate.of(2026, 10, 10), TIME, note = "Пользователь A"),
                )
            val otherDetails =
                TrainingDetails(
                    LocalDate.of(2026, 10, 14),
                    TIME,
                    listOf(TechniqueDraft("Чужая техника", "Чужое описание")),
                    "Пользователь B",
                )
            repository.save(USER_ID, expected.id, expected.details).getOrThrow()
            repository.save(OTHER_USER_ID, TrainingId(UUID.randomUUID()), otherDetails).getOrThrow()

            assertEquals(expected, repository.findLatest(USER_ID).getOrThrow())
        }
    }

    @Test
    fun `latest training returns all details and techniques ordered by position`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val repository = repository(database.dsl)
            val trainingId = TrainingId(UUID.randomUUID())
            val expected =
                TrainingDetails(
                    DATE,
                    TIME,
                    listOf(TechniqueDraft("Triangle", "Треугольник из гарда"), TechniqueDraft("Armbar")),
                    "Отрабатывали выход из сайд-контроля",
                )
            repository.save(USER_ID, trainingId, expected.copy(techniques = emptyList())).getOrThrow()
            // Insert in reverse order so reading must use position, not insertion order or name.
            expected.techniques.withIndex().toList().asReversed().forEach { (index, technique) ->
                database.dsl.execute(
                    "INSERT INTO training_techniques (id, training_id, position, name, description) " +
                        "VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    trainingId.value,
                    index + 1,
                    technique.name,
                    technique.description,
                )
            }

            val actual: SavedTraining = assertNotNull(repository.findLatest(USER_ID).getOrThrow())
            val storedId =
                assertNotNull(
                    database.dsl
                        .fetchOne(
                            "SELECT t.id FROM trainings t JOIN users u ON u.id = t.user_id " +
                                "WHERE u.telegram_id_lookup = ?",
                            protector.lookup(USER_ID.value),
                        )?.get("id", UUID::class.java),
                )
            assertEquals(TrainingId(storedId), actual.id)
            assertEquals(expected, actual.details)
        }
    }

    @Test
    fun `latest training without techniques and note preserves empty optional data`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val repository = repository(database.dsl)
            val expected = SavedTraining(TrainingId(UUID.randomUUID()), TrainingDetails(DATE, TIME))
            repository.save(USER_ID, expected.id, expected.details).getOrThrow()

            assertEquals(expected, repository.findLatest(USER_ID).getOrThrow())
        }
    }

    @Test
    fun `PostgreSQL query failure is returned as failure instead of throwing or returning absence`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val repository = repository(database.dsl)
            repository.save(USER_ID, TrainingId(UUID.randomUUID()), TrainingDetails(DATE, TIME)).getOrThrow()
            // Only this isolated Testcontainers schema is changed to provoke an actual SQL error.
            database.dsl.execute("ALTER TABLE trainings RENAME TO unavailable_trainings")

            val result = repository.findLatest(USER_ID)

            assertTrue(result.isFailure)
            assertNotNull(result.exceptionOrNull())
        }
    }

    private fun repository(dsl: DSLContext): TrainingRepository = PostgresTrainingRepository(dsl, protector)
}

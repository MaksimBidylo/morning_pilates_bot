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
import org.junit.jupiter.api.Assertions.assertAll
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
import kotlin.test.assertTrue

@Tag("integration")
@Testcontainers
class PostgresTrainingsByDateIntegrationTest {
    companion object {
        private val USER_ID = TelegramUserId(7_000_000_123L)
        private val OTHER_USER_ID = TelegramUserId(7_000_000_124L)
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

    @Test
    fun `unknown user gets successful empty list without creating a user`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val before = snapshot(database.dsl)

            val result = repository(database.dsl).findByDate(USER_ID, DATE)

            assertAll(
                { assertEquals(Result.success(emptyList()), result) },
                { assertEquals(0, database.dsl.fetchSingle("SELECT count(*) FROM users").get(0, Int::class.java)) },
                { assertEquals(before, snapshot(database.dsl)) },
            )
        }
    }

    @Test
    fun `existing user gets empty list for a date outside their diary without changing stored data`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val repository = repository(database.dsl)
            val saved = training("00000000-0000-0000-0000-000000000001", LocalTime.of(7, 0))
            repository.save(USER_ID, saved.id, saved.details.copy(date = DATE.minusDays(1))).getOrThrow()
            val before = snapshot(database.dsl)

            val result = repository.findByDate(USER_ID, DATE)

            assertAll(
                { assertEquals(Result.success(emptyList()), result) },
                { assertEquals(before, snapshot(database.dsl)) },
            )
        }
    }

    @Test
    fun `query returns all and only matching full trainings with start time taking priority over creation time`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val repository = repository(database.dsl)
            val evening =
                training("00000000-0000-0000-0000-000000000002", LocalTime.of(19, 0)).copy(
                    details =
                        TrainingDetails(
                            DATE,
                            LocalTime.of(19, 0),
                            listOf(
                                TechniqueDraft("Triangle", "Треугольник из гарда"),
                                TechniqueDraft("Armbar"),
                                TechniqueDraft("Triangle", "Другая вариация треугольника"),
                            ),
                            "Вечерняя тренировка",
                        ),
                )
            val noon = training("00000000-0000-0000-0000-000000000001", LocalTime.of(12, 0))
            val morning =
                training("00000000-0000-0000-0000-000000000003", LocalTime.of(7, 0)).copy(
                    details =
                        TrainingDetails(
                            DATE,
                            LocalTime.of(7, 0),
                            listOf(TechniqueDraft("Armbar", "Рычаг из маунта")),
                            "Утренняя тренировка",
                        ),
                )
            val yesterday =
                training("00000000-0000-0000-0000-000000000004", LocalTime.of(23, 0)).let {
                    it.copy(details = it.details.copy(date = DATE.minusDays(1)))
                }
            val tomorrow =
                training("00000000-0000-0000-0000-000000000005", LocalTime.of(23, 0)).let {
                    it.copy(details = it.details.copy(date = DATE.plusDays(1)))
                }
            val foreign =
                training("00000000-0000-0000-0000-000000000006", LocalTime.of(23, 0)).let {
                    it.copy(details = it.details.copy(techniques = listOf(TechniqueDraft("Triangle", "Чужая техника"))))
                }
            saveWithReversedTechniques(database.dsl, repository, USER_ID, noon)
            saveWithReversedTechniques(database.dsl, repository, OTHER_USER_ID, foreign)
            saveWithReversedTechniques(database.dsl, repository, USER_ID, yesterday)
            saveWithReversedTechniques(database.dsl, repository, USER_ID, evening)
            saveWithReversedTechniques(database.dsl, repository, USER_ID, tomorrow)
            saveWithReversedTechniques(database.dsl, repository, USER_ID, morning)
            setCreatedAt(database.dsl, evening.id, "2026-10-14T09:00:00Z")
            setCreatedAt(database.dsl, noon.id, "2026-10-14T10:00:00Z")
            setCreatedAt(database.dsl, morning.id, "2026-10-14T11:00:00Z")
            val before = snapshot(database.dsl)

            val actual = repository.findByDate(USER_ID, DATE).getOrThrow()

            assertAll(
                { assertEquals(listOf(evening, noon, morning), actual) },
                { assertEquals(before, snapshot(database.dsl)) },
            )
        }
    }

    @Test
    fun `equal start times are ordered by created at rather than UUID or insertion order`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val repository = repository(database.dsl)
            val time = LocalTime.of(18, 0)
            val newest = training("00000000-0000-0000-0000-000000000002", time)
            val middle = training("00000000-0000-0000-0000-000000000001", time)
            val oldest = training("00000000-0000-0000-0000-000000000003", time)
            listOf(oldest, newest, middle).forEach { repository.save(USER_ID, it.id, it.details).getOrThrow() }
            setCreatedAt(database.dsl, newest.id, "2026-10-14T11:00:00Z")
            setCreatedAt(database.dsl, middle.id, "2026-10-14T10:00:00Z")
            setCreatedAt(database.dsl, oldest.id, "2026-10-14T09:00:00Z")

            val actual = repository.findByDate(USER_ID, DATE).getOrThrow()

            assertEquals(listOf(newest, middle, oldest), actual)
        }
    }

    @Test
    fun `SQL error is returned as failure instead of escaping or becoming an empty list`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val repository = repository(database.dsl)
            val saved = training("00000000-0000-0000-0000-000000000001", LocalTime.of(7, 0))
            repository.save(USER_ID, saved.id, saved.details).getOrThrow()
            // Only the isolated Testcontainers schema is changed to provoke a real SQL error.
            database.dsl.execute("ALTER TABLE trainings RENAME TO unavailable_trainings")

            val result = repository.findByDate(USER_ID, DATE)

            assertTrue(result.isFailure, "A PostgreSQL read failure must not become a successful empty list")
            assertNotNull(result.exceptionOrNull())
        }
    }

    private fun repository(dsl: DSLContext): TrainingRepository = PostgresTrainingRepository(dsl, protector)

    private fun training(
        id: String,
        time: LocalTime,
    ): SavedTraining = SavedTraining(TrainingId(UUID.fromString(id)), TrainingDetails(DATE, time))

    private fun saveWithReversedTechniques(
        dsl: DSLContext,
        repository: TrainingRepository,
        user: TelegramUserId,
        training: SavedTraining,
    ) {
        repository.save(user, training.id, training.details.copy(techniques = emptyList())).getOrThrow()
        // Neither insertion order nor alphabetical name order may replace position.
        training.details.techniques.withIndex().toList().asReversed().forEach { (index, technique) ->
            dsl.execute(
                "INSERT INTO training_techniques (id, training_id, position, name, description) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                training.id.value,
                index + 1,
                technique.name,
                technique.description,
            )
        }
    }

    private fun setCreatedAt(
        dsl: DSLContext,
        trainingId: TrainingId,
        timestamp: String,
    ) {
        assertEquals(
            1,
            dsl
                .update(DSL.table(DSL.name("trainings")))
                .set(
                    DSL.field(DSL.name("created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE),
                    OffsetDateTime.parse(timestamp),
                ).where(DSL.field(DSL.name("id"), SQLDataType.UUID).eq(trainingId.value))
                .execute(),
        )
    }

    private fun snapshot(dsl: DSLContext): List<List<Map<String, Any?>>> =
        listOf(
            dsl
                .fetch(
                    "SELECT id, encode(telegram_id_encrypted, 'hex') AS encrypted, " +
                        "encode(telegram_id_lookup, 'hex') AS lookup, is_admin FROM users ORDER BY id",
                ).intoMaps(),
            dsl.fetch("SELECT * FROM trainings ORDER BY id").intoMaps(),
            dsl.fetch("SELECT * FROM training_techniques ORDER BY id").intoMaps(),
        )
}

package morningpilates.infrastructure.persistence

import morningpilates.infrastructure.security.TelegramIdProtector
import morningpilates.training.TechniqueDraft
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingId
import morningpilates.training.TrainingRepository
import morningpilates.user.TelegramUserId
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("integration")
@Testcontainers
class PostgresTrainingRepositoryIntegrationTest {
    companion object {
        private val TELEGRAM_USER_ID = TelegramUserId(7_000_000_123L)
        private val OTHER_TELEGRAM_USER_ID = TelegramUserId(7_000_000_124L)
        private val FULL_DETAILS =
            TrainingDetails(
                date = LocalDate.of(2026, 10, 11),
                time = LocalTime.of(8, 30),
                techniques =
                    listOf(
                        TechniqueDraft("Сотня"),
                        TechniqueDraft("Скручивание", "Медленно, с контролем дыхания"),
                    ),
                note = "Утренняя тренировка",
            )

        @Container
        @JvmField
        val postgres = postgresTestContainer()

        @JvmStatic
        fun conflictingDetails(): List<Arguments> =
            listOf(
                Arguments.of("date", FULL_DETAILS.copy(date = FULL_DETAILS.date.plusDays(1))),
                Arguments.of("time", FULL_DETAILS.copy(time = FULL_DETAILS.time.plusMinutes(1))),
                Arguments.of("note", FULL_DETAILS.copy(note = null)),
                Arguments.of(
                    "technique name",
                    FULL_DETAILS.copy(
                        techniques =
                            listOf(
                                FULL_DETAILS.techniques.first().copy(name = "Другая техника"),
                                FULL_DETAILS.techniques.last(),
                            ),
                    ),
                ),
                Arguments.of(
                    "technique description",
                    FULL_DETAILS.copy(
                        techniques =
                            listOf(
                                FULL_DETAILS.techniques.first().copy(description = "Новое описание"),
                                FULL_DETAILS.techniques.last(),
                            ),
                    ),
                ),
                Arguments.of(
                    "technique order",
                    FULL_DETAILS.copy(techniques = FULL_DETAILS.techniques.reversed()),
                ),
                Arguments.of(
                    "technique count",
                    FULL_DETAILS.copy(techniques = FULL_DETAILS.techniques.dropLast(1)),
                ),
            )
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
    fun `first training creates a protected user and stores all details with ordered techniques`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val trainingId = TrainingId(UUID.randomUUID())

            repository(database.dsl).save(TELEGRAM_USER_ID, trainingId, FULL_DETAILS).getOrThrow()

            val userId = assertStoredUser(database.dsl, TELEGRAM_USER_ID)
            assertStoredTraining(database.dsl, trainingId, userId, FULL_DETAILS)
            assertCounts(database.dsl, users = 1, trainings = 1, techniques = 2)
        }
    }

    @Test
    fun `existing user can save two training ids with identical dates times and details`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val userId = PostgresUserRepository(database.dsl, protector).getOrCreate(TELEGRAM_USER_ID)
            val firstTrainingId = TrainingId(UUID.randomUUID())
            val secondTrainingId = TrainingId(UUID.randomUUID())

            repository(database.dsl).save(TELEGRAM_USER_ID, firstTrainingId, FULL_DETAILS).getOrThrow()
            repository(database.dsl).save(TELEGRAM_USER_ID, secondTrainingId, FULL_DETAILS).getOrThrow()

            assertEquals(userId, assertStoredUser(database.dsl, TELEGRAM_USER_ID))
            assertStoredTraining(database.dsl, firstTrainingId, userId, FULL_DETAILS)
            assertStoredTraining(database.dsl, secondTrainingId, userId, FULL_DETAILS)
            assertCounts(database.dsl, users = 1, trainings = 2, techniques = 4)
        }
    }

    @Test
    fun `training without techniques and note can be saved and repeated without duplicates`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val trainingId = TrainingId(UUID.randomUUID())
            val details = FULL_DETAILS.copy(techniques = emptyList(), note = null)

            repository(database.dsl).save(TELEGRAM_USER_ID, trainingId, details).getOrThrow()
            val originalRows = snapshot(database.dsl)
            repository(database.dsl).save(TELEGRAM_USER_ID, trainingId, details).getOrThrow()

            val userId = assertStoredUser(database.dsl, TELEGRAM_USER_ID)
            assertStoredTraining(database.dsl, trainingId, userId, details)
            assertEquals(originalRows, snapshot(database.dsl))
            assertCounts(database.dsl, users = 1, trainings = 1, techniques = 0)
        }
    }

    @Test
    fun `failure inserting second technique rolls back user training and first technique`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            database.dsl.execute(
                "ALTER TABLE training_techniques " +
                    "ADD CONSTRAINT reject_second_technique CHECK (position <> 2)",
            )

            val result =
                repository(database.dsl).save(TELEGRAM_USER_ID, TrainingId(UUID.randomUUID()), FULL_DETAILS)

            val failure = assertIs<DataAccessException>(result.exceptionOrNull())
            assertEquals("23514", failure.sqlState())
            assertCounts(database.dsl, users = 0, trainings = 0, techniques = 0)
        }
    }

    @Test
    fun `repeating a complete training through a new repository preserves all stored rows and ids`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val trainingId = TrainingId(UUID.randomUUID())
            repository(database.dsl).save(TELEGRAM_USER_ID, trainingId, FULL_DETAILS).getOrThrow()
            val originalRows = snapshot(database.dsl)

            repository(database.dsl).save(TELEGRAM_USER_ID, trainingId, FULL_DETAILS).getOrThrow()

            assertEquals(originalRows, snapshot(database.dsl))
            assertCounts(database.dsl, users = 1, trainings = 1, techniques = 2)
        }
    }

    @Test
    fun `same training id with another user fails and rolls back creation of the other user`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val trainingId = TrainingId(UUID.randomUUID())
            repository(database.dsl).save(TELEGRAM_USER_ID, trainingId, FULL_DETAILS).getOrThrow()
            val originalRows = snapshot(database.dsl)

            val result = repository(database.dsl).save(OTHER_TELEGRAM_USER_ID, trainingId, FULL_DETAILS)

            assertTrue(result.isFailure)
            assertEquals(originalRows, snapshot(database.dsl))
            assertCounts(database.dsl, users = 1, trainings = 1, techniques = 2)
        }
    }

    @ParameterizedTest(name = "same training id with different {0} fails without changes")
    @MethodSource("conflictingDetails")
    fun `same training id with different data fails and preserves the original training`(
        scenario: String,
        conflictingDetails: TrainingDetails,
    ) {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val trainingId = TrainingId(UUID.randomUUID())
            repository(database.dsl).save(TELEGRAM_USER_ID, trainingId, FULL_DETAILS).getOrThrow()
            val originalRows = snapshot(database.dsl)

            val result = repository(database.dsl).save(TELEGRAM_USER_ID, trainingId, conflictingDetails)

            assertTrue(result.isFailure, "Expected a conflict for $scenario")
            assertEquals(originalRows, snapshot(database.dsl))
            assertCounts(database.dsl, users = 1, trainings = 1, techniques = 2)
        }
    }

    private fun repository(dsl: DSLContext): TrainingRepository = PostgresTrainingRepository(dsl, protector)

    private fun assertStoredUser(
        dsl: DSLContext,
        telegramUserId: TelegramUserId,
    ): UUID {
        val user =
            dsl.fetchSingle(
                "SELECT id, telegram_id_encrypted, telegram_id_lookup FROM users WHERE telegram_id_lookup = ?",
                protector.lookup(telegramUserId.value),
            )
        val userId = assertNotNull(user.get("id", UUID::class.java))
        val encryptedId = assertNotNull(user.get("telegram_id_encrypted", ByteArray::class.java))
        assertEquals(telegramUserId.value, protector.decrypt(encryptedId))
        assertContentEquals(
            protector.lookup(telegramUserId.value),
            user.get("telegram_id_lookup", ByteArray::class.java),
        )
        return userId
    }

    private fun assertStoredTraining(
        dsl: DSLContext,
        trainingId: TrainingId,
        userId: UUID,
        details: TrainingDetails,
    ) {
        val training =
            dsl.fetchSingle(
                "SELECT id, user_id, training_date, start_time, note FROM trainings WHERE id = ?",
                trainingId.value,
            )
        assertEquals(trainingId.value, training.get("id", UUID::class.java))
        assertEquals(userId, training.get("user_id", UUID::class.java))
        assertEquals(details.date, training.get("training_date", LocalDate::class.java))
        assertEquals(details.time, training.get("start_time", LocalTime::class.java))
        assertEquals(details.note, training.get("note", String::class.java))

        val techniques =
            dsl.fetch(
                "SELECT id, training_id, position, name, description FROM training_techniques " +
                    "WHERE training_id = ? ORDER BY position",
                trainingId.value,
            )
        assertEquals(details.techniques.size, techniques.size)
        assertEquals(details.techniques.indices.map { it + 1 }, techniques.map { it.get("position", Int::class.java) })
        assertEquals(
            details.techniques,
            techniques.map {
                TechniqueDraft(
                    name = assertNotNull(it.get("name", String::class.java)),
                    description = it.get("description", String::class.java),
                )
            },
        )
        techniques.forEach {
            assertEquals(trainingId.value, it.get("training_id", UUID::class.java))
            assertNotNull(it.get("id", UUID::class.java))
        }
    }

    private fun assertCounts(
        dsl: DSLContext,
        users: Int,
        trainings: Int,
        techniques: Int,
    ) {
        assertEquals(users, dsl.fetchSingle("SELECT count(*) FROM users").get(0, Int::class.java))
        assertEquals(trainings, dsl.fetchSingle("SELECT count(*) FROM trainings").get(0, Int::class.java))
        assertEquals(techniques, dsl.fetchSingle("SELECT count(*) FROM training_techniques").get(0, Int::class.java))
    }

    private fun snapshot(dsl: DSLContext): List<List<Map<String, Any?>>> =
        listOf(
            dsl
                .fetch(
                    "SELECT id, encode(telegram_id_encrypted, 'hex') AS encrypted, " +
                        "encode(telegram_id_lookup, 'hex') AS lookup, is_admin FROM users ORDER BY id",
                ).intoMaps(),
            dsl
                .fetch("SELECT id, user_id, training_date, start_time, note FROM trainings ORDER BY id")
                .intoMaps(),
            dsl
                .fetch(
                    "SELECT id, training_id, position, name, description FROM training_techniques ORDER BY id",
                ).intoMaps(),
        )
}

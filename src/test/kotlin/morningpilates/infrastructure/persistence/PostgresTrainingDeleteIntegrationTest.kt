package morningpilates.infrastructure.persistence

import morningpilates.infrastructure.security.TelegramIdProtector
import morningpilates.training.TechniqueDraft
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingId
import morningpilates.training.TrainingRepository
import morningpilates.user.TelegramUserId
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
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
class PostgresTrainingDeleteIntegrationTest {
    companion object {
        private val USER_A = TelegramUserId(7_000_000_123L)
        private val USER_B = TelegramUserId(7_000_000_124L)
        private val UNKNOWN_USER = TelegramUserId(7_000_000_125L)
        private val TARGET = TrainingId(UUID(92L, 1L))
        private val OTHER_A = TrainingId(UUID(92L, 2L))
        private val OTHER_B = TrainingId(UUID(92L, 3L))
        private val MISSING = TrainingId(UUID(92L, 4L))
        private val TARGET_DETAILS =
            TrainingDetails(
                date = LocalDate.of(2026, 10, 11),
                time = LocalTime.of(8, 30),
                techniques = listOf(TechniqueDraft("Triangle", "Контроль руки"), TechniqueDraft("Armbar")),
                note = "Выбранная тренировка A",
            )

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
    fun `deletes only the selected owned training and its techniques preserving identical date and time records`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val repository = repository(database.dsl)
            seedThreeTrainings(repository)
            val before = committedSnapshot()
            assertEquals(setOf(TARGET.value, OTHER_A.value, OTHER_B.value), before.trainings.map { it["id"] }.toSet())
            assertEquals(2, before.techniques.count { it["training_id"] == TARGET.value })

            val result = repository.delete(USER_A, TARGET)

            assertAll(
                { assertEquals(Result.success(true), result) },
                { assertEquals(before.withoutTraining(TARGET), committedSnapshot()) },
            )
        }
    }

    @Test
    fun `deletes a sole training without techniques and keeps its protected user unchanged`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val repository = repository(database.dsl)
            repository.save(USER_A, TARGET, TARGET_DETAILS.copy(techniques = emptyList())).getOrThrow()
            assertProtectedUser(database.dsl, USER_A)
            val before = committedSnapshot()
            assertEquals(1, before.users.size)
            assertEquals(listOf(TARGET.value), before.trainings.map { it["id"] })
            assertTrue(before.techniques.isEmpty())

            val result = repository.delete(USER_A, TARGET)

            assertAll(
                { assertEquals(Result.success(true), result) },
                { assertEquals(before.copy(trainings = emptyList()), committedSnapshot()) },
            )
        }
    }

    @Test
    fun `a foreign training id returns false without removing its techniques or changing either user`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val repository = repository(database.dsl)
            seedThreeTrainings(repository)
            val before = committedSnapshot()
            assertTrue(before.techniques.any { it["training_id"] == OTHER_B.value })

            val result = repository.delete(USER_A, OTHER_B)

            assertEquals(Result.success(false), result)
            assertEquals(before, committedSnapshot())
        }
    }

    @ParameterizedTest
    @EnumSource(AbsentOwnedTraining::class)
    fun `missing owned training does not create users or change data`(scenario: AbsentOwnedTraining) {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val repository = repository(database.dsl)
            seedThreeTrainings(repository)
            val before = committedSnapshot()
            val user = if (scenario == AbsentOwnedTraining.MISSING_ID) USER_A else UNKNOWN_USER
            val id = if (scenario == AbsentOwnedTraining.MISSING_ID) MISSING else OTHER_B

            val result = repository.delete(user, id)

            assertEquals(Result.success(false), result)
            assertEquals(before, committedSnapshot())
        }
    }

    @Test
    fun `repeated deletion returns false and preserves the other training of the same user`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val repository = repository(database.dsl)
            seedThreeTrainings(repository)
            val expected = committedSnapshot().withoutTraining(TARGET)

            assertEquals(Result.success(true), repository.delete(USER_A, TARGET))
            assertEquals(expected, committedSnapshot())

            val repeated = repository(database.dsl).delete(USER_A, TARGET)

            assertEquals(Result.success(false), repeated)
            assertEquals(expected, committedSnapshot())
        }
    }

    @Test
    fun `parent delete failure is returned and rolls back all technique deletion`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val repository = repository(database.dsl)
            seedThreeTrainings(repository)
            rejectTargetParentDeletion(database.dsl)
            val before = committedSnapshot()

            val result = repository.delete(USER_A, TARGET)
            // Read committed rows using a separate connection after delete has returned.
            val after = committedSnapshot()

            assertAll(
                { assertTrue(result.isFailure, "A parent DELETE failure must not become success(false)") },
                {
                    val failure = assertIs<DataAccessException>(result.exceptionOrNull())
                    assertEquals("P0001", failure.sqlState())
                    assertTrue(failure.message.orEmpty().contains("Controlled training delete failure"))
                },
                { assertEquals(before, after) },
            )
        }
    }

    private fun repository(dsl: DSLContext): TrainingRepository = PostgresTrainingRepository(dsl, protector)

    private fun seedThreeTrainings(repository: TrainingRepository) {
        repository.save(USER_A, TARGET, TARGET_DETAILS).getOrThrow()
        repository
            .save(
                USER_A,
                OTHER_A,
                TARGET_DETAILS.copy(
                    techniques = listOf(TechniqueDraft("Другая техника A", "Не удалять")),
                    note = "Другая тренировка A с теми же датой и временем",
                ),
            ).getOrThrow()
        repository
            .save(
                USER_B,
                OTHER_B,
                TARGET_DETAILS.copy(
                    techniques = listOf(TechniqueDraft("Техника B", "Чужие данные")),
                    note = "Тренировка B",
                ),
            ).getOrThrow()
        postgres.createConnection("").use { connection ->
            val dsl = DSL.using(connection, SQLDialect.POSTGRES)
            assertProtectedUser(dsl, USER_A)
            assertProtectedUser(dsl, USER_B)
            dsl.execute("UPDATE users SET is_admin = TRUE WHERE telegram_id_lookup = ?", protector.lookup(USER_A.value))
        }
    }

    private fun assertProtectedUser(
        dsl: DSLContext,
        telegramUserId: TelegramUserId,
    ) {
        val row =
            dsl.fetchSingle(
                "SELECT telegram_id_encrypted, telegram_id_lookup FROM users WHERE telegram_id_lookup = ?",
                protector.lookup(telegramUserId.value),
            )
        assertEquals(
            telegramUserId.value,
            protector.decrypt(assertNotNull(row.get("telegram_id_encrypted", ByteArray::class.java))),
        )
        assertContentEquals(
            protector.lookup(telegramUserId.value),
            row.get("telegram_id_lookup", ByteArray::class.java),
        )
    }

    private fun rejectTargetParentDeletion(dsl: DSLContext) {
        dsl.execute(
            """
            CREATE FUNCTION reject_target_training_delete() RETURNS trigger AS $$
            BEGIN
                IF OLD.id = '${TARGET.value}'::uuid THEN
                    RAISE EXCEPTION 'Controlled training delete failure' USING ERRCODE = 'P0001';
                END IF;
                RETURN OLD;
            END;
            $$ LANGUAGE plpgsql
            """.trimIndent(),
        )
        dsl.execute(
            "CREATE TRIGGER reject_target_training_delete BEFORE DELETE ON trainings " +
                "FOR EACH ROW EXECUTE FUNCTION reject_target_training_delete()",
        )
    }

    private data class DatabaseSnapshot(
        val users: List<Map<String, Any?>>,
        val trainings: List<Map<String, Any?>>,
        val techniques: List<Map<String, Any?>>,
    ) {
        fun withoutTraining(trainingId: TrainingId): DatabaseSnapshot =
            copy(
                trainings = trainings.filterNot { it["id"] == trainingId.value },
                techniques = techniques.filterNot { it["training_id"] == trainingId.value },
            )
    }

    private fun committedSnapshot(): DatabaseSnapshot =
        postgres.createConnection("").use { connection ->
            val dsl = DSL.using(connection, SQLDialect.POSTGRES)
            DatabaseSnapshot(
                users =
                    dsl
                        .fetch(
                            "SELECT id, encode(telegram_id_encrypted, 'hex') AS encrypted, " +
                                "encode(telegram_id_lookup, 'hex') AS lookup, is_admin FROM users ORDER BY id",
                        ).intoMaps(),
                trainings =
                    dsl
                        .fetch(
                            "SELECT id, user_id, training_date, start_time, note, created_at " +
                                "FROM trainings ORDER BY id",
                        ).intoMaps(),
                techniques =
                    dsl
                        .fetch(
                            "SELECT id, training_id, position, name, description FROM training_techniques ORDER BY id",
                        ).intoMaps(),
            )
        }

    enum class AbsentOwnedTraining { MISSING_ID, UNKNOWN_USER }
}

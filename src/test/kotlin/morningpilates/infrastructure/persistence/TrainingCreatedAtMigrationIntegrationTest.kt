package morningpilates.infrastructure.persistence

import morningpilates.infrastructure.security.TelegramIdProtector
import morningpilates.training.TechniqueDraft
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingId
import morningpilates.user.TelegramUserId
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
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

@Tag("integration")
@Testcontainers
class TrainingCreatedAtMigrationIntegrationTest {
    companion object {
        private val USER_ID = TelegramUserId(7_000_000_123L)
        private val DETAILS =
            TrainingDetails(
                LocalDate.of(2026, 10, 12),
                LocalTime.of(6, 30),
                listOf(TechniqueDraft("Armbar", "Рычаг локтя из маунта"), TechniqueDraft("Triangle")),
                "Заметка до миграции",
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
    fun `created at is required timestamptz and new training defaults to database transaction time`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val column =
                assertNotNull(
                    database.dsl.fetchOne(
                        "SELECT data_type, is_nullable FROM information_schema.columns " +
                            "WHERE table_schema = 'public' AND table_name = 'trainings' AND column_name = 'created_at'",
                    ),
                    "New migration must add trainings.created_at",
                )
            assertEquals("timestamp with time zone", column.get("data_type", String::class.java))
            assertEquals("NO", column.get("is_nullable", String::class.java))
            database.dsl.transaction { configuration ->
                val dsl = DSL.using(configuration)
                val trainingId = TrainingId(UUID.randomUUID())
                repository(dsl).save(USER_ID, trainingId, DETAILS).getOrThrow()

                val createdAt =
                    assertNotNull(
                        dsl
                            .fetchOne("SELECT created_at FROM trainings WHERE id = ?", trainingId.value)
                            ?.get("created_at", OffsetDateTime::class.java),
                    )
                val databaseTime =
                    assertNotNull(dsl.fetchOne("SELECT now()")?.get(0, OffsetDateTime::class.java))
                assertEquals(databaseTime.toInstant(), createdAt.toInstant())
            }
        }
    }

    @Test
    fun `migration from V2 preserves existing users trainings and ordered techniques and backfills created at`() {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .target("2")
            .load()
            .migrate()
        val trainingId = TrainingId(UUID.randomUUID())
        val before =
            postgres.createConnection("").use { connection ->
                val dsl = DSL.using(connection, SQLDialect.POSTGRES)
                repository(dsl).save(USER_ID, trainingId, DETAILS).getOrThrow()
                snapshot(dsl)
            }

        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            assertEquals(before, snapshot(database.dsl))
            assertEquals(
                true,
                database.dsl
                    .fetchOne("SELECT created_at IS NOT NULL FROM trainings WHERE id = ?", trainingId.value)
                    ?.get(0, Boolean::class.java),
                "Existing training must receive a creation timestamp during migration",
            )
        }
    }

    private fun repository(dsl: DSLContext) = PostgresTrainingRepository(dsl, protector)

    private fun snapshot(dsl: DSLContext): List<String> =
        listOf(
            dsl.fetch("SELECT id, is_admin FROM users ORDER BY id").formatJSON(),
            dsl.fetch("SELECT id, user_id, training_date, start_time, note FROM trainings ORDER BY id").formatJSON(),
            dsl
                .fetch(
                    "SELECT id, training_id, position, name, description FROM training_techniques " +
                        "ORDER BY training_id, position",
                ).formatJSON(),
        )
}

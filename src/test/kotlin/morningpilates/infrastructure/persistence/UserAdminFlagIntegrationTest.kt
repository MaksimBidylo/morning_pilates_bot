package morningpilates.infrastructure.persistence

import morningpilates.infrastructure.security.TelegramIdProtector
import morningpilates.user.UserRepository
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("integration")
@Testcontainers
class UserAdminFlagIntegrationTest {
    companion object {
        private const val TELEGRAM_USER_ID = 7_000_000_123L

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
    fun `new user is not an administrator by default`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val userId = repository(database.dsl).getOrCreate(TELEGRAM_USER_ID)

            assertFalse(adminFlag(database.dsl, userId))
        }
    }

    @Test
    fun `V2 migration gives an existing V1 user a false admin flag`() {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .target("1")
            .failOnMissingLocations(true)
            .load()
            .migrate()

        val existingUserId =
            postgres.createConnection("").use { connection ->
                val dsl = DSL.using(connection, SQLDialect.POSTGRES)
                assertEquals(
                    listOf("1"),
                    dsl
                        .fetch("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                        .map { it.get("version", String::class.java) },
                )
                repository(dsl).getOrCreate(TELEGRAM_USER_ID)
            }

        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            assertEquals(
                "boolean",
                database.dsl
                    .fetchOne(
                        "SELECT data_type FROM information_schema.columns " +
                            "WHERE table_schema = ? AND table_name = ? AND column_name = ?",
                        "public",
                        "users",
                        "is_admin",
                    )?.get("data_type", String::class.java),
                "users.is_admin must have BOOLEAN type after upgrading from V1",
            )
            assertFalse(adminFlag(database.dsl, existingUserId))
            assertEquals(
                1,
                database.dsl
                    .fetchOne("SELECT count(*) FROM flyway_schema_history WHERE version = ? AND success", "2")
                    ?.get(0, Int::class.java),
            )
        }
    }

    @Test
    fun `admin flag rejects null with a not null constraint violation`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val userId = repository(database.dsl).getOrCreate(TELEGRAM_USER_ID)

            val failure =
                assertFailsWith<DataAccessException> {
                    database.dsl.execute("UPDATE users SET is_admin = NULL WHERE id = ?", userId)
                }

            assertEquals("23502", failure.sqlState())
            assertFalse(adminFlag(database.dsl, userId))
        }
    }

    @Test
    fun `admin flag can be set to true and read through another connection`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val userId = repository(database.dsl).getOrCreate(TELEGRAM_USER_ID)

            assertEquals(1, database.dsl.execute("UPDATE users SET is_admin = ? WHERE id = ?", true, userId))

            postgres.createConnection("").use { connection ->
                assertTrue(adminFlag(DSL.using(connection, SQLDialect.POSTGRES), userId))
            }
        }
    }

    @Test
    fun `getOrCreate preserves an existing administrator UUID and admin flag`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val userId = repository(database.dsl).getOrCreate(TELEGRAM_USER_ID)
            assertEquals(1, database.dsl.execute("UPDATE users SET is_admin = ? WHERE id = ?", true, userId))

            val existingUserId = repository(database.dsl).getOrCreate(TELEGRAM_USER_ID)

            assertEquals(userId, existingUserId)
            assertTrue(adminFlag(database.dsl, userId))
        }
    }

    private fun repository(dsl: DSLContext): UserRepository = PostgresUserRepository(dsl, protector)

    private fun adminFlag(
        dsl: DSLContext,
        userId: UUID,
    ): Boolean =
        assertNotNull(
            dsl.fetchOne("SELECT is_admin FROM users WHERE id = ?", userId)?.get("is_admin", Boolean::class.java),
        )
}

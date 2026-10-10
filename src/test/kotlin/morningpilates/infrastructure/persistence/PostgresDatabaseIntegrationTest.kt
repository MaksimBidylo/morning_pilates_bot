package morningpilates.infrastructure.persistence

import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import morningpilates.config.DatabaseProperties
import morningpilates.configureApplication
import morningpilates.telegram.BotLifecycle
import org.flywaydb.core.api.FlywayException
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Tag("integration")
@Testcontainers
class PostgresDatabaseIntegrationTest {
    companion object {
        @Container
        @JvmField
        val postgres =
            PostgreSQLContainer("postgres:18")
                .withDatabaseName("integration_test")
                .withUsername("integration_user")
                .withPassword("integration-password")
    }

    @BeforeEach
    fun resetTestSchema() {
        postgres.createConnection("").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP SCHEMA public CASCADE")
                statement.execute("CREATE SCHEMA public")
            }
        }
    }

    @Test
    fun `jooq connects to PostgreSQL and closing pool prevents further queries`() {
        val database = PostgresDatabase.connect(properties())
        try {
            assertEquals(
                "integration_test",
                database.dsl.fetchOne("SELECT current_database()")?.get(0, String::class.java),
            )
            assertEquals(1, database.dsl.selectOne().fetchOne(0, Int::class.java))
        } finally {
            database.close()
        }

        assertFailsWith<DataAccessException> { database.dsl.selectOne().fetch() }
    }

    @Test
    fun `Flyway applies migration once across restarts`() {
        repeat(2) {
            migratedDatabase().use { database ->
                assertEquals(
                    "migrated",
                    database.dsl.fetchOne("SELECT value FROM database_probe")?.get(0, String::class.java),
                )
                assertEquals(1, database.dsl.fetchCount(DSL.table("database_probe")))
                assertEquals(
                    1,
                    database.dsl
                        .fetchOne(
                            "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success",
                        )?.get(0, Int::class.java),
                )
            }
        }
    }

    @Test
    fun `failed transaction rolls back its insert`() {
        migratedDatabase().use { database ->
            assertFailsWith<IllegalStateException> {
                database.dsl.transaction { configuration ->
                    DSL.using(configuration).execute("INSERT INTO database_probe (value) VALUES (?)", "rolled-back")
                    error("Rollback this transaction")
                }
            }
            assertEquals(
                0,
                database.dsl
                    .fetchOne(
                        "SELECT count(*) FROM database_probe WHERE value = ?",
                        "rolled-back",
                    )?.get(0, Int::class.java),
            )
        }
    }

    @Test
    fun `migration failure releases pool connections`() {
        assertFailsWith<FlywayException> {
            PostgresDatabase.connect(properties(), listOf("classpath:db/failing-migration"))
        }
        postgres.createConnection("").use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT count(*) FROM pg_stat_activity WHERE application_name = 'morning-pilates'",
                    ).use { result ->
                        result.next()
                        assertEquals(0, result.getInt(1))
                    }
            }
        }
    }

    @Test
    fun `Ktor shutdown closes real PostgreSQL pool`() {
        lateinit var database: PostgresDatabase
        testApplication {
            val properties = properties()
            environment {
                config =
                    MapApplicationConfig(
                        "telegram.botToken" to "test-token",
                        "db.host" to properties.host,
                        "db.port" to properties.port.toString(),
                        "db.database" to properties.database,
                        "db.user" to properties.user,
                        "db.password" to properties.password,
                        "db.sslMode" to "disable",
                    )
            }
            application {
                configureApplication(
                    databaseFactory = { PostgresDatabase.connect(it).also { connected -> database = connected } },
                    botFactory = {
                        object : BotLifecycle {
                            override fun start() = Unit

                            override fun stop() = Unit
                        }
                    },
                )
            }
            startApplication()
            assertEquals(1, database.dsl.selectOne().fetchOne(0, Int::class.java))
        }

        assertFailsWith<DataAccessException> { database.dsl.selectOne().fetch() }
    }

    private fun properties() =
        DatabaseProperties(
            host = postgres.host,
            port = postgres.getMappedPort(5432),
            database = postgres.databaseName,
            user = postgres.username,
            password = postgres.password,
            sslMode = "disable",
        )

    private fun migratedDatabase() = PostgresDatabase.connect(properties(), listOf("classpath:db/test-migration"))
}

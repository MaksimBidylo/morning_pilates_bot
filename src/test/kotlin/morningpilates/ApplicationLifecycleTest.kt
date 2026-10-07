package morningpilates

import io.ktor.client.request.get
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import morningpilates.database.Database
import morningpilates.telegram.BotLifecycle
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApplicationLifecycleTest {
    @Test
    fun `starting Ktor opens database and starts bot once`() = testApplication {
        val events = mutableListOf<String>()
        val database = RecordingDatabase(events)
        val bot = RecordingBot(events)
        var databaseCreations = 0
        var botCreations = 0
        environment { config = applicationConfig() }
        application {
            configureApplication(
                databaseFactory = { properties ->
                    assertEquals("test_database", properties.database)
                    databaseCreations++
                    events += "database-open"
                    database
                },
                botFactory = { properties ->
                    assertEquals("test-token", properties.botToken)
                    botCreations++
                    bot
                },
            )
        }

        startApplication()
        startApplication()
        client.get("/health")
        client.get("/health")

        assertEquals(1, databaseCreations)
        assertEquals(1, botCreations)
        assertEquals(listOf("database-open", "bot-start"), events)
    }

    @Test
    fun `stopping Ktor stops bot and then closes database`() {
        val events = mutableListOf<String>()
        testApplication {
            environment { config = applicationConfig() }
            application {
                configureApplication(
                    databaseFactory = { RecordingDatabase(events) },
                    botFactory = { RecordingBot(events) },
                )
            }

            startApplication()
            assertEquals(listOf("bot-start"), events)
        }

        assertEquals(listOf("bot-start", "bot-stop", "database-close"), events)
    }

    @Test
    fun `database startup failure prevents bot creation`() {
        var botCreations = 0
        assertFailsWith<IllegalStateException> {
            testApplication {
                environment { config = applicationConfig() }
                application {
                    configureApplication(
                        databaseFactory = { error("Database unavailable") },
                        botFactory = {
                            botCreations++
                            RecordingBot(mutableListOf())
                        },
                    )
                }
                startApplication()
            }
        }

        assertEquals(0, botCreations)
    }

    @Test
    fun `failed bot startup stops bot and closes database`() {
        val events = mutableListOf<String>()
        assertFailsWith<IllegalStateException> {
            testApplication {
                environment { config = applicationConfig() }
                application {
                    configureApplication(
                        databaseFactory = { RecordingDatabase(events) },
                        botFactory = {
                            object : BotLifecycle {
                                override fun start() { error("Bot startup failed") }
                                override fun stop() { events += "bot-stop" }
                            }
                        },
                    )
                }
                startApplication()
            }
        }

        assertEquals(listOf("bot-stop", "database-close"), events)
    }

    private fun applicationConfig() = MapApplicationConfig(
        "telegram.botToken" to "test-token",
        "db.host" to "localhost",
        "db.port" to "5432",
        "db.database" to "test_database",
        "db.user" to "test_user",
        "db.password" to "test-password",
    )

    private class RecordingDatabase(private val events: MutableList<String>) : Database {
        override val dsl = DSL.using(SQLDialect.POSTGRES)
        override fun close() { events += "database-close" }
    }

    private class RecordingBot(private val events: MutableList<String>) : BotLifecycle {
        override fun start() { events += "bot-start" }
        override fun stop() { events += "bot-stop" }
    }
}

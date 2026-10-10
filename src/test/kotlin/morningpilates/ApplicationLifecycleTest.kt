package morningpilates

import io.ktor.client.request.get
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import morningpilates.config.telegramIdTestKeys
import morningpilates.infrastructure.persistence.Database
import morningpilates.infrastructure.security.TelegramIdProtector
import morningpilates.infrastructure.security.telegramIdProtector
import morningpilates.telegram.BotLifecycle
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Nested
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.Base64
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ApplicationLifecycleTest {
    @Test
    fun `starting Ktor opens database and starts bot once`() =
        testApplication {
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
                                override fun start() {
                                    error("Bot startup failed")
                                }

                                override fun stop() {
                                    events += "bot-stop"
                                }
                            }
                        },
                    )
                }
                startApplication()
            }
        }

        assertEquals(listOf("bot-stop", "database-close"), events)
    }

    @Test
    fun `failed bot startup preserves error and both cleanup failures`() {
        // Given
        val events = mutableListOf<String>()
        val startupFailure = AssertionError("Bot startup failed")
        val stopFailure = IllegalStateException("Bot stop failed")
        val closeFailure = IllegalStateException("Database close failed")
        var failure: AssertionError? = null

        // When
        testApplication {
            environment { config = applicationConfig() }
            application {
                failure =
                    assertFailsWith<AssertionError> {
                        configureApplication(
                            databaseFactory = {
                                object : Database {
                                    override val dsl = DSL.using(SQLDialect.POSTGRES)

                                    override fun close() {
                                        events += "database-close"
                                        throw closeFailure
                                    }
                                }
                            },
                            botFactory = {
                                object : BotLifecycle {
                                    override fun start(): Unit = throw startupFailure

                                    override fun stop() {
                                        events += "bot-stop"
                                        throw stopFailure
                                    }
                                }
                            },
                        )
                    }
            }
            startApplication()
        }

        // Then
        assertSame(startupFailure, failure)
        assertEquals(listOf(stopFailure, closeFailure), failure?.suppressed?.toList())
        assertEquals(listOf("bot-stop", "database-close"), events)
    }

    @Nested
    inner class TelegramIdProtection {
        private val encryptionKey = ByteArray(32) { it.toByte() }
        private val lookupKey = ByteArray(32) { (it + 32).toByte() }
        private val encoder = Base64.getEncoder()
        private val validKeys = telegramIdTestKeys()

        @Test
        fun `valid keys initialize protection before database and bot and expose a working instance`() =
            testApplication {
                val events = mutableListOf<String>()
                lateinit var initializedProtector: TelegramIdProtector
                environment { config = applicationConfig(validKeys) }
                application {
                    configureApplication(
                        databaseFactory = {
                            initializedProtector = telegramIdProtector
                            events += "database-open"
                            RecordingDatabase(events)
                        },
                        botFactory = {
                            assertSame(initializedProtector, telegramIdProtector)
                            events += "bot-create"
                            RecordingBot(events)
                        },
                    )
                }

                startApplication()
                startApplication()

                val protector = application.telegramIdProtector
                val telegramId = 7_000_000_123L
                val referenceProtector = TelegramIdProtector(encryptionKey, lookupKey)
                val encrypted = protector.encrypt(telegramId)

                assertSame(initializedProtector, protector)
                assertEquals(telegramId, protector.decrypt(encrypted))
                assertContentEquals(referenceProtector.lookup(telegramId), protector.lookup(telegramId))
                assertEquals(listOf("database-open", "bot-create", "bot-start"), events)
            }

        @ParameterizedTest
        @ValueSource(strings = ["encryptionKey", "lookupKey"])
        @OptIn(ExperimentalSerializationApi::class)
        fun `missing key prevents database and bot creation without revealing secrets`(parameterName: String) {
            val failure = assertStartupRejected<MissingFieldException>(validKeys - parameterName)

            assertTrue(parameterName in failure.missingFields)
        }

        @ParameterizedTest
        @ValueSource(strings = ["encryptionKey", "lookupKey"])
        fun `invalid Base64 prevents database and bot creation without revealing secrets`(parameterName: String) {
            val keys = validKeys + (parameterName to "test-secret:not-base64!")

            assertStartupRejected<IllegalArgumentException>(keys)
        }

        @ParameterizedTest
        @CsvSource("encryptionKey, 31", "encryptionKey, 33", "lookupKey, 31", "lookupKey, 33")
        fun `invalid key length prevents database and bot creation without revealing secrets`(
            parameterName: String,
            keySize: Int,
        ) {
            val wrongKey = ByteArray(keySize) { (it + 64).toByte() }
            val keys = validKeys + (parameterName to encoder.encodeToString(wrongKey))

            assertStartupRejected<IllegalArgumentException>(keys)
        }

        @Test
        fun `equal keys prevent database and bot creation without revealing secrets`() {
            val keys = validKeys + ("lookupKey" to validKeys.getValue("encryptionKey"))

            assertStartupRejected<IllegalArgumentException>(keys)
        }

        private inline fun <reified T : Throwable> assertStartupRejected(keys: Map<String, String>): T {
            val events = mutableListOf<String>()
            val failure =
                assertFailsWith<T> {
                    testApplication {
                        environment { config = applicationConfig(keys) }
                        application {
                            configureApplication(
                                databaseFactory = {
                                    events += "database-open"
                                    RecordingDatabase(events)
                                },
                                botFactory = {
                                    events += "bot-create"
                                    RecordingBot(events)
                                },
                            )
                        }
                        startApplication()
                    }
                }

            assertEquals(emptyList(), events, "Invalid keys must be rejected before database and bot creation")
            assertNoSecrets(failure.stackTraceToString(), keys)
            return failure
        }

        private fun assertNoSecrets(
            text: String,
            keys: Map<String, String>,
        ) {
            for (secret in validKeys.values + keys.values) {
                assertFalse(secret in text)
            }
            val decodedKeys =
                listOf(encryptionKey, lookupKey) +
                    keys.values.mapNotNull { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            for (key in decodedKeys) {
                assertFalse(key.contentToString() in text)
                val hex = HexFormat.of().formatHex(key)
                assertFalse(hex in text)
                assertFalse(hex.uppercase() in text)
            }
        }
    }

    private fun applicationConfig(keys: Map<String, String> = telegramIdTestKeys()) =
        MapApplicationConfig(
            "telegram.botToken" to "test-token",
            "db.host" to "localhost",
            "db.port" to "5432",
            "db.database" to "test_database",
            "db.user" to "test_user",
            "db.password" to "test-password",
        ).apply {
            for ((parameterName, value) in keys) {
                put("security.telegramId.$parameterName", value)
            }
        }

    private class RecordingDatabase(
        private val events: MutableList<String>,
    ) : Database {
        override val dsl = DSL.using(SQLDialect.POSTGRES)

        override fun close() {
            events += "database-close"
        }
    }

    private class RecordingBot(
        private val events: MutableList<String>,
    ) : BotLifecycle {
        override fun start() {
            events += "bot-start"
        }

        override fun stop() {
            events += "bot-stop"
        }
    }
}

package morningpilates.bootstrap

import io.ktor.client.request.get
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import morningpilates.config.security.telegramIdTestKeys
import morningpilates.flow.model.UserSession
import morningpilates.flow.session.FlowStorage
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.infrastructure.persistence.Database
import morningpilates.infrastructure.security.TelegramIdProtector
import morningpilates.telegram.BotLifecycle
import morningpilates.telegram.TelegramBot
import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.support.trainingQuestion
import morningpilates.telegram.support.withoutQuestionToken
import morningpilates.telegram.training.creation.TrainingCreationMessageSessionStorage
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.user.TelegramUserId
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Nested
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val SENDER_ID = 42L
private const val TRAINING_ADD_COMMAND = "/training_add"
private const val VALID_DATE_INPUT = "11/10/2026"
private val EXPECTED_TRAINING_DATE = LocalDate.of(2026, 10, 11)
private val EXPECTED_TIME_PROMPT =
    """
    🕒 Введите время начала тренировки.

    Например: 8:00, 19:30 или 8-00.
    """.trimIndent()

class ApplicationLifecycleTest {
    @Test
    fun `application handles training date through registered bot handlers`() =
        testApplication {
            // Given
            val database = RecordingDatabase(mutableListOf())
            val telegramUserId = TelegramUserId(SENDER_ID)
            lateinit var flowStorage: FlowStorage
            lateinit var testBot: TestTelegramBot
            environment { config = applicationConfig() }
            application {
                configureApplication(
                    databaseFactory = { database },
                    botFactory = { properties, storage, sessionManager, queryService, profiles, deleteTraining ->
                        flowStorage = storage
                        testBot =
                            TestTelegramBot(
                                telegramBot =
                                    TelegramBot(
                                        profileRepository = profiles,
                                        properties = properties,
                                        flowStorage = storage,
                                        sessionManager = sessionManager,
                                        messageSessionStorage = TrainingCreationMessageSessionStorage(),
                                        queryService = queryService,
                                        deleteTraining = deleteTraining,
                                    ),
                                chatId = SENDER_ID,
                            )
                        object : BotLifecycle {
                            override fun start() = Unit

                            override fun stop() = testBot.close()
                        }
                    },
                )
            }
            startApplication()
            val question = testBot.sendCommand(TRAINING_ADD_COMMAND, senderId = SENDER_ID)
            val originalSession = checkNotNull(flowStorage.get(telegramUserId))

            // When
            val replies = testBot.sendTextAndCollectReplies(VALID_DATE_INPUT, senderId = SENDER_ID)

            // Then
            assertEquals(
                originalSession.copy(state = WaitingStartTime(EXPECTED_TRAINING_DATE)),
                flowStorage.get(telegramUserId),
            )
            val expectedEdit =
                trainingQuestion(
                    chatId = TelegramChatId(question.chatId),
                    messageId = TelegramMessageId(question.messageId),
                    text = EXPECTED_TIME_PROMPT,
                )
            assertEquals(listOf(expectedEdit), testBot.editedMessages.map { it.withoutQuestionToken() })
            assertTrue(replies.isEmpty())
        }

    @Test
    fun `starting Ktor passes one shared flow storage to bot and starts resources once`() =
        testApplication {
            val events = mutableListOf<String>()
            val database = RecordingDatabase(events)
            val bot = RecordingBot(events)
            var databaseCreations = 0
            var botCreations = 0
            lateinit var flowStorage: FlowStorage
            environment { config = applicationConfig() }
            application {
                configureApplication(
                    databaseFactory = { properties ->
                        assertEquals("test_database", properties.database)
                        databaseCreations++
                        events += "database-open"
                        database
                    },
                    botFactory = { properties, storage, _, _, _, _ ->
                        assertEquals("test-token", properties.botToken)
                        flowStorage = storage
                        botCreations++
                        bot
                    },
                )
            }

            startApplication()
            val initializedStorage = assertIs<InMemoryFlowStorage>(flowStorage)
            val telegramUserId = TelegramUserId(42L)
            val session = UserSession(state = WaitingDate, createdAt = Instant.EPOCH)
            check(initializedStorage.start(telegramUserId, session))

            startApplication()
            client.get("/health")
            client.get("/health")

            assertEquals(1, databaseCreations)
            assertEquals(1, botCreations)
            assertEquals(session, flowStorage.get(telegramUserId))
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
                    botFactory = { _, _, _, _, _, _ -> RecordingBot(events) },
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
                        botFactory = { _, _, _, _, _, _ ->
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
    fun `failed bot creation closes database and propagates error`() {
        val events = mutableListOf<String>()
        val creationFailure = IllegalStateException("Bot creation failed")
        val failure =
            assertFailsWith<IllegalStateException> {
                testApplication {
                    environment { config = applicationConfig() }
                    application {
                        configureApplication(
                            databaseFactory = { RecordingDatabase(events) },
                            botFactory = { _, _, _, _, _, _ -> throw creationFailure },
                        )
                    }
                    startApplication()
                }
            }

        assertEquals(creationFailure.message, failure.message)
        assertEquals(listOf("database-close"), events)
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
                        botFactory = { _, _, _, _, _, _ ->
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
                            botFactory = { _, _, _, _, _, _ ->
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
                        botFactory = { _, _, _, _, _, _ ->
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
                                botFactory = { _, _, _, _, _, _ ->
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

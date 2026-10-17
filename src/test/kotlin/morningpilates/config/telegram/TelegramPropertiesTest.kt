package morningpilates.config.telegram

import com.typesafe.config.ConfigFactory
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.config.property
import io.ktor.server.engine.CommandLineConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import morningpilates.config.security.TEST_TELEGRAM_ID_ENCRYPTION_KEY
import morningpilates.config.security.TEST_TELEGRAM_ID_LOOKUP_KEY
import morningpilates.module
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramPropertiesTest {
    @Test
    fun `local config overrides token from main config`(
        @TempDir directory: Path,
    ) = testApplication {
        val mainConfig = directory.resolve("application.conf")
        val localConfig = directory.resolve("application-local.conf")
        Files.writeString(
            mainConfig,
            """
            ktor.deployment.port = 8080
            telegram.botToken = "main-token"
            """.trimIndent(),
        )
        Files.writeString(localConfig, "telegram.botToken = \"local-token\"")
        val commandLine =
            CommandLineConfig(
                arrayOf("-config=$mainConfig", "-config=$localConfig"),
            )
        environment {
            config = commandLine.environment.config
        }
        lateinit var properties: TelegramProperties
        application {
            properties = property<TelegramProperties>("telegram")
        }

        startApplication()

        assertEquals("local-token", properties.botToken)
    }

    @Test
    fun `loads bot token from telegram section`() =
        testApplication {
            environment {
                config =
                    HoconApplicationConfig(
                        ConfigFactory.parseString(
                            """
                            telegram {
                                botToken = "test-token"
                            }
                            """.trimIndent(),
                        ),
                    )
            }
            lateinit var properties: TelegramProperties
            application {
                properties = property<TelegramProperties>("telegram")
            }

            startApplication()

            assertEquals("test-token", properties.botToken)
            assertFalse(properties.trainingDateCalendarEnabled)
            assertFalse(properties.trainingSearchDateCalendarEnabled)
        }

    @Test
    fun `calendar flag can be enabled explicitly`() =
        testApplication {
            environment {
                config =
                    HoconApplicationConfig(
                        ConfigFactory.parseString(
                            "telegram { botToken = test-token, trainingDateCalendarEnabled = true }",
                        ),
                    )
            }
            lateinit var properties: TelegramProperties
            application { properties = property<TelegramProperties>("telegram") }
            startApplication()
            assertTrue(properties.trainingDateCalendarEnabled)
            assertFalse(properties.trainingSearchDateCalendarEnabled)
        }

    @Test
    fun `search calendar can be enabled without creation calendar`() =
        testApplication {
            environment {
                config =
                    HoconApplicationConfig(
                        ConfigFactory.parseString(
                            "telegram { botToken = test-token, trainingSearchDateCalendarEnabled = true }",
                        ),
                    )
            }
            lateinit var properties: TelegramProperties
            application { properties = property<TelegramProperties>("telegram") }
            startApplication()
            assertTrue(properties.trainingSearchDateCalendarEnabled)
            assertFalse(properties.trainingDateCalendarEnabled)
        }

    @Test
    @OptIn(ExperimentalSerializationApi::class)
    fun `application does not start without bot token`() {
        val exception =
            assertFailsWith<MissingFieldException> {
                testApplication {
                    environment {
                        config =
                            HoconApplicationConfig(
                                ConfigFactory.parseString(
                                    """
                                    telegram {}
                                    security.telegramId {
                                        encryptionKey = "$TEST_TELEGRAM_ID_ENCRYPTION_KEY"
                                        lookupKey = "$TEST_TELEGRAM_ID_LOOKUP_KEY"
                                    }
                                    """.trimIndent(),
                                ),
                            )
                    }
                    application {
                        module()
                    }

                    startApplication()
                }
            }

        assertTrue("botToken" in exception.missingFields)
    }
}

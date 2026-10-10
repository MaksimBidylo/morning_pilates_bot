package morningpilates.config

import com.typesafe.config.ConfigFactory
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.config.property
import io.ktor.server.testing.testApplication
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import morningpilates.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatabasePropertiesTest {
    @Test
    @OptIn(ExperimentalSerializationApi::class)
    fun `missing database password prevents application startup`() {
        val exception =
            assertFailsWith<MissingFieldException> {
                testApplication {
                    environment {
                        config =
                            HoconApplicationConfig(
                                ConfigFactory.parseString(
                                    """
                                    telegram.botToken = "test-token"
                                    db {
                                        host = "localhost"
                                        port = 5432
                                        database = "test_database"
                                        user = "test_user"
                                    }
                                    """.trimIndent(),
                                ),
                            )
                    }
                    application { module() }
                    startApplication()
                }
            }

        assertTrue("password" in exception.missingFields)
    }

    @Test
    fun `loads connection settings from db section`() =
        testApplication {
            environment {
                config =
                    HoconApplicationConfig(
                        ConfigFactory.parseString(
                            """
                            db {
                                host = "localhost"
                                port = 5433
                                database = "test_database"
                                user = "test_user"
                                password = "test-password"
                                sslMode = "require"
                                maximumPoolSize = 3
                            }
                            """.trimIndent(),
                        ),
                    )
            }
            lateinit var properties: DatabaseProperties
            application { properties = property<DatabaseProperties>("db") }
            startApplication()

            assertEquals("localhost", properties.host)
            assertEquals(5433, properties.port)
            assertEquals("test_database", properties.database)
            assertEquals("test_user", properties.user)
            assertEquals("test-password", properties.password)
            assertEquals("require", properties.sslMode)
            assertEquals(3, properties.maximumPoolSize)
        }
}

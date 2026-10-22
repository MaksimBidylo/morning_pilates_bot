package morningpilates.diagnostics

import kotlinx.coroutines.runBlocking
import morningpilates.infrastructure.persistence.PostgresDatabase
import morningpilates.infrastructure.persistence.PostgresTrainingRepository
import morningpilates.infrastructure.persistence.databaseProperties
import morningpilates.infrastructure.persistence.postgresTestContainer
import morningpilates.infrastructure.security.TelegramIdProtector
import org.junit.jupiter.api.Tag
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("integration")
@Testcontainers
class PostgresRequestLoggingIntegrationTest {
    companion object {
        @Container
        @JvmField
        val postgres = postgresTestContainer()
    }

    @Test
    fun `real PostgreSQL query failure logs safe SQL state and preserves Telegram error reply`() =
        runBlocking {
            PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
                database.dsl.execute("DROP TABLE trainings CASCADE")
                val protector = TelegramIdProtector(ByteArray(32) { 1 }, ByteArray(32) { 2 })
                val repository = PostgresTrainingRepository(database.dsl, protector)
                LogCapture().use { logs ->
                    loggingFixture(repository).use { bot ->
                        val replies = bot.sendCommandAndCollectReplies("/training_get_last", senderId = 987654321L)
                        assertEquals(1, replies.size)
                        assertTrue(replies.single().text.contains("Не удалось загрузить"))
                        assertTrue(logs.completions.single().contains("result=technical_failure"))
                        assertTrue(logs.rendered.contains("sql_state=42P01"))
                        assertTrue(logs.rendered.contains("org.postgresql.util.PSQLException"))
                        listOf("SELECT", "987654321", "integration-password", "jdbc:postgresql")
                            .forEach { assertFalse(logs.rendered.contains(it)) }
                    }
                }
            }
        }
}

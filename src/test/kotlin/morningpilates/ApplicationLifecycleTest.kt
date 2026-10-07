package morningpilates

import io.ktor.client.request.get
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import morningpilates.telegram.BotLifecycle
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationLifecycleTest {
    @Test
    fun `starting Ktor starts bot once`() = testApplication {
        val bot = RecordingBot()
        var botCreations = 0
        environment {
            config = MapApplicationConfig("telegram.botToken" to "test-token")
        }
        application {
            configureApplication { properties ->
                assertEquals("test-token", properties.botToken)
                botCreations++
                bot
            }
        }

        startApplication()
        startApplication()
        client.get("/health")
        client.get("/health")

        assertEquals(1, botCreations)
        assertEquals(1, bot.startCalls)
        assertEquals(0, bot.stopCalls)
    }

    @Test
    fun `stopping Ktor stops bot`() {
        val bot = RecordingBot()
        testApplication {
            environment {
                config = MapApplicationConfig("telegram.botToken" to "test-token")
            }
            application {
                configureApplication { bot }
            }

            startApplication()

            assertEquals(1, bot.startCalls)
            assertEquals(0, bot.stopCalls)
        }

        assertEquals(1, bot.stopCalls)
    }

    private class RecordingBot : BotLifecycle {
        var startCalls = 0
            private set
        var stopCalls = 0
            private set

        override fun start() {
            startCalls++
        }

        override fun stop() {
            stopCalls++
        }
    }
}

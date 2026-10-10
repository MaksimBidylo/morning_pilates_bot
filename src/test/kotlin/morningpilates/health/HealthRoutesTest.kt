package morningpilates.health

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthRoutesTest {
    @Test
    fun `health returns HTTP 200`() =
        testApplication {
            environment {
                config = MapApplicationConfig()
            }
            application {
                routing {
                    healthRoutes()
                }
            }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
        }
}

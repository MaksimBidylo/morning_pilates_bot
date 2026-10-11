package morningpilates.infrastructure.persistence

import morningpilates.infrastructure.security.TelegramIdProtector
import morningpilates.user.UserRepository
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

@Tag("integration")
@Testcontainers
class PostgresUserRepositoryIntegrationTest {
    companion object {
        private const val TELEGRAM_USER_ID = 7_000_000_123L
        private const val WAIT_TIMEOUT_SECONDS = 10L

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
    fun `new telegram id creates a user with the returned UUID`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val userId: UUID = repository(database.dsl).getOrCreate(TELEGRAM_USER_ID)

            val user = assertNotNull(database.dsl.fetchOne("SELECT id FROM users"))
            assertEquals(userId, user.get("id", UUID::class.java))
            assertEquals(1, userCount(database.dsl))
        }
    }

    @Test
    fun `stores decryptable telegram id and correct HMAC lookup`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val userId = repository(database.dsl).getOrCreate(TELEGRAM_USER_ID)

            assertStoredUser(database.dsl, userId)
        }
    }

    @Test
    fun `repeated call returns the existing UUID without creating another user`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val firstUserId = repository(database.dsl).getOrCreate(TELEGRAM_USER_ID)

            val repeatedUserId = repository(database.dsl).getOrCreate(TELEGRAM_USER_ID)

            assertEquals(firstUserId, repeatedUserId)
            assertEquals(1, userCount(database.dsl))
            assertEquals(firstUserId, storedUserId(database.dsl))
        }
    }

    @Test
    fun `external transaction rollback undoes user creation`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val rollback = IllegalStateException("Rollback user creation")

            val failure =
                assertFailsWith<IllegalStateException> {
                    database.dsl.transaction { configuration ->
                        val transactionalDsl = DSL.using(configuration)
                        val userId = repository(transactionalDsl).getOrCreate(TELEGRAM_USER_ID)
                        assertEquals(userId, storedUserId(transactionalDsl))
                        throw rollback
                    }
                }

            assertSame(rollback, failure)
            assertEquals(0, userCount(database.dsl))
        }
    }

    @Test
    @Timeout(45)
    fun `concurrent creation returns the same UUID without a unique constraint failure`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            postgres.createConnection("").use { firstConnection ->
                postgres.createConnection("").use { secondConnection ->
                    val firstDsl = DSL.using(firstConnection, SQLDialect.POSTGRES)
                    val secondDsl = DSL.using(secondConnection, SQLDialect.POSTGRES)
                    val firstPid = backendPid(firstDsl)
                    val secondPid = backendPid(secondDsl)
                    val executor = Executors.newSingleThreadExecutor()
                    try {
                        lateinit var secondCall: Future<UUID>
                        val firstUserId =
                            firstDsl.transactionResult { firstConfiguration ->
                                val firstTransactionalDsl = DSL.using(firstConfiguration)
                                firstTransactionalDsl.execute("SET LOCAL statement_timeout = '15s'")
                                val userId = repository(firstTransactionalDsl).getOrCreate(TELEGRAM_USER_ID)
                                secondCall =
                                    executor.submit<UUID> {
                                        secondDsl.transactionResult { secondConfiguration ->
                                            val secondTransactionalDsl = DSL.using(secondConfiguration)
                                            secondTransactionalDsl.execute("SET LOCAL statement_timeout = '15s'")
                                            repository(secondTransactionalDsl).getOrCreate(TELEGRAM_USER_ID)
                                        }
                                    }
                                awaitBlockedBy(database.dsl, firstPid, secondPid)
                                userId
                            }

                        val secondUserId = secondCall.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        assertEquals(firstUserId, secondUserId)
                        assertEquals(1, userCount(database.dsl))
                        assertStoredUser(database.dsl, firstUserId)
                    } finally {
                        executor.shutdownNow()
                        assertTrue(
                            executor.awaitTermination(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                            "Concurrent call did not terminate",
                        )
                    }
                }
            }
        }
    }

    private fun backendPid(dsl: DSLContext): Int =
        assertNotNull(
            dsl.fetchOne("SELECT pg_backend_pid()")?.get(0, Int::class.java),
        )

    private fun awaitBlockedBy(
        dsl: DSLContext,
        blockingPid: Int,
        blockedPid: Int,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            val blocked =
                dsl
                    .resultQuery("SELECT ? = ANY(pg_blocking_pids(?))", blockingPid, blockedPid)
                    .queryTimeout(2)
                    .fetchOne()
                    ?.get(0, Boolean::class.java)
            if (blocked == true) {
                return
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
        }
        fail("Second transaction did not block on the uncommitted user within $WAIT_TIMEOUT_SECONDS seconds")
    }

    private fun assertStoredUser(
        dsl: DSLContext,
        expectedUserId: UUID,
    ) {
        val user =
            assertNotNull(
                dsl.fetchOne(
                    "SELECT id, telegram_id_encrypted, telegram_id_lookup FROM users WHERE telegram_id_lookup = ?",
                    protector.lookup(TELEGRAM_USER_ID),
                ),
            )
        val encryptedId = assertNotNull(user.get("telegram_id_encrypted", ByteArray::class.java))
        assertEquals(expectedUserId, user.get("id", UUID::class.java))
        assertEquals(TELEGRAM_USER_ID, protector.decrypt(encryptedId))
        assertContentEquals(
            protector.lookup(TELEGRAM_USER_ID),
            user.get("telegram_id_lookup", ByteArray::class.java),
        )
    }

    private fun repository(dsl: DSLContext): UserRepository = PostgresUserRepository(dsl, protector)

    private fun userCount(dsl: DSLContext): Int =
        assertNotNull(
            dsl.fetchOne("SELECT count(*) FROM users")?.get(0, Int::class.java),
        )

    private fun storedUserId(dsl: DSLContext): UUID? =
        dsl
            .fetchOne("SELECT id FROM users WHERE telegram_id_lookup = ?", protector.lookup(TELEGRAM_USER_ID))
            ?.get("id", UUID::class.java)
}

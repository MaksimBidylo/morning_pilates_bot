package morningpilates.infrastructure.persistence

import morningpilates.infrastructure.security.TelegramIdProtector
import morningpilates.user.UserRepository
import org.jooq.DSLContext
import java.util.UUID

class PostgresUserRepository(
    private val dsl: DSLContext,
    private val protector: TelegramIdProtector,
) : UserRepository {
    override fun getOrCreate(telegramUserId: Long): UUID {
        val lookup = protector.lookup(telegramUserId)
        val existingUserId =
            dsl
                .fetchOne("SELECT id FROM users WHERE telegram_id_lookup = ?", lookup)
                ?.get("id", UUID::class.java)
        if (existingUserId != null) {
            return existingUserId
        }

        val userId = UUID.randomUUID()
        dsl.execute(
            "INSERT INTO users (id, telegram_id_encrypted, telegram_id_lookup) VALUES (?, ?, ?)",
            userId,
            protector.encrypt(telegramUserId),
            lookup,
        )
        return userId
    }
}

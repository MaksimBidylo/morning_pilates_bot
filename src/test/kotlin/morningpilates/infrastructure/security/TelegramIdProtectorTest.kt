package morningpilates.infrastructure.security

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.HexFormat
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame

class TelegramIdProtectorTest {
    companion object {
        private const val TELEGRAM_ID = 7_000_000_123L
        private const val OTHER_TELEGRAM_ID = 11_294_967_419L
    }

    private val encryptionKey = ByteArray(32) { it.toByte() }
    private val lookupKey = ByteArray(32) { (it + 32).toByte() }
    private val protector = TelegramIdProtector(encryptionKey = encryptionKey, lookupKey = lookupKey)

    @ParameterizedTest
    @ValueSource(ints = [31, 33])
    fun `rejects encryption keys with invalid length`(keySize: Int) {
        assertFailsWith<IllegalArgumentException> {
            TelegramIdProtector(encryptionKey = encryptionKey.copyOf(keySize), lookupKey = lookupKey)
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [31, 33])
    fun `rejects lookup keys with invalid length`(keySize: Int) {
        assertFailsWith<IllegalArgumentException> {
            TelegramIdProtector(encryptionKey = encryptionKey, lookupKey = lookupKey.copyOf(keySize))
        }
    }

    @Test
    fun `rejects keys with equal contents in different arrays`() {
        val matchingLookupKey = encryptionKey.copyOf()
        assertNotSame(encryptionKey, matchingLookupKey)

        assertFailsWith<IllegalArgumentException> {
            TelegramIdProtector(encryptionKey = encryptionKey, lookupKey = matchingLookupKey)
        }
    }

    @Test
    fun `accepts different 32 byte keys`() {
        assertDoesNotThrow {
            TelegramIdProtector(encryptionKey = encryptionKey, lookupKey = lookupKey)
        }
    }

    @Test
    fun `encrypted telegram id can be decrypted`() {
        val encrypted = protector.encrypt(TELEGRAM_ID)

        assertEquals(TELEGRAM_ID, protector.decrypt(encrypted))
    }

    @Test
    fun `encrypting the same telegram id twice produces different ciphertexts`() {
        val firstEncrypted = protector.encrypt(TELEGRAM_ID)
        val secondEncrypted = protector.encrypt(TELEGRAM_ID)

        assertFalse(firstEncrypted.contentEquals(secondEncrypted))
        assertEquals(TELEGRAM_ID, protector.decrypt(firstEncrypted))
        assertEquals(TELEGRAM_ID, protector.decrypt(secondEncrypted))
    }

    @Test
    fun `lookup is stable HMAC SHA256 for the same telegram id and key`() {
        val expectedLookup =
            HexFormat.of().parseHex("954c25b5ffc23958415f17f949fadb81fa4e093247376df35764f01d3d0697cf")
        val firstLookup = protector.lookup(TELEGRAM_ID)
        val secondLookup = protector.lookup(TELEGRAM_ID)
        val anotherProtector = TelegramIdProtector(encryptionKey = encryptionKey, lookupKey = lookupKey)

        assertContentEquals(expectedLookup, firstLookup)
        assertContentEquals(firstLookup, secondLookup)
        assertContentEquals(firstLookup, anotherProtector.lookup(TELEGRAM_ID))
    }

    @Test
    fun `different telegram ids produce different lookup values`() {
        val firstLookup = protector.lookup(TELEGRAM_ID)
        val secondLookup = protector.lookup(OTHER_TELEGRAM_ID)

        assertFalse(firstLookup.contentEquals(secondLookup))
    }

    @Test
    fun `tampered encrypted data fails authentication`() {
        val encrypted: ByteArray = protector.encrypt(TELEGRAM_ID)
        val tampered = encrypted.copyOf()
        tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()

        assertFailsWith<AEADBadTagException> {
            protector.decrypt(tampered)
        }
    }

    @Test
    fun `decrypting with a different encryption key fails authentication`() {
        val encrypted = protector.encrypt(TELEGRAM_ID)
        val wrongEncryptionKey = encryptionKey.copyOf()
        wrongEncryptionKey[0] = (wrongEncryptionKey[0].toInt() xor 1).toByte()
        val wrongKeyProtector = TelegramIdProtector(encryptionKey = wrongEncryptionKey, lookupKey = lookupKey)

        assertFailsWith<AEADBadTagException> {
            wrongKeyProtector.decrypt(encrypted)
        }
    }
}

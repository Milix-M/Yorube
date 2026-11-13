package com.hermes.client.data.store

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.hermes.client.testutil.runVmTest
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SecretCipherTest {
    @Test fun roundTripUsesUniqueNoncesAndDoesNotStorePlaintext() {
        val first = SecretCipher.encrypt("secret-日本語", "api_key")
        val second = SecretCipher.encrypt("secret-日本語", "api_key")
        assertNotEquals(first, second)
        assertFalse(first.contains("secret"))
        assertEquals("secret-日本語", SecretCipher.decrypt(first, "api_key"))
        assertEquals("", SecretCipher.encrypt("", "api_key"))
        assertEquals("", SecretCipher.decrypt("", "api_key"))
    }

    @Test fun tamperingAndMovingCiphertextBetweenFieldsFailClosed() {
        val encrypted = SecretCipher.encrypt("secret", "api_key")
        assertThrows(Exception::class.java) { SecretCipher.decrypt(encrypted, "cf_client_secret") }
        val bytes = Base64.getDecoder().decode(encrypted.removePrefix("v1:"))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { SecretCipher.decrypt("v1:" + Base64.getEncoder().encodeToString(bytes), "api_key") }
        assertThrows(IllegalArgumentException::class.java) { SecretCipher.decrypt("plaintext", "api_key") }
        assertThrows(IllegalArgumentException::class.java) { SecretCipher.decrypt("v1:AQID", "api_key") }
    }

    @Test fun migrationReplacesAllLegacySecretsAndKeepsOtherSettings() = runVmTest {
        val names = listOf("api_key", "cf_client_id", "cf_client_secret", "fb_api_key", "fcm_token")
        val old = preferencesOf(*(names.map { stringPreferencesKey(it) to "legacy-$it" } +
            (stringPreferencesKey("base_url") to "https://example.com")).toTypedArray())
        val migration = SecretPreferencesMigration()
        assertTrue(migration.shouldMigrate(old))
        val migrated = migration.migrate(old)
        names.forEach { name ->
            assertNull(migrated[stringPreferencesKey(name)])
            assertEquals("legacy-$name", SecretCipher.decrypt(migrated[stringPreferencesKey("$name.encrypted.v1")]!!, name))
        }
        assertEquals("https://example.com", migrated[stringPreferencesKey("base_url")])
        assertFalse(migration.shouldMigrate(migrated))
        migration.cleanUp()
    }

    @Test fun interruptedMigrationPreservesAlreadyEncryptedValues() = runVmTest {
        val encrypted = SecretCipher.encrypt("new-key", "api_key")
        val old = preferencesOf(stringPreferencesKey("api_key") to "old-key",
            stringPreferencesKey("api_key.encrypted.v1") to encrypted)
        val migrated = SecretPreferencesMigration().migrate(old)
        assertEquals(encrypted, migrated[stringPreferencesKey("api_key.encrypted.v1")])
        assertNull(migrated[stringPreferencesKey("api_key")])
    }
}

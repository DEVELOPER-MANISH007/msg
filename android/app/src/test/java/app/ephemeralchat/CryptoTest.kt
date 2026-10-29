package app.ephemeralchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Base64

class CryptoTest {
    @Test fun workerEndpointRejectsPlaceholderAndNonHttpsUrls() {
        assertEquals(false, isUsableWorkerEndpoint("https://REPLACE_WITH_WORKER.workers.dev"))
        assertEquals(false, isUsableWorkerEndpoint("http://worker.workers.dev"))
        assertEquals(false, isUsableWorkerEndpoint("not-a-url"))
        assertEquals(true, isUsableWorkerEndpoint("https://ephemeral-chat.workers.dev"))
    }

    @Test fun bothParticipantsDeriveSameSessionKey() {
        val alice = Crypto.generate()
        val bob = Crypto.generate()
        val aliceKey = Crypto.shared(alice, bob.public.encoded, "test-room")
        val bobKey = Crypto.shared(bob, alice.public.encoded, "test-room")
        assertEquals(aliceKey.toList(), bobKey.toList())
    }

    @Test fun aesGcmEncryptsAndAuthenticatesMessage() {
        val alice = Crypto.generate()
        val bob = Crypto.generate()
        val key = Crypto.shared(alice, bob.public.encoded, "test-room")
        val encrypted = Crypto.encrypt(key, "test-room", "ephemeral hello")
        assertNotEquals("ephemeral hello", encrypted)
        assertEquals("ephemeral hello", Crypto.decrypt(key, "test-room", encrypted))
        val bytes = Base64.getDecoder().decode(encrypted)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        try {
            Crypto.decrypt(key, "test-room", Base64.getEncoder().encodeToString(bytes))
            throw AssertionError("Tampered ciphertext must fail authentication")
        } catch (_: javax.crypto.AEADBadTagException) { }
    }

    @Test fun keyConfirmationTagMatchesOnlyForSameKeyAndRoom() {
        val alice = Crypto.generate()
        val bob = Crypto.generate()
        val aliceKey = Crypto.shared(alice, bob.public.encoded, "test-room")
        val bobKey = Crypto.shared(bob, alice.public.encoded, "test-room")
        assertEquals(Crypto.authenticationTag(aliceKey, "test-room").toList(), Crypto.authenticationTag(bobKey, "test-room").toList())
        assertNotEquals(Crypto.authenticationTag(aliceKey, "test-room").toList(), Crypto.authenticationTag(aliceKey, "other-room").toList())
    }
}

package app.ephemeralchat

import org.junit.Assert.assertEquals
import org.junit.Test

class TextPurgeTest {
    @Test fun senderExitRemovesOnlyThatSendersInMemoryLines() {
        val aFile = ByteArray(4) { 7 }
        val bFile = ByteArray(4) { 9 }
        val messages = listOf(
            Line("participant-a", "A1", false),
            Line("participant-b", "B1", true),
            Line("participant-a", "A2", false, bytes = aFile),
            Line("participant-b", "B2", true),
            Line("participant-b", "B file", true, bytes = bFile),
        )
        assertEquals(listOf("B1", "B2", "B file"), purgeMessagesFrom(messages, "participant-a").map { it.text })
        assertEquals(listOf<Byte>(0, 0, 0, 0), aFile.toList())
        assertEquals(listOf<Byte>(9, 9, 9, 9), bFile.toList())
        assertEquals(messages, purgeMessagesFrom(messages, "unknown-participant"))
    }
}

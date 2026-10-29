package app.ephemeralchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferManifestTest {
    @Test fun acceptsEmptyAndMaximumSizedFilesWithExpectedChunks() {
        assertEquals(0, TransferManifest.chunkCount(0))
        assertEquals(1, TransferManifest.chunkCount(1))
        val max = PeerTransport.MAX_FILE_BYTES
        val chunks = TransferManifest.chunkCount(max)
        assertEquals(chunks, (max / PeerTransport.FILE_CHUNK_BYTES + if (max % PeerTransport.FILE_CHUNK_BYTES == 0L) 0 else 1).toInt())
        TransferManifest.validate(max, chunks)
    }

    @Test fun rejectsOversizeFilesAndInconsistentChunkCounts() {
        assertThrows(IllegalArgumentException::class.java) { TransferManifest.chunkCount(PeerTransport.MAX_FILE_BYTES + 1) }
        assertThrows(IllegalArgumentException::class.java) { TransferManifest.chunkCount(-1) }
        assertThrows(IllegalArgumentException::class.java) { TransferManifest.validate(20_000, 1) }
    }
}

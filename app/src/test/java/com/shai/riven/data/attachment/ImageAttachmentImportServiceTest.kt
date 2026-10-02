package com.shai.riven.data.attachment

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.persistence.RivenDatabase
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImageAttachmentImportServiceTest {
    private lateinit var database: RivenDatabase
    private lateinit var attachments: AttachmentService
    private lateinit var importer: ImageAttachmentImportService
    private lateinit var root: java.io.File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        root = Files.createTempDirectory("riven-image-import").toFile()
        attachments = AttachmentService(database, FileAttachmentBlobStore(root))
        importer = ImageAttachmentImportService(attachments, TEST_IMAGE_DECODER)
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun validatedSnapshotIsDurableAndIndependentOfMutableSource() = runBlocking {
        val original = pngBytes()
        var selectedBytes = original

        val result = importer.import(
            SelectedImageInput(
                declaredMimeType = "image/png",
                declaredByteSize = original.size.toLong(),
                openStream = { ByteArrayInputStream(selectedBytes) },
                occurredAt = 10,
            ),
        )
        selectedBytes = byteArrayOf(9, 8, 7)

        assertTrue("Expected success, got $result", result is ImageAttachmentImportResult.Success)
        val stored = (result as ImageAttachmentImportResult.Success).image
        assertEquals(2, stored.width)
        assertEquals(2, stored.height)
        val reopened = attachments.readAvailableBlob(stored.metadata.attachmentId)
            as AttachmentBlobReadResult.Success
        assertArrayEquals(original, reopened.bytes)
    }

    @Test
    fun oversizedCorruptAndMimeMismatchedSelectionsFailBeforeStorage() = runBlocking {
        val oversized = importer.import(
            SelectedImageInput(
                declaredMimeType = "image/png",
                declaredByteSize = MAX_IMPORTED_IMAGE_BYTES + 1,
                openStream = { error("must not open oversized source") },
                occurredAt = 1,
            ),
        )
        val corrupt = importer.import(
            SelectedImageInput(
                declaredMimeType = "image/png",
                declaredByteSize = 4,
                openStream = { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) },
                occurredAt = 2,
            ),
        )
        val mismatched = importer.import(
            SelectedImageInput(
                declaredMimeType = "image/jpeg",
                declaredByteSize = null,
                openStream = { ByteArrayInputStream(pngBytes()) },
                occurredAt = 3,
            ),
        )

        assertEquals(
            ImageAttachmentImportResult.Failure(ImageAttachmentImportError.Oversized),
            oversized,
        )
        assertEquals(
            ImageAttachmentImportResult.Failure(ImageAttachmentImportError.CorruptOrUndecodable),
            corrupt,
        )
        assertEquals(
            ImageAttachmentImportResult.Failure(ImageAttachmentImportError.UnsupportedMimeType),
            mismatched,
        )
        assertTrue(database.attachmentDao().allAttachments().isEmpty())
    }

    private fun pngBytes(): ByteArray = Base64.getDecoder().decode(VALID_ONE_PIXEL_PNG)

    private companion object {
        val TEST_IMAGE_DECODER = ImageMetadataDecoder { bytes ->
            if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) {
                DecodedImageMetadata("image/png", 2, 2)
            } else {
                null
            }
        }
        val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        const val VALID_ONE_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAAALSURBVBhXY2BABwAAEgABp3qZbgAAAABJRU5ErkJggg=="
    }
}

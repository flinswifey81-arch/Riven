package com.shai.riven.data.background

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.attachment.AttachmentBlobStore
import com.shai.riven.data.attachment.AttachmentBlobWriteResult
import com.shai.riven.data.attachment.AttachmentByteSource
import com.shai.riven.data.attachment.AttachmentService
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.AttachmentEntity
import com.shai.riven.data.persistence.model.AttachmentKind
import com.shai.riven.data.persistence.model.AttachmentSource
import com.shai.riven.data.persistence.model.AttachmentState
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AttachmentMaintenanceServiceTest {
    private lateinit var database: RivenDatabase
    private lateinit var blobStore: FakeAttachmentBlobStore
    private var now = 10_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        blobStore = FakeAttachmentBlobStore()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun deletePendingAttachmentBlobAndRowAreRemoved() = runBlocking {
        insertAttachment("pending", AttachmentState.DELETE_PENDING, updatedAt = 1)
        blobStore.put("attachments/pending.blob")

        val result = service().runMaintenance() as AttachmentMaintenanceResult.Completed

        assertEquals(listOf("pending"), result.removedAttachmentIds)
        assertNull(database.attachmentDao().attachment("pending"))
        assertFalse(blobStore.exists("attachments/pending.blob"))
    }

    @Test
    fun staleStagingUsesHardenedClaimAndCleanupPath() = runBlocking {
        insertAttachment(
            id = "stale-staging",
            state = AttachmentState.STAGING,
            updatedAt = now - ATTACHMENT_STAGING_STALE_AFTER_MS,
        )
        blobStore.put("attachments/stale-staging.blob")

        val result = service().runMaintenance() as AttachmentMaintenanceResult.Completed

        assertEquals(listOf("stale-staging"), result.removedAttachmentIds)
        assertNull(database.attachmentDao().attachment("stale-staging"))
        assertFalse(blobStore.exists("attachments/stale-staging.blob"))
    }

    @Test
    fun freshStagingAttachmentIsPreserved() = runBlocking {
        insertAttachment("fresh-staging", AttachmentState.STAGING, updatedAt = now - 1)
        blobStore.put("attachments/fresh-staging.blob")

        val result = service().runMaintenance() as AttachmentMaintenanceResult.Completed

        assertTrue(result.removedAttachmentIds.isEmpty())
        assertEquals(AttachmentState.STAGING, database.attachmentDao().attachment("fresh-staging")?.state)
        assertTrue(blobStore.exists("attachments/fresh-staging.blob"))
    }

    @Test
    fun availableAttachmentIsNeverDeletedEvenWhenOldAndUnreferenced() = runBlocking {
        insertAttachment("available", AttachmentState.AVAILABLE, updatedAt = 1)
        blobStore.put("attachments/available.blob")

        val result = service().runMaintenance() as AttachmentMaintenanceResult.Completed

        assertTrue(result.removedAttachmentIds.isEmpty())
        assertEquals(AttachmentState.AVAILABLE, database.attachmentDao().attachment("available")?.state)
        assertTrue(blobStore.exists("attachments/available.blob"))
    }

    @Test
    fun retryableDeleteFailurePreservesCanonicalStateAndMapsToWorkerRetry() = runBlocking {
        insertAttachment("retry-delete", AttachmentState.DELETE_PENDING, updatedAt = 1)
        blobStore.put("attachments/retry-delete.blob")
        blobStore.failDeleteFor += "attachments/retry-delete.blob"
        val maintenance = service()
        val executor = RivenBackgroundWorkExecutor(
            attachmentMaintenance = maintenance,
            repairJobRunner = RepairJobRunOperations { id ->
                RepairJobRunResult.NoOp(id, RepairJobNoOpReason.MISSING)
            },
            repairSweep = RepairSweepOperations {
                RepairSweepResult.Completed(emptyList(), emptyList(), moreWorkRemaining = false)
            },
        )

        val sweep = maintenance.runMaintenance() as AttachmentMaintenanceResult.Completed
        val workerOutcome = executor.execute(RivenBackgroundWorkKind.ATTACHMENT_CLEANUP, "retry-delete")

        assertEquals(listOf("retry-delete"), sweep.retryableAttachmentIds)
        assertEquals(AttachmentState.DELETE_PENDING, database.attachmentDao().attachment("retry-delete")?.state)
        assertEquals(RivenBackgroundExecutionOutcome.Retryable, workerOutcome)
    }

    @Test
    fun maintenanceSweepIsBoundedAndReportsMoreWork() = runBlocking {
        listOf("pending-a", "pending-b", "pending-c").forEachIndexed { index, id ->
            insertAttachment(id, AttachmentState.DELETE_PENDING, updatedAt = index.toLong())
            blobStore.put("attachments/$id.blob")
        }

        val result = service(itemLimit = 2).runMaintenance() as AttachmentMaintenanceResult.Completed

        assertEquals(listOf("pending-a", "pending-b"), result.removedAttachmentIds)
        assertTrue(result.moreWorkRemaining)
        assertEquals(AttachmentState.DELETE_PENDING, database.attachmentDao().attachment("pending-c")?.state)
    }

    @Test
    fun targetedMissingAttachmentIsSuccessfulNoOp() = runBlocking {
        val result = service().cleanupTarget("missing")

        assertEquals(
            TargetedAttachmentCleanupResult.NoOp("missing", AttachmentCleanupNoOpReason.MISSING),
            result,
        )
    }

    @Test
    fun targetedAvailableAttachmentIsSuccessfulNoOp() = runBlocking {
        insertAttachment("available-target", AttachmentState.AVAILABLE, updatedAt = 1)
        blobStore.put("attachments/available-target.blob")

        val result = service().cleanupTarget("available-target")

        assertEquals(
            TargetedAttachmentCleanupResult.NoOp(
                "available-target",
                AttachmentCleanupNoOpReason.AVAILABLE,
            ),
            result,
        )
        assertEquals(AttachmentState.AVAILABLE, database.attachmentDao().attachment("available-target")?.state)
    }

    private fun service(itemLimit: Int = DEFAULT_ATTACHMENT_MAINTENANCE_LIMIT): AttachmentMaintenanceService =
        AttachmentMaintenanceService(
            attachmentDao = database.attachmentDao(),
            attachmentService = AttachmentService(database, blobStore),
            clock = RivenBackgroundClock { now },
            itemLimit = itemLimit,
        )

    private fun insertAttachment(id: String, state: AttachmentState, updatedAt: Long) {
        database.attachmentDao().insertAttachment(
            AttachmentEntity(
                id = id,
                kind = AttachmentKind.IMAGE,
                mimeType = "image/png",
                state = state,
                storageKey = "attachments/$id.blob",
                byteSize = if (state == AttachmentState.AVAILABLE) 1 else null,
                contentSha256 = if (state == AttachmentState.AVAILABLE) "a".repeat(64) else null,
                source = AttachmentSource.SHAI_IMPORT,
                createdAt = 1,
                updatedAt = updatedAt,
            ),
        )
    }

    private class FakeAttachmentBlobStore : AttachmentBlobStore {
        private val blobs = linkedMapOf<String, ByteArray>()
        val failDeleteFor = mutableSetOf<String>()

        fun put(storageKey: String) {
            blobs[storageKey] = byteArrayOf(1)
        }

        override fun write(
            storageKey: String,
            source: AttachmentByteSource,
        ): AttachmentBlobWriteResult {
            val bytes = source.openStream().use { it.readBytes() }
            blobs[storageKey] = bytes
            return AttachmentBlobWriteResult(bytes.size.toLong(), "a".repeat(64))
        }

        override fun exists(storageKey: String): Boolean = blobs.containsKey(storageKey)

        override fun open(storageKey: String): InputStream =
            ByteArrayInputStream(checkNotNull(blobs[storageKey]))

        override fun delete(storageKey: String) {
            if (storageKey in failDeleteFor) throw IllegalStateException("private delete detail")
            blobs.remove(storageKey)
        }
    }
}

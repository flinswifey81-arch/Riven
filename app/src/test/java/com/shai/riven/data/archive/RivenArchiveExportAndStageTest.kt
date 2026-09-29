package com.shai.riven.data.archive

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.shai.riven.data.attachment.AttachmentByteSource
import com.shai.riven.data.attachment.FileAttachmentBlobStore
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.AttachmentEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactEntity
import com.shai.riven.data.persistence.entity.GeneratedMediaProvenanceEntity
import com.shai.riven.data.persistence.model.AttachmentKind
import com.shai.riven.data.persistence.model.AttachmentSource
import com.shai.riven.data.persistence.model.AttachmentState
import com.shai.riven.data.persistence.model.DerivedArtifactState
import com.shai.riven.data.persistence.model.DerivedArtifactType
import com.shai.riven.data.persistence.model.GeneratedMediaKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RivenArchiveExportAndStageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = InstrumentationRegistry.getInstrumentation().targetContext
            .getDatabasePath(MIGRATION_DATABASE_NAME),
        driver = AndroidSQLiteDriver(),
        databaseClass = RivenDatabase::class,
    )

    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var database: RivenDatabase
    private lateinit var blobStore: FileAttachmentBlobStore
    private lateinit var exporter: RivenArchiveExportService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = temporaryFolder.newFolder("archive-${UUID.randomUUID()}")
        database = RivenDatabase.buildNamedForRestoreValidation(context, File(root, "source.db").absolutePath)
        database.openHelper.writableDatabase
        blobStore = FileAttachmentBlobStore(File(root, "source-attachments"))
        exporter = RivenArchiveExportService(
            context = context,
            database = database,
            blobStore = blobStore,
            stagingRoot = File(root, "export-staging"),
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(MIGRATION_DATABASE_NAME)
    }

    @Test
    fun exportBasicArchiveHasExactRequiredStructureAndDoesNotCloseCallerOutput() {
        val output = CloseTrackingOutputStream()

        val result = exporter.export(ExportRivenArchiveInput(output, exportedAt = 101))

        assertTrue(result is ExportRivenArchiveResult.Exported)
        assertFalse(output.closed)
        val entries = readZip(output.bytes())
        assertEquals(setOf(ARCHIVE_MANIFEST_PATH, ARCHIVE_DATABASE_PATH), entries.map { it.first }.toSet())
        val manifest = checkNotNull(RivenArchiveManifestJson.decode(entries.single { it.first == ARCHIVE_MANIFEST_PATH }.second))
        assertEquals(RIVEN_ARCHIVE_FORMAT_VERSION, manifest.archiveFormatVersion)
        assertEquals(101, manifest.exportedAt)
        assertEquals(5, manifest.databaseSchemaVersion)
        assertFalse(manifest.secretsIncluded)
    }

    @Test
    fun exportUsesConsistentSelfContainedDatabaseSnapshot() {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO conversations VALUES ('snapshot-conversation', 1, 1, 'ACTIVE', 'Snapshot title')",
        )

        val databaseBytes = readZip(validArchive()).single { it.first == ARCHIVE_DATABASE_PATH }.second
        val snapshot = File(root, "inspect-snapshot.db").apply { writeBytes(databaseBytes) }

        SQLiteDatabase.openDatabase(snapshot.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            val title = sqlite.rawQuery(
                "SELECT title FROM conversations WHERE conversation_id = 'snapshot-conversation'",
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            }
            assertEquals("Snapshot title", title)
        }
        assertFalse(readZip(validArchive()).any { it.first.endsWith("-wal") || it.first.endsWith("-shm") })
    }

    @Test
    fun exportIncludesVerifiedAvailableAttachment() {
        val bytes = "available attachment".toByteArray()
        insertAttachment("available", AttachmentState.AVAILABLE, bytes = bytes)

        val entries = readZip(validArchive())
        val manifest = manifest(entries)
        val record = manifest.attachments.single()

        assertTrue(record.blobPresent)
        assertArrayEquals(bytes, entries.single { it.first == record.archivePath }.second)
    }

    @Test
    fun exportIncludesGeneratedMediaAndItsDatabaseProvenance() {
        val bytes = "generated media".toByteArray()
        insertAttachment("generated", AttachmentState.AVAILABLE, bytes = bytes, source = AttachmentSource.RIVEN_GENERATED)
        database.attachmentDao().insertGeneratedMediaProvenance(
            GeneratedMediaProvenanceEntity(
                attachmentId = "generated",
                generationKind = GeneratedMediaKind.GENERATED_IMAGE,
                generatedAt = 5,
            ),
        )

        val entries = readZip(validArchive())
        assertTrue(manifest(entries).attachments.single().blobPresent)
        val dbFile = File(root, "generated-snapshot.db").apply {
            writeBytes(entries.single { it.first == ARCHIVE_DATABASE_PATH }.second)
        }
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            val count = sqlite.rawQuery("SELECT COUNT(*) FROM generated_media_provenance", null).use {
                it.moveToFirst()
                it.getInt(0)
            }
            assertEquals(1, count)
        }
    }

    @Test
    fun exportFailsWhenAvailableAttachmentBlobIsMissing() {
        insertAttachment("missing", AttachmentState.AVAILABLE, bytes = "expected".toByteArray(), writeBlob = false)

        val error = exportFailure()

        assertEquals(RivenArchiveExportError.ExportAttachmentMissing("missing"), error)
    }

    @Test
    fun exportFailsWhenAvailableAttachmentHashMismatches() {
        insertAttachment(
            "mismatch",
            AttachmentState.AVAILABLE,
            bytes = "canonical".toByteArray(),
            blobBytes = "tampered".toByteArray(),
        )

        val error = exportFailure()

        assertEquals(RivenArchiveExportError.ExportAttachmentMismatch("mismatch"), error)
    }

    @Test
    fun exportAllowsMissingStagingBlobAndDeclaresItAbsent() {
        insertAttachment("staging", AttachmentState.STAGING, bytes = null, writeBlob = false)

        val record = manifest(readZip(validArchive())).attachments.single()

        assertFalse(record.blobPresent)
        assertNull(record.archivePath)
    }

    @Test
    fun exportAllowsMissingDeletePendingBlobAndDeclaresItAbsent() {
        insertAttachment("delete-pending", AttachmentState.DELETE_PENDING, bytes = null, writeBlob = false)

        val record = manifest(readZip(validArchive())).attachments.single()

        assertFalse(record.blobPresent)
        assertNull(record.archivePath)
    }

    @Test
    fun exportIsReadOnlyForCanonicalRows() {
        val bytes = "read only".toByteArray()
        insertAttachment("read-only", AttachmentState.AVAILABLE, bytes = bytes)
        val before = database.attachmentDao().attachment("read-only")
        val countsBefore = canonicalCounts()

        validArchive()

        assertEquals(before, database.attachmentDao().attachment("read-only"))
        assertEquals(countsBefore, canonicalCounts())
    }

    @Test
    fun exportExcludesCredentialDirectoryAndCredentialBytes() {
        val marker = "credential-marker-never-export".toByteArray()
        val credentialRoot = File(context.noBackupFilesDir, "riven_provider_credentials")
        credentialRoot.mkdirs()
        File(credentialRoot, "opaque.cred").writeBytes(marker)
        try {
            val archive = validArchive()
            assertFalse(archive.containsSubsequence(marker))
            assertFalse(readZip(archive).any { it.first.contains("credential", ignoreCase = true) })
            assertFalse(manifest(readZip(archive)).secretsIncluded)
        } finally {
            credentialRoot.deleteRecursively()
        }
    }

    @Test
    fun stageRejectsZipTraversal() {
        assertRestoreError(
            zip(readZip(validArchive()) + ("../escape" to byteArrayOf(1))),
            RivenArchiveRestoreError.UnsafeArchivePath,
        )
    }

    @Test
    fun stageRejectsAbsolutePath() {
        assertRestoreError(
            zip(readZip(validArchive()) + ("/absolute" to byteArrayOf(1))),
            RivenArchiveRestoreError.UnsafeArchivePath,
        )
    }

    @Test
    fun stageRejectsDuplicateEntry() {
        val entries = readZip(validArchive()).toMutableList()
        entries += ARCHIVE_MANIFEST_PATH to entries.single { it.first == ARCHIVE_MANIFEST_PATH }.second

        assertRestoreError(zip(entries), RivenArchiveRestoreError.DuplicateArchiveEntry)
    }

    @Test
    fun stageRejectsUnknownExtraEntry() {
        val result = stage(zip(readZip(validArchive()) + ("unknown/file.bin" to byteArrayOf(1))))

        assertTrue(result is StageRivenRestoreResult.Failure)
        assertEquals(
            RivenArchiveRestoreError.ArchiveIntegrityFailure("UNEXPECTED_ENTRY"),
            (result as StageRivenRestoreResult.Failure).error,
        )
    }

    @Test
    fun stageRejectsUnknownFutureArchiveVersion() {
        val archive = rewriteManifest(validArchive()) { it.put("archiveFormatVersion", 2) }

        assertRestoreError(archive, RivenArchiveRestoreError.UnsupportedArchiveVersion)
    }

    @Test
    fun stageRejectsTamperedDatabase() {
        val entries = readZip(validArchive()).map { (name, bytes) ->
            if (name == ARCHIVE_DATABASE_PATH) name to bytes.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
            else name to bytes
        }

        val result = stage(zip(entries))

        assertTrue(result is StageRivenRestoreResult.Failure)
        assertEquals(
            RivenArchiveRestoreError.ArchiveIntegrityFailure("DATABASE_HASH"),
            (result as StageRivenRestoreResult.Failure).error,
        )
    }

    @Test
    fun stageRejectsTamperedAttachment() {
        insertAttachment("tampered", AttachmentState.AVAILABLE, bytes = "original".toByteArray())
        val entries = readZip(validArchive())
        val attachmentPath = manifest(entries).attachments.single().archivePath
        val tampered = entries.map { (name, bytes) ->
            if (name == attachmentPath) name to "changed".toByteArray() else name to bytes
        }

        val result = stage(zip(tampered))

        assertTrue(result is StageRivenRestoreResult.Failure)
        assertEquals(
            RivenArchiveRestoreError.AttachmentIntegrityFailure("tampered"),
            (result as StageRivenRestoreResult.Failure).error,
        )
    }

    @Test
    fun stageRejectsArchiveThatExceedsDecompressionLimit() {
        val service = RivenArchiveRestoreService(
            context,
            limits = RivenArchiveLimits(
                maximumManifestBytes = 8,
                maximumDatabaseBytes = Long.MAX_VALUE,
                maximumAttachmentBytes = Long.MAX_VALUE,
                maximumTotalExtractedBytes = Long.MAX_VALUE,
                maximumEntryCount = 10,
            ),
            restoreRoot = File(root, "limited-restore"),
        )

        val result = service.stageRestore(StageRivenRestoreInput(ByteArrayInputStream(validArchive()), 1))

        assertTrue(result is StageRivenRestoreResult.Failure)
        assertEquals(
            RivenArchiveRestoreError.ArchiveLimitExceeded(ArchiveLimit.MANIFEST_BYTES),
            (result as StageRivenRestoreResult.Failure).error,
        )
    }

    @Test
    fun stageDoesNotTouchCurrentDatabaseAttachmentsOrCredentials() {
        val currentDatabase = context.getDatabasePath(RivenDatabase.DATABASE_NAME)
        currentDatabase.parentFile?.mkdirs()
        currentDatabase.writeText("current-db-sentinel")
        val currentAttachments = File(context.filesDir, "riven_attachments/still-here").apply {
            parentFile?.mkdirs()
            writeText("attachment-sentinel")
        }
        val currentCredential = File(context.noBackupFilesDir, "riven_provider_credentials/still-here").apply {
            parentFile?.mkdirs()
            writeText("credential-sentinel")
        }
        try {
            assertTrue(stage(validArchive()) is StageRivenRestoreResult.RestoreStaged)
            assertEquals("current-db-sentinel", currentDatabase.readText())
            assertEquals("attachment-sentinel", currentAttachments.readText())
            assertEquals("credential-sentinel", currentCredential.readText())
        } finally {
            currentDatabase.delete()
            currentAttachments.parentFile?.deleteRecursively()
            currentCredential.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun stageMigratesSupportedOlderDatabaseThroughCanonicalMigrations() {
        context.deleteDatabase(MIGRATION_DATABASE_NAME)
        migrationHelper.createDatabase(4).close()
        val oldDatabase = context.getDatabasePath(MIGRATION_DATABASE_NAME)
        val archive = archiveForDatabase(oldDatabase, databaseVersion = 4)

        val result = stage(archive)

        assertTrue(result is StageRivenRestoreResult.RestoreStaged)
        result as StageRivenRestoreResult.RestoreStaged
        assertEquals(4, result.sourceDatabaseVersion)
        assertEquals(5, result.resultingDatabaseVersion)
    }

    @Test
    fun stageRejectsDatabaseNewerThanCurrentVersion() {
        val archive = mutateDatabase(validArchive()) { sqlite -> sqlite.execSQL("PRAGMA user_version = 6") }

        val result = stage(archive)

        assertTrue(result is StageRivenRestoreResult.Failure)
        assertEquals(
            RivenArchiveRestoreError.DatabaseTooNew(6, 5),
            (result as StageRivenRestoreResult.Failure).error,
        )
    }

    @Test
    fun stageRejectsDatabaseThatFailsSqliteIntegrity() {
        val entries = readZip(validArchive()).toMutableList()
        val databaseIndex = entries.indexOfFirst { it.first == ARCHIVE_DATABASE_PATH }
        val corrupt = entries[databaseIndex].second.copyOf().also { bytes ->
            repeat(minOf(16, bytes.size)) { bytes[it] = 0 }
        }
        entries[databaseIndex] = ARCHIVE_DATABASE_PATH to corrupt
        val rewritten = rewriteDatabaseManifest(entries)

        val result = stage(zip(rewritten))

        assertTrue(result is StageRivenRestoreResult.Failure)
        assertTrue((result as StageRivenRestoreResult.Failure).error is RivenArchiveRestoreError.DatabaseIntegrityFailure)
    }

    @Test
    fun stageRejectsDatabaseWithForeignKeyViolation() {
        val archive = mutateDatabase(validArchive()) { sqlite ->
            sqlite.execSQL("PRAGMA foreign_keys = OFF")
            sqlite.execSQL(
                "INSERT INTO message_attachments VALUES ('missing-message', 'missing-attachment', 0, 1)",
            )
        }

        val result = stage(archive)

        assertEquals(
            RivenArchiveRestoreError.DatabaseIntegrityFailure("FOREIGN_KEY_CHECK"),
            (result as StageRivenRestoreResult.Failure).error,
        )
    }

    @Test
    fun stageMarksDerivedArtifactsForRebuildWithoutDeletingDependencies() {
        database.maintenanceDao().insertDerivedArtifact(
            DerivedArtifactEntity(
                id = "derived",
                artifactType = DerivedArtifactType.SUMMARY,
                state = DerivedArtifactState.CURRENT,
                producerVersion = "one",
                sourceRevision = 1,
                artifactHash = "hash",
                createdAt = 1,
            ),
        )

        assertTrue(stage(validArchive(), occurredAt = 777) is StageRivenRestoreResult.RestoreStaged)
        val staged = RivenDatabase.buildNamedForRestoreValidation(
            context,
            RivenRestorePaths(context, File(root, "restore")).pendingDatabase.absolutePath,
        )
        try {
            val artifact = checkNotNull(staged.maintenanceDao().derivedArtifact("derived"))
            assertEquals(DerivedArtifactState.REBUILD_PENDING, artifact.state)
            assertNull(artifact.artifactHash)
            assertEquals(777L, artifact.invalidatedAt)
        } finally {
            staged.close()
        }
    }

    @Test
    fun stageMaterializesAndReverifiesAvailableRestoredAttachment() {
        val bytes = "restored available attachment".toByteArray()
        insertAttachment("restored", AttachmentState.AVAILABLE, bytes = bytes)

        val result = stage(validArchive())
        val stagedRoot = RivenRestorePaths(context, File(root, "restore")).pendingAttachmentRoot
        val restored = File(stagedRoot, "attachments/restored.blob")

        assertTrue(result is StageRivenRestoreResult.RestoreStaged)
        assertTrue(restored.isFile)
        assertArrayEquals(bytes, restored.readBytes())
    }

    private fun insertAttachment(
        id: String,
        state: AttachmentState,
        bytes: ByteArray?,
        blobBytes: ByteArray? = bytes,
        writeBlob: Boolean = true,
        source: AttachmentSource = AttachmentSource.SHAI_IMPORT,
    ) {
        val storageKey = "attachments/$id.blob"
        if (writeBlob && blobBytes != null) {
            blobStore.write(storageKey, AttachmentByteSource.fromBytes(blobBytes))
        }
        database.attachmentDao().insertAttachment(
            AttachmentEntity(
                id = id,
                kind = AttachmentKind.IMAGE,
                mimeType = "image/png",
                state = state,
                storageKey = storageKey,
                byteSize = bytes?.size?.toLong(),
                contentSha256 = bytes?.let(::sha256Hex),
                source = source,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
    }

    private fun validArchive(): ByteArray {
        val output = ByteArrayOutputStream()
        val result = exporter.export(ExportRivenArchiveInput(output, 100))
        assertTrue("Expected successful export but got $result", result is ExportRivenArchiveResult.Exported)
        return output.toByteArray()
    }

    private fun exportFailure(): RivenArchiveExportError {
        val result = exporter.export(ExportRivenArchiveInput(ByteArrayOutputStream(), 100))
        assertTrue(result is ExportRivenArchiveResult.Failure)
        return (result as ExportRivenArchiveResult.Failure).error
    }

    private fun stage(
        archive: ByteArray,
        occurredAt: Long = 200,
    ): StageRivenRestoreResult = RivenArchiveRestoreService(
        context,
        restoreRoot = File(root, "restore"),
    ).stageRestore(StageRivenRestoreInput(ByteArrayInputStream(archive), occurredAt))

    private fun assertRestoreError(archive: ByteArray, expected: RivenArchiveRestoreError) {
        val result = stage(archive)
        assertTrue(result is StageRivenRestoreResult.Failure)
        assertEquals(expected, (result as StageRivenRestoreResult.Failure).error)
    }

    private fun manifest(entries: List<Pair<String, ByteArray>>): RivenArchiveManifest =
        checkNotNull(RivenArchiveManifestJson.decode(entries.single { it.first == ARCHIVE_MANIFEST_PATH }.second))

    private fun readZip(bytes: ByteArray): List<Pair<String, ByteArray>> = buildList {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                add(entry.name to zip.readBytes())
                zip.closeEntry()
            }
        }
    }

    private fun rewriteManifest(
        archive: ByteArray,
        mutate: (JSONObject) -> Unit,
    ): ByteArray {
        val entries = readZip(archive).map { (name, bytes) ->
            if (name == ARCHIVE_MANIFEST_PATH) {
                val json = JSONObject(bytes.toString(Charsets.UTF_8))
                mutate(json)
                name to json.toString().toByteArray()
            } else {
                name to bytes
            }
        }
        return zip(entries)
    }

    private fun mutateDatabase(
        archive: ByteArray,
        mutate: (SQLiteDatabase) -> Unit,
    ): ByteArray {
        val entries = readZip(archive).toMutableList()
        val index = entries.indexOfFirst { it.first == ARCHIVE_DATABASE_PATH }
        val file = File(root, "mutate-${UUID.randomUUID()}.db").apply { writeBytes(entries[index].second) }
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use(mutate)
        entries[index] = ARCHIVE_DATABASE_PATH to file.readBytes()
        return zip(rewriteDatabaseManifest(entries))
    }

    private fun rewriteDatabaseManifest(
        entries: List<Pair<String, ByteArray>>,
    ): List<Pair<String, ByteArray>> {
        val databaseBytes = entries.single { it.first == ARCHIVE_DATABASE_PATH }.second
        val file = File(root, "hash-${UUID.randomUUID()}.db").apply { writeBytes(databaseBytes) }
        val version = runCatching {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                sqlite.rawQuery("PRAGMA user_version", null).use {
                    it.moveToFirst()
                    it.getInt(0)
                }
            }
        }.getOrDefault(5)
        return entries.map { (name, bytes) ->
            if (name == ARCHIVE_MANIFEST_PATH) {
                val json = JSONObject(bytes.toString(Charsets.UTF_8))
                    .put("databaseSha256", sha256Hex(databaseBytes))
                    .put("databaseSchemaVersion", version)
                name to json.toString().toByteArray()
            } else {
                name to bytes
            }
        }
    }

    private fun archiveForDatabase(file: File, databaseVersion: Int): ByteArray {
        val bytes = file.readBytes()
        val manifest = RivenArchiveManifest(
            archiveFormatVersion = 1,
            exportedAt = 1,
            databaseSchemaVersion = databaseVersion,
            databaseSha256 = sha256Hex(bytes),
            secretsIncluded = false,
            attachments = emptyList(),
        )
        return zip(
            listOf(
                ARCHIVE_MANIFEST_PATH to RivenArchiveManifestJson.encode(manifest),
                ARCHIVE_DATABASE_PATH to bytes,
            ),
        )
    }

    private fun canonicalCounts(): Map<String, Long> = listOf(
        "conversations",
        "messages",
        "experiences",
        "memories",
        "candidate_memories",
        "open_loops",
        "suppression_tombstones",
        "provider_profiles",
        "attachments",
        "shai_system_instructions",
        "repair_jobs",
    ).associateWith { table ->
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use {
            it.moveToFirst()
            it.getLong(0)
        }
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        data class Central(val name: ByteArray, val crc: Long, val size: Int, val offset: Int)
        val bytes = ByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        val central = mutableListOf<Central>()
        entries.forEach { (nameString, data) ->
            val name = nameString.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(data) }.value
            val offset = bytes.size()
            output.writeLeInt(0x04034b50)
            output.writeLeShort(20)
            output.writeLeShort(0x0800)
            output.writeLeShort(0)
            output.writeLeShort(0)
            output.writeLeShort(0)
            output.writeLeInt(crc.toInt())
            output.writeLeInt(data.size)
            output.writeLeInt(data.size)
            output.writeLeShort(name.size)
            output.writeLeShort(0)
            output.write(name)
            output.write(data)
            central += Central(name, crc, data.size, offset)
        }
        val centralOffset = bytes.size()
        central.forEach { entry ->
            output.writeLeInt(0x02014b50)
            output.writeLeShort(20)
            output.writeLeShort(20)
            output.writeLeShort(0x0800)
            output.writeLeShort(0)
            output.writeLeShort(0)
            output.writeLeShort(0)
            output.writeLeInt(entry.crc.toInt())
            output.writeLeInt(entry.size)
            output.writeLeInt(entry.size)
            output.writeLeShort(entry.name.size)
            output.writeLeShort(0)
            output.writeLeShort(0)
            output.writeLeShort(0)
            output.writeLeShort(0)
            output.writeLeInt(0)
            output.writeLeInt(entry.offset)
            output.write(entry.name)
        }
        val centralSize = bytes.size() - centralOffset
        output.writeLeInt(0x06054b50)
        output.writeLeShort(0)
        output.writeLeShort(0)
        output.writeLeShort(central.size)
        output.writeLeShort(central.size)
        output.writeLeInt(centralSize)
        output.writeLeInt(centralOffset)
        output.writeLeShort(0)
        output.flush()
        return bytes.toByteArray()
    }

    private fun DataOutputStream.writeLeShort(value: Int) {
        writeByte(value and 0xff)
        writeByte(value ushr 8 and 0xff)
    }

    private fun DataOutputStream.writeLeInt(value: Int) {
        writeByte(value and 0xff)
        writeByte(value ushr 8 and 0xff)
        writeByte(value ushr 16 and 0xff)
        writeByte(value ushr 24 and 0xff)
    }

    private fun ByteArray.containsSubsequence(value: ByteArray): Boolean {
        if (value.isEmpty()) return true
        return indices.any { start ->
            start + value.size <= size && value.indices.all { offset -> this[start + offset] == value[offset] }
        }
    }

    private class CloseTrackingOutputStream : FilterOutputStream(ByteArrayOutputStream()) {
        var closed = false
        override fun close() {
            closed = true
            super.close()
        }

        fun bytes(): ByteArray = (out as ByteArrayOutputStream).toByteArray()
    }

    private companion object {
        const val MIGRATION_DATABASE_NAME = "archive-restore-migration.db"
    }
}

package com.shai.riven.data.credential

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal class FileProviderCredentialStore(
    internal val rootDirectory: File,
    private val cipher: ProviderCredentialCipher,
    private val beforeAtomicReplace: (temporaryFile: File, finalFile: File) -> Unit = { _, _ -> },
) : ProviderCredentialStore {
    override fun putCredential(
        credentialSlotId: String,
        secret: ProviderSecret,
    ): PutProviderCredentialResult {
        validateCredentialSlotId(credentialSlotId)?.let {
            return PutProviderCredentialResult.Failure(it)
        }
        val plaintext = secret.reveal()
        if (plaintext.isBlank()) {
            return PutProviderCredentialResult.Failure(ProviderCredentialError.InvalidSecret)
        }
        val plaintextBytes = plaintext.toByteArray(StandardCharsets.UTF_8)
        if (plaintextBytes.size > MAX_SECRET_BYTE_COUNT) {
            plaintextBytes.fill(0)
            return PutProviderCredentialResult.Failure(ProviderCredentialError.InvalidSecret)
        }

        var temporaryFile: File? = null
        return try {
            ensureRootDirectory()
            val encrypted = cipher.encrypt(plaintextBytes, authenticatedData(credentialSlotId))
            val encoded = CredentialFileFormat.encode(encrypted)
            val finalFile = credentialFile(credentialSlotId)
            temporaryFile = File(
                rootDirectory,
                ".${finalFile.name}.${UUID.randomUUID()}.tmp",
            )
            FileOutputStream(temporaryFile).use { output ->
                output.write(encoded)
                output.fd.sync()
            }
            beforeAtomicReplace(temporaryFile, finalFile)
            Files.move(
                temporaryFile.toPath(),
                finalFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            forceDirectoryBestEffort()
            PutProviderCredentialResult.Success
        } catch (failure: Exception) {
            temporaryFile?.delete()
            PutProviderCredentialResult.Failure(
                failure.toStorageFailure(ProviderCredentialOperation.PUT),
            )
        } finally {
            plaintextBytes.fill(0)
        }
    }

    override fun readCredential(credentialSlotId: String): ReadProviderCredentialResult {
        validateCredentialSlotId(credentialSlotId)?.let {
            return ReadProviderCredentialResult.Failure(it)
        }
        val file = credentialFile(credentialSlotId)
        if (!file.isFile) {
            return ReadProviderCredentialResult.Failure(
                ProviderCredentialError.MissingCredential(credentialSlotId),
            )
        }
        val encrypted = try {
            CredentialFileFormat.decode(file.readBytes())
        } catch (_: CredentialFileFormatException) {
            return ReadProviderCredentialResult.Failure(
                ProviderCredentialError.CredentialUnreadable(credentialSlotId),
            )
        } catch (failure: Exception) {
            return ReadProviderCredentialResult.Failure(
                failure.toStorageFailure(ProviderCredentialOperation.READ),
            )
        }
        val plaintextBytes = try {
            cipher.decrypt(encrypted, authenticatedData(credentialSlotId))
        } catch (_: Exception) {
            return ReadProviderCredentialResult.Failure(
                ProviderCredentialError.CredentialUnreadable(credentialSlotId),
            )
        }
        return try {
            ReadProviderCredentialResult.Success(
                ProviderSecret.fromPlaintext(String(plaintextBytes, StandardCharsets.UTF_8)),
            )
        } finally {
            plaintextBytes.fill(0)
        }
    }

    override fun hasCredential(credentialSlotId: String): HasProviderCredentialResult {
        validateCredentialSlotId(credentialSlotId)?.let {
            return HasProviderCredentialResult.Failure(it)
        }
        return try {
            HasProviderCredentialResult.Success(credentialFile(credentialSlotId).isFile)
        } catch (failure: Exception) {
            HasProviderCredentialResult.Failure(
                failure.toStorageFailure(ProviderCredentialOperation.EXISTS),
            )
        }
    }

    override fun deleteCredential(credentialSlotId: String): DeleteProviderCredentialResult {
        validateCredentialSlotId(credentialSlotId)?.let {
            return DeleteProviderCredentialResult.Failure(it)
        }
        return try {
            val file = credentialFile(credentialSlotId)
            if (file.exists() && !file.delete()) {
                error("Credential file could not be deleted")
            }
            DeleteProviderCredentialResult.Success
        } catch (failure: Exception) {
            DeleteProviderCredentialResult.Failure(
                failure.toStorageFailure(ProviderCredentialOperation.DELETE),
            )
        }
    }

    override fun clearAllCredentials(): ClearProviderCredentialsResult {
        if (!rootDirectory.exists()) return ClearProviderCredentialsResult.Success(0)
        return try {
            check(rootDirectory.isDirectory)
            val children = checkNotNull(rootDirectory.listFiles())
            var deleted = 0
            children.filter(File::isFile).forEach { file ->
                check(file.delete())
                deleted += 1
            }
            ClearProviderCredentialsResult.Success(deleted)
        } catch (failure: Exception) {
            ClearProviderCredentialsResult.Failure(
                failure.toStorageFailure(ProviderCredentialOperation.CLEAR_ALL),
            )
        }
    }

    internal fun credentialFile(credentialSlotId: String): File = File(
        rootDirectory,
        sha256Hex(credentialSlotId) + CREDENTIAL_FILE_SUFFIX,
    )

    private fun ensureRootDirectory() {
        if (rootDirectory.exists()) {
            check(rootDirectory.isDirectory)
        } else {
            check(rootDirectory.mkdirs())
        }
    }

    private fun forceDirectoryBestEffort() {
        runCatching {
            Files.newByteChannel(rootDirectory.toPath()).use { channel ->
                if (channel is java.nio.channels.FileChannel) channel.force(true)
            }
        }
    }

    private fun validateCredentialSlotId(value: String): ProviderCredentialError? =
        if (value.isBlank() || value.length > MAX_CREDENTIAL_SLOT_ID_LENGTH) {
            ProviderCredentialError.InvalidCredentialSlotId
        } else {
            null
        }

    private fun authenticatedData(credentialSlotId: String): ByteArray =
        (AAD_PREFIX + credentialSlotId).toByteArray(StandardCharsets.UTF_8)

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun Exception.toStorageFailure(operation: ProviderCredentialOperation) =
        ProviderCredentialError.StorageFailure(
            operation = operation,
            causeType = this::class.java.simpleName,
        )

    internal companion object {
        const val DIRECTORY_NAME = "riven_provider_credentials"
        const val CREDENTIAL_FILE_SUFFIX = ".cred"
        const val AAD_PREFIX = "RIVEN_PROVIDER_CREDENTIAL_V1:"
        const val MAX_CREDENTIAL_SLOT_ID_LENGTH = 200
        const val MAX_SECRET_BYTE_COUNT = 1_048_576

        fun rootForContext(context: Context): File = File(
            context.applicationContext.noBackupFilesDir,
            DIRECTORY_NAME,
        )
    }
}

private object CredentialFileFormat {
    private const val MAGIC = 0x52565043
    private const val VERSION = 1
    private const val MAX_IV_LENGTH = 64
    private const val MAX_CIPHERTEXT_LENGTH = FileProviderCredentialStore.MAX_SECRET_BYTE_COUNT + 128

    fun encode(encrypted: EncryptedCredential): ByteArray {
        require(encrypted.initializationVector.isNotEmpty())
        require(encrypted.initializationVector.size <= MAX_IV_LENGTH)
        require(encrypted.ciphertext.isNotEmpty())
        require(encrypted.ciphertext.size <= MAX_CIPHERTEXT_LENGTH)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(encrypted.initializationVector.size)
                output.writeInt(encrypted.ciphertext.size)
                output.write(encrypted.initializationVector)
                output.write(encrypted.ciphertext)
            }
            bytes.toByteArray()
        }
    }

    fun decode(bytes: ByteArray): EncryptedCredential = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != MAGIC) throw CredentialFileFormatException()
            if (input.readInt() != VERSION) throw CredentialFileFormatException()
            val ivLength = input.readInt()
            val ciphertextLength = input.readInt()
            if (ivLength !in 1..MAX_IV_LENGTH) throw CredentialFileFormatException()
            if (ciphertextLength !in 1..MAX_CIPHERTEXT_LENGTH) {
                throw CredentialFileFormatException()
            }
            val expectedLength = 16L + ivLength.toLong() + ciphertextLength.toLong()
            if (bytes.size.toLong() != expectedLength) throw CredentialFileFormatException()
            val iv = ByteArray(ivLength)
            val ciphertext = ByteArray(ciphertextLength)
            input.readFully(iv)
            input.readFully(ciphertext)
            if (input.available() != 0) throw CredentialFileFormatException()
            EncryptedCredential(iv, ciphertext)
        }
    } catch (failure: CredentialFileFormatException) {
        throw failure
    } catch (_: Exception) {
        throw CredentialFileFormatException()
    }
}

private class CredentialFileFormatException : Exception()

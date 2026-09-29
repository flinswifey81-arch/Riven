package com.shai.riven.data.memory.intent

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.shai.riven.data.background.RivenBackgroundScheduleResult
import com.shai.riven.data.background.RivenBackgroundWorkScheduler
import com.shai.riven.data.deletion.DeleteMemoryInput
import com.shai.riven.data.deletion.MemoryDeleteResult
import com.shai.riven.data.deletion.SafeDeleteError
import com.shai.riven.data.deletion.SafeDeleteService
import com.shai.riven.data.experience.ExperienceOrderAllocator
import com.shai.riven.data.experience.ExperienceOrderOverflowException
import com.shai.riven.data.memory.CorrectMemoryInput
import com.shai.riven.data.memory.IntrinsicSignificanceInput
import com.shai.riven.data.memory.MemoryEntityLinkInput
import com.shai.riven.data.memory.MemoryEvidenceInput
import com.shai.riven.data.memory.MemoryRelationshipInput
import com.shai.riven.data.memory.MemoryStateTransitionInput
import com.shai.riven.data.memory.MemoryTransactionService
import com.shai.riven.data.memory.MemoryWriteError
import com.shai.riven.data.memory.MemoryWriteResult
import com.shai.riven.data.memory.ValidatedMemoryInput
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.ExperienceEntity
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.EvidenceRole
import com.shai.riven.data.persistence.model.ExperienceActor
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.ExperienceType
import com.shai.riven.data.persistence.model.SensitivityLevel
import kotlinx.coroutines.CancellationException

/**
 * Deterministic application operations for Shai-controlled Memory intent. These methods do not
 * inspect natural-language commands, invoke a model, or depend on provider configuration.
 */
class ManualMemoryIntentService(
    private val database: RivenDatabase,
    private val backgroundScheduler: RivenBackgroundWorkScheduler,
    private val idGenerator: ManualMemoryIntentIdGenerator = UuidManualMemoryIntentIdGenerator,
    private val memoryTransactions: MemoryTransactionService = MemoryTransactionService(database),
    private val safeDeleteService: SafeDeleteService = SafeDeleteService(database),
    private val afterExperienceInserted: (ManualMemoryIntentKind) -> Unit = {},
) {
    private val memoryDao = database.memoryDao()
    private val experienceOrderAllocator = ExperienceOrderAllocator(memoryDao)

    suspend fun remember(input: ManualRememberMemoryInput): ManualMemoryIntentResult {
        validateMeaning(input.meaning)?.let { return failure(it) }
        validateTemporalWindow(input.validFrom, input.validUntil)?.let { return failure(it) }
        validateStructuredReferences(input.entityLinks, input.relationships)?.let { return failure(it) }

        val memoryId = input.memoryId ?: generateMemoryId()
            ?: return storageFailure(ManualMemoryIntentKind.REMEMBER)
        val experienceId = input.experienceId ?: generateExperienceId()
            ?: return storageFailure(ManualMemoryIntentKind.REMEMBER)
        validateId(memoryId, ManualMemoryIdField.MEMORY_ID)?.let { return failure(it) }
        validateId(experienceId, ManualMemoryIdField.EXPERIENCE_ID)?.let { return failure(it) }

        return executeTransaction(ManualMemoryIntentKind.REMEMBER) {
            requireUnusedIds(memoryId, experienceId)
            val eventOrder = allocateEventOrder()
            insertManualExperience(
                experienceId = experienceId,
                eventOrder = eventOrder,
                sourceContent = input.meaning,
                occurredAt = input.occurredAt,
                sensitivity = input.sensitivity,
            )
            afterExperienceInserted(ManualMemoryIntentKind.REMEMBER)

            requireMemoryWriteSuccess(
                kind = ManualMemoryIntentKind.REMEMBER,
                result = memoryTransactions.createValidatedInCurrentTransaction(
                    input = ValidatedMemoryInput(
                        memoryId = memoryId,
                        kind = input.kind,
                        scope = input.scope,
                        meaning = input.meaning,
                        epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                        certainty = input.certainty,
                        learnedAt = input.occurredAt,
                        sensitivity = input.sensitivity,
                        evidence = listOf(
                            MemoryEvidenceInput(
                                experienceId = experienceId,
                                role = EvidenceRole.SUPPORTS,
                                epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                                sourceCertainty = input.certainty,
                                lineageKey = manualLineage(experienceId),
                            ),
                        ),
                        temporalState = input.temporalState,
                        validFrom = input.validFrom,
                        validUntil = input.validUntil,
                        significance = input.significance.toCanonical(),
                        entityLinks = input.entityLinks.map { link ->
                            MemoryEntityLinkInput(link.entityId, link.role)
                        },
                        relationships = input.relationships.map { relationship ->
                            MemoryRelationshipInput(
                                targetMemoryId = relationship.targetMemoryId,
                                relationshipType = relationship.relationshipType,
                                createdByExperienceId = experienceId,
                            )
                        },
                    ),
                    occurredAt = input.occurredAt,
                ),
            )
            ManualMemoryIntentResult.Remembered(
                memoryId = memoryId,
                experienceId = experienceId,
                maintenanceScheduling = ManualMemoryMaintenanceScheduling.NOT_REQUIRED,
            )
        }
    }

    suspend fun correct(input: ManualCorrectMemoryInput): ManualMemoryIntentResult {
        validateMeaning(input.replacementMeaning)?.let { return failure(it) }
        validateId(input.memoryId, ManualMemoryIdField.MEMORY_ID)?.let { return failure(it) }

        val replacementMemoryId = input.replacementMemoryId
            ?: generateMemoryId()
            ?: return storageFailure(ManualMemoryIntentKind.CORRECT)
        val experienceId = input.experienceId
            ?: generateExperienceId()
            ?: return storageFailure(ManualMemoryIntentKind.CORRECT)
        validateId(replacementMemoryId, ManualMemoryIdField.REPLACEMENT_MEMORY_ID)?.let { return failure(it) }
        validateId(experienceId, ManualMemoryIdField.EXPERIENCE_ID)?.let { return failure(it) }

        val committed = executeTransaction(ManualMemoryIntentKind.CORRECT) {
            val inaccurate = memoryDao.memory(input.memoryId)
                ?: abort(ManualMemoryIntentError.MemoryNotFound(input.memoryId))
            requireUnusedIds(replacementMemoryId, experienceId)
            val eventOrder = allocateEventOrder()
            insertManualExperience(
                experienceId = experienceId,
                eventOrder = eventOrder,
                sourceContent = input.replacementMeaning,
                occurredAt = input.occurredAt,
                sensitivity = inaccurate.sensitivity,
            )
            afterExperienceInserted(ManualMemoryIntentKind.CORRECT)

            val replacement = ValidatedMemoryInput(
                memoryId = replacementMemoryId,
                kind = inaccurate.kind,
                scope = inaccurate.scope,
                meaning = input.replacementMeaning,
                epistemicBasis = EpistemicBasis.EXPLICIT_CORRECTION,
                certainty = input.certainty ?: inaccurate.certainty,
                learnedAt = input.occurredAt,
                sensitivity = inaccurate.sensitivity,
                evidence = listOf(
                    MemoryEvidenceInput(
                        experienceId = experienceId,
                        role = EvidenceRole.CORRECTS,
                        epistemicBasis = EpistemicBasis.EXPLICIT_CORRECTION,
                        sourceCertainty = input.certainty ?: inaccurate.certainty,
                        lineageKey = manualLineage(experienceId),
                    ),
                ),
                temporalState = inaccurate.temporalState,
                validFrom = inaccurate.validFrom,
                validUntil = inaccurate.validUntil,
                lastConfirmedAt = inaccurate.lastConfirmedAt,
                significance = IntrinsicSignificanceInput(
                    autobiographical = inaccurate.autobiographicalSignificance,
                    relationship = inaccurate.relationshipSignificance,
                    emotional = inaccurate.emotionalSignificance,
                    practical = inaccurate.practicalSignificance,
                    identity = inaccurate.identitySignificance,
                ),
                entityLinks = memoryDao.entityLinksForMemory(inaccurate.id).map { link ->
                    MemoryEntityLinkInput(link.entityId, link.role)
                },
                relationships = emptyList(),
            )
            requireMemoryWriteSuccess(
                kind = ManualMemoryIntentKind.CORRECT,
                result = memoryTransactions.correctInCurrentTransaction(
                    CorrectMemoryInput(
                        inaccurateMemoryId = inaccurate.id,
                        replacement = replacement,
                        occurredAt = input.occurredAt,
                        triggeringExperienceId = experienceId,
                    ),
                ),
            )
            ManualMemoryIntentResult.Corrected(
                inaccurateMemoryId = inaccurate.id,
                replacementMemoryId = replacementMemoryId,
                experienceId = experienceId,
                maintenanceScheduling = ManualMemoryMaintenanceScheduling.NOT_REQUIRED,
            )
        }
        return if (committed is ManualMemoryIntentResult.Corrected) {
            committed.copy(maintenanceScheduling = scheduleRepairSweep())
        } else {
            committed
        }
    }

    suspend fun forget(input: ManualForgetMemoryInput): ManualMemoryIntentResult {
        validateId(input.memoryId, ManualMemoryIdField.MEMORY_ID)?.let { return failure(it) }
        val canonical = memoryTransactions.forget(
            MemoryStateTransitionInput(
                memoryId = input.memoryId,
                occurredAt = input.occurredAt,
            ),
        )
        return when (canonical) {
            is MemoryWriteResult.Success -> ManualMemoryIntentResult.Forgotten(
                memoryId = input.memoryId,
                maintenanceScheduling = scheduleRepairSweep(),
            )
            is MemoryWriteResult.Failure -> failure(mapMemoryWriteError(ManualMemoryIntentKind.FORGET, canonical.error))
        }
    }

    suspend fun delete(input: ManualDeleteMemoryInput): ManualMemoryIntentResult {
        validateId(input.memoryId, ManualMemoryIdField.MEMORY_ID)?.let { return failure(it) }
        return when (
            val canonical = safeDeleteService.deleteMemory(
                DeleteMemoryInput(memoryId = input.memoryId, occurredAt = input.occurredAt),
            )
        ) {
            is MemoryDeleteResult.Deleted -> ManualMemoryIntentResult.Deleted(
                memoryId = canonical.deletedMemoryId,
                suppressionTombstoneIds = canonical.suppressionTombstones
                    .mapTo(linkedSetOf()) { it.tombstoneId },
                maintenanceScheduling = scheduleRepairSweep(),
            )
            is MemoryDeleteResult.Failure -> failure(mapSafeDeleteError(canonical.error))
        }
    }

    private fun validateMeaning(meaning: String): ManualMemoryIntentError? = when {
        meaning.isBlank() -> ManualMemoryIntentError.InvalidMeaning(InvalidManualMeaningReason.BLANK)
        meaning.length > MAX_MANUAL_MEMORY_MEANING_CHARS ->
            ManualMemoryIntentError.InvalidMeaning(InvalidManualMeaningReason.TOO_LONG)
        else -> null
    }

    private fun validateTemporalWindow(validFrom: Long?, validUntil: Long?): ManualMemoryIntentError? =
        if (validFrom != null && validUntil != null && validUntil <= validFrom) {
            ManualMemoryIntentError.InvalidTemporalWindow
        } else {
            null
        }

    private fun validateStructuredReferences(
        entityLinks: List<ManualMemoryEntityLinkInput>,
        relationships: List<ManualMemoryRelationshipInput>,
    ): ManualMemoryIntentError? {
        entityLinks.forEach { link ->
            validateId(link.entityId, ManualMemoryIdField.ENTITY_ID)?.let { return it }
        }
        relationships.forEach { relationship ->
            validateId(relationship.targetMemoryId, ManualMemoryIdField.RELATED_MEMORY_ID)?.let { return it }
        }
        return null
    }

    private fun validateId(id: String, field: ManualMemoryIdField): ManualMemoryIntentError? =
        if (id.isBlank() || id.length > MAX_MANUAL_MEMORY_ID_CHARS) {
            ManualMemoryIntentError.InvalidId(field)
        } else {
            null
        }

    private fun requireUnusedIds(memoryId: String, experienceId: String) {
        if (memoryDao.memory(memoryId) != null) {
            abort(ManualMemoryIntentError.MemoryAlreadyExists(memoryId))
        }
        if (memoryDao.experienceCount(experienceId) != 0) {
            abort(ManualMemoryIntentError.ExperienceAlreadyExists(experienceId))
        }
    }

    private fun allocateEventOrder(): Long {
        return try {
            experienceOrderAllocator.next()
        } catch (_: ExperienceOrderOverflowException) {
            abort(ManualMemoryIntentError.EventOrderOverflow)
        }
    }

    private fun insertManualExperience(
        experienceId: String,
        eventOrder: Long,
        sourceContent: String,
        occurredAt: Long,
        sensitivity: SensitivityLevel,
    ) {
        memoryDao.insertExperience(
            ExperienceEntity(
                id = experienceId,
                eventOrder = eventOrder,
                experienceType = ExperienceType.MANUAL_MEMORY_INTENT,
                actor = ExperienceActor.SHAI,
                sourceContent = sourceContent,
                occurredAt = occurredAt,
                recordedAt = occurredAt,
                sensitivity = sensitivity,
                availability = ExperienceAvailability.AVAILABLE,
            ),
        )
    }

    private fun requireMemoryWriteSuccess(kind: ManualMemoryIntentKind, result: MemoryWriteResult) {
        if (result is MemoryWriteResult.Failure) {
            abort(mapMemoryWriteError(kind, result.error))
        }
    }

    private fun mapMemoryWriteError(
        kind: ManualMemoryIntentKind,
        error: MemoryWriteError,
    ): ManualMemoryIntentError = when (error) {
        is MemoryWriteError.MemoryNotFound -> ManualMemoryIntentError.MemoryNotFound(error.memoryId)
        is MemoryWriteError.MemoryAlreadyExists -> ManualMemoryIntentError.MemoryAlreadyExists(error.memoryId)
        is MemoryWriteError.IllegalStateTransition -> ManualMemoryIntentError.IllegalMemoryState(error.memoryId)
        is MemoryWriteError.InvalidTarget -> ManualMemoryIntentError.IllegalMemoryState(error.targetId)
        is MemoryWriteError.EntityNotFound ->
            ManualMemoryIntentError.InvalidReference(ManualMemoryReferenceType.ENTITY, error.entityId)
        is MemoryWriteError.RelatedMemoryNotFound ->
            ManualMemoryIntentError.InvalidReference(ManualMemoryReferenceType.RELATED_MEMORY, error.memoryId)
        is MemoryWriteError.ExperienceNotFound ->
            ManualMemoryIntentError.InvalidReference(ManualMemoryReferenceType.EXPERIENCE, error.experienceId)
        else -> ManualMemoryIntentError.MemoryWriteFailure(kind)
    }

    private fun mapSafeDeleteError(error: SafeDeleteError): ManualMemoryIntentError = when (error) {
        is SafeDeleteError.MissingMemory -> ManualMemoryIntentError.MemoryNotFound(error.memoryId)
        is SafeDeleteError.StorageFailure ->
            ManualMemoryIntentError.SafeDeleteFailure(ManualSafeDeleteFailureCode.STORAGE)
        else -> ManualMemoryIntentError.SafeDeleteFailure(ManualSafeDeleteFailureCode.CANONICAL_REJECTION)
    }

    private fun scheduleRepairSweep(): ManualMemoryMaintenanceScheduling = try {
        when (backgroundScheduler.enqueueRepairSweep()) {
            is RivenBackgroundScheduleResult.Enqueued -> ManualMemoryMaintenanceScheduling.ENQUEUED
            is RivenBackgroundScheduleResult.Failure -> ManualMemoryMaintenanceScheduling.FAILED
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ManualMemoryMaintenanceScheduling.FAILED
    }

    private suspend fun executeTransaction(
        kind: ManualMemoryIntentKind,
        block: suspend () -> ManualMemoryIntentResult,
    ): ManualMemoryIntentResult = try {
        database.withTransaction { block() }
    } catch (abort: ManualMemoryIntentAbort) {
        failure(abort.error)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SQLiteConstraintException) {
        storageFailure(kind)
    } catch (_: Exception) {
        storageFailure(kind)
    }

    private fun generateMemoryId(): String? = try {
        idGenerator.nextMemoryId()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun generateExperienceId(): String? = try {
        idGenerator.nextExperienceId()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun ManualMemorySignificanceInput.toCanonical() = IntrinsicSignificanceInput(
        autobiographical = autobiographical,
        relationship = relationship,
        emotional = emotional,
        practical = practical,
        identity = identity,
    )

    private fun manualLineage(experienceId: String): String = "MANUAL_INTENT:$experienceId"

    private fun failure(error: ManualMemoryIntentError): ManualMemoryIntentResult.Failure =
        ManualMemoryIntentResult.Failure(error)

    private fun storageFailure(kind: ManualMemoryIntentKind): ManualMemoryIntentResult.Failure =
        failure(ManualMemoryIntentError.StorageFailure(kind))

    private fun abort(error: ManualMemoryIntentError): Nothing = throw ManualMemoryIntentAbort(error)

    private class ManualMemoryIntentAbort(val error: ManualMemoryIntentError) : RuntimeException()
}

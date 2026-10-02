package com.shai.riven.data.memory

import androidx.room.withTransaction
import com.shai.riven.data.automaticmemory.AutomaticMemoryModelFailure
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.ConsolidationCheckpointEntity
import com.shai.riven.data.persistence.entity.MemoryEvidenceEntity
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.EvidenceRole
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryRelationshipType
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.validation.ValidationRecallCorpusChange
import com.shai.riven.data.validation.validationRecallCorpusFence
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException

class MemoryConsolidationService(
    private val database: RivenDatabase,
    private val decider: MemoryConsolidationDecider,
    private val profileId: String,
    private val idGenerator: MemoryLifecycleIdGenerator = MemoryLifecycleIdGenerator { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lifecycleDao = database.memoryLifecycleDao()
    private val memoryDao = database.memoryDao()
    private val memoryTransactions = MemoryTransactionService(database)
    private val recallFence = database.validationRecallCorpusFence()

    init {
        require(profileId.isNotBlank())
    }

    suspend fun consolidate(): MemoryConsolidationResult {
        val snapshot = try {
            readSnapshot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return MemoryConsolidationResult.Failure("CONSOLIDATION_READ_${failure::class.java.simpleName}", true)
        }
        lifecycleDao.consolidationCheckpointForCorpus(snapshot.corpusFingerprint)?.let { checkpoint ->
            return MemoryConsolidationResult.AlreadyProcessed(checkpoint.resultMemoryId)
        }
        if (snapshot.sources.size < MIN_SOURCE_MEMORIES) {
            return persistNoConsolidation(snapshot)
        }
        val proposal = try {
            decider.proposeConsolidation(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AutomaticMemoryModelFailure) {
            throw failure
        } catch (failure: Exception) {
            return MemoryConsolidationResult.Failure("CONSOLIDATION_MODEL_${failure::class.java.simpleName}", true)
        }
        validate(snapshot, proposal)?.let { code ->
            return MemoryConsolidationResult.Failure(code, false)
        }
        return try {
            recallFence.withCanonicalMutation(
                change = { result ->
                    ValidationRecallCorpusChange.memoryIds(
                        (result as? MemoryConsolidationResult.Created)?.let { listOf(it.memoryId) }.orEmpty(),
                    )
                },
            ) { mutation ->
                database.withTransaction {
                    val current = readSnapshotInCurrentTransaction()
                    if (current.corpusFingerprint != snapshot.corpusFingerprint) {
                        return@withTransaction MemoryConsolidationResult.Failure(
                            "CONSOLIDATION_STALE_CORPUS",
                            true,
                        )
                    }
                    lifecycleDao.consolidationCheckpointForCorpus(snapshot.corpusFingerprint)?.let { checkpoint ->
                        return@withTransaction MemoryConsolidationResult.AlreadyProcessed(checkpoint.resultMemoryId)
                    }
                    when (proposal) {
                        ConsolidationProposal.NoConsolidation -> {
                            insertCheckpoint(snapshot.corpusFingerprint, null, null)
                            MemoryConsolidationResult.NoConsolidation
                        }
                        is ConsolidationProposal.Create -> {
                            val sourceIds = proposal.sourceMemoryIds.distinct().sorted()
                            val sourceSetHash = fingerprint(sourceIds.joinToString("\u001f"))
                            lifecycleDao.consolidationCheckpointForSourceSet(sourceSetHash)?.let { checkpoint ->
                                insertCheckpoint(snapshot.corpusFingerprint, null, checkpoint.resultMemoryId)
                                return@withTransaction MemoryConsolidationResult.AlreadyProcessed(
                                    checkpoint.resultMemoryId,
                                )
                            }
                            val sourceById = current.sources.associateBy { it.memoryId }
                            val sourceMemories = sourceIds.map { checkNotNull(memoryDao.memory(it)) }
                            val sourceEvidence = sourceIds.flatMap { sourceId ->
                                lifecycleDao.availableEvidence(listOf(sourceId), MAX_EVIDENCE_PER_SOURCE)
                            }
                                .sortedWith(compareBy(MemoryEvidenceEntity::memoryId, MemoryEvidenceEntity::experienceId))
                            val uniqueEvidence = sourceEvidence.distinctBy(MemoryEvidenceEntity::experienceId)
                            check(uniqueEvidence.size >= MIN_INDEPENDENT_EXPERIENCES)
                            val memoryId = idGenerator.nextId().also { require(it.isNotBlank()) }
                            val occurredAt = clock()
                            val write = memoryTransactions.createValidatedInCurrentTransaction(
                                input = ValidatedMemoryInput(
                                    memoryId = memoryId,
                                    kind = proposal.kind,
                                    scope = proposal.scope,
                                    meaning = proposal.meaning.trim(),
                                    epistemicBasis = EpistemicBasis.CONSOLIDATION,
                                    certainty = proposal.certainty,
                                    learnedAt = occurredAt,
                                    sensitivity = proposal.sensitivity,
                                    evidence = uniqueEvidence.mapIndexed { index, evidence ->
                                        MemoryEvidenceInput(
                                            experienceId = evidence.experienceId,
                                            role = EvidenceRole.SUPPORTS,
                                            epistemicBasis = evidence.epistemicBasis,
                                            sourceCertainty = evidence.sourceCertainty,
                                            lineageKey = "consolidation:${sourceSetHash.take(32)}:$index",
                                        )
                                    },
                                    temporalState = proposal.temporalState,
                                    lastConfirmedAt = uniqueEvidence.maxOfOrNull { evidence ->
                                        sourceById.getValue(evidence.memoryId).updatedAt
                                    },
                                    significance = proposal.significance,
                                    entityLinks = proposal.entityLinks.map {
                                        MemoryEntityLinkInput(it.entityId, it.role)
                                    },
                                    relationships = sourceMemories.map { source ->
                                        MemoryRelationshipInput(
                                            targetMemoryId = source.id,
                                            relationshipType = MemoryRelationshipType.DERIVED_FROM,
                                        )
                                    },
                                ),
                                occurredAt = occurredAt,
                                mutation = mutation,
                            )
                            check(write is MemoryWriteResult.Success)
                            insertCheckpoint(snapshot.corpusFingerprint, sourceSetHash, memoryId)
                            MemoryConsolidationResult.Created(memoryId)
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            MemoryConsolidationResult.Failure("CONSOLIDATION_WRITE_${failure::class.java.simpleName}", true)
        }
    }

    private suspend fun persistNoConsolidation(
        snapshot: ConsolidationSnapshot,
    ): MemoryConsolidationResult = try {
        database.withTransaction {
            lifecycleDao.consolidationCheckpointForCorpus(snapshot.corpusFingerprint)?.let { checkpoint ->
                return@withTransaction MemoryConsolidationResult.AlreadyProcessed(checkpoint.resultMemoryId)
            }
            val current = readSnapshotInCurrentTransaction()
            if (current.corpusFingerprint != snapshot.corpusFingerprint) {
                return@withTransaction MemoryConsolidationResult.Failure("CONSOLIDATION_STALE_CORPUS", true)
            }
            insertCheckpoint(snapshot.corpusFingerprint, null, null)
            MemoryConsolidationResult.NoConsolidation
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        MemoryConsolidationResult.Failure("CONSOLIDATION_CHECKPOINT_${failure::class.java.simpleName}", true)
    }

    private suspend fun readSnapshot(): ConsolidationSnapshot = database.withTransaction {
        readSnapshotInCurrentTransaction()
    }

    private fun readSnapshotInCurrentTransaction(): ConsolidationSnapshot {
        val memories = lifecycleDao.eligibleSourceMemories(MAX_SOURCE_MEMORIES)
        val evidence = memories.flatMap { memory ->
            lifecycleDao.availableEvidence(listOf(memory.id), MAX_EVIDENCE_PER_SOURCE)
        }
        val evidenceByMemory = evidence.groupBy(MemoryEvidenceEntity::memoryId)
        val sources = memories.map { memory ->
            ConsolidationSourceMemory(
                memoryId = memory.id,
                kind = memory.kind,
                scope = memory.scope,
                meaning = memory.meaning.take(MAX_SOURCE_MEANING_CHARS),
                certainty = memory.certainty,
                sensitivity = memory.sensitivity,
                sourceExperienceIds = evidenceByMemory[memory.id].orEmpty()
                    .map(MemoryEvidenceEntity::experienceId)
                    .distinct()
                    .sorted(),
                updatedAt = memory.updatedAt,
            )
        }
        return ConsolidationSnapshot(
            corpusFingerprint = fingerprint(
                profileId + "\u001d" + sources.joinToString("\u001e") { source ->
                    listOf(
                        source.memoryId,
                        source.kind,
                        source.scope,
                        source.meaning,
                        source.certainty,
                        source.sensitivity,
                        source.updatedAt,
                        source.sourceExperienceIds.joinToString(","),
                    ).joinToString("|")
                },
            ),
            sources = sources,
        )
    }

    private fun validate(snapshot: ConsolidationSnapshot, proposal: ConsolidationProposal): String? {
        if (proposal is ConsolidationProposal.NoConsolidation) return null
        proposal as ConsolidationProposal.Create
        val ids = proposal.sourceMemoryIds
        if (ids.size !in MIN_SOURCE_MEMORIES..MAX_PROPOSAL_SOURCES || ids.distinct().size != ids.size) {
            return "CONSOLIDATION_INVALID_SOURCE_SET"
        }
        val sourceById = snapshot.sources.associateBy(ConsolidationSourceMemory::memoryId)
        val sources = ids.map { sourceById[it] ?: return "CONSOLIDATION_UNKNOWN_SOURCE" }
        val experiences = sources.flatMap(ConsolidationSourceMemory::sourceExperienceIds).distinct()
        if (experiences.size < MIN_INDEPENDENT_EXPERIENCES) return "CONSOLIDATION_NOT_INDEPENDENT"
        if (proposal.meaning.isBlank() || proposal.meaning.length > MAX_CONSOLIDATED_MEANING_CHARS) {
            return "CONSOLIDATION_INVALID_MEANING"
        }
        val weakestCertainty = sources.maxBy(ConsolidationSourceMemory::certainty).certainty
        if (proposal.certainty.ordinal < weakestCertainty.ordinal) return "CONSOLIDATION_CONFIDENCE_INFLATION"
        val sensitivityFloor = sources.maxBy(ConsolidationSourceMemory::sensitivity).sensitivity
        if (proposal.sensitivity.ordinal < sensitivityFloor.ordinal) return "CONSOLIDATION_SENSITIVITY_DOWNGRADE"
        val selfDevelopment = proposal.kind == MemoryKind.SELF_DEVELOPMENT || proposal.scope == MemoryScope.RIVEN
        if (selfDevelopment && (ids.size < 3 || experiences.size < 3)) {
            return "CONSOLIDATION_SELF_EVIDENCE_THRESHOLD"
        }
        val allowedEntities = ids.flatMap { memoryDao.entityLinksForMemory(it) }.mapTo(hashSetOf()) { it.entityId }
        if (proposal.entityLinks.any { it.entityId !in allowedEntities }) return "CONSOLIDATION_UNGROUNDED_ENTITY"
        return null
    }

    private fun insertCheckpoint(corpus: String, sourceSet: String?, memoryId: String?) {
        lifecycleDao.insertConsolidationCheckpoint(
            ConsolidationCheckpointEntity(
                id = idGenerator.nextId(),
                corpusFingerprint = corpus,
                sourceSetHash = sourceSet,
                resultMemoryId = memoryId,
                profileId = profileId,
                createdAt = clock(),
            ),
        )
    }

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MIN_SOURCE_MEMORIES = 2
        const val MIN_INDEPENDENT_EXPERIENCES = 2
        const val MAX_PROPOSAL_SOURCES = 5
        const val MAX_SOURCE_MEMORIES = 48
        const val MAX_SOURCE_MEANING_CHARS = 768
        const val MAX_EVIDENCE_PER_SOURCE = 16
        const val MAX_CONSOLIDATED_MEANING_CHARS = 4_096
    }
}

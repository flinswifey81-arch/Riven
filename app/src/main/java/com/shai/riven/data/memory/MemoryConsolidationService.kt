package com.shai.riven.data.memory

import androidx.room.withTransaction
import com.shai.riven.data.automaticmemory.AutomaticMemoryModelFailure
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.ConsolidationCheckpointEntity
import com.shai.riven.data.persistence.entity.MemoryEvidenceEntity
import com.shai.riven.data.persistence.model.EpistemicBasis
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
    private val maintenanceDao = database.maintenanceDao()
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
        lifecycleDao.consolidationCompletionForCorpus(snapshot.corpusFingerprint)?.let { checkpoint ->
            return MemoryConsolidationResult.AlreadyProcessed(checkpoint.resultMemoryId)
        }
        val sourceUniverse = snapshot.sources.map(ConsolidationSourceMemory::memoryId).sorted()
        processedCheckpoint(snapshot.corpusFingerprint, sourceUniverse)?.let { checkpoint ->
            return MemoryConsolidationResult.AlreadyProcessed(checkpoint.resultMemoryId)
        }
        if (snapshot.sources.size < MIN_SOURCE_MEMORIES) return persistNoConsolidation(snapshot)

        val proposal = try {
            decider.proposeConsolidation(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AutomaticMemoryModelFailure) {
            throw failure
        } catch (failure: Exception) {
            return MemoryConsolidationResult.Failure("CONSOLIDATION_MODEL_${failure::class.java.simpleName}", true)
        }
        if (proposal is ConsolidationProposal.Create) {
            processedCheckpoint(snapshot.corpusFingerprint, proposal.sourceMemoryIds.sorted())?.let { checkpoint ->
                return MemoryConsolidationResult.AlreadyProcessed(checkpoint.resultMemoryId)
            }
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
                    lifecycleDao.consolidationCompletionForCorpus(snapshot.corpusFingerprint)?.let { checkpoint ->
                        return@withTransaction MemoryConsolidationResult.AlreadyProcessed(checkpoint.resultMemoryId)
                    }
                    if (proposal is ConsolidationProposal.Create) {
                        processedCheckpoint(
                            current.corpusFingerprint,
                            proposal.sourceMemoryIds.sorted(),
                        )?.let { checkpoint ->
                            return@withTransaction MemoryConsolidationResult.AlreadyProcessed(
                                checkpoint.resultMemoryId,
                            )
                        }
                    }
                    validate(current, proposal)?.let { code ->
                        return@withTransaction MemoryConsolidationResult.Failure(code, false)
                    }
                    when (proposal) {
                        ConsolidationProposal.NoConsolidation -> {
                            insertCheckpoint(snapshot.corpusFingerprint, null, null, null)
                            MemoryConsolidationResult.NoConsolidation
                        }
                        is ConsolidationProposal.Create -> createConsolidation(current, proposal, mutation)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            MemoryConsolidationResult.Failure("CONSOLIDATION_WRITE_${failure::class.java.simpleName}", true)
        }
    }

    private fun createConsolidation(
        snapshot: ConsolidationSnapshot,
        proposal: ConsolidationProposal.Create,
        mutation: com.shai.riven.data.validation.ValidationRecallMutationToken,
    ): MemoryConsolidationResult {
        val sourceIds = proposal.sourceMemoryIds.distinct().sorted()
        val sourceById = snapshot.sources.associateBy(ConsolidationSourceMemory::memoryId)
        val selected = sourceIds.map(sourceById::getValue)
        val sourceSetHash = fingerprint(sourceSetCanonical(selected))
        lifecycleDao.consolidationCheckpointForSourceSet(sourceSetHash)?.let { checkpoint ->
            if (lifecycleDao.consolidationCheckpointsForCorpus(snapshot.corpusFingerprint)
                    .none { it.sourceSetHash == sourceSetHash }
            ) {
                insertCheckpoint(
                    corpus = snapshot.corpusFingerprint,
                    sourceSet = sourceSetHash,
                    sourceMemoryIds = sourceIds,
                    memoryId = checkpoint.resultMemoryId,
                )
            }
            return MemoryConsolidationResult.Reused(checkpoint.resultMemoryId)
        }

        val sourceMemories = sourceIds.map { checkNotNull(memoryDao.memory(it)) }
        val supportingEvidence = sourceIds.flatMap { sourceId ->
            currentEvidence(sourceId)
                .filter { it.role.isPositiveMemoryGrounding() }
        }.sortedWith(compareBy(MemoryEvidenceEntity::memoryId, MemoryEvidenceEntity::experienceId))
        val uniqueEvidence = supportingEvidence.distinctBy(MemoryEvidenceEntity::experienceId)
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
                        role = evidence.role,
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
                entityLinks = proposal.entityLinks.map { MemoryEntityLinkInput(it.entityId, it.role) },
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
        insertCheckpoint(snapshot.corpusFingerprint, sourceSetHash, sourceIds, memoryId)
        return MemoryConsolidationResult.Created(memoryId)
    }

    private suspend fun persistNoConsolidation(snapshot: ConsolidationSnapshot): MemoryConsolidationResult = try {
        database.withTransaction {
            lifecycleDao.consolidationCompletionForCorpus(snapshot.corpusFingerprint)?.let { checkpoint ->
                return@withTransaction MemoryConsolidationResult.AlreadyProcessed(checkpoint.resultMemoryId)
            }
            val current = readSnapshotInCurrentTransaction()
            if (current.corpusFingerprint != snapshot.corpusFingerprint) {
                return@withTransaction MemoryConsolidationResult.Failure("CONSOLIDATION_STALE_CORPUS", true)
            }
            insertCheckpoint(snapshot.corpusFingerprint, null, null, null)
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
        val sources = lifecycleDao.eligibleSourceMemories(MAX_SOURCE_MEMORIES).mapNotNull { memory ->
            val evidence = currentEvidence(memory.id)
            val supportingIds = evidence.asSequence()
                .filter { it.role.isPositiveMemoryGrounding() }
                .map(MemoryEvidenceEntity::experienceId)
                .distinct()
                .sorted()
                .toList()
            if (supportingIds.isEmpty()) return@mapNotNull null
            ConsolidationSourceMemory(
                memoryId = memory.id,
                kind = memory.kind,
                scope = memory.scope,
                meaning = memory.meaning.take(MAX_SOURCE_MEANING_CHARS),
                certainty = memory.certainty,
                sensitivity = memory.sensitivity,
                sourceExperienceIds = supportingIds,
                evidenceFingerprint = fingerprint(
                    evidence.sortedWith(compareBy(MemoryEvidenceEntity::experienceId, MemoryEvidenceEntity::role))
                        .joinToString("\u001e") { row ->
                            listOf(
                                row.experienceId,
                                row.role,
                                row.epistemicBasis,
                                row.sourceCertainty,
                                row.lineageKey,
                                row.createdAt,
                            ).joinToString("|")
                        },
                ),
                updatedAt = memory.updatedAt,
            )
        }
        val corpus = fingerprint(sources.joinToString("\u001d", transform = ::sourceCanonical))
        val processed = lifecycleDao.consolidationCheckpointsForCorpus(corpus)
            .mapNotNull { it.sourceMemoryIds?.let(::decodeSourceIds) }
        return ConsolidationSnapshot(corpus, sources, processed)
    }

    private fun currentEvidence(memoryId: String): List<MemoryEvidenceEntity> {
        val evidence = lifecycleDao.availableEvidence(listOf(memoryId), MAX_EVIDENCE_PER_SOURCE + 1)
        if (evidence.size > MAX_EVIDENCE_PER_SOURCE) return emptyList()
        return evidence.filterNot { row ->
            isEvidenceSuppressedInCurrentTransaction(
                maintenanceDao,
                row.experienceId,
                row.lineageKey,
            )
        }
    }

    private fun validate(snapshot: ConsolidationSnapshot, proposal: ConsolidationProposal): String? {
        if (proposal is ConsolidationProposal.NoConsolidation) return null
        proposal as ConsolidationProposal.Create
        val ids = proposal.sourceMemoryIds
        if (ids.size !in MIN_SOURCE_MEMORIES..MAX_PROPOSAL_SOURCES || ids.distinct().size != ids.size) {
            return "CONSOLIDATION_INVALID_SOURCE_SET"
        }
        if (snapshot.processedSourceSets.any { it.sorted() == ids.sorted() }) {
            return "CONSOLIDATION_SOURCE_SET_ALREADY_PROCESSED"
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

    private fun insertCheckpoint(
        corpus: String,
        sourceSet: String?,
        sourceMemoryIds: List<String>?,
        memoryId: String?,
    ) {
        lifecycleDao.insertConsolidationCheckpoint(
            ConsolidationCheckpointEntity(
                id = idGenerator.nextId(),
                corpusFingerprint = corpus,
                sourceSetHash = sourceSet,
                sourceMemoryIds = sourceMemoryIds?.let(::encodeSourceIds),
                resultMemoryId = memoryId,
                profileId = profileId,
                createdAt = clock(),
            ),
        )
    }

    private fun processedCheckpoint(
        corpus: String,
        sourceIds: List<String>,
    ): ConsolidationCheckpointEntity? = lifecycleDao.consolidationCheckpointsForCorpus(corpus)
        .firstOrNull { checkpoint ->
            checkpoint.sourceMemoryIds?.let(::decodeSourceIds)?.sorted() == sourceIds
        }

    private fun sourceSetCanonical(sources: List<ConsolidationSourceMemory>): String =
        sources.sortedBy(ConsolidationSourceMemory::memoryId).joinToString("\u001d", transform = ::sourceCanonical)

    private fun sourceCanonical(source: ConsolidationSourceMemory): String = listOf(
        source.memoryId,
        source.kind,
        source.scope,
        source.meaning,
        source.certainty,
        source.sensitivity,
        source.updatedAt,
        source.sourceExperienceIds.joinToString(","),
        source.evidenceFingerprint,
    ).joinToString("|")

    private fun encodeSourceIds(ids: List<String>): String = buildString {
        ids.sorted().forEach { id -> append(id.length).append(':').append(id) }
    }

    private fun decodeSourceIds(value: String): List<String>? {
        val ids = mutableListOf<String>()
        var offset = 0
        while (offset < value.length) {
            val separator = value.indexOf(':', offset)
            if (separator < 0) return null
            val length = value.substring(offset, separator).toIntOrNull() ?: return null
            val start = separator + 1
            val end = start + length
            if (length < 1 || end > value.length) return null
            ids += value.substring(start, end)
            offset = end
        }
        return ids
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
        const val MAX_EVIDENCE_PER_SOURCE = 64
        const val MAX_CONSOLIDATED_MEANING_CHARS = 4_096
    }
}

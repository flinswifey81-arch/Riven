package com.shai.riven.data.memory

import androidx.room.withTransaction
import com.shai.riven.data.automaticmemory.AutomaticMemoryModelFailure
import com.shai.riven.data.attention.ImmediateAttentionAbort
import com.shai.riven.data.attention.ImmediateAttentionGrounding
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.OpenLoopAuditHistoryEntity
import com.shai.riven.data.persistence.entity.OpenLoopEntity
import com.shai.riven.data.persistence.entity.OpenLoopPassCheckpointEntity
import com.shai.riven.data.persistence.entity.RepairJobEntity
import com.shai.riven.data.persistence.model.DerivedArtifactState
import com.shai.riven.data.persistence.model.OpenLoopAuditAction
import com.shai.riven.data.persistence.model.OpenLoopState
import com.shai.riven.data.persistence.model.RepairJobState
import com.shai.riven.data.persistence.model.RepairJobType
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException

class OpenLoopLifecycleService(
    private val database: RivenDatabase,
    private val decider: OpenLoopLifecycleDecider,
    private val idGenerator: MemoryLifecycleIdGenerator = MemoryLifecycleIdGenerator { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val grounding = ImmediateAttentionGrounding(database)
    private val lifecycleDao = database.memoryLifecycleDao()
    private val openLoopDao = database.openLoopDao()
    private val maintenanceDao = database.maintenanceDao()

    suspend fun process(experienceId: String): OpenLoopLifecycleResult {
        val snapshot = try {
            database.withTransaction {
                val actionable = grounding.readActionableForwardInCurrentTransaction(experienceId)
                OpenLoopLifecycleSnapshot(
                    attention = actionable.assessment,
                    grounding = actionable.snapshot,
                    currentLoops = relevantCurrentLoops(actionable.snapshot),
                )
            }
        } catch (abort: ImmediateAttentionAbort) {
            return OpenLoopLifecycleResult.Failure("OPEN_LOOP_${abort.error::class.java.simpleName}", false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return OpenLoopLifecycleResult.Failure("OPEN_LOOP_READ_${failure::class.java.simpleName}", true)
        }
        lifecycleDao.openLoopCheckpoint(experienceId)?.let { existing ->
            if (existing.attentionRevision == snapshot.attention.revision &&
                existing.timelineRevision == snapshot.grounding.timelineRevision
            ) {
                return OpenLoopLifecycleResult.AlreadyProcessed(experienceId)
            }
        }
        val inputFingerprint = fingerprint(snapshot.canonicalInput())
        val proposal = try {
            decider.proposeOpenLoop(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AutomaticMemoryModelFailure) {
            throw failure
        } catch (failure: Exception) {
            return OpenLoopLifecycleResult.Failure("OPEN_LOOP_MODEL_${failure::class.java.simpleName}", true)
        }
        validate(proposal, snapshot)?.let { code ->
            return OpenLoopLifecycleResult.Failure(code, false)
        }

        return try {
            database.withTransaction {
                grounding.revalidateActionableForwardInCurrentTransaction(snapshot.attention, snapshot.grounding)
                val currentLoops = relevantCurrentLoops(snapshot.grounding)
                val currentFingerprint = fingerprint(snapshot.copy(currentLoops = currentLoops).canonicalInput())
                if (currentFingerprint != inputFingerprint) {
                    return@withTransaction OpenLoopLifecycleResult.Failure("OPEN_LOOP_STALE_INPUT", true)
                }
                lifecycleDao.openLoopCheckpoint(experienceId)?.let { existing ->
                    if (existing.attentionRevision == snapshot.attention.revision &&
                        existing.timelineRevision == snapshot.grounding.timelineRevision
                    ) {
                        return@withTransaction OpenLoopLifecycleResult.AlreadyProcessed(experienceId)
                    }
                }
                val now = clock()
                val appliedId = when (proposal) {
                    OpenLoopLifecycleProposal.NoChange -> null
                    is OpenLoopLifecycleProposal.Create -> create(experienceId, proposal, now)
                    is OpenLoopLifecycleProposal.Transition -> transition(experienceId, proposal, now)
                }
                lifecycleDao.upsertOpenLoopCheckpoint(
                    OpenLoopPassCheckpointEntity(
                        experienceId = experienceId,
                        attentionRevision = snapshot.attention.revision,
                        timelineRevision = snapshot.grounding.timelineRevision,
                        inputFingerprint = inputFingerprint,
                        resultFingerprint = fingerprint(proposal.toString()),
                        updatedAt = now,
                    ),
                )
                OpenLoopLifecycleResult.Applied(appliedId)
            }
        } catch (abort: ImmediateAttentionAbort) {
            OpenLoopLifecycleResult.Failure("OPEN_LOOP_${abort.error::class.java.simpleName}", true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            OpenLoopLifecycleResult.Failure("OPEN_LOOP_WRITE_${failure::class.java.simpleName}", true)
        }
    }

    private fun create(
        experienceId: String,
        proposal: OpenLoopLifecycleProposal.Create,
        now: Long,
    ): String {
        val id = idGenerator.nextId().also { require(it.isNotBlank()) }
        val terminal = proposal.state in TERMINAL_STATES
        openLoopDao.insertOpenLoop(
            OpenLoopEntity(
                id = id,
                creationExperienceId = experienceId,
                resolutionExperienceId = experienceId.takeIf { terminal },
                title = proposal.title.trim(),
                description = proposal.description?.trim()?.takeIf(String::isNotEmpty),
                state = proposal.state,
                openedAt = now,
                dueAt = proposal.dueAt,
                closedAt = now.takeIf { terminal },
                sensitivity = proposal.sensitivity,
                createdAt = now,
                updatedAt = now,
            ),
        )
        openLoopDao.insertAuditHistory(
            OpenLoopAuditHistoryEntity(
                id = idGenerator.nextId(),
                openLoopId = id,
                action = OpenLoopAuditAction.CREATED,
                triggeringExperienceId = experienceId,
                toState = proposal.state,
                occurredAt = now,
            ),
        )
        return id
    }

    private fun transition(
        experienceId: String,
        proposal: OpenLoopLifecycleProposal.Transition,
        now: Long,
    ): String {
        val current = lifecycleDao.openLoop(proposal.openLoopId) ?: error("Open loop disappeared")
        val affectedArtifacts = lifecycleDao.artifactIdsForOpenLoop(current.id)
        val terminal = proposal.state in TERMINAL_STATES
        check(lifecycleDao.updateOpenLoop(
            current.copy(
                state = proposal.state,
                resolutionExperienceId = experienceId.takeIf { terminal },
                dueAt = proposal.dueAt,
                closedAt = now.takeIf { terminal },
                updatedAt = now,
            ),
        ) == 1)
        openLoopDao.insertAuditHistory(
            OpenLoopAuditHistoryEntity(
                id = idGenerator.nextId(),
                openLoopId = current.id,
                action = OpenLoopAuditAction.STATE_CHANGED,
                triggeringExperienceId = experienceId,
                fromState = current.state,
                toState = proposal.state,
                occurredAt = now,
            ),
        )
        if (affectedArtifacts.isNotEmpty()) {
            maintenanceDao.markOpenLoopDerivedArtifacts(
                openLoopIds = listOf(current.id),
                state = DerivedArtifactState.STALE,
                invalidatedAt = now,
            )
            lifecycleDao.deleteDerivedPayloads(affectedArtifacts)
            affectedArtifacts.distinct().sorted().forEach { artifactId ->
                maintenanceDao.insertRepairJob(
                    RepairJobEntity(
                        id = idGenerator.nextId(),
                        jobType = RepairJobType.REBUILD_DERIVED,
                        state = RepairJobState.PENDING,
                        targetType = "DERIVED_ARTIFACT",
                        targetId = artifactId,
                        attemptCount = 0,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
        }
        return current.id
    }

    private fun validate(
        proposal: OpenLoopLifecycleProposal,
        snapshot: OpenLoopLifecycleSnapshot,
    ): String? = when (proposal) {
        OpenLoopLifecycleProposal.NoChange -> null
        is OpenLoopLifecycleProposal.Create -> when {
            proposal.title.isBlank() || proposal.title.length > MAX_TITLE_CHARS -> "OPEN_LOOP_INVALID_TITLE"
            proposal.description?.length?.let { it > MAX_DESCRIPTION_CHARS } == true -> "OPEN_LOOP_INVALID_DESCRIPTION"
            proposal.state in TERMINAL_STATES -> "OPEN_LOOP_TERMINAL_CREATE"
            proposal.sensitivity.ordinal < snapshot.grounding.sensitivity.ordinal ->
                "OPEN_LOOP_SENSITIVITY_DOWNGRADE"
            else -> null
        }
        is OpenLoopLifecycleProposal.Transition -> {
            val current = snapshot.currentLoops.singleOrNull { it.openLoopId == proposal.openLoopId }
                ?: return "OPEN_LOOP_UNKNOWN_TARGET"
            if (proposal.state !in allowedTransitions(current.state)) "OPEN_LOOP_INVALID_TRANSITION" else null
        }
    }

    private fun allowedTransitions(state: OpenLoopState): Set<OpenLoopState> = when (state) {
        OpenLoopState.PLANNED -> setOf(OpenLoopState.ACTIVE, OpenLoopState.WAITING, OpenLoopState.BLOCKED) + TERMINAL_STATES
        OpenLoopState.ACTIVE -> setOf(OpenLoopState.WAITING, OpenLoopState.BLOCKED) + TERMINAL_STATES
        OpenLoopState.WAITING -> setOf(OpenLoopState.ACTIVE, OpenLoopState.BLOCKED) + TERMINAL_STATES
        OpenLoopState.BLOCKED -> setOf(OpenLoopState.ACTIVE, OpenLoopState.WAITING) + TERMINAL_STATES
        else -> emptySet()
    }

    private fun OpenLoopEntity.toLifecycleItem() = OpenLoopLifecycleItem(
        openLoopId = id,
        title = title,
        description = description,
        state = state,
        dueAt = dueAt,
        sensitivity = sensitivity,
        updatedAt = updatedAt,
    )

    /**
     * Bounded lexical/entity lookup reaches beyond the recent-page scan. Higher-sensitivity loop
     * details enter a STANDARD conversation only for an explicit id/entity/title target; unrelated
     * sensitive loops are never used as general recent context.
     */
    private fun relevantCurrentLoops(
        source: com.shai.riven.data.attention.ImmediateAttentionSnapshot,
    ): List<OpenLoopLifecycleItem> {
        val queryText = buildString {
            append(source.sourceContent.orEmpty().take(MAX_OPEN_LOOP_QUERY_CHARS))
            source.followingActiveContext.forEach { message ->
                append(' ').append(message.content.take(MAX_OPEN_LOOP_QUERY_CHARS - length.coerceAtMost(MAX_OPEN_LOOP_QUERY_CHARS)))
                if (length >= MAX_OPEN_LOOP_QUERY_CHARS) return@forEach
            }
        }.take(MAX_OPEN_LOOP_QUERY_CHARS)
        val queryTokens = lexicalTokens(queryText)
        val entityIds = source.groundedEntityLinks.mapTo(hashSetOf()) { it.entityId }
        val targeted = buildList {
            if (queryTokens.isNotEmpty()) {
                addAll(
                    lifecycleDao.unresolvedOpenLoopsByIds(
                        NONTERMINAL_STATES,
                        queryTokens.toList(),
                        MAX_TARGETED_OPEN_LOOPS,
                    ),
                )
            }
            queryTokens.asSequence()
                .filter { it.length >= MIN_TARGET_TERM_CHARS && it !in TARGET_STOP_WORDS }
                .take(MAX_TARGET_QUERY_TERMS)
                .forEach { term ->
                    addAll(
                        lifecycleDao.unresolvedOpenLoopsMatchingTerm(
                            NONTERMINAL_STATES,
                            term,
                            MAX_TARGETS_PER_TERM,
                        ),
                    )
                }
            if (entityIds.isNotEmpty()) {
                addAll(
                    lifecycleDao.unresolvedOpenLoopsForEntities(
                        NONTERMINAL_STATES,
                        entityIds.toList(),
                        MAX_TARGETED_OPEN_LOOPS,
                    ),
                )
            }
        }.distinctBy(OpenLoopEntity::id)

        val recent = mutableListOf<OpenLoopEntity>()
        var cursor = ""
        while (recent.size < MAX_OPEN_LOOP_LOOKUP) {
            val page = lifecycleDao.unresolvedOpenLoopPage(
                NONTERMINAL_STATES,
                cursor,
                minOf(OPEN_LOOP_LOOKUP_PAGE_SIZE, MAX_OPEN_LOOP_LOOKUP - recent.size),
            )
            if (page.isEmpty()) break
            recent += page
            cursor = page.last().id
            if (page.size < OPEN_LOOP_LOOKUP_PAGE_SIZE) break
        }
        val loops = (targeted + recent).distinctBy(OpenLoopEntity::id)
        if (loops.isEmpty()) return emptyList()
        val links = openLoopDao.conversationalContextEntityLinks(
            loops.map(OpenLoopEntity::id),
            MAX_OPEN_LOOP_STRUCTURAL_ROWS + 1,
        )
        check(links.size <= MAX_OPEN_LOOP_STRUCTURAL_ROWS) { "Open-loop structural capacity exceeded" }
        val entitiesByLoop = links.groupBy { it.openLoopId }
            .mapValues { (_, values) -> values.mapTo(hashSetOf()) { it.entityId } }
        val normalizedQuery = queryText.lowercase()
        val relevant = loops.mapNotNull { loop ->
            val directlyNamed = queryText.contains(loop.id, ignoreCase = true)
            val loopTokens = lexicalTokens(loop.title + " " + loop.description.orEmpty())
            val tokenOverlap = queryTokens.intersect(loopTokens).size
            val entityOverlap = entityIds.intersect(entitiesByLoop[loop.id].orEmpty()).size
            val targetTokens = lexicalTokens(loop.title)
                .filterNotTo(linkedSetOf()) { it in TARGET_STOP_WORDS }
            val titlePhraseTarget = loop.title.trim().lowercase()
                .takeIf { it.length >= MIN_TARGET_TERM_CHARS }
                ?.let { normalizedTitle -> normalizedQuery.contains(normalizedTitle) } == true
            val strongLexicalTarget = titlePhraseTarget || when {
                targetTokens.isEmpty() -> false
                targetTokens.size == 1 -> targetTokens.single().length >= 6 &&
                    targetTokens.single() in queryTokens
                else -> {
                    val matched = queryTokens.intersect(targetTokens).size
                    matched >= 2 && matched * 2 >= targetTokens.size
                }
            }
            val explicitTarget = directlyNamed || entityOverlap > 0 || strongLexicalTarget
            val isRelevant = explicitTarget || tokenOverlap > 0
            if (!isRelevant) return@mapNotNull null
            if (loop.sensitivity.ordinal > source.sensitivity.ordinal && !explicitTarget) {
                return@mapNotNull null
            }
            RankedLoop(
                loop,
                (if (directlyNamed) 1_000 else 0) +
                    (if (strongLexicalTarget) 500 else 0) +
                    tokenOverlap * 10 +
                    entityOverlap * 100,
            )
        }.sortedWith(compareByDescending<RankedLoop> { it.score }
            .thenByDescending { it.loop.updatedAt }
            .thenBy { it.loop.id })
        val selectedIds = relevant.mapTo(linkedSetOf()) { it.loop.id }
        val safeRecent = loops.asSequence()
            .filter { it.sensitivity == com.shai.riven.data.persistence.model.SensitivityLevel.STANDARD }
            .filterNot { it.id in selectedIds }
            .sortedWith(compareByDescending<OpenLoopEntity> { it.updatedAt }.thenBy { it.id })
            .take(MAX_OPEN_LOOPS)
            .toList()
        return (relevant.map { it.loop } + safeRecent)
            .distinctBy(OpenLoopEntity::id)
            .take(MAX_OPEN_LOOPS)
            .map { it.toLifecycleItem() }
    }

    private fun lexicalTokens(value: String): Set<String> = TOKEN_PATTERN.findAll(value)
        .take(MAX_OPEN_LOOP_QUERY_TERMS)
        .map { it.value.lowercase() }
        .toSet()

    private fun OpenLoopLifecycleSnapshot.canonicalInput(): String = buildString {
        append(attention.experienceId).append('|').append(attention.revision).append('|')
        append(grounding.timelineRevision).append('|').append(grounding.sourceContent).append('|')
        append(grounding.followingActiveContext.joinToString("\u001f") { it.messageId + ":" + it.content }).append('|')
        append(currentLoops.sortedBy { it.openLoopId }.joinToString("\u001e") {
            "${it.openLoopId}:${it.state}:${it.dueAt}:${it.sensitivity}:${it.updatedAt}:${it.title}:${it.description}"
        })
    }

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_OPEN_LOOPS = 64
        const val MAX_OPEN_LOOP_LOOKUP = 512
        const val OPEN_LOOP_LOOKUP_PAGE_SIZE = 64
        const val MAX_TARGETED_OPEN_LOOPS = 256
        const val MAX_TARGET_QUERY_TERMS = 16
        const val MAX_TARGETS_PER_TERM = 16
        const val MIN_TARGET_TERM_CHARS = 4
        const val MAX_OPEN_LOOP_STRUCTURAL_ROWS = 4_096
        const val MAX_OPEN_LOOP_QUERY_CHARS = 8_192
        const val MAX_OPEN_LOOP_QUERY_TERMS = 64
        const val MAX_TITLE_CHARS = 256
        const val MAX_DESCRIPTION_CHARS = 2_048
        val TERMINAL_STATES = setOf(OpenLoopState.COMPLETED, OpenLoopState.ABANDONED, OpenLoopState.EXPIRED)
        val NONTERMINAL_STATES = OpenLoopState.entries.filterNot(TERMINAL_STATES::contains)
        val TARGET_STOP_WORDS = setOf(
            "about", "after", "again", "also", "been", "before", "complete", "done", "from",
            "have", "into", "just", "later", "need", "that", "their", "them", "then", "there",
            "this", "with", "your",
        )
        val TOKEN_PATTERN = Regex("[\\p{L}\\p{N}_-]{2,64}")
    }

    private data class RankedLoop(val loop: OpenLoopEntity, val score: Int)
}

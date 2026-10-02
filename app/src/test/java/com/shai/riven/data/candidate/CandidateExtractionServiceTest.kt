package com.shai.riven.data.candidate

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.attention.AttentionAntiSignal
import com.shai.riven.data.attention.PositiveAttentionSignal
import com.shai.riven.data.conversation.AppendTimelineMessageInput
import com.shai.riven.data.conversation.CommitRegeneratedAssistantResponseInput
import com.shai.riven.data.conversation.ConversationTimelineService
import com.shai.riven.data.conversation.CreateTimelineConversationInput
import com.shai.riven.data.conversation.NewTimelineMessageInput
import com.shai.riven.data.conversation.RewindTimelineInput
import com.shai.riven.data.conversation.TimelineWriteResult
import com.shai.riven.data.experience.ConversationExperienceLookupResult
import com.shai.riven.data.experience.ConversationExperienceService
import com.shai.riven.data.memory.MAX_CANDIDATE_MEANING_CHARS
import com.shai.riven.data.memory.sourceLineageHash
import com.shai.riven.data.memory.sourceClaimSuppressionHash
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.CandidateMemoryEntity
import com.shai.riven.data.persistence.entity.CandidateMemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.ConversationDraftEntity
import com.shai.riven.data.persistence.entity.ExperienceAttentionAssessmentEntity
import com.shai.riven.data.persistence.entity.ExperienceAttentionSignalEntity
import com.shai.riven.data.persistence.entity.ExperienceEntity
import com.shai.riven.data.persistence.entity.ShaiSystemInstructionsEntity
import com.shai.riven.data.persistence.entity.SuppressionTombstoneEntity
import com.shai.riven.data.persistence.model.AttentionOutcome
import com.shai.riven.data.persistence.model.AttentionSignal
import com.shai.riven.data.persistence.model.AttentionSignalPolarity
import com.shai.riven.data.persistence.model.CandidateEvidenceRole
import com.shai.riven.data.persistence.model.CandidateMemoryState
import com.shai.riven.data.persistence.model.ConversationStatus
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.ExperienceActor
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.ExperienceType
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.MessageDeliveryState
import com.shai.riven.data.persistence.model.MessageRole
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.persistence.model.SuppressionKind
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CandidateExtractionServiceTest {
    private lateinit var database: RivenDatabase
    private lateinit var extractor: RecordingExtractor
    private lateinit var service: CandidateExtractionService
    private lateinit var timeline: ConversationTimelineService
    private lateinit var experienceService: ConversationExperienceService
    private var idCounter = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        extractor = RecordingExtractor()
        service = candidateService()
        timeline = ConversationTimelineService(database)
        experienceService = ConversationExperienceService(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun oneDirectUserCandidatePersistsExactProposalAndOneSeed() = runBlocking {
        forwardExperience()
        val exactMeaning = "  Shai is pretty sure she currently likes ramen eggs.  "
        extractor.proposal = extraction(proposal(meaning = exactMeaning, certainty = MemoryCertainty.PROBABLE))

        val result = extracted(extract())

        assertEquals(listOf("candidate-1"), result.createdCandidateIds)
        val candidate = database.memoryDao().candidateMemory("candidate-1")!!
        assertEquals(exactMeaning, candidate.proposedMeaning)
        assertEquals(MemoryKind.SEMANTIC, candidate.proposedKind)
        assertEquals(MemoryScope.SHAI, candidate.proposedScope)
        assertEquals(EpistemicBasis.DIRECT_USER_STATEMENT, candidate.proposedEpistemicBasis)
        assertEquals(MemoryCertainty.PROBABLE, candidate.proposedCertainty)
        val evidence = database.memoryDao().candidateEvidence("candidate-1").single()
        assertEquals("experience", evidence.experienceId)
        assertEquals(CandidateEvidenceRole.SEED, evidence.role)
        assertEquals(0, evidence.evidenceOrder)
        assertEquals(99, evidence.createdAt)
    }

    @Test
    fun multipleCandidatesFromOneExperienceEachGetDistinctSeedLineage() = runBlocking {
        forwardExperience()
        extractor.proposal = extraction(
            proposal("Shai disliked eggs during childhood."),
            proposal("Shai currently likes ramen eggs.", kind = MemoryKind.EPISODIC),
            proposal("Shai currently likes runny fried eggs.", kind = MemoryKind.RELATIONSHIP),
        )

        val result = extracted(extract())

        assertEquals(3, result.createdCandidateIds.size)
        val seeds = result.createdCandidateIds.map { database.memoryDao().candidateEvidence(it).single() }
        assertEquals(3, seeds.map { it.lineageKey }.toSet().size)
        assertTrue(seeds.all { it.role == CandidateEvidenceRole.SEED && it.evidenceOrder == 0 })
    }

    @Test
    fun zeroCandidatesIsValidAndCreatesNoRows() = runBlocking {
        forwardExperience()
        extractor.proposal = extraction()

        assertTrue(extract() is CandidateExtractionResult.NoCandidates)
        assertEquals(0, database.memoryDao().candidateMemoryCount())
        assertEquals(0L, rowCount("candidate_memory_evidence"))
    }

    @Test
    fun tentativeCandidateStateIsPreserved() = runBlocking {
        assertStatePreserved(CandidateMemoryState.TENTATIVE)
    }

    @Test
    fun pendingContextCandidateStateIsPreserved() = runBlocking {
        assertStatePreserved(CandidateMemoryState.PENDING_CONTEXT)
    }

    @Test
    fun readyForValidationCandidateStateIsPreserved() = runBlocking {
        assertStatePreserved(CandidateMemoryState.READY_FOR_VALIDATION)
    }

    @Test
    fun acceptedProposalIsRejectedWithoutRows() = runBlocking {
        assertDisallowedState(CandidateMemoryState.ACCEPTED)
    }

    @Test
    fun rejectedProposalIsRejectedWithoutRows() = runBlocking {
        assertDisallowedState(CandidateMemoryState.REJECTED)
    }

    @Test
    fun blankMeaningIsRejectedWithoutRows() = runBlocking {
        forwardExperience()
        extractor.proposal = extraction(proposal(" \t\n"))

        val error = failure(extract())

        assertEquals(
            CandidateExtractionError.InvalidCandidateProposal(0, InvalidCandidateProposalReason.BLANK_MEANING),
            error,
        )
        assertEquals(0, database.memoryDao().candidateMemoryCount())
    }

    @Test
    fun overLimitMeaningIsRejectedWithoutRows() = runBlocking {
        forwardExperience()
        extractor.proposal = extraction(proposal("x".repeat(MAX_CANDIDATE_MEANING_CHARS + 1)))

        val error = failure(extract())

        assertEquals(
            CandidateExtractionError.InvalidCandidateProposal(0, InvalidCandidateProposalReason.MEANING_TOO_LONG),
            error,
        )
        assertEquals(0, database.memoryDao().candidateMemoryCount())
    }

    @Test
    fun tooManyCandidatesIsRejectedAtomically() = runBlocking {
        forwardExperience()
        extractor.proposal = CandidateExtractionProposal(
            (0..MAX_CANDIDATES_PER_EXTRACTION).map { proposal("meaning-$it") },
        )

        assertTrue(failure(extract()) is CandidateExtractionError.TooManyCandidates)
        assertEquals(0, database.memoryDao().candidateMemoryCount())
        assertEquals(0L, rowCount("candidate_memory_evidence"))
    }

    @Test
    fun duplicateProposalsAreRejectedAtomically() = runBlocking {
        forwardExperience()
        val same = proposal("Exact duplicate")
        extractor.proposal = extraction(proposal("Distinct first"), same, same)

        assertEquals(CandidateExtractionError.DuplicateCandidateProposal(1, 2), failure(extract()))
        assertEquals(0, database.memoryDao().candidateMemoryCount())
    }

    @Test
    fun userDirectStatementIsAllowed() = runBlocking {
        forwardExperience(actor = ExperienceActor.SHAI)
        extractor.proposal = extraction(proposal(basis = EpistemicBasis.DIRECT_USER_STATEMENT))
        assertEquals(1, extracted(extract()).createdCandidateIds.size)
    }

    @Test
    fun userToolObservationIsRejected() = runBlocking {
        forwardExperience(actor = ExperienceActor.SHAI)
        extractor.proposal = extraction(proposal(basis = EpistemicBasis.TOOL_OBSERVATION))
        assertTrue(failure(extract()) is CandidateExtractionError.InvalidEpistemicBasis)
    }

    @Test
    fun toolObservationIsAllowedForToolSource() = runBlocking {
        forwardExperience(actor = ExperienceActor.TOOL, type = ExperienceType.TOOL_RESULT)
        extractor.proposal = extraction(proposal(basis = EpistemicBasis.TOOL_OBSERVATION))
        assertEquals(1, extracted(extract()).createdCandidateIds.size)
    }

    @Test
    fun rivenDirectExperienceIsAllowedForRivenSource() = runBlocking {
        forwardExperience(actor = ExperienceActor.RIVEN)
        extractor.proposal = extraction(
            proposal(
                basis = EpistemicBasis.DIRECT_RIVEN_EXPERIENCE,
                scope = MemoryScope.RIVEN,
                state = CandidateMemoryState.TENTATIVE,
            ),
        )
        assertEquals(1, extracted(extract()).createdCandidateIds.size)
    }

    @Test
    fun consolidationIsRejectedInImmediateExtraction() = runBlocking {
        forwardExperience()
        extractor.proposal = extraction(proposal(basis = EpistemicBasis.CONSOLIDATION))
        val error = failure(extract()) as CandidateExtractionError.InvalidEpistemicBasis
        assertEquals(InvalidEpistemicBasisReason.CONSOLIDATION_NOT_ALLOWED, error.reason)
    }

    @Test
    fun shaiExplicitCorrectionIsAllowedForGroundedUserSource() = runBlocking {
        createConversation()
        append("shai-correction", role = MessageRole.USER)
        val experienceId = experienceId("shai-correction")
        insertAttention(
            experienceId,
            signals = listOf(AttentionSignal.CORRECTION_OR_REVISION to AttentionSignalPolarity.POSITIVE),
        )
        extractor.proposal = extraction(proposal(basis = EpistemicBasis.EXPLICIT_CORRECTION))

        val candidateId = extracted(extract(experienceId)).createdCandidateIds.single()

        assertEquals(
            EpistemicBasis.EXPLICIT_CORRECTION,
            database.memoryDao().candidateMemory(candidateId)!!.proposedEpistemicBasis,
        )
    }

    @Test
    fun rivenExplicitSelfCorrectionIsAllowedForGroundedAssistantSource() = runBlocking {
        createConversation()
        append(
            "riven-correction",
            role = MessageRole.ASSISTANT,
            delivery = MessageDeliveryState.SUCCEEDED,
        )
        val experienceId = experienceId("riven-correction")
        insertAttention(
            experienceId,
            signals = listOf(AttentionSignal.CORRECTION_OR_REVISION to AttentionSignalPolarity.POSITIVE),
        )
        extractor.proposal = extraction(
            proposal(
                basis = EpistemicBasis.EXPLICIT_CORRECTION,
                scope = MemoryScope.RIVEN,
                state = CandidateMemoryState.TENTATIVE,
            ),
        )

        val candidateId = extracted(extract(experienceId)).createdCandidateIds.single()
        val candidate = database.memoryDao().candidateMemory(candidateId)!!
        val seed = database.memoryDao().candidateEvidence(candidateId).single()

        assertEquals(EpistemicBasis.EXPLICIT_CORRECTION, candidate.proposedEpistemicBasis)
        assertEquals(experienceId, seed.experienceId)
        assertEquals(CandidateEvidenceRole.SEED, seed.role)
        assertEquals(0L, rowCount("memories"))
    }

    @Test
    fun rivenExplicitCorrectionWithoutSignalIsRejected() = runBlocking {
        createConversation()
        append(
            "riven-no-signal",
            role = MessageRole.ASSISTANT,
            delivery = MessageDeliveryState.SUCCEEDED,
        )
        val experienceId = experienceId("riven-no-signal")
        insertAttention(
            experienceId,
            signals = listOf(AttentionSignal.PREFERENCE to AttentionSignalPolarity.POSITIVE),
        )
        extractor.proposal = extraction(
            proposal(
                basis = EpistemicBasis.EXPLICIT_CORRECTION,
                scope = MemoryScope.RIVEN,
                state = CandidateMemoryState.TENTATIVE,
            ),
        )

        val error = failure(extract(experienceId)) as CandidateExtractionError.InvalidEpistemicBasis

        assertEquals(InvalidEpistemicBasisReason.CORRECTION_SIGNAL_REQUIRED, error.reason)
        assertEquals(0, database.memoryDao().candidateMemoryCount())
    }

    @Test
    fun toolExplicitCorrectionIsRejectedEvenWithCorrectionSignal() = runBlocking {
        forwardExperience(
            actor = ExperienceActor.TOOL,
            type = ExperienceType.TOOL_RESULT,
            signals = listOf(AttentionSignal.CORRECTION_OR_REVISION to AttentionSignalPolarity.POSITIVE),
        )
        extractor.proposal = extraction(proposal(basis = EpistemicBasis.EXPLICIT_CORRECTION))

        val error = failure(extract()) as CandidateExtractionError.InvalidEpistemicBasis

        assertEquals(InvalidEpistemicBasisReason.SOURCE_ACTOR_MISMATCH, error.reason)
        assertEquals(0, database.memoryDao().candidateMemoryCount())
    }

    @Test
    fun mismatchedRivenActorAndUserSourceIsRejected() = runBlocking {
        createConversation()
        append("mismatched-source", role = MessageRole.USER)
        val experienceId = experienceId("mismatched-source")
        database.openHelper.writableDatabase.execSQL(
            "UPDATE experiences SET actor='RIVEN' WHERE experience_id='$experienceId'",
        )
        insertAttention(
            experienceId,
            signals = listOf(AttentionSignal.CORRECTION_OR_REVISION to AttentionSignalPolarity.POSITIVE),
        )
        extractor.proposal = extraction(
            proposal(
                basis = EpistemicBasis.EXPLICIT_CORRECTION,
                scope = MemoryScope.RIVEN,
                state = CandidateMemoryState.TENTATIVE,
            ),
        )

        val error = failure(extract(experienceId)) as CandidateExtractionError.InvalidEpistemicBasis

        assertEquals(InvalidEpistemicBasisReason.SOURCE_ACTOR_MISMATCH, error.reason)
        assertEquals(0, database.memoryDao().candidateMemoryCount())
    }

    @Test
    fun inferenceRemainsInferenceAndDoesNotBecomeDirectEvidence() = runBlocking {
        forwardExperience()
        extractor.proposal = extraction(proposal(basis = EpistemicBasis.INFERENCE))
        val id = extracted(extract()).createdCandidateIds.single()
        assertEquals(EpistemicBasis.INFERENCE, database.memoryDao().candidateMemory(id)!!.proposedEpistemicBasis)
    }

    @Test
    fun uncertainCandidatePreservesUncertainCertainty() = runBlocking {
        forwardExperience()
        extractor.proposal = extraction(proposal(certainty = MemoryCertainty.UNCERTAIN))
        val id = extracted(extract()).createdCandidateIds.single()
        assertEquals(MemoryCertainty.UNCERTAIN, database.memoryDao().candidateMemory(id)!!.proposedCertainty)
    }

    @Test
    fun sameExtractionTwiceReturnsExistingWithoutDuplicate() = runBlocking {
        forwardExperience()
        extractor.proposal = extraction(proposal("Idempotent claim"))

        val first = extracted(extract())
        val second = extracted(extract(extractedAt = 100))

        assertEquals(first.createdCandidateIds, second.existingCandidateIds)
        assertTrue(second.createdCandidateIds.isEmpty())
        assertEquals(1, database.memoryDao().candidateMemoryCount())
        assertEquals(1L, rowCount("candidate_memory_evidence"))
    }

    @Test
    fun shortWindowReassessmentPromotesMatchingDeferredCandidate() = runBlocking {
        forwardExperience()
        val pending = proposal(
            "Context now resolves the claim",
            certainty = MemoryCertainty.UNCERTAIN,
            state = CandidateMemoryState.PENDING_CONTEXT,
            sensitivity = SensitivityLevel.HIGHLY_SENSITIVE,
        )
        val ready = pending.copy(
            proposedCertainty = MemoryCertainty.CERTAIN,
            proposedState = CandidateMemoryState.READY_FOR_VALIDATION,
            proposedSensitivity = SensitivityLevel.SENSITIVE,
        )
        insertExistingCandidateForLineage(ready, stored = pending)
        extractor.proposal = extraction(ready)

        val result = extracted(extract(extractedAt = 100))

        assertEquals(listOf("existing"), result.existingCandidateIds)
        val promoted = checkNotNull(database.memoryDao().candidateMemory("existing"))
        assertEquals(CandidateMemoryState.READY_FOR_VALIDATION, promoted.state)
        assertEquals(MemoryCertainty.CERTAIN, promoted.proposedCertainty)
        assertEquals(SensitivityLevel.SENSITIVE, promoted.sensitivity)
        assertEquals(100, promoted.updatedAt)
    }

    @Test
    fun multiClaimExperienceHasDistinctLineages() = runBlocking {
        forwardExperience()
        extractor.proposal = extraction(proposal("Claim A"), proposal("Claim B"))
        val ids = extracted(extract()).createdCandidateIds
        val lineages = ids.map { database.memoryDao().candidateEvidence(it).single().lineageKey }
        assertNotEquals(lineages[0], lineages[1])
        assertEquals(listOf("0", "1"), lineages.map { it.split(':')[1] })
        assertTrue(lineages.all { it.matches(Regex("AUTO_CANDIDATE_V2:\\d+:[0-9a-f]{64}")) })
    }

    @Test
    fun sameLineageWithDifferentStoredMeaningFailsClosed() = runBlocking {
        forwardExperience()
        val requested = proposal("Requested meaning")
        insertExistingCandidateForLineage(requested, stored = requested.copy(proposedMeaning = "Corrupted meaning"))
        extractor.proposal = extraction(requested)

        val error = failure(extract()) as CandidateExtractionError.CandidateLineageConflict

        assertEquals(CandidateLineageConflictReason.PROPOSAL_MISMATCH, error.reason)
        assertEquals(1, database.memoryDao().candidateMemoryCount())
    }

    @Test
    fun sameSourceClaimSlotWithChangedMeaningFailsClosedWithoutCreatingDuplicate() = runBlocking {
        forwardExperience()
        val original = proposal("Original pending wording", state = CandidateMemoryState.PENDING_CONTEXT)
        val paraphrase = original.copy(proposedMeaning = "Paraphrased pending wording")
        insertExistingCandidateForLineage(original)
        extractor.proposal = extraction(paraphrase)

        val error = failure(extract()) as CandidateExtractionError.CandidateLineageConflict

        assertEquals(CandidateLineageConflictReason.PROPOSAL_MISMATCH, error.reason)
        assertEquals(1, database.memoryDao().candidateMemoryCount())
        assertEquals("Original pending wording", database.memoryDao().candidateMemory("existing")?.proposedMeaning)
    }

    @Test
    fun initialCandidateHasExactlyOneSeed() = runBlocking {
        val candidateId = extractOneCandidate()
        assertEquals(1, database.memoryDao().candidateEvidenceRoleCount(candidateId, CandidateEvidenceRole.SEED))
        assertEquals(1, database.memoryDao().candidateMemoryEvidenceCount(candidateId))
    }

    @Test
    fun initialSeedEvidenceOrderIsZero() = runBlocking {
        val candidateId = extractOneCandidate()
        assertEquals(0, database.memoryDao().candidateEvidence(candidateId).single().evidenceOrder)
    }

    @Test
    fun seedExperienceIsExactSourceExperience() = runBlocking {
        val candidateId = extractOneCandidate()
        assertEquals("experience", database.memoryDao().candidateEvidence(candidateId).single().experienceId)
    }

    @Test
    fun activeForgetTombstoneBlocksSameHistoricalClaim() = runBlocking {
        assertSuppressed(SuppressionKind.FORGET, isActive = true)
    }

    @Test
    fun activeDeleteTombstoneBlocksSameHistoricalClaim() = runBlocking {
        assertSuppressed(SuppressionKind.DELETE, isActive = true)
    }

    @Test
    fun inactiveTombstoneDoesNotBlock() = runBlocking {
        forwardExperience()
        val candidate = proposal("Inactive suppression")
        insertTombstone("experience", candidate, SuppressionKind.FORGET, isActive = false)
        extractor.proposal = extraction(candidate)

        assertEquals(1, extracted(extract()).createdCandidateIds.size)
    }

    @Test
    fun suppressingOneClaimDoesNotBlockOtherClaimFromSameExperience() = runBlocking {
        forwardExperience()
        val blocked = proposal("Blocked claim")
        val allowed = proposal("Independent claim", kind = MemoryKind.EPISODIC)
        insertTombstone("experience", blocked, SuppressionKind.DELETE, isActive = true)
        extractor.proposal = extraction(blocked, allowed)

        val result = extracted(extract())

        assertEquals(1, result.suppressedLineageCount)
        assertEquals(1, result.createdCandidateIds.size)
        assertEquals("Independent claim", database.memoryDao().candidateMemory(result.createdCandidateIds.single())!!.proposedMeaning)
    }

    @Test
    fun claimSlotTombstoneBlocksParaphraseWithoutBlockingSecondClaim() = runBlocking {
        forwardExperience()
        insertTombstone(
            "experience",
            proposal("Original forgotten phrasing"),
            SuppressionKind.FORGET,
            isActive = true,
            claimOrdinal = 0,
        )
        extractor.proposal = extraction(
            proposal("Paraphrased forgotten meaning"),
            proposal("Unrelated fact from the same source", kind = MemoryKind.EPISODIC),
        )

        val result = extracted(extract())

        assertEquals(1, result.suppressedLineageCount)
        assertEquals(1, result.createdCandidateIds.size)
        assertEquals(
            "Unrelated fact from the same source",
            database.memoryDao().candidateMemory(result.createdCandidateIds.single())?.proposedMeaning,
        )
    }

    @Test
    fun independentExperienceMayRelearnSameHumanReadableMeaning() = runBlocking {
        val meaning = "Shai likes the same thing."
        forwardExperience("experience-a")
        val candidate = proposal(meaning)
        insertTombstone("experience-a", candidate, SuppressionKind.FORGET, isActive = true)
        extractor.proposal = extraction(candidate)
        assertEquals(1, extracted(extract("experience-a")).suppressedLineageCount)

        forwardExperience("experience-b")
        val second = extracted(extract("experience-b"))

        assertEquals(1, second.createdCandidateIds.size)
        assertEquals(meaning, database.memoryDao().candidateMemory(second.createdCandidateIds.single())!!.proposedMeaning)
    }

    @Test
    fun tombstoneContentRemainsOpaque() = runBlocking {
        forwardExperience()
        val privateMeaning = "Private meaning that must never enter a tombstone"
        val row = insertTombstone(
            "experience",
            proposal(privateMeaning),
            SuppressionKind.FORGET,
            isActive = true,
        )

        assertEquals(64, row.sourceLineageHash.length)
        assertFalse(row.sourceLineageHash.contains(privateMeaning))
        assertEquals(row, database.maintenanceDao().suppressionTombstone(row.sourceLineageHash))
    }

    @Test
    fun sharedHashHelperPreservesExistingForgetAndDeleteHashFormat() {
        val expected = sha256("experience:AUTO_CANDIDATE_V1:abc")
        assertEquals(expected, sourceLineageHash("experience", "AUTO_CANDIDATE_V1:abc"))
        assertEquals(64, expected.length)
        assertEquals(expected.lowercase(), expected)
    }

    @Test
    fun claimSuppressionHashIgnoresSemanticDigestButKeepsSourceSlotNarrow() {
        val original = sourceClaimSuppressionHash(
            "experience",
            "AUTO_CANDIDATE_V2:0:${"a".repeat(64)}",
        )
        val paraphrase = sourceClaimSuppressionHash(
            "experience",
            "AUTO_CANDIDATE_V2:0:${"b".repeat(64)}",
        )
        val sibling = sourceClaimSuppressionHash(
            "experience",
            "AUTO_CANDIDATE_V2:1:${"b".repeat(64)}",
        )

        assertEquals(original, paraphrase)
        assertNotEquals(original, sibling)
        assertEquals(64, original.length)
    }

    @Test
    fun rewindDuringExtractorPersistsNoCandidate() = runBlocking {
        createConversation()
        append("root")
        append("source")
        val experienceId = experienceId("source")
        insertAttention(experienceId)
        extractor.proposal = extraction(proposal())
        extractor.beforeReturn = {
            val head = database.conversationTimelineDao().timelineHead("conversation")!!
            assertTrue(
                timeline.rewindTo(RewindTimelineInput("conversation", "root", head.timelineRevision, 20))
                    is TimelineWriteResult.Rewound,
            )
        }

        assertTrue(failure(extract(experienceId)) is CandidateExtractionError.StaleCandidateExtractionContext)
        assertEquals(0, database.memoryDao().candidateMemoryCount())
    }

    @Test
    fun regenerateDuringExtractorPersistsNoCandidate() = runBlocking {
        createConversation()
        append("root")
        append("source", role = MessageRole.ASSISTANT, delivery = MessageDeliveryState.SUCCEEDED)
        val experienceId = experienceId("source")
        insertAttention(experienceId)
        extractor.proposal = extraction(
            proposal(
                basis = EpistemicBasis.DIRECT_RIVEN_EXPERIENCE,
                scope = MemoryScope.RIVEN,
                state = CandidateMemoryState.TENTATIVE,
            ),
        )
        extractor.beforeReturn = {
            val head = database.conversationTimelineDao().timelineHead("conversation")!!
            val result = timeline.commitRegeneratedAssistantResponse(
                CommitRegeneratedAssistantResponseInput(
                    "conversation",
                    "source",
                    newMessage("replacement", MessageRole.ASSISTANT, MessageDeliveryState.SUCCEEDED, 30),
                    head.timelineRevision,
                    30,
                ),
            )
            assertTrue(result is TimelineWriteResult.AssistantRegenerated)
        }

        assertTrue(failure(extract(experienceId)) is CandidateExtractionError.StaleCandidateExtractionContext)
        assertEquals(0, database.memoryDao().candidateMemoryCount())
    }

    @Test
    fun attentionReassessmentDuringExtractorPersistsNoCandidate() = runBlocking {
        forwardExperience()
        extractor.proposal = extraction(proposal())
        extractor.beforeReturn = {
            val current = database.experienceAttentionDao().assessment("experience")!!
            database.experienceAttentionDao().updateAssessment(current.copy(revision = 2, updatedAt = 2))
        }

        val error = failure(extract()) as CandidateExtractionError.StaleCandidateExtractionContext

        assertEquals(StaleCandidateExtractionReason.ATTENTION_REVISION_CHANGED, error.reason)
        assertEquals(0, database.memoryDao().candidateMemoryCount())
    }

    @Test
    fun experienceDeletedDuringExtractorPersistsNoCandidate() = runBlocking {
        forwardExperience()
        extractor.proposal = extraction(proposal())
        extractor.beforeReturn = {
            database.memoryDao().deleteExperience(database.memoryDao().experience("experience")!!)
        }

        assertTrue(failure(extract()) is CandidateExtractionError.MissingExperience)
        assertEquals(0, database.memoryDao().candidateMemoryCount())
    }

    @Test
    fun sourceProvenanceCorruptedDuringExtractorPersistsNoCandidate() = runBlocking {
        createConversation()
        append("source")
        val experienceId = experienceId("source")
        insertAttention(experienceId)
        extractor.proposal = extraction(proposal())
        extractor.beforeReturn = {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE experience_message_sources SET source_role='SUPPORTING' WHERE experience_id='$experienceId'",
            )
        }

        assertTrue(failure(extract(experienceId)) is CandidateExtractionError.InvalidConversationSourceProvenance)
        assertEquals(0, database.memoryDao().candidateMemoryCount())
    }

    @Test
    fun extractorFailureIsTypedAndPersistsNoCandidate() = runBlocking {
        forwardExperience()
        extractor.failure = IllegalStateException("private source must not appear in error")

        val error = failure(extract()) as CandidateExtractionError.ExtractorFailure

        assertEquals("IllegalStateException", error.causeType)
        assertEquals(0, database.memoryDao().candidateMemoryCount())
    }

    @Test(expected = CancellationException::class)
    fun extractorCancellationPropagates() = runBlocking {
        forwardExperience()
        extractor.failure = CancellationException("cancelled")
        extract()
        Unit
    }

    @Test
    fun noCandidateAttentionCannotExtract() = runBlocking {
        forwardExperience(outcome = AttentionOutcome.NO_CANDIDATE)
        val error = failure(extract()) as CandidateExtractionError.AttentionNotForward
        assertEquals(AttentionOutcome.NO_CANDIDATE, error.outcome)
        assertTrue(extractor.snapshots.isEmpty())
    }

    @Test
    fun deferForContextAttentionCannotExtract() = runBlocking {
        forwardExperience(outcome = AttentionOutcome.DEFER_FOR_CONTEXT)
        val error = failure(extract()) as CandidateExtractionError.AttentionNotForward
        assertEquals(AttentionOutcome.DEFER_FOR_CONTEXT, error.outcome)
        assertTrue(extractor.snapshots.isEmpty())
    }

    @Test
    fun missingAttentionCannotExtract() = runBlocking {
        insertExperience()
        assertTrue(failure(extract()) is CandidateExtractionError.AttentionNotFound)
        assertTrue(extractor.snapshots.isEmpty())
    }

    @Test
    fun manualMemoryIntentCannotExtract() = runBlocking {
        forwardExperience(type = ExperienceType.MANUAL_MEMORY_INTENT)
        assertTrue(failure(extract()) is CandidateExtractionError.AlreadyConsumedManualMemoryIntent)
        assertEquals(0, database.memoryDao().candidateMemoryCount())
    }

    @Test
    fun inactiveHistoricalForwardAssessmentCannotExtract() = runBlocking {
        createConversation()
        append("root")
        append("source")
        val experienceId = experienceId("source")
        insertAttention(experienceId)
        val head = database.conversationTimelineDao().timelineHead("conversation")!!
        assertTrue(
            timeline.rewindTo(RewindTimelineInput("conversation", "root", head.timelineRevision, 20))
                is TimelineWriteResult.Rewound,
        )

        assertTrue(failure(extract(experienceId)) is CandidateExtractionError.InactiveConversationSource)
        assertEquals(0, database.memoryDao().candidateMemoryCount())
    }

    @Test
    fun activeForwardAssessmentCanExtract() = runBlocking {
        createConversation()
        append("source")
        val experienceId = experienceId("source")
        insertAttention(experienceId)
        extractor.proposal = extraction(proposal())
        assertEquals(1, extracted(extract(experienceId)).createdCandidateIds.size)
    }

    @Test
    fun openLoopSignalDoesNotAutomaticallyCreateOpenLoopRow() = runBlocking {
        forwardExperience(signals = listOf(AttentionSignal.OPEN_LOOP to AttentionSignalPolarity.POSITIVE))
        extractor.proposal = extraction(proposal())
        extract()
        assertEquals(0L, rowCount("open_loops"))
    }

    @Test
    fun zeroMemoryCandidatesForOpenLoopOnlyInterpretationIsValid() = runBlocking {
        forwardExperience(signals = listOf(AttentionSignal.OPEN_LOOP to AttentionSignalPolarity.POSITIVE))
        extractor.proposal = extraction()
        assertTrue(extract() is CandidateExtractionResult.NoCandidates)
        assertEquals(0L, rowCount("open_loops"))
    }

    @Test
    fun candidateExtractionCreatesZeroOpenLoopState() = runBlocking {
        extractOneCandidate()
        assertEquals(0L, rowCount("open_loops"))
        assertEquals(0L, rowCount("open_loop_entity_links"))
        assertEquals(0L, rowCount("open_loop_audit_history"))
    }

    @Test
    fun extractionCreatesZeroMemoryRows() = runBlocking {
        extractOneCandidate()
        assertEquals(0L, rowCount("memories"))
    }

    @Test
    fun extractionCreatesZeroMemoryEvidence() = runBlocking {
        extractOneCandidate()
        assertEquals(0L, rowCount("memory_evidence"))
    }

    @Test
    fun extractionCreatesZeroMemoryRelationships() = runBlocking {
        extractOneCandidate()
        assertEquals(0L, rowCount("memory_relationships"))
    }

    @Test
    fun extractionCreatesZeroAudits() = runBlocking {
        extractOneCandidate()
        assertEquals(0L, rowCount("memory_audit_history"))
        assertEquals(0L, rowCount("open_loop_audit_history"))
    }

    @Test
    fun extractionCreatesZeroRepairJobs() = runBlocking {
        extractOneCandidate()
        assertEquals(0L, rowCount("repair_jobs"))
    }

    @Test
    fun extractionDoesNotChangeAttentionRows() = runBlocking {
        forwardExperience(signals = listOf(AttentionSignal.PREFERENCE to AttentionSignalPolarity.POSITIVE))
        extractor.proposal = extraction(proposal())
        val assessment = database.experienceAttentionDao().assessment("experience")
        val signals = database.experienceAttentionDao().signals("experience")

        extract()

        assertEquals(assessment, database.experienceAttentionDao().assessment("experience"))
        assertEquals(signals, database.experienceAttentionDao().signals("experience"))
    }

    @Test
    fun extractionDoesNotChangeExperience() = runBlocking {
        forwardExperience()
        extractor.proposal = extraction(proposal())
        val before = database.memoryDao().experience("experience")
        extract()
        assertEquals(before, database.memoryDao().experience("experience"))
    }

    @Test
    fun extractionDoesNotChangeTimeline() = runBlocking {
        createConversation()
        append("source")
        val experienceId = experienceId("source")
        insertAttention(experienceId)
        extractor.proposal = extraction(proposal())
        val head = database.conversationTimelineDao().timelineHead("conversation")
        val messages = database.conversationTimelineDao().allMessages("conversation")
        extract(experienceId)
        assertEquals(head, database.conversationTimelineDao().timelineHead("conversation"))
        assertEquals(messages, database.conversationTimelineDao().allMessages("conversation"))
    }

    @Test
    fun extractionDoesNotChangeDraft() = runBlocking {
        database.conversationTimelineDao().insertConversation(
            com.shai.riven.data.persistence.entity.ConversationEntity(
                "draft-conversation",
                1,
                1,
                ConversationStatus.ACTIVE,
            ),
        )
        database.conversationDraftDao().insertDraft(
            ConversationDraftEntity("draft-conversation", "private draft", 1, 1, 1),
        )
        val before = database.conversationDraftDao().draft("draft-conversation")
        extractOneCandidate()
        assertEquals(before, database.conversationDraftDao().draft("draft-conversation"))
    }

    @Test
    fun extractionDoesNotChangeSystemInstructions() = runBlocking {
        database.shaiSystemInstructionsDao().insert(
            ShaiSystemInstructionsEntity("instructions", "private", true, 1, 1, 1),
        )
        val before = database.shaiSystemInstructionsDao().instructions("instructions")
        extractOneCandidate()
        assertEquals(before, database.shaiSystemInstructionsDao().instructions("instructions"))
    }

    @Test
    fun successfulExtractionChangesOnlyCandidateTables() = runBlocking {
        forwardExperience()
        extractor.proposal = extraction(proposal())
        val before = allTableCounts()

        extracted(extract())

        val after = allTableCounts()
        assertEquals(before.keys, after.keys)
        before.keys
            .filterNot { it == "candidate_memories" || it == "candidate_memory_evidence" }
            .forEach { table -> assertEquals("Unexpected mutation in $table", before[table], after[table]) }
        assertEquals(before.getValue("candidate_memories") + 1, after["candidate_memories"])
        assertEquals(before.getValue("candidate_memory_evidence") + 1, after["candidate_memory_evidence"])
    }

    @Test
    fun extractorReceivesGroundedAttentionSnapshotAndNoMemories() = runBlocking {
        forwardExperience(
            content = "Exact grounded source",
            signals = listOf(
                AttentionSignal.PREFERENCE to AttentionSignalPolarity.POSITIVE,
                AttentionSignal.MODEL_GENERATED_ASSUMPTION to AttentionSignalPolarity.ANTI_SIGNAL,
            ),
        )
        extractor.proposal = extraction()
        assertTrue(extract() is CandidateExtractionResult.NoCandidates)
        val snapshot = extractor.snapshots.single()
        assertEquals("Exact grounded source", snapshot.sourceContent)
        assertEquals(setOf(PositiveAttentionSignal.PREFERENCE), snapshot.positiveSignals)
        assertEquals(setOf(AttentionAntiSignal.MODEL_GENERATED_ASSUMPTION), snapshot.antiSignals)
        assertEquals(1, snapshot.attentionRevision)
        assertEquals(AttentionOutcome.FORWARD_FOR_INTERPRETATION, snapshot.attentionOutcome)
        assertEquals(0L, rowCount("memories"))
    }

    @Test
    fun candidateReadApiReturnsStableProposalSnapshots() = runBlocking {
        val id = extractOneCandidate()
        val result = service.readCandidatesSeededByExperience("experience")
            as ReadCandidatesForExperienceResult.Candidates
        assertEquals(listOf(id), result.candidates.map { it.candidateId })
        assertEquals("Retained meaning", result.candidates.single().proposedMeaning)
        assertTrue(result.candidates.single().lineageKey.startsWith("AUTO_CANDIDATE_V2:0:"))
    }

    @Test
    fun candidateIdCollisionIsTypedAndRollsBackWholeExtraction() = runBlocking {
        forwardExperience()
        database.memoryDao().insertCandidateMemory(candidateEntity("collision", proposal("Unrelated existing")))
        var call = 0
        service = candidateService(CandidateIdGenerator { if (call++ == 0) "first-new" else "collision" })
        extractor.proposal = extraction(proposal("First new"), proposal("Second new"))

        assertEquals(CandidateExtractionError.CandidateIdCollision("collision"), failure(extract()))
        assertNull(database.memoryDao().candidateMemory("first-new"))
        assertNotNull(database.memoryDao().candidateMemory("collision"))
        assertEquals(1, database.memoryDao().candidateMemoryCount())
    }

    @Test
    fun blankGeneratedCandidateIdIsTypedWithoutRows() = runBlocking {
        forwardExperience()
        service = candidateService(CandidateIdGenerator { " " })
        extractor.proposal = extraction(proposal())
        val error = failure(extract()) as CandidateExtractionError.CandidateIdGenerationFailure
        assertEquals(CandidateIdGenerationFailureReason.BLANK_ID, error.reason)
        assertEquals(0, database.memoryDao().candidateMemoryCount())
    }

    @Test
    fun candidateIdGeneratorExceptionIsBoundedAndTyped() = runBlocking {
        forwardExperience(content = "private source")
        service = candidateService(CandidateIdGenerator { error("private generator message") })
        extractor.proposal = extraction(proposal())
        val error = failure(extract()) as CandidateExtractionError.CandidateIdGenerationFailure
        assertEquals(CandidateIdGenerationFailureReason.GENERATOR_EXCEPTION, error.reason)
        assertEquals("IllegalStateException", error.causeType)
        assertEquals(0, database.memoryDao().candidateMemoryCount())
    }

    @Test
    fun rejectedExistingCandidateRemainsExistingWithoutReopen() = runBlocking {
        forwardExperience()
        val candidate = proposal("Resolved rejected proposal")
        insertExistingCandidateForLineage(candidate, state = CandidateMemoryState.REJECTED)
        extractor.proposal = extraction(candidate)

        val result = extracted(extract())

        assertEquals(listOf("existing"), result.existingCandidateIds)
        assertEquals(CandidateMemoryState.REJECTED, database.memoryDao().candidateMemory("existing")!!.state)
    }

    @Test
    fun acceptedResidueFailsClosedWithoutReopeningCandidate() = runBlocking {
        forwardExperience()
        val candidate = proposal("Improper accepted residue")
        insertExistingCandidateForLineage(candidate, state = CandidateMemoryState.ACCEPTED)
        extractor.proposal = extraction(candidate)

        val error = failure(extract()) as CandidateExtractionError.CandidateLineageConflict

        assertEquals(CandidateLineageConflictReason.ACCEPTED_RESIDUE, error.reason)
        assertEquals(CandidateMemoryState.ACCEPTED, database.memoryDao().candidateMemory("existing")!!.state)
    }

    @Test
    fun missingExperienceReturnsTypedErrorBeforeExtractorInvocation() = runBlocking {
        val error = failure(extract("missing"))
        assertEquals(CandidateExtractionError.MissingExperience("missing"), error)
        assertTrue(extractor.snapshots.isEmpty())
    }

    @Test
    fun unavailableExperienceReturnsTypedErrorBeforeExtractorInvocation() = runBlocking {
        forwardExperience(availability = ExperienceAvailability.DELETED)
        assertEquals(CandidateExtractionError.ExperienceUnavailable("experience"), failure(extract()))
        assertTrue(extractor.snapshots.isEmpty())
    }

    private fun candidateService(
        generator: CandidateIdGenerator = CandidateIdGenerator { "candidate-${++idCounter}" },
    ) = CandidateExtractionService(database, extractor, generator)

    private fun forwardExperience(
        id: String = "experience",
        actor: ExperienceActor = ExperienceActor.SHAI,
        type: ExperienceType = ExperienceType.SHARED_EVENT,
        availability: ExperienceAvailability = ExperienceAvailability.AVAILABLE,
        outcome: AttentionOutcome = AttentionOutcome.FORWARD_FOR_INTERPRETATION,
        content: String = "Grounded source content",
        signals: List<Pair<AttentionSignal, AttentionSignalPolarity>> = listOf(
            AttentionSignal.PREFERENCE to AttentionSignalPolarity.POSITIVE,
        ),
    ): String {
        insertExperience(id, actor, type, availability, content)
        insertAttention(id, outcome, signals = signals)
        return id
    }

    private fun insertExperience(
        id: String = "experience",
        actor: ExperienceActor = ExperienceActor.SHAI,
        type: ExperienceType = ExperienceType.SHARED_EVENT,
        availability: ExperienceAvailability = ExperienceAvailability.AVAILABLE,
        content: String = "Grounded source content",
    ) {
        database.memoryDao().insertExperience(
            ExperienceEntity(
                id = id,
                eventOrder = (database.memoryDao().maximumEventOrder() ?: 0) + 1,
                experienceType = type,
                actor = actor,
                sourceContent = content,
                occurredAt = 10,
                recordedAt = 10,
                sensitivity = SensitivityLevel.SENSITIVE,
                availability = availability,
            ),
        )
    }

    private fun insertAttention(
        experienceId: String,
        outcome: AttentionOutcome = AttentionOutcome.FORWARD_FOR_INTERPRETATION,
        revision: Long = 1,
        signals: List<Pair<AttentionSignal, AttentionSignalPolarity>> = listOf(
            AttentionSignal.PREFERENCE to AttentionSignalPolarity.POSITIVE,
        ),
    ) {
        database.experienceAttentionDao().insertAssessment(
            ExperienceAttentionAssessmentEntity(experienceId, outcome, revision, 11, 11),
        )
        signals.forEach { (signal, polarity) ->
            database.experienceAttentionDao().insertSignal(
                ExperienceAttentionSignalEntity(experienceId, signal, polarity, 11),
            )
        }
    }

    private fun proposal(
        meaning: String = "Retained meaning",
        kind: MemoryKind = MemoryKind.SEMANTIC,
        scope: MemoryScope = MemoryScope.SHAI,
        basis: EpistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
        certainty: MemoryCertainty = MemoryCertainty.CERTAIN,
        state: CandidateMemoryState = CandidateMemoryState.READY_FOR_VALIDATION,
        sensitivity: SensitivityLevel = SensitivityLevel.SENSITIVE,
    ) = CandidateMemoryProposal(meaning, kind, scope, basis, certainty, state, sensitivity)

    private fun extraction(vararg candidates: CandidateMemoryProposal) =
        CandidateExtractionProposal(candidates.toList())

    private suspend fun extract(
        experienceId: String = "experience",
        extractedAt: Long = 99,
    ) = service.extract(ExtractCandidateMemoriesInput(experienceId, extractedAt))

    private suspend fun extractOneCandidate(): String {
        forwardExperience()
        extractor.proposal = extraction(proposal())
        return extracted(extract()).createdCandidateIds.single()
    }

    private suspend fun assertStatePreserved(state: CandidateMemoryState) {
        forwardExperience()
        extractor.proposal = extraction(proposal(state = state))
        val id = extracted(extract()).createdCandidateIds.single()
        assertEquals(state, database.memoryDao().candidateMemory(id)!!.state)
    }

    private suspend fun assertDisallowedState(state: CandidateMemoryState) {
        forwardExperience()
        extractor.proposal = extraction(proposal(state = state))
        val error = failure(extract()) as CandidateExtractionError.InvalidCandidateProposal
        assertEquals(InvalidCandidateProposalReason.DISALLOWED_STATE, error.reason)
        assertEquals(0, database.memoryDao().candidateMemoryCount())
    }

    private suspend fun assertSuppressed(kind: SuppressionKind, isActive: Boolean) {
        forwardExperience()
        val candidate = proposal("Suppressed historical claim")
        insertTombstone("experience", candidate, kind, isActive)
        extractor.proposal = extraction(candidate)

        val result = extracted(extract())

        assertEquals(1, result.suppressedLineageCount)
        assertTrue(result.createdCandidateIds.isEmpty())
        assertEquals(0, database.memoryDao().candidateMemoryCount())
    }

    private fun insertTombstone(
        experienceId: String,
        candidate: CandidateMemoryProposal,
        kind: SuppressionKind,
        isActive: Boolean,
        claimOrdinal: Int = 0,
    ): SuppressionTombstoneEntity {
        val lineage = candidateClaimLineageKey(experienceId, candidate, claimOrdinal)
        val row = SuppressionTombstoneEntity(
            id = "tombstone-${rowCount("suppression_tombstones") + 1}",
            kind = kind,
            sourceLineageHash = sourceClaimSuppressionHash(experienceId, lineage),
            isActive = isActive,
            createdAt = 1,
            formatVersion = 1,
        )
        database.maintenanceDao().insertSuppressionTombstone(row)
        return row
    }

    private fun insertExistingCandidateForLineage(
        requested: CandidateMemoryProposal,
        stored: CandidateMemoryProposal = requested,
        state: CandidateMemoryState = stored.proposedState,
    ) {
        database.memoryDao().insertCandidateMemory(candidateEntity("existing", stored, state))
        database.memoryDao().insertCandidateMemoryEvidence(
            CandidateMemoryEvidenceEntity(
                candidateMemoryId = "existing",
                experienceId = "experience",
                evidenceOrder = 0,
                role = CandidateEvidenceRole.SEED,
                lineageKey = candidateClaimLineageKey("experience", requested, 0),
                createdAt = 1,
            ),
        )
    }

    private fun candidateEntity(
        id: String,
        proposal: CandidateMemoryProposal,
        state: CandidateMemoryState = proposal.proposedState,
    ) = CandidateMemoryEntity(
        id = id,
        proposedKind = proposal.proposedKind,
        proposedScope = proposal.proposedScope,
        proposedMeaning = proposal.proposedMeaning,
        proposedEpistemicBasis = proposal.proposedEpistemicBasis,
        proposedCertainty = proposal.proposedCertainty,
        state = state,
        sensitivity = proposal.proposedSensitivity,
        createdAt = 1,
        updatedAt = 1,
    )

    private suspend fun createConversation() {
        assertTrue(
            timeline.createConversationWithTimeline(
                CreateTimelineConversationInput("conversation", 0, 0, ConversationStatus.ACTIVE),
            ) is TimelineWriteResult.ConversationCreated,
        )
    }

    private suspend fun append(
        id: String,
        role: MessageRole = MessageRole.USER,
        delivery: MessageDeliveryState = MessageDeliveryState.PERSISTED,
    ) {
        val head = database.conversationTimelineDao().timelineHead("conversation")!!
        val result = timeline.appendMessage(
            AppendTimelineMessageInput(
                "conversation",
                newMessage(id, role, delivery, head.timelineRevision + 1),
                head.timelineRevision,
                head.timelineRevision + 1,
            ),
        )
        assertTrue("Expected append but was $result", result is TimelineWriteResult.MessageAppended)
    }

    private fun newMessage(
        id: String,
        role: MessageRole,
        delivery: MessageDeliveryState,
        at: Long,
    ) = NewTimelineMessageInput(id, role, delivery, id, at, at)

    private suspend fun experienceId(messageId: String): String =
        (experienceService.conversationExperienceForMessage(messageId) as ConversationExperienceLookupResult.Found)
            .experience.id

    private fun extracted(result: CandidateExtractionResult): CandidateExtractionResult.Extracted {
        assertTrue("Expected extracted but was $result", result is CandidateExtractionResult.Extracted)
        return result as CandidateExtractionResult.Extracted
    }

    private fun failure(result: CandidateExtractionResult): CandidateExtractionError {
        assertTrue("Expected failure but was $result", result is CandidateExtractionResult.Failure)
        return (result as CandidateExtractionResult.Failure).error
    }

    private fun rowCount(table: String): Long = database.openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM `$table`")
        .use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }

    private fun allTableCounts(): Map<String, Long> = database.openHelper.writableDatabase
        .query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
        .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        .associateWith(::rowCount)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private class RecordingExtractor : CandidateMemoryExtractor {
        var proposal = CandidateExtractionProposal(emptyList())
        var failure: Throwable? = null
        var beforeReturn: suspend () -> Unit = {}
        val snapshots = mutableListOf<CandidateExtractionSnapshot>()

        override suspend fun extract(snapshot: CandidateExtractionSnapshot): CandidateExtractionProposal {
            snapshots += snapshot
            failure?.let { throw it }
            beforeReturn()
            return proposal
        }
    }
}

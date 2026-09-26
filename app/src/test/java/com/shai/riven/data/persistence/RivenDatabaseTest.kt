package com.shai.riven.data.persistence

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.persistence.entity.CandidateMemoryEntity
import com.shai.riven.data.persistence.entity.CandidateMemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.ConversationEntity
import com.shai.riven.data.persistence.entity.ConversationTimelineHeadEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactExperienceDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactMemoryDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactMessageDependencyEntity
import com.shai.riven.data.persistence.entity.ExperienceEntity
import com.shai.riven.data.persistence.entity.ExperienceMessageSourceEntity
import com.shai.riven.data.persistence.entity.KnownEntityEntity
import com.shai.riven.data.persistence.entity.MemoryEntity
import com.shai.riven.data.persistence.entity.MemoryEntityLinkEntity
import com.shai.riven.data.persistence.entity.MemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.MemoryRelationshipEntity
import com.shai.riven.data.persistence.entity.MessageEntity
import com.shai.riven.data.persistence.entity.MessageParentEdgeEntity
import com.shai.riven.data.persistence.entity.OpenLoopEntity
import com.shai.riven.data.persistence.entity.OpenLoopEntityLinkEntity
import com.shai.riven.data.persistence.model.CandidateEvidenceRole
import com.shai.riven.data.persistence.model.CandidateMemoryState
import com.shai.riven.data.persistence.model.ConversationStatus
import com.shai.riven.data.persistence.model.DerivedArtifactState
import com.shai.riven.data.persistence.model.DerivedArtifactType
import com.shai.riven.data.persistence.model.EntityKind
import com.shai.riven.data.persistence.model.EntityLinkRole
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.EvidenceRole
import com.shai.riven.data.persistence.model.ExperienceActor
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.ExperienceMessageSourceRole
import com.shai.riven.data.persistence.model.ExperienceType
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryLifecycleState
import com.shai.riven.data.persistence.model.MemoryRelationshipType
import com.shai.riven.data.persistence.model.MemoryRetentionState
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.MemoryTruthState
import com.shai.riven.data.persistence.model.MessageDeliveryState
import com.shai.riven.data.persistence.model.MessageRole
import com.shai.riven.data.persistence.model.OpenLoopState
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.persistence.model.SignificanceLevel
import com.shai.riven.data.persistence.model.SignificanceLevelConverters
import com.shai.riven.data.persistence.model.TemporalState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RivenDatabaseTest {
    private lateinit var database: RivenDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun databaseCreationSucceeds() {
        val expectedTables = setOf(
            "conversations",
            "messages",
            "entities",
            "experiences",
            "experience_message_sources",
            "memories",
            "memory_evidence",
            "memory_relationships",
            "memory_entity_links",
            "experience_entity_links",
            "candidate_memories",
            "candidate_memory_evidence",
            "open_loops",
            "open_loop_entity_links",
            "suppression_tombstones",
            "derived_artifacts",
            "derived_artifact_memory_dependencies",
            "derived_artifact_experience_dependencies",
            "derived_artifact_message_dependencies",
            "derived_artifact_open_loop_dependencies",
            "repair_jobs",
            "memory_audit_history",
            "open_loop_audit_history",
            "message_parent_edges",
            "conversation_timeline_heads",
            "shai_system_instructions",
            "attachments",
            "message_attachments",
            "generated_media_provenance",
            "derived_artifact_attachment_dependencies",
            "provider_profiles",
            "provider_profile_capabilities",
        )

        val actualTables = database.openHelper.writableDatabase
            .query(
                """
                SELECT name FROM sqlite_master
                WHERE type = 'table'
                  AND name NOT LIKE 'android_%'
                  AND name NOT LIKE 'room_%'
                  AND name NOT LIKE 'sqlite_%'
                """.trimIndent(),
            )
            .use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(0))
                    }
                }
            }

        assertEquals(expectedTables, actualTables)
    }

    @Test
    fun foreignKeyEnforcementWorks() {
        database.openHelper.writableDatabase.query("PRAGMA foreign_keys").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        val orphanMessage = MessageEntity(
            id = "message-orphan",
            conversationId = "conversation-missing",
            sequenceNumber = 1,
            role = MessageRole.USER,
            deliveryState = MessageDeliveryState.PERSISTED,
            content = "Evidence that conversation history remains separate from memory.",
            createdAt = 1,
            updatedAt = 1,
        )

        assertThrows(SQLiteConstraintException::class.java) {
            database.conversationDao().insertMessage(orphanMessage)
        }
    }

    @Test
    fun timelineForeignKeysRestrictCanonicalParentsAndHeadsWhileCascadingOwnedRows() {
        val conversation = conversation("conversation-1")
        val parent = message("message-parent", conversation.id, 1)
        val child = message("message-child", conversation.id, 2)
        database.conversationDao().insertConversation(conversation)
        database.conversationDao().insertMessage(parent)
        database.conversationDao().insertMessage(child)
        database.conversationTimelineDao().insertParentEdge(
            MessageParentEdgeEntity(child.id, parent.id, 2),
        )

        assertThrows(SQLiteConstraintException::class.java) {
            database.conversationDao().deleteMessage(parent)
        }

        database.conversationDao().deleteMessage(child)
        assertNull(database.conversationTimelineDao().parentEdge(child.id))

        database.conversationTimelineDao().insertTimelineHead(
            ConversationTimelineHeadEntity(
                conversationId = conversation.id,
                activeHeadMessageId = parent.id,
                timelineRevision = 0,
                updatedAt = 2,
            ),
        )
        assertThrows(SQLiteConstraintException::class.java) {
            database.conversationDao().deleteMessage(parent)
        }
        assertThrows(SQLiteConstraintException::class.java) {
            database.conversationTimelineDao().insertTimelineHead(
                ConversationTimelineHeadEntity(
                    conversationId = conversation.id,
                    activeHeadMessageId = parent.id,
                    timelineRevision = 0,
                    updatedAt = 3,
                ),
            )
        }
    }

    @Test
    fun oneExperienceCanReferenceMultipleMessagesInStableSourceOrder() {
        val conversation = conversation("conversation-1")
        val firstMessage = message("message-1", conversation.id, 1)
        val secondMessage = message("message-2", conversation.id, 2)
        val thirdMessage = message("message-3", conversation.id, 3)
        val experience = experience("experience-1", 1)
        database.conversationDao().insertConversation(conversation)
        database.conversationDao().insertMessage(firstMessage)
        database.conversationDao().insertMessage(secondMessage)
        database.conversationDao().insertMessage(thirdMessage)
        database.memoryDao().insertExperience(experience)

        database.memoryDao().insertExperienceMessageSource(
            ExperienceMessageSourceEntity(
                experienceId = experience.id,
                messageId = thirdMessage.id,
                sourceOrder = 2,
                sourceRole = ExperienceMessageSourceRole.CORRECTING,
                createdAt = 1,
            ),
        )
        database.memoryDao().insertExperienceMessageSource(
            ExperienceMessageSourceEntity(
                experienceId = experience.id,
                messageId = firstMessage.id,
                sourceOrder = 0,
                sourceRole = ExperienceMessageSourceRole.PRIMARY,
                characterStart = 0,
                characterEnd = 12,
                createdAt = 1,
            ),
        )
        database.memoryDao().insertExperienceMessageSource(
            ExperienceMessageSourceEntity(
                experienceId = experience.id,
                messageId = secondMessage.id,
                sourceOrder = 1,
                sourceRole = ExperienceMessageSourceRole.CONTEXT,
                createdAt = 1,
            ),
        )

        val sources = database.memoryDao().messageSourcesForExperience(experience.id)
        assertEquals(listOf("message-1", "message-2", "message-3"), sources.map { it.messageId })
        assertEquals(listOf(0, 1, 2), sources.map { it.sourceOrder })
        assertEquals(ExperienceMessageSourceRole.PRIMARY, sources.first().sourceRole)
        assertEquals(0, sources.first().characterStart)
        assertEquals(12, sources.first().characterEnd)
    }

    @Test
    fun deletingMessageReferencedByExperienceIsRejected() {
        val conversation = conversation("conversation-1")
        val message = message("message-1", conversation.id, 1)
        val experience = experience("experience-1", 1)
        database.conversationDao().insertConversation(conversation)
        database.conversationDao().insertMessage(message)
        database.memoryDao().insertExperience(experience)
        database.memoryDao().insertExperienceMessageSource(
            ExperienceMessageSourceEntity(
                experienceId = experience.id,
                messageId = message.id,
                sourceOrder = 0,
                sourceRole = ExperienceMessageSourceRole.PRIMARY,
                createdAt = 1,
            ),
        )

        assertThrows(SQLiteConstraintException::class.java) {
            database.conversationDao().deleteMessage(message)
        }
        assertEquals(1, database.memoryDao().messageSourcesForExperience(experience.id).size)
    }

    @Test
    fun significanceLevelsPersistAndReadBackAsStableNames() {
        val memory = memory("memory-significance").copy(
            autobiographicalSignificance = SignificanceLevel.NONE,
            relationshipSignificance = SignificanceLevel.LOW,
            emotionalSignificance = SignificanceLevel.MODERATE,
            practicalSignificance = SignificanceLevel.HIGH,
            identitySignificance = SignificanceLevel.CORE,
        )
        database.memoryDao().insertMemory(memory)

        database.openHelper.writableDatabase.query(
            """
                SELECT autobiographical_significance,
                       relationship_significance,
                       emotional_significance,
                       practical_significance,
                       identity_significance
                FROM memories
                WHERE memory_id = ?
            """.trimIndent(),
            arrayOf(memory.id),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            val converters = SignificanceLevelConverters()
            assertEquals(SignificanceLevel.NONE, converters.toSignificanceLevel(cursor.getString(0)))
            assertEquals(SignificanceLevel.LOW, converters.toSignificanceLevel(cursor.getString(1)))
            assertEquals(SignificanceLevel.MODERATE, converters.toSignificanceLevel(cursor.getString(2)))
            assertEquals(SignificanceLevel.HIGH, converters.toSignificanceLevel(cursor.getString(3)))
            assertEquals(SignificanceLevel.CORE, converters.toSignificanceLevel(cursor.getString(4)))
        }
    }

    @Test
    fun nullSignificancePersistsAsUnassessed() {
        val memory = memory("memory-unassessed")
        database.memoryDao().insertMemory(memory)

        database.openHelper.writableDatabase.query(
            """
                SELECT autobiographical_significance,
                       relationship_significance,
                       emotional_significance,
                       practical_significance,
                       identity_significance
                FROM memories
                WHERE memory_id = ?
            """.trimIndent(),
            arrayOf(memory.id),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            repeat(5) { column -> assertTrue(cursor.isNull(column)) }
        }
    }

    @Test
    fun candidateMemoryDoesNotContainSeedExperienceColumn() {
        val columns = database.openHelper.writableDatabase
            .query("PRAGMA table_info(candidate_memories)")
            .use { cursor ->
                buildSet {
                    val nameColumn = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameColumn))
                }
            }

        assertFalse(columns.contains("seed_experience_id"))
    }

    @Test
    fun oneCandidateMemoryCanReferenceMultipleExperiences() {
        val firstExperience = experience("experience-1", 1)
        val secondExperience = experience("experience-2", 2)
        val candidate = candidateMemory("candidate-1")
        database.memoryDao().insertExperience(firstExperience)
        database.memoryDao().insertExperience(secondExperience)
        database.memoryDao().insertCandidateMemory(candidate)
        database.memoryDao().insertCandidateMemoryEvidence(
            CandidateMemoryEvidenceEntity(
                candidateMemoryId = candidate.id,
                experienceId = firstExperience.id,
                evidenceOrder = 0,
                role = CandidateEvidenceRole.SEED,
                lineageKey = "lineage-1",
                createdAt = 1,
            ),
        )
        database.memoryDao().insertCandidateMemoryEvidence(
            CandidateMemoryEvidenceEntity(
                candidateMemoryId = candidate.id,
                experienceId = secondExperience.id,
                evidenceOrder = 1,
                role = CandidateEvidenceRole.SUPPORTING,
                lineageKey = "lineage-2",
                createdAt = 2,
            ),
        )

        assertEquals(2, database.memoryDao().candidateMemoryEvidenceCount(candidate.id))
        database.openHelper.writableDatabase.query(
            """
                SELECT experience_id
                FROM candidate_memory_evidence
                WHERE candidate_memory_id = ? AND role = ?
            """.trimIndent(),
            arrayOf(candidate.id, CandidateEvidenceRole.SEED.name),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(firstExperience.id, cursor.getString(0))
            assertFalse(cursor.moveToNext())
        }
    }

    @Test
    fun oneDerivedArtifactCanDependOnMultipleMemories() {
        val artifact = derivedArtifact("artifact-1")
        val firstMemory = memory("memory-1")
        val secondMemory = memory("memory-2")
        database.memoryDao().insertMemory(firstMemory)
        database.memoryDao().insertMemory(secondMemory)
        database.maintenanceDao().insertDerivedArtifact(artifact)
        database.maintenanceDao().insertDerivedArtifactMemoryDependency(
            DerivedArtifactMemoryDependencyEntity(artifact.id, firstMemory.id, 1),
        )
        database.maintenanceDao().insertDerivedArtifactMemoryDependency(
            DerivedArtifactMemoryDependencyEntity(artifact.id, secondMemory.id, 1),
        )

        assertEquals(2, database.maintenanceDao().memoryDependencyCount(artifact.id))
    }

    @Test
    fun oneDerivedArtifactCanDependOnMultipleExperiences() {
        val artifact = derivedArtifact("artifact-1")
        val firstExperience = experience("experience-1", 1)
        val secondExperience = experience("experience-2", 2)
        database.memoryDao().insertExperience(firstExperience)
        database.memoryDao().insertExperience(secondExperience)
        database.maintenanceDao().insertDerivedArtifact(artifact)
        database.maintenanceDao().insertDerivedArtifactExperienceDependency(
            DerivedArtifactExperienceDependencyEntity(artifact.id, firstExperience.id, 1),
        )
        database.maintenanceDao().insertDerivedArtifactExperienceDependency(
            DerivedArtifactExperienceDependencyEntity(artifact.id, secondExperience.id, 1),
        )

        assertEquals(2, database.maintenanceDao().experienceDependencyCount(artifact.id))
    }

    @Test
    fun oneDerivedArtifactCanDependOnMultipleMessages() {
        val conversation = conversation("conversation-1")
        val firstMessage = message("message-1", conversation.id, 1)
        val secondMessage = message("message-2", conversation.id, 2)
        val artifact = derivedArtifact("artifact-1")
        database.conversationDao().insertConversation(conversation)
        database.conversationDao().insertMessage(firstMessage)
        database.conversationDao().insertMessage(secondMessage)
        database.maintenanceDao().insertDerivedArtifact(artifact)
        database.maintenanceDao().insertDerivedArtifactMessageDependency(
            DerivedArtifactMessageDependencyEntity(artifact.id, firstMessage.id, 1),
        )
        database.maintenanceDao().insertDerivedArtifactMessageDependency(
            DerivedArtifactMessageDependencyEntity(artifact.id, secondMessage.id, 1),
        )

        assertEquals(2, database.maintenanceDao().messageDependencyCount(artifact.id))
    }

    @Test
    fun oneOpenLoopCanLinkToMultipleEntities() {
        val experience = experience("experience-1", 1)
        val openLoop = OpenLoopEntity(
            id = "open-loop-1",
            creationExperienceId = experience.id,
            title = "Finish the reading assignment",
            state = OpenLoopState.ACTIVE,
            openedAt = 1,
            sensitivity = SensitivityLevel.STANDARD,
            createdAt = 1,
            updatedAt = 1,
        )
        val course = knownEntity("entity-course", EntityKind.SUBJECT, "Literature")
        val book = knownEntity("entity-book", EntityKind.WORK, "Assigned novel")
        database.memoryDao().insertExperience(experience)
        database.memoryDao().insertEntity(course)
        database.memoryDao().insertEntity(book)
        database.openLoopDao().insertOpenLoop(openLoop)
        database.openLoopDao().insertEntityLink(
            OpenLoopEntityLinkEntity(openLoop.id, course.id, EntityLinkRole.ABOUT, 1),
        )
        database.openLoopDao().insertEntityLink(
            OpenLoopEntityLinkEntity(openLoop.id, book.id, EntityLinkRole.ABOUT, 1),
        )

        assertEquals(2, database.openLoopDao().entityLinkCount(openLoop.id))
    }

    @Test
    fun memoryKindDoesNotContainOpenLoop() {
        assertFalse(MemoryKind.entries.any { it.name == "OPEN_LOOP" })
    }

    @Test
    fun memoryEvidenceCannotReferenceNonexistentExperience() {
        database.memoryDao().insertMemory(memory("memory-1"))

        val orphanEvidence = MemoryEvidenceEntity(
            memoryId = "memory-1",
            experienceId = "experience-missing",
            role = EvidenceRole.SUPPORTS,
            epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
            sourceCertainty = MemoryCertainty.CERTAIN,
            lineageKey = "lineage-1",
            createdAt = 1,
        )

        assertThrows(SQLiteConstraintException::class.java) {
            database.memoryDao().insertMemoryEvidence(orphanEvidence)
        }
    }

    @Test
    fun memoryRelationshipCannotReferenceNonexistentMemory() {
        database.memoryDao().insertMemory(memory("memory-source"))

        val orphanRelationship = MemoryRelationshipEntity(
            sourceMemoryId = "memory-source",
            targetMemoryId = "memory-missing",
            relationshipType = MemoryRelationshipType.CORRECTS,
            createdAt = 1,
        )

        assertThrows(SQLiteConstraintException::class.java) {
            database.memoryDao().insertMemoryRelationship(orphanRelationship)
        }
    }

    @Test
    fun openLoopCannotReferenceNonexistentCreationEvidence() {
        val orphanOpenLoop = OpenLoopEntity(
            id = "open-loop-1",
            creationExperienceId = "experience-missing",
            title = "Unresolved assignment",
            state = OpenLoopState.ACTIVE,
            openedAt = 1,
            sensitivity = SensitivityLevel.STANDARD,
            createdAt = 1,
            updatedAt = 1,
        )

        assertThrows(SQLiteConstraintException::class.java) {
            database.openLoopDao().insertOpenLoop(orphanOpenLoop)
        }
    }

    @Test
    fun memoryOwnedJunctionRecordsCascadeWhenMemoryIsRemoved() {
        val experience = experience("experience-1", 1)
        val memory = memory("memory-1")
        val entity = KnownEntityEntity(
            id = "entity-1",
            kind = EntityKind.PERSON,
            displayName = "Shai",
            normalizedName = "shai",
            createdAt = 1,
            updatedAt = 1,
        )

        database.memoryDao().insertExperience(experience)
        database.memoryDao().insertMemory(memory)
        database.memoryDao().insertEntity(entity)
        database.memoryDao().insertMemoryEvidence(
            MemoryEvidenceEntity(
                memoryId = memory.id,
                experienceId = experience.id,
                role = EvidenceRole.SUPPORTS,
                epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                sourceCertainty = MemoryCertainty.CERTAIN,
                lineageKey = "lineage-1",
                createdAt = 1,
            ),
        )
        database.memoryDao().insertMemoryEntityLink(
            MemoryEntityLinkEntity(
                memoryId = memory.id,
                entityId = entity.id,
                role = EntityLinkRole.ABOUT,
                createdAt = 1,
            ),
        )

        database.memoryDao().deleteMemory(memory)

        assertEquals(0, database.memoryDao().memoryEvidenceCount(memory.id))
        assertEquals(0, database.memoryDao().memoryEntityLinkCount(memory.id))
        assertEquals(1, database.memoryDao().experienceCount(experience.id))
    }

    @Test
    fun canonicalEvidenceCannotBeRemovedWhileProvenanceDependsOnIt() {
        val experience = experience("experience-1", 1)
        val memory = memory("memory-1")
        database.memoryDao().insertExperience(experience)
        database.memoryDao().insertMemory(memory)
        database.memoryDao().insertMemoryEvidence(
            MemoryEvidenceEntity(
                memoryId = memory.id,
                experienceId = experience.id,
                role = EvidenceRole.SUPPORTS,
                epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                sourceCertainty = MemoryCertainty.CERTAIN,
                lineageKey = "lineage-1",
                createdAt = 1,
            ),
        )

        assertThrows(SQLiteConstraintException::class.java) {
            database.memoryDao().deleteExperience(experience)
        }
        assertEquals(1, database.memoryDao().experienceCount(experience.id))
        assertEquals(1, database.memoryDao().memoryEvidenceCount(memory.id))
    }

    private fun conversation(id: String) = ConversationEntity(
        id = id,
        createdAt = 1,
        updatedAt = 1,
        status = ConversationStatus.ACTIVE,
    )

    private fun message(id: String, conversationId: String, sequenceNumber: Long) = MessageEntity(
        id = id,
        conversationId = conversationId,
        sequenceNumber = sequenceNumber,
        role = MessageRole.USER,
        deliveryState = MessageDeliveryState.PERSISTED,
        content = "Grounded conversation source $sequenceNumber.",
        createdAt = sequenceNumber,
        updatedAt = sequenceNumber,
    )

    private fun knownEntity(id: String, kind: EntityKind, name: String) = KnownEntityEntity(
        id = id,
        kind = kind,
        displayName = name,
        normalizedName = name.lowercase(),
        createdAt = 1,
        updatedAt = 1,
    )

    private fun candidateMemory(id: String) = CandidateMemoryEntity(
        id = id,
        proposedKind = MemoryKind.SEMANTIC,
        proposedScope = MemoryScope.SHAI,
        proposedMeaning = "A tentative understanding awaiting validation.",
        proposedEpistemicBasis = EpistemicBasis.INFERENCE,
        proposedCertainty = MemoryCertainty.PROBABLE,
        state = CandidateMemoryState.TENTATIVE,
        sensitivity = SensitivityLevel.STANDARD,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun derivedArtifact(id: String) = DerivedArtifactEntity(
        id = id,
        artifactType = DerivedArtifactType.SUMMARY,
        state = DerivedArtifactState.CURRENT,
        producerVersion = "test-v1",
        sourceRevision = 1,
        createdAt = 1,
    )

    private fun experience(id: String, eventOrder: Long) = ExperienceEntity(
        id = id,
        eventOrder = eventOrder,
        experienceType = ExperienceType.SHARED_EVENT,
        actor = ExperienceActor.SHAI,
        sourceContent = "A grounded source event.",
        occurredAt = 1,
        recordedAt = 1,
        sensitivity = SensitivityLevel.STANDARD,
        availability = ExperienceAvailability.AVAILABLE,
    )

    private fun memory(id: String) = MemoryEntity(
        id = id,
        kind = MemoryKind.SEMANTIC,
        scope = MemoryScope.SHAI,
        meaning = "A validated retained understanding.",
        epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
        certainty = MemoryCertainty.CERTAIN,
        truthState = MemoryTruthState.SUPPORTED,
        retentionState = MemoryRetentionState.ACTIVE,
        lifecycleState = MemoryLifecycleState.VALIDATED,
        temporalState = TemporalState.CURRENT,
        learnedAt = 1,
        sensitivity = SensitivityLevel.STANDARD,
        createdAt = 1,
        updatedAt = 1,
    )
}

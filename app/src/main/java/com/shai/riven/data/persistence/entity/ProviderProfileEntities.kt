package com.shai.riven.data.persistence.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.shai.riven.data.provider.ProviderCapability

@Entity(
    tableName = "provider_profiles",
    indices = [
        Index(value = ["adapter_id"]),
        Index(value = ["credential_slot_id"]),
        Index(value = ["is_enabled"]),
    ],
)
data class ProviderProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "adapter_id")
    val adapterId: String,
    @ColumnInfo(name = "endpoint_base_url")
    val endpointBaseUrl: String,
    @ColumnInfo(name = "model_id")
    val modelId: String,
    @ColumnInfo(name = "credential_slot_id")
    val credentialSlotId: String?,
    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean,
    val revision: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "provider_profile_capabilities",
    primaryKeys = ["profile_id", "capability"],
    foreignKeys = [
        ForeignKey(
            entity = ProviderProfileEntity::class,
            parentColumns = ["profile_id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["capability"])],
)
data class ProviderProfileCapabilityEntity(
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    val capability: ProviderCapability,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

package com.shai.riven.data.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.shai.riven.data.persistence.entity.ProviderProfileCapabilityEntity
import com.shai.riven.data.persistence.entity.ProviderProfileEntity
import com.shai.riven.data.provider.ProviderCapability

@Dao
interface ProviderProfileDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertProfile(profile: ProviderProfileEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertCapabilities(capabilities: List<ProviderProfileCapabilityEntity>)

    @Update
    fun updateProfile(profile: ProviderProfileEntity): Int

    @Query("DELETE FROM provider_profile_capabilities WHERE profile_id = :profileId")
    fun deleteCapabilities(profileId: String): Int

    @Query("SELECT * FROM provider_profiles WHERE profile_id = :profileId")
    fun profile(profileId: String): ProviderProfileEntity?

    @Query(
        """
        SELECT capability
        FROM provider_profile_capabilities
        WHERE profile_id = :profileId
        ORDER BY capability
        """,
    )
    fun capabilities(profileId: String): List<ProviderCapability>

    @Query(
        """
        SELECT *
        FROM provider_profiles
        ORDER BY display_name COLLATE NOCASE ASC, profile_id ASC
        """,
    )
    fun allProfiles(): List<ProviderProfileEntity>

    @Query("SELECT COUNT(*) FROM provider_profiles")
    fun profileCount(): Int

    @Query("SELECT COUNT(*) FROM provider_profile_capabilities")
    fun capabilityCount(): Int
}

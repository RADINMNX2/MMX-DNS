package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DnsProfileDao {
    @Query("SELECT * FROM dns_profiles ORDER BY id ASC")
    fun getAllProfiles(): Flow<List<DnsProfile>>

    @Query("SELECT * FROM dns_profiles WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultProfile(): DnsProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: DnsProfile): Long

    @Update
    suspend fun updateProfile(profile: DnsProfile)

    @Delete
    suspend fun deleteProfile(profile: DnsProfile)

    @Query("UPDATE dns_profiles SET isDefault = 0")
    suspend fun clearDefaultProfiles()

    @Transaction
    suspend fun setDefaultProfile(profileId: Int) {
        clearDefaultProfiles()
        updateDefaultProfile(profileId)
    }

    @Query("UPDATE dns_profiles SET isDefault = 1 WHERE id = :profileId")
    suspend fun updateDefaultProfile(profileId: Int)
}

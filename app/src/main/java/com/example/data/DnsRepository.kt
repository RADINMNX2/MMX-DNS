package com.example.data

import kotlinx.coroutines.flow.Flow

class DnsRepository(private val dao: DnsProfileDao) {
    val allProfiles: Flow<List<DnsProfile>> = dao.getAllProfiles()

    suspend fun getDefaultProfile(): DnsProfile? = dao.getDefaultProfile()

    suspend fun insertProfile(profile: DnsProfile): Long = dao.insertProfile(profile)

    suspend fun updateProfile(profile: DnsProfile) = dao.updateProfile(profile)

    suspend fun deleteProfile(profile: DnsProfile) = dao.deleteProfile(profile)

    suspend fun setDefaultProfile(profileId: Int) = dao.setDefaultProfile(profileId)
}

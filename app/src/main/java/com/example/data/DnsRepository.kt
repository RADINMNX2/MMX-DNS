package com.example.data

import kotlinx.coroutines.flow.Flow

class DnsRepository(
    private val profileDao: DnsProfileDao,
    private val gamingAppDao: GamingAppDao
) {
    val allProfiles: Flow<List<DnsProfile>> = profileDao.getAllProfiles()

    suspend fun getDefaultProfile(): DnsProfile? = profileDao.getDefaultProfile()

    suspend fun insertProfile(profile: DnsProfile): Long = profileDao.insertProfile(profile)

    suspend fun updateProfile(profile: DnsProfile) = profileDao.updateProfile(profile)

    suspend fun deleteProfile(profile: DnsProfile) = profileDao.deleteProfile(profile)

    suspend fun setDefaultProfile(profileId: Int) = profileDao.setDefaultProfile(profileId)

    // Gaming Apps
    val allGamingApps: Flow<List<GamingApp>> = gamingAppDao.getAllApps()

    suspend fun getSelectedGamingApps(): List<GamingApp> = gamingAppDao.getSelectedApps()

    suspend fun updateGamingApp(app: GamingApp) = gamingAppDao.updateApp(app)

    suspend fun insertGamingApp(app: GamingApp) = gamingAppDao.insertApps(listOf(app))

    suspend fun setGamingAppSelected(packageName: String, selected: Boolean) = gamingAppDao.setAppSelected(packageName, selected)
}

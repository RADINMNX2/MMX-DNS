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

    suspend fun ensureDefaultProfilesExist() {
        val existing = profileDao.getAllProfilesList()
        val defaultList = listOf(
            DnsProfile(name = "Google Public DNS", primaryDns = "8.8.8.8", secondaryDns = "8.8.4.4", enableIpv6 = true, primaryIpv6 = "2001:4860:4860::8888", secondaryIpv6 = "2001:4860:4860::8884", isDefault = true, isCustom = false),
            DnsProfile(name = "Cloudflare DNS", primaryDns = "1.1.1.1", secondaryDns = "1.0.0.1", enableIpv6 = true, primaryIpv6 = "2606:4700:4700::1111", secondaryIpv6 = "2606:4700:4700::1001", isDefault = false, isCustom = false),
            DnsProfile(name = "Radar Game (رادار گیم)", primaryDns = "10.202.10.10", secondaryDns = "10.202.10.11", enableIpv6 = true, primaryIpv6 = "2001:470:1f1b:b3::10", secondaryIpv6 = "2001:470:1f1b:b3::11", isDefault = false, isCustom = false),
            DnsProfile(name = "Shecan (شکن)", primaryDns = "178.22.122.100", secondaryDns = "185.51.200.2", enableIpv6 = true, primaryIpv6 = "2a02:920:600:100::1", secondaryIpv6 = "2a02:920:600:100::2", isDefault = false, isCustom = false),
            DnsProfile(name = "Electro (الکترون)", primaryDns = "78.157.42.100", secondaryDns = "78.157.42.101", enableIpv6 = false, primaryIpv6 = "", secondaryIpv6 = "", isDefault = false, isCustom = false),
            DnsProfile(name = "AdGuard DNS (Blocks Ads)", primaryDns = "94.140.14.14", secondaryDns = "94.140.15.15", enableIpv6 = true, primaryIpv6 = "2a10:50C0::ad1:ff", secondaryIpv6 = "2a10:50C0::ad2:ff", isDefault = false, isCustom = false),
            DnsProfile(name = "Quad9 DNS (Secure)", primaryDns = "9.9.9.9", secondaryDns = "149.112.112.112", enableIpv6 = true, primaryIpv6 = "2620:fe::fe", secondaryIpv6 = "2620:fe::9", isDefault = false, isCustom = false),
            DnsProfile(name = "OpenDNS", primaryDns = "208.67.222.222", secondaryDns = "208.67.220.220", enableIpv6 = true, primaryIpv6 = "2620:119:35::35", secondaryIpv6 = "2620:119:41::41", isDefault = false, isCustom = false)
        )

        for (def in defaultList) {
            val exists = existing.any { it.primaryDns == def.primaryDns || it.name.startsWith(def.name.take(5), ignoreCase = true) }
            if (!exists) {
                profileDao.insertProfile(def)
            }
        }
    }

    // Gaming Apps
    val allGamingApps: Flow<List<GamingApp>> = gamingAppDao.getAllApps()

    suspend fun getSelectedGamingApps(): List<GamingApp> = gamingAppDao.getSelectedApps()

    suspend fun updateGamingApp(app: GamingApp) = gamingAppDao.updateApp(app)

    suspend fun insertGamingApp(app: GamingApp) = gamingAppDao.insertApps(listOf(app))

    suspend fun setGamingAppSelected(packageName: String, selected: Boolean) = gamingAppDao.setAppSelected(packageName, selected)
}

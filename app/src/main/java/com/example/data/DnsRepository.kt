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
        var existing = profileDao.getAllProfilesList()

        // 1. Clean up any existing duplicate profiles from database
        val seenIps = mutableSetOf<String>()
        val seenNames = mutableSetOf<String>()
        val duplicates = mutableListOf<DnsProfile>()

        for (profile in existing) {
            val ipKey = profile.primaryDns.trim()
            val nameKey = profile.name.trim().lowercase()

            val isDuplicateIp = ipKey.isNotEmpty() && seenIps.contains(ipKey)
            val isDuplicateName = nameKey.isNotEmpty() && seenNames.contains(nameKey)

            if (isDuplicateIp || isDuplicateName) {
                duplicates.add(profile)
            } else {
                if (ipKey.isNotEmpty()) seenIps.add(ipKey)
                if (nameKey.isNotEmpty()) seenNames.add(nameKey)
            }
        }

        for (dup in duplicates) {
            profileDao.deleteProfile(dup)
        }

        // Re-query remaining profiles after deduplication
        existing = profileDao.getAllProfilesList()

        val defaultList = listOf(
            DnsProfile(name = "Google Public DNS", primaryDns = "8.8.8.8", secondaryDns = "8.8.4.4", enableIpv6 = true, primaryIpv6 = "2001:4860:4860::8888", secondaryIpv6 = "2001:4860:4860::8884", isDefault = true, isCustom = false),
            DnsProfile(name = "Cloudflare DNS", primaryDns = "1.1.1.1", secondaryDns = "1.0.0.1", enableIpv6 = true, primaryIpv6 = "2606:4700:4700::1111", secondaryIpv6 = "2606:4700:4700::1001", isDefault = false, isCustom = false),
            DnsProfile(name = "Electro Server 1", primaryDns = "78.157.42.101", secondaryDns = "50.118.234.125", enableIpv6 = true, primaryIpv6 = "32c4::3b03:c76c:4072:265:8cdd:98c7", secondaryIpv6 = "32c4::b51e:bdb9:14f1:fe7b:457:ee1e", isDefault = false, isCustom = false),
            DnsProfile(name = "Electro Server 2", primaryDns = "78.157.42.101", secondaryDns = "50.237.19.44", enableIpv6 = true, primaryIpv6 = "32c4::7d2b:4f84:eb51:9b32:a5d4:146d", secondaryIpv6 = "32c4::11a4:7c1e:ee5d:30a3:277:a655", isDefault = false, isCustom = false),
            DnsProfile(name = "Electro Server 3", primaryDns = "78.157.42.101", secondaryDns = "50.50.6.128", enableIpv6 = true, primaryIpv6 = "32c4::a900:40d9:d76:9bf0:9e43:b5de", secondaryIpv6 = "32c4::190b:c3f6:f22b:f571:d0b5:ad88", isDefault = false, isCustom = false),
            DnsProfile(name = "Electro Server 4", primaryDns = "50.178.68.110", secondaryDns = "78.157.42.101", enableIpv6 = true, primaryIpv6 = "32c4::9e17:fb6e:a7d5:614c:6bb4:7a3a", secondaryIpv6 = "32c4::884f:9cc3:4138:c637:ddec:2722", isDefault = false, isCustom = false),
            DnsProfile(name = "Electro Server 5", primaryDns = "50.36.116.57", secondaryDns = "78.157.42.101", enableIpv6 = true, primaryIpv6 = "32c4::f231:c414:b594:d527:4fd1:874b", secondaryIpv6 = "32c4::eaab:3600:d3e2:6b85:fead:f77f", isDefault = false, isCustom = false),
            DnsProfile(name = "Electro Server 6", primaryDns = "50.148.94.173", secondaryDns = "78.157.42.101", enableIpv6 = true, primaryIpv6 = "32c4::df37:e2cd:a3d:b0f4:a6d0:e0d7", secondaryIpv6 = "32c4::2a01:386c:cdd7:dabf:3a45:7bb9", isDefault = false, isCustom = false),
            DnsProfile(name = "Radar Game", primaryDns = "10.202.10.10", secondaryDns = "10.202.10.11", enableIpv6 = true, primaryIpv6 = "2001:470:1f1b:b3::10", secondaryIpv6 = "2001:470:1f1b:b3::11", isDefault = false, isCustom = false),
            DnsProfile(name = "Shecan DNS", primaryDns = "178.22.122.100", secondaryDns = "185.51.200.2", enableIpv6 = true, primaryIpv6 = "2a02:920:600:100::1", secondaryIpv6 = "2a02:920:600:100::2", isDefault = false, isCustom = false),
            DnsProfile(name = "AdGuard DNS (Blocks Ads)", primaryDns = "94.140.14.14", secondaryDns = "94.140.15.15", enableIpv6 = true, primaryIpv6 = "2a10:50C0::ad1:ff", secondaryIpv6 = "2a10:50C0::ad2:ff", isDefault = false, isCustom = false),
            DnsProfile(name = "Quad9 DNS (Secure)", primaryDns = "9.9.9.9", secondaryDns = "149.112.112.112", enableIpv6 = true, primaryIpv6 = "2620:fe::fe", secondaryIpv6 = "2620:fe::9", isDefault = false, isCustom = false),
            DnsProfile(name = "OpenDNS", primaryDns = "208.67.222.222", secondaryDns = "208.67.220.220", enableIpv6 = true, primaryIpv6 = "2620:119:35::35", secondaryIpv6 = "2620:119:41::41", isDefault = false, isCustom = false)
        )

        for (def in defaultList) {
            val exists = existing.any {
                it.name.trim().equals(def.name.trim(), ignoreCase = true) ||
                (it.primaryDns == def.primaryDns && it.secondaryDns == def.secondaryDns && it.primaryIpv6 == def.primaryIpv6)
            }
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

    suspend fun insertGamingApps(apps: List<GamingApp>) = gamingAppDao.insertApps(apps)

    suspend fun setGamingAppSelected(packageName: String, selected: Boolean) = gamingAppDao.setAppSelected(packageName, selected)

    suspend fun setGamingAppMultiPathEnabled(packageName: String, enabled: Boolean) = gamingAppDao.setAppMultiPathEnabled(packageName, enabled)

    suspend fun deleteGamingApp(packageName: String) = gamingAppDao.deleteApp(packageName)

    suspend fun deleteAllGamingApps() = gamingAppDao.deleteAllApps()
}

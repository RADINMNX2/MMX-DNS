package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(entities = [DnsProfile::class, GamingApp::class], version = 2, exportSchema = false)
abstract class DnsDatabase : RoomDatabase() {
    abstract fun dnsProfileDao(): DnsProfileDao
    abstract fun gamingAppDao(): GamingAppDao

    companion object {
        @Volatile
        private var INSTANCE: DnsDatabase? = null

        fun getDatabase(context: Context): DnsDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DnsDatabase::class.java,
                    "dns_changer_database"
                )
                .fallbackToDestructiveMigration()
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // Seed database on background thread
                        CoroutineScope(Dispatchers.IO).launch {
                            val dbInstance = getDatabase(context)
                            val dao = dbInstance.dnsProfileDao()
                            dao.insertProfile(
                                DnsProfile(
                                    name = "Google Public DNS",
                                    primaryDns = "8.8.8.8",
                                    secondaryDns = "8.8.4.4",
                                    isDefault = true,
                                    isCustom = false
                                )
                            )
                            dao.insertProfile(
                                DnsProfile(
                                    name = "Cloudflare DNS",
                                    primaryDns = "1.1.1.1",
                                    secondaryDns = "1.0.0.1",
                                    isDefault = false,
                                    isCustom = false
                                )
                            )
                            dao.insertProfile(
                                DnsProfile(
                                    name = "AdGuard DNS (Blocks Ads)",
                                    primaryDns = "94.140.14.14",
                                    secondaryDns = "94.140.15.15",
                                    isDefault = false,
                                    isCustom = false
                                )
                            )
                            dao.insertProfile(
                                DnsProfile(
                                    name = "Quad9 DNS (Secure)",
                                    primaryDns = "9.9.9.9",
                                    secondaryDns = "149.112.112.112",
                                    isDefault = false,
                                    isCustom = false
                                )
                            )

                            // Seed popular e-sports and matchmaking gaming endpoints
                            val gamingDao = dbInstance.gamingAppDao()
                            gamingDao.insertApps(
                                listOf(
                                    GamingApp("com.activision.callofduty.shooter", "Call of Duty: Mobile", true),
                                    GamingApp("com.tencent.ig", "PUBG MOBILE", true),
                                    GamingApp("com.dts.freefireth", "Free Fire", false),
                                    GamingApp("com.mobile.legends", "Mobile Legends", false),
                                    GamingApp("com.riotgames.league.wildrift", "League of Legends: Wild Rift", false),
                                    GamingApp("com.supercell.brawlstars", "Brawl Stars", false),
                                    GamingApp("com.supercell.clashofclans", "Clash of Clans", false),
                                    GamingApp("com.miHoYo.GenshinImpact", "Genshin Impact", false),
                                    GamingApp("com.roblox.client", "Roblox", false),
                                    GamingApp("com.mojang.minecraftpe", "Minecraft", false)
                                )
                            )
                        }
                    }
                })
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

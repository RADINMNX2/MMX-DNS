package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(entities = [DnsProfile::class], version = 1, exportSchema = false)
abstract class DnsDatabase : RoomDatabase() {
    abstract fun dnsProfileDao(): DnsProfileDao

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
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // Seed database on background thread
                        CoroutineScope(Dispatchers.IO).launch {
                            val dao = getDatabase(context).dnsProfileDao()
                            dao.insertProfile(
                                DnsProfile(
                                    name = "Radar Game",
                                    primaryDns = "10.202.10.10",
                                    secondaryDns = "10.202.10.11",
                                    isDefault = false,
                                    isCustom = false
                                )
                            )
                            dao.insertProfile(
                                DnsProfile(
                                    name = "Electro",
                                    primaryDns = "78.157.42.101",
                                    secondaryDns = "78.157.42.100",
                                    isDefault = false,
                                    isCustom = false
                                )
                            )
                            dao.insertProfile(
                                DnsProfile(
                                    name = "Shecan",
                                    primaryDns = "178.22.122.100",
                                    secondaryDns = "185.51.200.2",
                                    isDefault = false,
                                    isCustom = false
                                )
                            )
                            dao.insertProfile(
                                DnsProfile(
                                    name = "403.online",
                                    primaryDns = "10.202.10.202",
                                    secondaryDns = "10.202.10.102",
                                    isDefault = false,
                                    isCustom = false
                                )
                            )
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

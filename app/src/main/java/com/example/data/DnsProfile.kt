package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dns_profiles")
data class DnsProfile(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val primaryDns: String,
    val secondaryDns: String,
    val isDefault: Boolean = false,
    val isCustom: Boolean = true
)

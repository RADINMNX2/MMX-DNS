package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gaming_apps")
data class GamingApp(
    @PrimaryKey val packageName: String,
    val name: String,
    val isSelected: Boolean = false
)

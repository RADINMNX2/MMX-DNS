package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GamingAppDao {
    @Query("SELECT * FROM gaming_apps ORDER BY name ASC")
    fun getAllApps(): Flow<List<GamingApp>>

    @Query("SELECT * FROM gaming_apps WHERE isSelected = 1")
    suspend fun getSelectedApps(): List<GamingApp>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApps(apps: List<GamingApp>)

    @Update
    suspend fun updateApp(app: GamingApp)

    @Query("UPDATE gaming_apps SET isSelected = :selected WHERE packageName = :packageName")
    suspend fun setAppSelected(packageName: String, selected: Boolean)
}

package com.example.data

enum class GamePreset {
    STANDARD, FPS_SHOOTER, MMO_RPG, DOWNLOAD_UPDATE
}

data class GamePingInfo(
    val name: String,
    val ip: String,
    val latencyMs: Int?,
    val jitterMs: Int?,
    val status: String // "OPTIMAL", "STABLE", "HIGH PING", "OFFLINE"
)

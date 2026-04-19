package com.example.trafficmonitor

data class TrafficInfo(
    val appName: String,
    val packageName: String,
    val uid: Int,
    val speed: Long
)
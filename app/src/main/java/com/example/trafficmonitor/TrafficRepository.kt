package com.example.trafficmonitor

object TrafficRepository {

    data class Snapshot(
        val totalSpeed: Long = 0,
        val items: List<TrafficInfo> = emptyList()
    )

    @Volatile
    private var latest = Snapshot()

    fun update(totalSpeed: Long, items: List<TrafficInfo>) {
        latest = Snapshot(totalSpeed, items)
    }

    fun getSnapshot(): Snapshot = latest
}
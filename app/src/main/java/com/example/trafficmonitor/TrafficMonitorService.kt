package com.example.trafficmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat

class TrafficMonitorService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var floatingParams: WindowManager.LayoutParams
    private lateinit var tvTotalSpeed: TextView
    private lateinit var tvTopApp: TextView
    private lateinit var tvTopList: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var lastTickTime = 0L
    private var lastTotalBytes = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createNotification())

        initFloatingWindow()
        startMonitoring()
    }

    private fun initFloatingWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_window, null)

        tvTotalSpeed = floatingView.findViewById(R.id.tvTotalSpeed)
        tvTopApp = floatingView.findViewById(R.id.tvTopApp)
        tvTopList = floatingView.findViewById(R.id.tvTopList)

        floatingParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        floatingParams.gravity = Gravity.TOP or Gravity.START
        floatingParams.x = 100
        floatingParams.y = 220

        setupDragGesture()
        windowManager.addView(floatingView, floatingParams)
    }

    private fun setupDragGesture() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        floatingView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = floatingParams.x
                    initialY = floatingParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    floatingParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    floatingParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, floatingParams)
                    true
                }

                else -> false
            }
        }
    }

    private fun startMonitoring() {
        lastTickTime = System.currentTimeMillis()
        lastTotalBytes = getDeviceTotalBytes()

        handler.post(object : Runnable {
            override fun run() {
                updateTrafficData()
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun updateTrafficData() {
        val nsm = getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        val now = System.currentTimeMillis()
        val intervalMs = (now - lastTickTime).coerceAtLeast(1000L)
        val intervalSec = intervalMs / 1000.0

        val totalNow = getDeviceTotalBytes()
        val totalDiff = (totalNow - lastTotalBytes).coerceAtLeast(0L)
        val totalSpeed = (totalDiff / intervalSec).toLong()

        val windowMs = 5000L
        val start = now - windowMs
        val uidBytes = mutableMapOf<Int, Long>()
        collectUidBytesByWindow(nsm, ConnectivityManager.TYPE_WIFI, start, now, uidBytes)
        collectUidBytesByWindow(nsm, ConnectivityManager.TYPE_MOBILE, start, now, uidBytes)

        val uidToPackage = mutableMapOf<Int, String>()
        val uidToName = mutableMapOf<Int, String>()
        for (app in apps) {
            if (!uidToPackage.containsKey(app.uid)) {
                uidToPackage[app.uid] = app.packageName
                uidToName[app.uid] = pm.getApplicationLabel(app).toString()
            }
        }

        val items = uidBytes.entries
            .asSequence()
            .filter { it.value > 0L && uidToPackage.containsKey(it.key) }
            .map { entry ->
                val speed = (entry.value / (windowMs / 1000.0)).toLong()
                TrafficInfo(
                    appName = uidToName[entry.key] ?: entry.key.toString(),
                    packageName = uidToPackage[entry.key] ?: "unknown",
                    uid = entry.key,
                    speed = speed
                )
            }
            .filter { it.speed > 0L }
            .sortedByDescending { it.speed }
            .toList()

        TrafficRepository.update(totalSpeed, items)
        updateFloatingUI(totalSpeed, items)

        lastTickTime = now
        lastTotalBytes = totalNow
    }

    private fun collectUidBytesByWindow(
        nsm: NetworkStatsManager,
        networkType: Int,
        startTime: Long,
        endTime: Long,
        uidBytes: MutableMap<Int, Long>
    ) {
        try {
            val stats = nsm.querySummary(networkType, null, startTime, endTime)
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                if (bucket.uid < 10000) continue
                val bytes = (bucket.rxBytes + bucket.txBytes).coerceAtLeast(0L)
                uidBytes[bucket.uid] = (uidBytes[bucket.uid] ?: 0L) + bytes
            }
            stats.close()
        } catch (_: Exception) {
        }
    }

    private fun getDeviceTotalBytes(): Long {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) return 0L
        return rx + tx
    }

    private fun updateFloatingUI(totalSpeed: Long, items: List<TrafficInfo>) {
        tvTotalSpeed.text = "Total ${formatSpeed(totalSpeed)}"

        if (items.isEmpty()) {
            tvTopApp.text = "Top app: none"
            tvTopList.text = "No active app traffic"
            return
        }

        val top = items.first()
        tvTopApp.text = "Top app: ${top.appName} (${formatSpeed(top.speed)})"

        val topFive = items.take(5)
        val lines = topFive.mapIndexed { index, info ->
            "${index + 1}. ${info.appName} ${formatSpeed(info.speed)}"
        }
        tvTopList.text = lines.joinToString("\n")
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec < 1024) return "${bytesPerSec} B/s"
        val kb = bytesPerSec / 1024.0
        if (kb < 1024) return String.format("%.1f KB/s", kb)
        return String.format("%.2f MB/s", kb / 1024.0)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "traffic_channel",
                "Traffic Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "traffic_channel")
            .setContentTitle("Traffic monitor running")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        windowManager.removeView(floatingView)
        handler.removeCallbacksAndMessages(null)
    }
}
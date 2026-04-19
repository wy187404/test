package com.example.trafficmonitor

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvSummary: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private val items = mutableListOf<String>()

    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refreshTrafficList()
            uiHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvSummary = findViewById(R.id.tvSummary)
        listView = findViewById(R.id.listView)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        listView.adapter = adapter

        findViewById<Button>(R.id.btnStartService).setOnClickListener {
            checkPermissionsAndStart()
        }

        checkPermissionsAndStart()
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(refreshTask)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(refreshTask)
    }

    private fun refreshTrafficList() {
        val snapshot = TrafficRepository.getSnapshot()
        val data = snapshot.items

        tvSummary.text = "Total ${formatSpeed(snapshot.totalSpeed)} | Active apps ${data.size}"

        items.clear()
        if (data.isEmpty()) {
            items.add("No active app traffic yet")
        } else {
            data.forEachIndexed { index, info ->
                items.add(String.format("%02d. %s  %s", index + 1, info.appName, formatSpeed(info.speed)))
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun checkPermissionsAndStart() {
        if (!hasUsageStatsPermission()) {
            requestUsageStatsPermission()
            return
        }

        if (!hasOverlayPermission()) {
            requestOverlayPermission()
            return
        }

        startTrafficService()
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun requestUsageStatsPermission() {
        Toast.makeText(this, "Please enable usage access permission", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Toast.makeText(this, "Please enable overlay permission", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        }
    }

    private fun startTrafficService() {
        val intent = Intent(this, TrafficMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec < 1024) return "${bytesPerSec} B/s"
        val kb = bytesPerSec / 1024.0
        if (kb < 1024) return String.format("%.1f KB/s", kb)
        return String.format("%.2f MB/s", kb / 1024.0)
    }
}
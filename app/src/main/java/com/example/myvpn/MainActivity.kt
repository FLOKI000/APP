package com.example.myvpn

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.*
import android.content.ComponentName

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var statusText: TextView
    private lateinit var connectBtn: Button
    private lateinit var timerText: TextView
    private lateinit var serverLabel: TextView
    private var isConnected = false
    private var startTime: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        connectBtn = findViewById(R.id.connectBtn)
        timerText = findViewById(R.id.timerText)
        serverLabel = findViewById(R.id.serverLabel)

        connectBtn.setOnClickListener {
            connectBtn.isEnabled = false
            scope.launch {
                if (!isConnected) {
                    // load servers and pick best
                    statusText.text = "Selecting..."
                    val servers = loadServers(this@MainActivity)
                    if (servers.isEmpty()) {
                        statusText.text = "No servers"
                        connectBtn.isEnabled = true
                        return@launch
                    }
                    var best = servers[0]
                    var bestLatency = Long.MAX_VALUE
                    for (s in servers) {
                        val lat = measureTcpLatency(s.host, s.port, 2000)
                        if (lat < bestLatency) {
                            bestLatency = lat
                            best = s
                        }
                    }
                    serverLabel.text = "${best.remark} (${best.host}:${best.port})"
                    statusText.text = "Connecting..."
                    // send server to service via Intent extras
                    val i = Intent(this@MainActivity, XrayService::class.java)
                    i.action = XrayService.ACTION_START
                    i.putExtra("method", best.method)
                    i.putExtra("password", best.password)
                    i.putExtra("host", best.host)
                    i.putExtra("port", best.port)
                    i.putExtra("remark", best.remark)
                    startForegroundServiceCompat(i)
                    isConnected = true
                    statusText.text = "CONNECTED"
                    startTime = System.currentTimeMillis()
                    tickTimer()
                } else {
                    // stop
                    val i = Intent(this@MainActivity, XrayService::class.java)
                    i.action = XrayService.ACTION_STOP
                    startService(i)
                    isConnected = false
                    statusText.text = "DISCONNECTED"
                    serverLabel.text = "No server"
                    timerText.text = "00:00:00"
                }
                connectBtn.isEnabled = true
            }
        }
    }

    private fun tickTimer() {
        scope.launch {
            while (isConnected) {
                val elapsed = System.currentTimeMillis() - startTime
                val s = (elapsed / 1000) % 60
                val m = (elapsed / (1000*60)) % 60
                val h = (elapsed / (1000*60*60))
                timerText.text = String.format("%02d:%02d:%02d", h, m, s)
                delay(1000)
            }
        }
    }

    private fun startForegroundServiceCompat(intent: Intent) {
        // startForegroundService for API >= 26
        startService(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}

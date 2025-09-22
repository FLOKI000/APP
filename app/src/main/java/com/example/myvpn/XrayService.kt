package com.example.myvpn

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.content.Context
import kotlinx.coroutines.*
import java.io.File

class XrayService : Service() {
    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val CHANNEL_ID = "xray_service_channel"
    }

    private var process: Process? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            val method = intent.getStringExtra("method") ?: "chacha20-ietf-poly1305"
            val password = intent.getStringExtra("password") ?: ""
            val host = intent.getStringExtra("host") ?: ""
            val port = intent.getIntExtra("port", 443)
            val remark = intent.getStringExtra("remark") ?: ""

            startForeground(1, buildNotification("Connecting to $remark"))

            scope.launch {
                startXray(method, password, host, port)
            }
        } else if (action == ACTION_STOP) {
            stopXray()
            stopForeground(true)
            stopSelf()
        }
        return START_STICKY
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MyVPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "Xray Service", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
    }

    private suspend fun startXray(method: String, password: String, host: String, port: Int) {
        withContext(Dispatchers.IO) {
            try {
                val filesDir = applicationContext.filesDir
                // 1) prepare xray config JSON
                val config = buildXrayJsonForShadowsocks(host, port, method, password)
                val confFile = File(filesDir, "xray_config.json")
                confFile.writeText(config)

                // 2) copy xray binary from assets or jniLibs to filesDir and make executable
                // We expect binary at app/src/main/jniLibs/<ABI>/xray and it will be packaged to lib path.
                val bin = File(filesDir, "xray")
                if (!bin.exists()) {
                    // Attempt to copy from native libs path
                    val assetStream = try {
                        // Try assets first
                        applicationContext.assets.open("xray") // if you put in assets
                    } catch (e: Exception) {
                        null
                    }
                    if (assetStream != null) {
                        assetStream.use { ins ->
                            bin.outputStream().use { outs ->
                                ins.copyTo(outs)
                            }
                        }
                    } else {
                        // Might be in native library dir; try System.loadLibrary not used here.
                        // If binary not found, throw
                        throw IllegalStateException("xray binary not found in assets or jniLibs. Put xray into assets/xray or into jniLibs/<abi>/xray")
                    }
                }

                bin.setExecutable(true)

                // 3) start process
                val pb = ProcessBuilder(bin.absolutePath, "-c", confFile.absolutePath)
                pb.redirectErrorStream(true)
                process = pb.start()
                // optionally read process output to logs
                val input = process!!.inputStream
                val buffer = ByteArray(1024)
                while (process?.isAlive == true) {
                    val r = input.read(buffer)
                    if (r > 0) {
                        // we could write to a file or Log
                    }
                    delay(100)
                }
            } catch (e: Exception) {
                // log
            }
        }
    }

    private fun stopXray() {
        try {
            process?.destroy()
            process = null
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        stopXray()
        scope.cancel()
        super.onDestroy()
    }
}

fun buildXrayJsonForShadowsocks(host: String, port: Int, method: String, password: String): String {
    // Minimal outbound shadowsocks client
    return """
    {
      "log": { "access": "", "error": "", "loglevel": "warning" },
      "outbounds": [
        {
          "protocol": "shadowsocks",
          "settings": {
            "servers": [
              {
                "address": "$host",
                "port": $port,
                "method": "$method",
                "password": "$password"
              }
            ]
          }
        }
      ],
      "inbounds": [
        {
          "port": 1080,
          "listen": "127.0.0.1",
          "protocol": "socks",
          "settings": { "udp": false }
        }
      ]
    }
    """.trimIndent()
}

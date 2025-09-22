package com.example.myvpn

import android.content.Context
import java.net.URI
import android.util.Base64
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SsServer(val method: String, val password: String, val host: String, val port: Int, val remark: String)

suspend fun loadServers(context: Context): List<SsServer> = withContext(Dispatchers.IO) {
    val list = mutableListOf<SsServer>()
    val lines = context.assets.open("servers.txt").bufferedReader().useLines { it.toList() }
    for (line in lines.map { it.trim() }.filter { it.isNotEmpty() }) {
        // expecting ss://<base64 or method:password@host:port>#remark
        try {
            val hashIdx = line.indexOf("#")
            val remark = if (hashIdx >= 0) java.net.URLDecoder.decode(line.substring(hashIdx + 1), "utf-8") else ""
            val ssPart = if (hashIdx >= 0) line.substring(0, hashIdx) else line
            val payload = ssPart.removePrefix("ss://")
            val decoded = if (payload.contains("@")) {
                // method:password@host:port (already plain)
                payload
            } else {
                // base64 encoding
                val raw = String(Base64.decode(payload, Base64.NO_WRAP))
                raw
            }
            // Now raw is like method:password@host:port
            val idx = decoded.indexOf("@")
            val creds = decoded.substring(0, idx)
            val hostport = decoded.substring(idx + 1)
            val colon = hostport.lastIndexOf(":")
            val host = hostport.substring(0, colon)
            val port = hostport.substring(colon + 1).toInt()
            val methodPass = creds.split(":", limit = 2)
            val method = methodPass[0]
            val password = methodPass.getOrNull(1) ?: ""
            list.add(SsServer(method, password, host, port, remark))
        } catch (e: Exception) {
            // ignore malformed line
        }
    }
    list
}

// measure connect time (TCP) to host:port, returns ms or Long.MAX_VALUE on fail
suspend fun measureTcpLatency(host: String, port: Int, timeoutMs: Int = 2000): Long = withContext(Dispatchers.IO) {
    val socket = Socket()
    return@withContext try {
        val start = System.currentTimeMillis()
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        System.currentTimeMillis() - start
    } catch (e: Exception) {
        Long.MAX_VALUE
    } finally {
        try { socket.close() } catch (_: Exception) {}
    }
}

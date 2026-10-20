package com.nova.personalagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nova.personalagent.network.NovaApiClient
import com.nova.personalagent.network.NovaApiResult
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

@RunWith(AndroidJUnit4::class)
class NetworkConnectivityTest {

    @Test
    fun testRawSocket10022() {
        println("--- testRawSocket10022 START ---")
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("10.0.2.2", 8000), 3000)
                println("Raw socket 10.0.2.2:8000 CONNECTED successfully!")
            }
        } catch (e: Exception) {
            println("Raw socket 10.0.2.2:8000 FAILED: ${e.javaClass.name}: ${e.message}")
        }
    }

    @Test
    fun testHttpURLConnection10022() {
        println("--- testHttpURLConnection10022 START ---")
        try {
            val url = URL("http://10.0.2.2:8000/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val code = conn.responseCode
            val text = conn.inputStream.bufferedReader().readText()
            println("HttpURLConnection 10.0.2.2:8000 code=$code body=$text")
        } catch (e: Exception) {
            println("HttpURLConnection 10.0.2.2:8000 FAILED: ${e.javaClass.name}: ${e.message}")
        }
    }

    @Test
    fun testNovaApiClient10022() = runBlocking {
        println("--- testNovaApiClient10022 START ---")
        val client = NovaApiClient("http://10.0.2.2:8000", connectTimeoutSeconds = 3L)
        val result = client.getHealth()
        println("NovaApiClient 10.0.2.2:8000 result=$result")
    }

    @Test
    fun testNovaApiClient127001() = runBlocking {
        println("--- testNovaApiClient127001 START ---")
        val client = NovaApiClient("http://127.0.0.1:8000", connectTimeoutSeconds = 3L)
        val result = client.getHealth()
        println("NovaApiClient 127.0.0.1:8000 result=$result")
    }
}

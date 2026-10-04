package com.briqt.moke.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HeartbeatSshClientTest {
    @Test
    fun `heartbeat sends no IGNORE before initial key exchange completes`() {
        val endpoint = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                server.soTimeout = 5_000
                val packets = endpoint.submit(Callable {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        socket.getOutputStream().apply {
                            write("SSH-2.0-strict-kex-test\r\n".toByteArray(Charsets.US_ASCII))
                            flush()
                        }
                        val input = DataInputStream(socket.getInputStream())
                        while (input.readUnsignedByte() != '\n'.code) { /* client identification */ }
                        val types = mutableListOf(readPacketType(input))
                        // 不回复服务端 KEXINIT：密钥交换一直未完成，不能发送心跳。
                        socket.soTimeout = 1_000
                        try {
                            types += readPacketType(input)
                        } catch (_: SocketTimeoutException) {
                            // 握手暂停期间没有其他包才符合 Strict KEX。
                        }
                        types
                    }
                })
                HeartbeatSshClient().use { client ->
                    client.connectTimeout = 5_000
                    client.transport.timeoutMs = 5_000
                    try {
                        client.connect("127.0.0.1", server.localPort)
                        fail("The endpoint never completes key exchange")
                    } catch (_: IOException) {
                        // 端点捕获数据后关闭连接；不是一个完整 SSH 服务端。
                    }
                }
                assertEquals("Only KEXINIT is allowed while initial KEX is pending", listOf(20), packets.get(5, TimeUnit.SECONDS))
            }
        } finally {
            endpoint.shutdownNow()
            endpoint.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun readPacketType(input: DataInputStream): Int {
        val length = input.readInt()
        require(length in 6..262_144) { "Invalid SSH packet length: $length" }
        val padding = input.readUnsignedByte()
        require(padding >= 4 && padding < length - 1) { "Invalid SSH padding: $padding" }
        val type = input.readUnsignedByte()
        input.readFully(ByteArray(length - 2))
        return type
    }
}

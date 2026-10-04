package com.briqt.moke.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.briqt.moke.data.Host
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SshHeartbeatTest {
    /** Requires an isolated loopback SSH fixture and adb reverse for this port. */
    @Test
    fun heartbeatStartsWhenTargetAndJumpConnectionsAreEstablished() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Needs isolated loopback SSH fixture", args.containsKey("recoverySshPort"))
        val port = args.getString("recoverySshPort")!!.toInt()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connector = SshConnector(context)
        val host = Host(host = "127.0.0.1", port = port, username = "smoke", password = "smoke-only")
        val oldTrust = HostKeyPrompt.autoTrust
        val knownHosts = KnownHosts(context)
        val trustId = KnownHosts.idOf(host.host, host.port)
        val savedFingerprint = knownHosts.stored(trustId)
        HostKeyPrompt.autoTrust = true
        try {
            connector.connect(host, null).let { connection ->
                try {
                    assertFalse("Short connection needs no heartbeat", connection.client.connection.keepAlive.isEnabled)
                    assertFalse("Short connection must not start heartbeat thread", connection.client.connection.keepAlive.isAlive)
                } finally {
                    connection.close()
                }
            }
            // Assert after real connect/auth: merely setting the interval after connect never starts SSHJ's thread.
            connector.connect(host, null, heartbeat = true).let { connection ->
                try {
                    assertEquals(30, connection.client.connection.keepAlive.keepAliveInterval)
                    assertTrue("Direct target heartbeat thread must run", connection.client.connection.keepAlive.isAlive)
                } finally {
                    connection.close()
                }
            }
            connector.connect(host, host, heartbeat = true).let { connection ->
                try {
                    assertEquals(30, connection.client.connection.keepAlive.keepAliveInterval)
                    assertTrue("Forwarded target heartbeat thread must run", connection.client.connection.keepAlive.isAlive)
                    val jump = requireNotNull(connection.jump)
                    assertEquals(30, jump.connection.keepAlive.keepAliveInterval)
                    assertTrue("Jump heartbeat thread must run", jump.connection.keepAlive.isAlive)
                } finally {
                    connection.close()
                }
            }
        } finally {
            HostKeyPrompt.autoTrust = oldTrust
            if (savedFingerprint == null) knownHosts.forget(trustId)
            else knownHosts.store(trustId, savedFingerprint)
        }
    }
}

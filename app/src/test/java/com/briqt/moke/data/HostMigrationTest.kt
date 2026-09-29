package com.briqt.moke.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HostMigrationTest {
    private val gateway = Host(
        id = "gateway", label = "Gateway", host = "gateway.example", username = "alice",
        password = "unique-secret-password", privateKeyPem = "unique-secret-pem",
        passphrase = "unique-secret-passphrase", projectPath = "/work/a",
    )
    private val app = Host(
        id = "app", label = "Application", host = "app.example", username = "bob",
        jumpHostId = "gateway", authType = AuthType.KEY, useMosh = true,
        group = "dev", startupCommand = "bash", loginCommand = "pwd",
        persistence = SessionPersistence.TMUX, tmuxSessionName = "app-work",
        forwardPorts = "5173", projectPath = "/work/app",
    )

    @Test
    fun `export is portable versioned JSON without credentials or local usage history`() {
        val json = HostMigration.export(listOf(gateway.copy(lastConnectedAt = 999L), app))
        val root = JSONObject(json)
        assertEquals(1, root.getInt("version"))
        assertEquals(2, root.getJSONArray("hosts").length())
        assertFalse(json.contains("unique-secret-password"))
        assertFalse(json.contains("unique-secret-pem"))
        assertFalse(json.contains("unique-secret-passphrase"))
        val item = root.getJSONArray("hosts").getJSONObject(1)
        assertEquals("gateway", item.getString("jumpHostId"))
        assertEquals("KEY", item.getString("authType"))
        assertEquals("TMUX", item.getString("persistence"))
        assertEquals("/work/app", item.getString("projectPath"))
        assertFalse(item.has("password"))
        assertFalse(item.has("privateKeyPem"))
        assertFalse(item.has("passphrase"))
        assertFalse(item.has("lastConnectedAt"))
    }

    @Test
    fun `export can migrate a host whose deleted jump target is no longer present`() {
        val dangling = app.copy(jumpHostId = "deleted-gateway")
        val file = HostMigration.export(listOf(dangling))
        val exported = JSONObject(file).getJSONArray("hosts").getJSONObject(0)
        assertEquals("", exported.getString("jumpHostId"))
        val imported = HostMigration.preview(file, emptyList())
        val result = HostMigration.apply(imported, mapOf(0 to HostMigration.Decision.ADD))
        assertEquals("", result.single().jumpHostId)
        assertEquals("app.example", result.single().host)
        assertEquals("", result.single().password)
    }

    @Test
    fun `preview does not alter hosts and add remaps jump links to independent ids`() {
        val existing = listOf(gateway.copy(id = "local", label = "Already here"))
        val before = existing.toList()
        val preview = HostMigration.preview(HostMigration.export(listOf(gateway, app)), existing)
        assertEquals("local", preview.entries[0].conflictId)
        assertEquals(null, preview.entries[1].conflictId)
        assertEquals(before, existing)
        val merged = HostMigration.apply(preview, mapOf(0 to HostMigration.Decision.ADD, 1 to HostMigration.Decision.ADD))
        assertEquals(before, existing)
        assertEquals(3, merged.size)
        assertNotEquals("gateway", merged[1].id)
        assertNotEquals("local", merged[1].id)
        assertNotEquals("app", merged[2].id)
        assertEquals(merged[1].id, merged[2].jumpHostId)
        assertEquals("", merged[1].password)
        assertEquals("", merged[1].privateKeyPem)
        assertEquals("", merged[1].passphrase)
        assertEquals("/work/app", merged[2].projectPath)
    }

    @Test
    fun `overwrite keeps local credentials identity and usage while replacing public settings`() {
        val local = gateway.copy(
            id = "local-id", label = "Old", host = "gateway.example", lastConnectedAt = 123L,
        )
        val preview = HostMigration.preview(HostMigration.export(listOf(gateway, app)), listOf(local))
        val merged = HostMigration.apply(
            preview, mapOf(0 to HostMigration.Decision.OVERWRITE, 1 to HostMigration.Decision.ADD),
        )
        assertEquals(2, merged.size)
        assertEquals("local-id", merged[0].id)
        assertEquals("Gateway", merged[0].label)
        assertEquals(123L, merged[0].lastConnectedAt)
        assertEquals(local.password, merged[0].password)
        assertEquals(local.privateKeyPem, merged[0].privateKeyPem)
        assertEquals(local.passphrase, merged[0].passphrase)
        assertEquals("local-id", merged[1].jumpHostId)
        assertEquals(AuthType.KEY, merged[1].authType)
        assertEquals(SessionPersistence.TMUX, merged[1].persistence)
    }

    @Test
    fun `overwrite of another endpoint cannot inherit old credentials`() {
        val local = gateway.copy(id = "gateway", host = "old.example", username = "alice")
        val preview = HostMigration.preview(HostMigration.export(listOf(gateway)), listOf(local))
        val merged = HostMigration.apply(preview, mapOf(0 to HostMigration.Decision.OVERWRITE))
        assertEquals("gateway.example", merged.single().host)
        assertEquals("", merged.single().password)
        assertEquals("", merged.single().privateKeyPem)
        assertEquals("", merged.single().passphrase)
    }

    @Test
    fun `skip only resolves jump via a matching local host and cannot silently drop links`() {
        val json = HostMigration.export(listOf(gateway, app))
        val matched = HostMigration.preview(json, listOf(gateway.copy(id = "local-gateway")))
        val merged = HostMigration.apply(matched, mapOf(0 to HostMigration.Decision.SKIP, 1 to HostMigration.Decision.ADD))
        assertEquals("local-gateway", merged[1].jumpHostId)
        val unmatched = HostMigration.preview(json, emptyList())
        val error = assertThrows(HostMigration.MigrationException::class.java) {
            HostMigration.apply(unmatched, mapOf(0 to HostMigration.Decision.SKIP, 1 to HostMigration.Decision.ADD))
        }
        assertTrue(error.message!!.contains("hosts[1].jumpHostId"))
    }

    @Test
    fun `each row needs an explicit decision and overwrite cannot target the same local host twice`() {
        val imported = listOf(gateway, gateway.copy(id = "second", label = "Other"))
        val preview = HostMigration.preview(HostMigration.export(imported), listOf(gateway.copy(id = "local")))
        assertThrows(HostMigration.MigrationException::class.java) {
            HostMigration.apply(preview, mapOf(0 to HostMigration.Decision.SKIP))
        }
        val error = assertThrows(HostMigration.MigrationException::class.java) {
            HostMigration.apply(preview, mapOf(0 to HostMigration.Decision.OVERWRITE, 1 to HostMigration.Decision.OVERWRITE))
        }
        assertTrue(error.message!!.contains("hosts[1]"))
    }

    @Test
    fun `skip cannot serve as jump destination when another row overwrites it`() {
        val local = gateway.copy(id = "local")
        val imported = listOf(gateway, gateway.copy(id = "second"), app)
        val preview = HostMigration.preview(HostMigration.export(imported), listOf(local))
        val error = assertThrows(HostMigration.MigrationException::class.java) {
            HostMigration.apply(preview, mapOf(
                0 to HostMigration.Decision.SKIP,
                1 to HostMigration.Decision.OVERWRITE,
                2 to HostMigration.Decision.ADD,
            ))
        }
        assertTrue(error.message!!.contains("hosts[0]"))
    }

    @Test
    fun `overwrite cannot introduce cycle through an unchanged local host`() {
        val local = gateway.copy(id = "local", jumpHostId = "")
        val untouched = Host(id = "untouched", host = "other.example", username = "other", jumpHostId = "local")
        val imported = listOf(gateway.copy(jumpHostId = "other"), untouched.copy(id = "other", jumpHostId = ""))
        val preview = HostMigration.preview(HostMigration.export(imported), listOf(local, untouched))
        val error = assertThrows(HostMigration.MigrationException::class.java) {
            HostMigration.apply(preview, mapOf(0 to HostMigration.Decision.OVERWRITE, 1 to HostMigration.Decision.SKIP))
        }
        assertTrue(error.message!!.contains("hosts[0].jumpHostId"))
    }

    @Test
    fun `reject invalid shape types enums and relationships with row locations`() {
        val valid = HostMigration.export(listOf(gateway, app))
        fun broken(edit: (JSONObject) -> Unit): String = JSONObject(valid).also(edit).toString()
        val invalid = listOf(
            "$.version" to broken { it.put("version", 2) },
            "hosts[0].password" to broken { it.getJSONArray("hosts").getJSONObject(0).put("password", "stolen") },
            "hosts[1].host" to broken { it.getJSONArray("hosts").getJSONObject(1).remove("host") },
            "hosts[1].port" to broken { it.getJSONArray("hosts").getJSONObject(1).put("port", "22") },
            "hosts[1].authType" to broken { it.getJSONArray("hosts").getJSONObject(1).put("authType", "UNKNOWN") },
            "hosts[1].persistence" to broken { it.getJSONArray("hosts").getJSONObject(1).put("persistence", "BAD") },
            "hosts[1].useMosh" to broken { it.getJSONArray("hosts").getJSONObject(1).put("useMosh", "true") },
            "hosts[1].jumpHostId" to broken { it.getJSONArray("hosts").getJSONObject(1).put("jumpHostId", "absent") },
            "hosts[1].jumpHostId" to broken { it.getJSONArray("hosts").getJSONObject(1).put("jumpHostId", "app") },
            "hosts[1].id" to broken { it.getJSONArray("hosts").getJSONObject(1).put("id", "gateway") },
            "hosts[1]" to broken { it.getJSONArray("hosts").put(1, "not a host") },
        )
        invalid.forEach { (location, json) ->
            val error = assertThrows(HostMigration.MigrationException::class.java) {
                HostMigration.preview(json, emptyList())
            }
            assertTrue("expected $location in ${error.message}", error.message!!.contains(location))
        }
        assertThrows(HostMigration.MigrationException::class.java) {
            HostMigration.preview("not JSON", emptyList())
        }
        val wrongRoot = JSONObject().put("version", 1).put("hosts", JSONArray().put(4)).toString()
        assertTrue(assertThrows(HostMigration.MigrationException::class.java) {
            HostMigration.preview(wrongRoot, emptyList())
        }.message!!.contains("hosts[0]"))
    }
}

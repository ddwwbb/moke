package com.briqt.moke.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Portable, versioned host configuration. Credentials and device-local connection history never leave HostStore. */
object HostMigration {
    private const val VERSION = 1
    private val fields = setOf(
        "id", "label", "host", "port", "username", "authType", "useMosh", "jumpHostId",
        "startupCommand", "loginCommand", "group", "persistence", "tmuxSessionName",
        "forwardPorts", "projectPath",
    )

    class MigrationException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

    data class PublicHost(
        val id: String,
        val label: String,
        val host: String,
        val port: Int,
        val username: String,
        val authType: AuthType,
        val useMosh: Boolean,
        val jumpHostId: String,
        val startupCommand: String,
        val loginCommand: String,
        val group: String,
        val persistence: SessionPersistence,
        val tmuxSessionName: String,
        val forwardPorts: String,
        val projectPath: String,
    )

    data class Entry internal constructor(
        /** Zero-based index into the JSON hosts array, also the key used for apply decisions. */
        val index: Int,
        val source: PublicHost,
        /** A matching local record, if any; no credentials are exposed in the preview. */
        val conflictId: String?,
        val conflictLabel: String?,
    )

    class Preview internal constructor(
        val entries: List<Entry>,
        internal val existing: List<Host>,
    )

    /** 使用解析时的主机快照提交，拒绝预览后发生的并发本地编辑。 */
    suspend fun save(preview: Preview, decisions: Map<Int, Decision>, store: HostStore): Boolean =
        store.saveIfUnchanged(preview.existing, apply(preview, decisions))

    enum class Decision { ADD, OVERWRITE, SKIP }

    /** Export only public fields; never call Host.toJson(), which includes credentials. */
    fun export(hosts: List<Host>): String {
        val ids = hosts.mapTo(HashSet(hosts.size)) { it.id }
        val publicHosts = hosts.mapIndexed { i, host ->
            fromHost(host).copy(jumpHostId = host.jumpHostId.takeIf { it in ids }.orEmpty())
                .also { validate(it, "hosts[$i]") }
        }
        validateRelations(publicHosts)
        return JSONObject().apply {
            put("version", VERSION)
            put("hosts", JSONArray().apply { publicHosts.forEach { put(it.toJson()) } })
        }.toString()
    }

    /** Parse the whole document before previewing. Neither this nor apply writes to HostStore. */
    fun preview(json: String, existing: List<Host>): Preview {
        val root = try { JSONObject(json) } catch (e: Exception) {
            throw MigrationException("$: invalid JSON object", e)
        }
        exactFields(root, setOf("version", "hosts"), "$")
        if (integer(root, "version", "$") != VERSION) {
            throw MigrationException("$.version: unsupported migration version (expected $VERSION)")
        }
        val array = required(root, "hosts", "$") as? JSONArray
            ?: throw MigrationException("$.hosts: expected array")
        val hosts = (0 until array.length()).map { index ->
            val path = "hosts[$index]"
            val item = array.get(index) as? JSONObject
                ?: throw MigrationException("$path: expected object")
            exactFields(item, fields, path)
            PublicHost(
                id = string(item, "id", path), label = string(item, "label", path),
                host = string(item, "host", path), port = integer(item, "port", path),
                username = string(item, "username", path),
                authType = enumValue<AuthType>(string(item, "authType", path), "$path.authType"),
                useMosh = required(item, "useMosh", path) as? Boolean
                    ?: throw MigrationException("$path.useMosh: expected boolean"),
                jumpHostId = string(item, "jumpHostId", path),
                startupCommand = string(item, "startupCommand", path),
                loginCommand = string(item, "loginCommand", path),
                group = string(item, "group", path),
                persistence = enumValue<SessionPersistence>(
                    string(item, "persistence", path), "$path.persistence",
                ),
                tmuxSessionName = string(item, "tmuxSessionName", path),
                forwardPorts = string(item, "forwardPorts", path),
                projectPath = string(item, "projectPath", path),
            ).also { validate(it, path) }
        }
        validateRelations(hosts)
        val snapshot = existing.toList()
        val byId = snapshot.groupBy { it.id }
        val byAddress = snapshot.groupBy { Triple(it.host, it.port, it.username) }
        val entries = hosts.mapIndexed { index, host ->
            val matches = byId[host.id].orEmpty().ifEmpty {
                byAddress[Triple(host.host, host.port, host.username)].orEmpty()
            }
            if (matches.size > 1) throw MigrationException("hosts[$index]: ambiguous local match")
            Entry(index, host, matches.singleOrNull()?.id, matches.singleOrNull()?.label)
        }
        return Preview(entries, snapshot)
    }

    /** All rows require explicit decisions. A skipped jump host can only be used if it matched a local host. */
    fun apply(preview: Preview, decisions: Map<Int, Decision>): List<Host> {
        val entries = preview.entries
        val expected = entries.indices.toSet()
        if (decisions.keys != expected) {
            throw MigrationException("decisions: specify ADD, OVERWRITE or SKIP for every hosts index")
        }
        val existing = preview.existing
        val reserved = existing.mapTo(entries.mapTo(mutableSetOf()) { it.source.id }) { it.id }
        val targets = mutableMapOf<String, String>()
        val overwritten = mutableSetOf<String>()
        entries.forEach { entry ->
            val i = entry.index
            when (decisions.getValue(i)) {
                Decision.ADD -> {
                    var id: String
                    do { id = UUID.randomUUID().toString() } while (!reserved.add(id))
                    targets[entry.source.id] = id
                }
                Decision.OVERWRITE -> {
                    val id = entry.conflictId
                        ?: throw MigrationException("hosts[$i]: no local host to overwrite")
                    if (!overwritten.add(id)) throw MigrationException("hosts[$i]: local host already overwritten")
                    targets[entry.source.id] = id
                }
                Decision.SKIP -> entry.conflictId?.let { targets[entry.source.id] = it }
            }
        }
        entries.forEach { entry ->
            if (decisions.getValue(entry.index) == Decision.SKIP && entry.conflictId != null && entry.conflictId in overwritten) {
                throw MigrationException("hosts[${entry.index}]: skipped local host is overwritten by another entry")
            }
        }
        val result = existing.toMutableList()
        entries.forEach { entry ->
            val i = entry.index
            if (decisions.getValue(i) == Decision.SKIP) return@forEach
            val source = entry.source
            val jumpId = if (source.jumpHostId.isEmpty()) "" else targets[source.jumpHostId]
                ?: throw MigrationException("hosts[$i].jumpHostId: referenced host was skipped")
            val targetId = targets.getValue(source.id)
            when (decisions.getValue(i)) {
                Decision.ADD -> result.add(source.toHost(targetId, jumpId))
                Decision.OVERWRITE -> {
                    val position = result.indexOfFirst { it.id == targetId }
                    // Different endpoint or identity must not inherit credentials from the prior host.
                    val old = result[position]
                    val sameEndpoint = old.host == source.host && old.port == source.port && old.username == source.username
                    result[position] = source.toHost(targetId, jumpId, old).copy(
                        password = old.password.takeIf { sameEndpoint } ?: "",
                        privateKeyPem = old.privateKeyPem.takeIf { sameEndpoint } ?: "",
                        passphrase = old.passphrase.takeIf { sameEndpoint } ?: "",
                    )
                }
                Decision.SKIP -> error("handled above")
            }
        }
        // Existing jump links may refer to an overwritten host. Do not introduce a cycle through them.
        val byId = result.associateBy { it.id }
        entries.filter { decisions.getValue(it.index) != Decision.SKIP }.forEach { entry ->
            val start = targets.getValue(entry.source.id)
            val seen = mutableSetOf<String>()
            var id = start
            while (id.isNotEmpty() && seen.add(id)) id = byId[id]?.jumpHostId.orEmpty()
            if (id.isNotEmpty()) throw MigrationException("hosts[${entry.index}].jumpHostId: circular local jump relationship")
        }
        return result
    }

    private fun fromHost(h: Host) = PublicHost(
        h.id, h.label, h.host, h.port, h.username, h.authType, h.useMosh, h.jumpHostId,
        h.startupCommand, h.loginCommand, h.group, h.persistence, h.tmuxSessionName,
        h.forwardPorts, h.projectPath,
    )

    private fun PublicHost.toHost(id: String, jumpId: String, old: Host? = null): Host =
        (old ?: Host(id = id)).copy(
            label = label, host = host, port = port, username = username, authType = authType,
            useMosh = useMosh, jumpHostId = jumpId, startupCommand = startupCommand,
            loginCommand = loginCommand, group = group, persistence = persistence,
            tmuxSessionName = tmuxSessionName, forwardPorts = forwardPorts, projectPath = projectPath,
        )

    private fun PublicHost.toJson() = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("host", host)
        put("port", port)
        put("username", username)
        put("authType", authType.name)
        put("useMosh", useMosh)
        put("jumpHostId", jumpHostId)
        put("startupCommand", startupCommand)
        put("loginCommand", loginCommand)
        put("group", group)
        put("persistence", persistence.name)
        put("tmuxSessionName", tmuxSessionName)
        put("forwardPorts", forwardPorts)
        put("projectPath", projectPath)
    }

    private fun validate(host: PublicHost, path: String) {
        if (host.id.isBlank()) throw MigrationException("$path.id: required nonempty string")
        if (host.host.isBlank()) throw MigrationException("$path.host: required nonempty string")
        if (host.username.isBlank()) throw MigrationException("$path.username: required nonempty string")
        if (host.port !in 1..65535) throw MigrationException("$path.port: must be between 1 and 65535")
        if (host.projectPath.isNotBlank() && !host.projectPath.startsWith('/')) {
            throw MigrationException("$path.projectPath: expected absolute directory")
        }
    }

    private fun validateRelations(hosts: List<PublicHost>) {
        val byId = mutableMapOf<String, Int>()
        hosts.forEachIndexed { i, host ->
            if (byId.putIfAbsent(host.id, i) != null) throw MigrationException("hosts[$i].id: duplicate id")
        }
        hosts.forEachIndexed { i, host ->
            if (host.jumpHostId.isNotEmpty() && host.jumpHostId !in byId) {
                throw MigrationException("hosts[$i].jumpHostId: referenced host is not in the file")
            }
            val seen = mutableSetOf<String>()
            var id = host.id
            while (id.isNotEmpty() && seen.add(id)) id = hosts[byId.getValue(id)].jumpHostId
            if (id.isNotEmpty()) throw MigrationException("hosts[$i].jumpHostId: circular jump relationship")
        }
    }

    private fun exactFields(obj: JSONObject, expected: Set<String>, path: String) {
        val keys = obj.keys().asSequence().toSet()
        val missing = expected - keys
        if (missing.isNotEmpty()) throw MigrationException("$path.${missing.sorted().first()}: missing field")
        val extra = keys - expected
        if (extra.isNotEmpty()) throw MigrationException("$path.${extra.sorted().first()}: unknown field")
    }

    private fun required(obj: JSONObject, name: String, path: String): Any {
        if (!obj.has(name) || obj.isNull(name)) throw MigrationException("$path.$name: required non-null field")
        return obj.get(name)
    }

    private fun string(obj: JSONObject, name: String, path: String): String =
        required(obj, name, path) as? String
            ?: throw MigrationException("$path.$name: expected string")

    private fun integer(obj: JSONObject, name: String, path: String): Int {
        val value = required(obj, name, path)
        if (value !is Int && value !is Long) throw MigrationException("$path.$name: expected integer")
        val number = (value as Number).toLong()
        if (number !in Int.MIN_VALUE..Int.MAX_VALUE) throw MigrationException("$path.$name: expected integer")
        return number.toInt()
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, path: String): T =
        enumValues<T>().firstOrNull { it.name == value }
            ?: throw MigrationException("$path: unknown value '$value'")
}

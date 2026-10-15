package com.smarttvremote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.util.LinkedList

/** Tương đương server.js: giữ trạng thái kết nối và xử lý các "API" mà giao diện gọi. */
class Controller(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val certs by lazy { CertStore(ctx) }
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var state = "disconnected"
    @Volatile private var lastError: Pair<String, String>? = null
    @Volatile private var target: String? = null
    @Volatile private var remote: RemoteSession? = null
    @Volatile private var pairing: PairingSession? = null
    @Volatile private var pairingHost = ""

    private val found = LinkedHashMap<String, String>()
    @Volatile private var discovering = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val queue = LinkedList<NsdServiceInfo>()
    private var resolving = false

    companion object {
        val PRIVATE_IP = Regex("^(10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|192\\.168\\.\\d{1,3}\\.\\d{1,3}|172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}|169\\.254\\.\\d{1,3}\\.\\d{1,3})$")
        val KEYS = mapOf("power" to 26, "assistant" to 219, "up" to 19, "down" to 20, "left" to 21, "right" to 22, "ok" to 23,
            "back" to 4, "home" to 3, "menu" to 82, "volume_up" to 24, "volume_down" to 25, "mute" to 164, "open_apps" to 284)
        val LINKS = mapOf("open_netflix" to "https://www.netflix.com", "open_prime_video" to "https://app.primevideo.com",
            "open_disney_plus" to "https://www.disneyplus.com")
    }

    // ---------- định tuyến ----------
    fun handle(method: String, path: String, body: JSONObject): JSONObject = when (path) {
        "/health" -> ok()
        "/settings" -> { if (method == "POST") saveSettings(body); ok().put("settings", settingsJson()) }
        "/devices" -> devicesJson()
        "/discovery/start" -> { startDiscovery(); ok().put("discovering", true) }
        "/devices/connect" -> { connect(host(body.optString("host"))); snapshot() }
        "/devices/pair" -> {
            val code = body.optString("code")
            if (!Regex("^[0-9A-Fa-f]{6}$").matches(code)) throw ApiError(400, "invalid_code", "Mã ghép đôi gồm 6 ký tự (0-9, A-F).")
            pair(code); snapshot()
        }
        "/devices/disconnect" -> { closeAll(); state = "disconnected"; lastError = null; target = null; snapshot() }
        "/device/status" -> snapshot()
        "/remote/command" -> { command(body.optString("command")); ok() }
        "/remote/search" -> { search(body.optString("query")); ok() }
        "/remote/text" -> throw ApiError(400, "unsupported_text", "Bản app này chưa hỗ trợ nhập văn bản.")
        else -> throw ApiError(404, "not_found", "Không tìm thấy.")
    }

    private fun ok() = JSONObject().put("ok", true)

    private fun host(h: String): String {
        val v = h.trim()
        if (!PRIVATE_IP.matches(v) || v.split(".").any { it.toInt() > 255 })
            throw ApiError(400, "invalid_host", "Địa chỉ IP không hợp lệ. Chỉ chấp nhận IP trong mạng nội bộ.")
        return v
    }

    private fun snapshot(): JSONObject {
        val err = lastError
        return ok().put("state", state).put("method", "atv")
            .put("error", if (err == null) JSONObject.NULL else JSONObject().put("code", err.first).put("message", err.second))
            .put("device", JSONObject().put("name", prefs.getString("name", "") ?: "")
                .put("host", target ?: (prefs.getString("host", "") ?: "")).put("port", 5555).put("model", "Android TV"))
    }

    private fun settingsJson() = JSONObject().put("name", prefs.getString("name", "") ?: "")
        .put("host", prefs.getString("host", "") ?: "").put("port", 5555).put("method", "atv")
        .put("packages", JSONObject().put("netflix", "com.netflix.ninja").put("prime", "com.amazon.amazonvideo.livingroom").put("disney", "com.disney.disneyplus"))

    private fun saveSettings(b: JSONObject) {
        if (b.has("name")) prefs.edit().putString("name", b.optString("name").trim().take(40)).apply()
    }

    // ---------- kết nối ----------
    @Synchronized
    private fun connect(host: String) {
        closeAll()
        state = "connecting"; lastError = null; target = null
        try {
            if (prefs.getBoolean("paired_$host", false) && tryRemote(host)) return
            startPairing(host)
        } catch (e: ApiError) {
            state = "error"; lastError = e.code to (e.message ?: ""); throw e
        }
    }

    private fun tryRemote(host: String): Boolean {
        val s = RemoteSession(host, certs.socketFactory) { onRemoteClosed(it) }
        val ready = try { s.start(8000) } catch (e: Exception) { false }
        if (!ready) { s.close(); return false }
        remote = s; target = host; state = "connected"; lastError = null
        prefs.edit().putString("host", host).putBoolean("paired_$host", true).apply()
        return true
    }

    private fun startPairing(host: String) {
        val p = PairingSession(host, certs.socketFactory, certs.clientPublicKey)
        try { p.begin() } catch (e: Exception) {
            p.close()
            throw ApiError(502, "atv_error", "Không kết nối được tới TV. Kiểm tra TV đang bật, cùng Wi-Fi và đúng IP.")
        }
        pairing = p; pairingHost = host; state = "pairing"
    }

    @Synchronized
    private fun pair(code: String) {
        val p = pairing
        if (state != "pairing" || p == null) throw ApiError(409, "not_pairing", "TV chưa yêu cầu mã ghép đôi.")
        try {
            p.finish(code)
        } catch (e: WrongCode) {
            throw ApiError(400, "wrong_code", "Sai mã. Nhập lại mã đang hiện trên TV.")
        } catch (e: Exception) {
            p.close(); pairing = null
            state = "error"; lastError = "pair_failed" to "Ghép đôi thất bại hoặc hết thời gian. Hãy thử lại."
            throw ApiError(502, "pair_failed", "Ghép đôi thất bại hoặc hết thời gian. Hãy thử lại.")
        }
        p.close(); pairing = null
        val h = pairingHost
        prefs.edit().putBoolean("paired_$h", true).apply()
        var okRemote = false
        for (i in 0 until 2) { Thread.sleep(700); if (tryRemote(h)) { okRemote = true; break } }
        if (!okRemote) {
            state = "error"; lastError = "atv_error" to "Ghép đôi xong nhưng không kết nối được. Hãy thử lại."
            throw ApiError(502, "atv_error", "Ghép đôi xong nhưng không kết nối được. Hãy thử lại.")
        }
    }

    private fun onRemoteClosed(s: RemoteSession) {
        if (remote === s && state == "connected") {
            remote = null; state = "disconnected"; lastError = "lost" to "Mất kết nối với TV."
        }
    }

    private fun closeAll() {
        remote?.let { remote = null; it.close() }
        pairing?.let { pairing = null; it.close() }
    }

    private fun lost(): Nothing {
        closeAll(); state = "disconnected"; lastError = "lost" to "Mất kết nối với TV."
        throw ApiError(502, "send_failed", "Mất kết nối với TV. Hãy kết nối lại.")
    }

    // ---------- lệnh ----------
    private fun command(cmd: String) {
        val r = remote
        if (state != "connected" || r == null) throw ApiError(409, "not_connected", "Chưa kết nối với TV.")
        val key = KEYS[cmd]
        val link = LINKS[cmd]
        if (key == null && link == null) throw ApiError(400, "unsupported_command", "Lệnh không được hỗ trợ.")
        try {
            if (key != null) r.key(key) else r.appLink(link!!)
        } catch (e: Exception) { lost() }
    }

    private fun search(query: String) {
        val r = remote
        if (state != "connected" || r == null) throw ApiError(409, "not_connected", "Chưa kết nối với TV.")
        val t = query.replace(Regex("[\\u0000-\\u001f]"), " ").trim().take(100)
        if (t.isEmpty()) throw ApiError(400, "empty_query", "Không nhận được nội dung giọng nói.")
        val enc = java.net.URLEncoder.encode(t, "UTF-8").replace("+", "%20")
        try { r.appLink("https://www.youtube.com/results?search_query=$enc") } catch (e: Exception) { lost() }
    }

    // ---------- tìm thiết bị (mDNS) ----------
    private fun devicesJson(): JSONObject {
        val arr = JSONArray()
        synchronized(found) {
            for ((ip, name) in found) arr.put(JSONObject().put("name", name).put("host", ip).put("method", "atv"))
            val saved = prefs.getString("host", "") ?: ""
            if (saved.isNotEmpty() && !found.containsKey(saved))
                arr.put(JSONObject().put("name", prefs.getString("name", "").orEmpty().ifEmpty { "Thiết bị đã lưu" }).put("host", saved).put("method", "atv"))
        }
        return ok().put("devices", arr).put("discovering", discovering)
    }

    @Synchronized
    private fun startDiscovery() {
        if (discovering) return
        discovering = true
        synchronized(found) { found.clear() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) { synchronized(queue) { queue.add(serviceInfo) }; resolveNext() }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { discovering = false }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        try {
            nsd.discoverServices("_androidtvremote2._tcp", NsdManager.PROTOCOL_DNS_SD, l)
            discoveryListener = l
        } catch (e: Exception) { discovering = false; return }
        ui.postDelayed({ stopDiscovery() }, 7000)
    }

    private fun stopDiscovery() {
        try { discoveryListener?.let { nsd.stopServiceDiscovery(it) } } catch (_: Exception) {}
        discoveryListener = null; discovering = false
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        synchronized(queue) {
            if (resolving) return
            val si = queue.poll() ?: return
            resolving = true
            try {
                nsd.resolveService(si, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { resolveDone() }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val ip = (serviceInfo.host as? Inet4Address)?.hostAddress
                        if (ip != null && PRIVATE_IP.matches(ip)) synchronized(found) { found[ip] = serviceInfo.serviceName.take(60) }
                        resolveDone()
                    }
                })
            } catch (e: Exception) { resolveDone() }
        }
    }

    private fun resolveDone() { synchronized(queue) { resolving = false }; resolveNext() }
}

package com.mybypass.app

import android.content.Context
import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class WarpAccount(
    val privateKey: String,
    val publicKey: String,
    val ipv4: String,
    val peerPublicKey: String,
    val endpoint: String,
    val reserved: List<Int>
)

object WarpManager {

    private const val PREF_WARP_JSON = "cf_warp_account_json"
    private const val PREF_USE_WARP = "use_cf_warp"

    fun isWarpEnabled(context: Context): Boolean =
        context.getSharedPreferences(ServerScanner.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_USE_WARP, true)

    fun loadSavedAccount(context: Context): WarpAccount? {
        val raw = context.getSharedPreferences(ServerScanner.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_WARP_JSON, "").orEmpty()
        if (raw.isBlank()) return null
        return try {
            val obj = JSONObject(raw)
            val resArr = obj.getJSONArray("reserved")
            val reserved = List(resArr.length()) { idx -> resArr.getInt(idx) }
            WarpAccount(
                privateKey = obj.getString("privateKey"),
                publicKey = obj.getString("publicKey"),
                ipv4 = obj.getString("ipv4"),
                peerPublicKey = obj.getString("peerPublicKey"),
                endpoint = obj.getString("endpoint"),
                reserved = reserved
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun saveAccount(context: Context, acc: WarpAccount) {
        val resArr = JSONArray().apply {
            acc.reserved.forEach { put(it) }
        }
        val obj = JSONObject().apply {
            put("privateKey", acc.privateKey)
            put("publicKey", acc.publicKey)
            put("ipv4", acc.ipv4)
            put("peerPublicKey", acc.peerPublicKey)
            put("endpoint", acc.endpoint)
            put("reserved", resArr)
        }
        context.getSharedPreferences(ServerScanner.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_WARP_JSON, obj.toString())
            .apply()
    }

    fun getOrRegister(context: Context, socksPort: Int): WarpAccount? {
        loadSavedAccount(context)?.let { return it }

        val (privB64, pubB64) = generateX25519KeyPair()
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val payload = JSONObject().apply {
            put("key", pubB64)
            put("install_id", "")
            put("fcm_token", "")
            put("tos", sdf.format(Date()))
            put("model", "Android")
            put("serial_number", "")
            put("locale", "en_US")
        }.toString()

        val respBody = socks5HttpsPostJson(
            socksPort = socksPort,
            host = "api.cloudflareclient.com",
            path = "/v0a2158/reg",
            jsonBody = payload,
            timeoutMs = 5000
        ) ?: return null

        return try {
            val root = JSONObject(respBody)
            val cfg = root.getJSONObject("config")
            val iface = cfg.getJSONObject("interface").getJSONObject("addresses")
            val v4 = iface.getString("v4").substringBefore("/") + "/32"
            val peer = cfg.getJSONArray("peers").getJSONObject(0)
            val peerPub = peer.getString("public_key")
            val epV4 = peer.getJSONObject("endpoint").optString("v4", "162.159.192.1:2408")
            val clientIdB64 = cfg.optString("client_id", "")
            val reserved = decodeReserved(clientIdB64)

            val account = WarpAccount(
                privateKey = privB64,
                publicKey = pubB64,
                ipv4 = v4,
                peerPublicKey = peerPub,
                endpoint = epV4,
                reserved = reserved
            )
            saveAccount(context, account)
            account
        } catch (_: Exception) {
            null
        }
    }

    fun verifyWarpOverRelay(
        context: Context,
        relayNode: VlessNode,
        warp: WarpAccount,
        tempPort: Int
    ): Boolean {
        val xrayBin = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
        if (!xrayBin.exists()) return false

        val cfgFile = File(context.cacheDir, "xray_warp_test_$tempPort.json")
        cfgFile.writeText(VlessParser.buildWarpTestConfig(relayNode, warp, tempPort))

        var proc: Process? = null
        return try {
            proc = ProcessBuilder(xrayBin.absolutePath, "run", "-c", cfgFile.absolutePath)
                .redirectErrorStream(true)
                .start()

            var ready = false
            for (i in 0 until 10) {
                if (!proc.isAlive) return false
                if (ServerScanner.tcpPing("127.0.0.1", tempPort, 100) >= 0) {
                    ready = true
                    break
                }
                Thread.sleep(100)
            }
            if (!ready) return false

            val code = socks5HttpsGetStatus(tempPort, "www.gstatic.com", "/generate_204", 3500)
            code in 200..399
        } catch (_: Exception) {
            false
        } finally {
            try { proc?.destroy() } catch (_: Exception) {}
            try { proc?.destroyForcibly() } catch (_: Exception) {}
            try { cfgFile.delete() } catch (_: Exception) {}
        }
    }

    private fun decodeReserved(clientIdB64: String): List<Int> {
        if (clientIdB64.isBlank()) return listOf(0, 0, 0)
        return try {
            val bytes = Base64.decode(clientIdB64, Base64.DEFAULT)
            if (bytes.size >= 3) {
                listOf(bytes[0].toInt() and 0xFF, bytes[1].toInt() and 0xFF, bytes[2].toInt() and 0xFF)
            } else {
                listOf(0, 0, 0)
            }
        } catch (_: Exception) {
            listOf(0, 0, 0)
        }
    }

    private fun generateX25519KeyPair(): Pair<String, String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val kpg = KeyPairGenerator.getInstance("X25519")
                val kp = kpg.generateKeyPair()
                val privDer = kp.private.encoded
                val pubDer = kp.public.encoded
                if (privDer.size >= 32 && pubDer.size >= 32) {
                    val rawPriv = privDer.copyOfRange(privDer.size - 32, privDer.size)
                    val rawPub = pubDer.copyOfRange(pubDer.size - 32, pubDer.size)
                    return Pair(
                        Base64.encodeToString(rawPriv, Base64.NO_WRAP),
                        Base64.encodeToString(rawPub, Base64.NO_WRAP)
                    )
                }
            } catch (_: Exception) {
            }
        }
        val priv = ByteArray(32)
        SecureRandom().nextBytes(priv)
        priv[0] = (priv[0].toInt() and 248).toByte()
        priv[31] = ((priv[31].toInt() and 127) or 64).toByte()
        val pub = curve25519BaseMult(priv)
        return Pair(
            Base64.encodeToString(priv, Base64.NO_WRAP),
            Base64.encodeToString(pub, Base64.NO_WRAP)
        )
    }

    private fun curve25519BaseMult(scalar: ByteArray): ByteArray {
        val x = LongArray(16)
        val z = LongArray(16)
        val a = LongArray(16)
        val b = LongArray(16)
        val c = LongArray(16)
        val d = LongArray(16)
        val e = LongArray(16)
        val f = LongArray(16)
        val _9 = LongArray(16).apply { this[0] = 9L }
        val _121665 = LongArray(16).apply { this[0] = 0xDB41L; this[1] = 1L }

        for (i in 0 until 16) {
            x[i] = _9[i]
            a[i] = if (i == 0) 1L else 0L
            c[i] = if (i == 0) 1L else 0L
        }

        for (i in 254 downTo 0) {
            val r = ((scalar[i ushr 3].toInt() ushr (i and 7)) and 1)
            sel25519(a, x, r)
            sel25519(c, z, r)
            feAdd(e, a, c)
            feSub(a, a, c)
            feAdd(c, x, z)
            feSub(b, x, z)
            feSq(d, e)
            feSq(f, a)
            feMul(a, c, a)
            feMul(c, b, e)
            feAdd(e, a, c)
            feSub(a, a, c)
            feSq(b, a)
            feSub(c, d, f)
            feMul(a, c, _121665)
            feAdd(a, a, d)
            feMul(c, c, a)
            feMul(a, d, f)
            feMul(d, b, _9)
            feSq(z, e)
            sel25519(a, z, r)
            sel25519(c, d, r)
            for (k in 0 until 16) {
                x[k] = z[k]
                z[k] = d[k]
            }
        }

        val inv = LongArray(16)
        feInv(inv, c)
        feMul(a, a, inv)
        val out = ByteArray(32)
        pack25519(out, a)
        return out
    }

    private fun car25519(o: LongArray) {
        for (i in 0 until 16) {
            o[i] += 1L shl 16
            val c = o[i] shr 16
            if (i < 15) o[i + 1] += c - 1L else o[0] += 38L * (c - 1L)
            o[i] -= c shl 16
        }
    }

    private fun sel25519(p: LongArray, q: LongArray, b: Int) {
        val c = (b - 1).inv().toLong()
        for (i in 0 until 16) {
            val t = c and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    private fun feAdd(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until 16) o[i] = a[i] + b[i]
    }

    private fun feSub(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until 16) o[i] = a[i] - b[i]
    }

    private fun feMul(o: LongArray, a: LongArray, b: LongArray) {
        val t = LongArray(31)
        for (i in 0 until 16) {
            for (j in 0 until 16) {
                t[i + j] += a[i] * b[j]
            }
        }
        for (i in 0 until 15) t[i] += 38L * t[i + 16]
        for (i in 0 until 16) o[i] = t[i]
        car25519(o)
        car25519(o)
    }

    private fun feSq(o: LongArray, a: LongArray) = feMul(o, a, a)

    private fun feInv(o: LongArray, i: LongArray) {
        val c = LongArray(16)
        for (a in 0 until 16) c[a] = i[a]
        for (a in 253 downTo 0) {
            feSq(c, c)
            if (a != 2 && a != 4) feMul(c, c, i)
        }
        for (a in 0 until 16) o[a] = c[a]
    }

    private fun pack25519(o: ByteArray, n: LongArray) {
        val m = LongArray(16)
        val t = LongArray(16)
        for (i in 0 until 16) t[i] = n[i]
        car25519(t)
        car25519(t)
        car25519(t)
        for (j in 0 until 2) {
            m[0] = t[0] - 0xffedL
            for (i in 1 until 15) {
                m[i] = t[i] - 0xffffL - ((m[i - 1] shr 16) and 1L)
                m[i - 1] = m[i - 1] and 0xffffL
            }
            m[15] = t[15] - 0x7fffL - ((m[14] shr 16) and 1L)
            val b = ((m[15] shr 16) and 1L).toInt()
            m[14] = m[14] and 0xffffL
            sel25519(t, m, 1 - b)
        }
        for (i in 0 until 16) {
            o[2 * i] = (t[i] and 0xffL).toByte()
            o[2 * i + 1] = (t[i] shr 8).toByte()
        }
    }

    private fun openSocks5Ssl(socksPort: Int, host: String, timeoutMs: Int): Pair<Socket, SSLSocket>? {
        val domainBytes = host.toByteArray(Charsets.UTF_8)
        val raw = Socket()
        raw.connect(InetSocketAddress("127.0.0.1", socksPort), timeoutMs)
        raw.soTimeout = timeoutMs
        val out = raw.getOutputStream()
        val inp = raw.getInputStream()
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val greet = readExact(inp, 2) ?: run { raw.close(); return null }
        if (greet[0] != 0x05.toByte() || greet[1] != 0x00.toByte()) { raw.close(); return null }

        val req = ByteArray(7 + domainBytes.size)
        req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03
        req[4] = domainBytes.size.toByte()
        System.arraycopy(domainBytes, 0, req, 5, domainBytes.size)
        req[5 + domainBytes.size] = (443 shr 8).toByte()
        req[6 + domainBytes.size] = (443 and 0xFF).toByte()
        out.write(req)
        out.flush()

        val head = readExact(inp, 4) ?: run { raw.close(); return null }
        if (head[0] != 0x05.toByte() || head[1] != 0x00.toByte()) { raw.close(); return null }
        when (head[3].toInt() and 0xFF) {
            0x01 -> if (readExact(inp, 6) == null) { raw.close(); return null }
            0x03 -> {
                val len = readExact(inp, 1)?.get(0)?.toInt()?.and(0xFF) ?: run { raw.close(); return null }
                if (readExact(inp, len + 2) == null) { raw.close(); return null }
            }
            0x04 -> if (readExact(inp, 18) == null) { raw.close(); return null }
        }
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(raw, host, 443, true) as SSLSocket
        ssl.soTimeout = timeoutMs
        ssl.startHandshake()
        return Pair(raw, ssl)
    }

    private fun socks5HttpsPostJson(
        socksPort: Int,
        host: String,
        path: String,
        jsonBody: String,
        timeoutMs: Int
    ): String? {
        val pair = try { openSocks5Ssl(socksPort, host, timeoutMs) } catch (_: Exception) { null } ?: return null
        val (raw, ssl) = pair
        return try {
            val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)
            val req = "POST $path HTTP/1.1\r\n" +
                "Host: $host\r\n" +
                "User-Agent: okhttp/3.12.1\r\n" +
                "CF-Client-Version: a-6.3-2158\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            val out = ssl.getOutputStream()
            out.write(req.toByteArray(Charsets.UTF_8))
            out.write(bodyBytes)
            out.flush()

            val full = ssl.getInputStream().readBytes().toString(Charsets.UTF_8)
            val splitIdx = full.indexOf("\r\n\r\n")
            if (splitIdx < 0) null else full.substring(splitIdx + 4)
        } catch (_: Exception) {
            null
        } finally {
            try { ssl.close() } catch (_: Exception) {}
            try { raw.close() } catch (_: Exception) {}
        }
    }

    private fun socks5HttpsGetStatus(socksPort: Int, host: String, path: String, timeoutMs: Int): Int {
        val pair = try { openSocks5Ssl(socksPort, host, timeoutMs) } catch (_: Exception) { null } ?: return -1
        val (raw, ssl) = pair
        return try {
            val req = "GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
            ssl.getOutputStream().write(req.toByteArray(Charsets.UTF_8))
            ssl.getOutputStream().flush()
            val inp = ssl.getInputStream()
            val line = ByteArrayOutputStream()
            while (line.size() < 256) {
                val b = inp.read()
                if (b == -1 || b == '\n'.code) break
                line.write(b)
            }
            val parts = line.toString("ISO-8859-1").split(' ')
            if (parts.size >= 2) parts[1].toIntOrNull() ?: -1 else -1
        } catch (_: Exception) {
            -1
        } finally {
            try { ssl.close() } catch (_: Exception) {}
            try { raw.close() } catch (_: Exception) {}
        }
    }

    private fun readExact(input: InputStream, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var off = 0
        while (off < count) {
            val n = input.read(buf, off, count - off)
            if (n <= 0) return null
            off += n
        }
        return buf
    }
}

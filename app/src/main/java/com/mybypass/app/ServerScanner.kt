package com.mybypass.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class TestedNode(
    val node: VlessNode,
    val pingMs: Long,
    val countryCode: String = "",
    val geminiOk: Boolean = false,
    val flowOk: Boolean = false,
    val gptOk: Boolean = false,
    val ytOk: Boolean = false,
    val metaOk: Boolean = false,
    val tgOk: Boolean = false,
    val waOk: Boolean = false
) {
    val allWork: Boolean
        get() = geminiOk && flowOk && gptOk && ytOk && metaOk && tgOk && waOk

    /** Приоритет: 0 = работает вообще всё (ИИ + YT + Meta + TG + WA), затем по убыванию охвата */
    fun priorityTier(): Int = when {
        allWork -> 0
        geminiOk && gptOk && ytOk && metaOk && tgOk && waOk -> 1
        (geminiOk || gptOk) && ytOk && metaOk && tgOk -> 2
        geminiOk || flowOk || gptOk -> 3
        ytOk && metaOk && tgOk && waOk -> 4
        ytOk || metaOk || tgOk || waOk -> 5
        else -> 6
    }

    /** Подпись в скобках под какие сервисы и ИИ работает сервер */
    fun capabilitiesTag(): String {
        if (allWork) return "(ВСЕ: ИИ, YT, Meta, TG, WA)"
        val tags = mutableListOf<String>()
        if (geminiOk && flowOk && gptOk) {
            tags.add("ИИ")
        } else {
            if (geminiOk) tags.add("Gemini")
            if (flowOk) tags.add("Flow")
            if (gptOk) tags.add("GPT")
        }
        if (ytOk) tags.add("YT")
        if (metaOk) tags.add("Meta")
        if (tgOk) tags.add("TG")
        if (waOk) tags.add("WA")
        return if (tags.isEmpty()) "(Только базовый)" else "(${tags.joinToString(", ")})"
    }

    fun shortTitle(): String {
        val cc = if (countryCode.isNotBlank()) "[$countryCode] " else ""
        return "$cc${node.name.take(24)} ${capabilitiesTag()}"
    }

    fun listItemLabel(index: Int, isSelected: Boolean): String {
        val mark = if (isSelected) "▶ " else ""
        val cc = if (countryCode.isNotBlank()) "[$countryCode] " else ""
        return "$mark#${index + 1} • ${pingMs}мс • $cc${node.name.take(22)}\n" +
            "    ${capabilitiesTag()} • ${node.security}/${node.network}"
    }
}

object ServerScanner {

    const val PREFS_NAME = "xray_prefs"

    val ALL_FREE_URLS = listOf(
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS.txt",
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS_mobile.txt",
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
        "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/githubmirror/clean/vless.txt",
        "https://raw.githubusercontent.com/patterniha/Free-Configs/main/configs.txt"
    )

    private val BAD_GEO_CODES = setOf("RU", "BY", "CN", "HK", "IR", "KP", "SY", "CU")

    private val scanningFlag = AtomicBoolean(false)
    private val cancelFlag = AtomicBoolean(false)

    val isScanning: Boolean
        get() = scanningFlag.get()

    var onProgressListener: ((String) -> Unit)? = null
    var onListUpdatedListener: ((List<TestedNode>) -> Unit)? = null

    fun cancelScan() {
        cancelFlag.set(true)
    }

    fun getDefaultSubText(): String = ALL_FREE_URLS.joinToString("\n")

    fun getSavedSubText(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("sub_url", getDefaultSubText())
            .orEmpty()
            .ifBlank { getDefaultSubText() }
    }

    fun isAiFilterEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("ai_country_filter", true)

    fun isSplitRuEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("split_ru", true)

    fun isBlockQuicEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("block_quic", true)

    fun isBlockIpv6Enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("block_ipv6", false)

    fun saveWorkingList(context: Context, list: List<TestedNode>) {
        val arr = JSONArray()
        for (item in list) {
            arr.put(JSONObject().apply {
                put("uri", item.node.rawUri)
                put("ping", item.pingMs)
                put("cc", item.countryCode)
                put("gemini", item.geminiOk)
                put("flow", item.flowOk)
                put("gpt", item.gptOk)
                put("yt", item.ytOk)
                put("meta", item.metaOk)
                put("tg", item.tgOk)
                put("wa", item.waOk)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("working_nodes_json", arr.toString())
            .apply()
        onListUpdatedListener?.invoke(list)
    }

    fun loadWorkingList(context: Context): MutableList<TestedNode> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("working_nodes_json", "").orEmpty()
        if (raw.isBlank()) return mutableListOf()
        val result = mutableListOf<TestedNode>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val node = VlessParser.parseVlessUri(obj.optString("uri")) ?: continue
                result.add(
                    TestedNode(
                        node = node,
                        pingMs = obj.optLong("ping", 999L),
                        countryCode = obj.optString("cc", ""),
                        geminiOk = obj.optBoolean("gemini", false),
                        flowOk = obj.optBoolean("flow", false),
                        gptOk = obj.optBoolean("gpt", false),
                        ytOk = obj.optBoolean("yt", false),
                        metaOk = obj.optBoolean("meta", false),
                        tgOk = obj.optBoolean("tg", false),
                        waOk = obj.optBoolean("wa", false)
                    )
                )
            }
        } catch (_: Exception) {
        }
        return result
    }

    fun saveSelectedNode(context: Context, tested: TestedNode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("selected_vless", tested.node.rawUri)
            .putString("selected_tag", tested.capabilitiesTag())
            .putString("selected_cc", tested.countryCode)
            .putLong("selected_ping", tested.pingMs)
            .apply()
    }

    fun loadSelectedNode(context: Context): TestedNode? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uri = prefs.getString("selected_vless", "").orEmpty()
        val list = loadWorkingList(context)
        if (uri.isNotBlank()) {
            list.firstOrNull { it.node.rawUri == uri }?.let { return it }
            val parsed = VlessParser.parseVlessUri(uri)
            if (parsed != null) {
                return TestedNode(
                    node = parsed,
                    pingMs = prefs.getLong("selected_ping", -1L),
                    countryCode = prefs.getString("selected_cc", "").orEmpty()
                )
            }
        }
        return list.firstOrNull()
    }

    fun scanAll(
        context: Context,
        rawInput: String = getSavedSubText(context),
        filterAiCountries: Boolean = isAiFilterEnabled(context)
    ): List<TestedNode> {
        if (!scanningFlag.compareAndSet(false, true)) {
            return loadWorkingList(context)
        }
        cancelFlag.set(false)

        try {
            val lines = rawInput.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val allParsed = LinkedHashMap<String, VlessNode>()

            val urls = lines.filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
            val directUris = lines.filter { it.startsWith("vless://", true) }

            directUris.forEach { uri ->
                VlessParser.parseVlessUri(uri)?.let { n ->
                    allParsed["${n.host}:${n.port}:${n.uuid}"] = n
                }
            }

            if (urls.isNotEmpty() && !cancelFlag.get()) {
                notifyProgress("Загрузка баз (${urls.size})...")
                BypassVpnService.log("Загрузка ${urls.size} баз(ы) с GitHub...")
                val dlPool = Executors.newFixedThreadPool(minOf(urls.size, 6))
                try {
                    val futures = urls.map { u ->
                        dlPool.submit(Callable {
                            if (cancelFlag.get()) return@Callable emptyList<VlessNode>()
                            try {
                                val txt = downloadText(u)
                                val parsed = VlessParser.parseSubscription(txt)
                                BypassVpnService.log("  + ${parsed.size} ключей из ${u.substringAfterLast('/')}")
                                parsed
                            } catch (e: Exception) {
                                BypassVpnService.log("  ! Ошибка ${u.substringAfterLast('/')}: ${e.message}")
                                emptyList<VlessNode>()
                            }
                        })
                    }
                    futures.forEach { f ->
                        f.get().forEach { n ->
                            allParsed["${n.host}:${n.port}:${n.uuid}"] = n
                        }
                    }
                } finally {
                    dlPool.shutdownNow()
                }
            }

            if (cancelFlag.get()) return emptyList()

            var uniqueNodes = allParsed.values.toList()
            BypassVpnService.log("Всего уникальных VLESS: ${uniqueNodes.size}")

            if (filterAiCountries) {
                val before = uniqueNodes.size
                uniqueNodes = uniqueNodes.filterNot { VlessParser.isGeoBlockedForAi(it) }
                BypassVpnService.log("После фильтра стран для ИИ: ${uniqueNodes.size} (отсеяно ${before - uniqueNodes.size})")
            }

            if (uniqueNodes.isEmpty()) {
                BypassVpnService.log("ОШИБКА: подходящих VLESS-серверов не найдено.")
                return emptyList()
            }

            val realityList = uniqueNodes.filter { it.security == "reality" }.shuffled()
            val otherList = uniqueNodes.filter { it.security != "reality" }.shuffled()
            val candidates = (realityList.take(260) + otherList.take(40)).take(300)

            notifyProgress("TCP-пинг ${candidates.size} узлов...")
            BypassVpnService.log("Этап 1/2: TCP-пинг ${candidates.size} серверов (Reality в приоритете)...")
            val tcpPool = Executors.newFixedThreadPool(32)
            val tcpAlive = try {
                val futures = candidates.map { node ->
                    tcpPool.submit(Callable {
                        if (cancelFlag.get()) return@Callable null
                        val ping = tcpPing(node.host, node.port, 1400)
                        if (ping >= 0) node to ping else null
                    })
                }
                futures.mapNotNull {
                    try { it.get() } catch (_: Exception) { null }
                }.sortedBy { (node, ping) ->
                    if (node.security == "reality") ping else ping + 200
                }
            } finally {
                tcpPool.shutdownNow()
            }

            if (cancelFlag.get()) return emptyList()
            BypassVpnService.log("Ответили по TCP: ${tcpAlive.size}. Тестирую ИИ, YouTube, Meta, TG и WA...")
            if (tcpAlive.isEmpty()) return emptyList()

            val maxVerify = minOf(tcpAlive.size, 55)
            val toVerify = tcpAlive.take(maxVerify)
            val verified = Collections.synchronizedList(mutableListOf<TestedNode>())
            val fullCount = AtomicInteger(0)
            val doneCount = AtomicInteger(0)
            val portCounter = AtomicInteger(10830)

            val xrayPool = Executors.newFixedThreadPool(6)
            try {
                val tasks = toVerify.map { (node, _) ->
                    xrayPool.submit {
                        if (cancelFlag.get()) return@submit
                        if (verified.size >= 25 || (fullCount.get() >= 8 && verified.size >= 12)) {
                            return@submit
                        }
                        val tempPort = portCounter.getAndIncrement()
                        val tested = verifySingleNode(context, node, tempPort)
                        val done = doneCount.incrementAndGet()
                        if (tested != null) {
                            verified.add(tested)
                            if (tested.allWork) fullCount.incrementAndGet()
                            val cc = if (tested.countryCode.isNotBlank()) "[${tested.countryCode}] " else ""
                            BypassVpnService.log(
                                "✓ [${verified.size}] ${tested.pingMs}мс | $cc${node.name.take(20)} ${tested.capabilitiesTag()}"
                            )
                            notifyProgress("Найдено: ${verified.size} (ВСЕ: ${fullCount.get()})")
                        } else if (done % 10 == 0) {
                            notifyProgress("Тест $done/$maxVerify (найдено ${verified.size})")
                        }
                    }
                }
                tasks.forEach {
                    try { it.get() } catch (_: Exception) {}
                }
            } finally {
                xrayPool.shutdownNow()
            }

            if (verified.isEmpty()) {
                BypassVpnService.log("Рабочих серверов не найдено. Попробуйте ещё раз.")
                return emptyList()
            }

            val sorted = verified.sortedWith(
                compareBy<TestedNode>({ it.priorityTier() }, { it.pingMs })
            )
            saveWorkingList(context, sorted)
            saveSelectedNode(context, sorted[0])

            val best = sorted[0]
            BypassVpnService.log(
                "ГОТОВО! Найдено ${sorted.size} (ВСЕ работают: ${sorted.count { it.allWork }}). " +
                    "Лучший: ${best.shortTitle()} • ${best.pingMs}мс"
            )
            return sorted
        } finally {
            scanningFlag.set(false)
            notifyProgress("")
        }
    }

    fun verifySingleNode(context: Context, node: VlessNode, tempPort: Int): TestedNode? {
        val xrayBin = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
        if (!xrayBin.exists()) return null

        val testConfigFile = File(context.cacheDir, "xray_test_$tempPort.json")
        testConfigFile.writeText(
            VlessParser.buildXrayConfig(
                node = node,
                socksPort = tempPort,
                splitRu = false,
                blockQuic = true,
                isTestMode = true
            )
        )

        var proc: Process? = null
        return try {
            proc = ProcessBuilder(xrayBin.absolutePath, "run", "-c", testConfigFile.absolutePath)
                .redirectErrorStream(true)
                .start()

            var portReady = false
            for (i in 0 until 9) {
                if (cancelFlag.get() || !proc.isAlive) return null
                if (tcpPing("127.0.0.1", tempPort, 100) >= 0) {
                    portReady = true
                    break
                }
                Thread.sleep(100)
            }
            if (!portReady) return null

            // 1. Базовый тест + замер пинга (через SOCKS5h — DNS на стороне VLESS)
            val pingStart = System.currentTimeMillis()
            val baseResp = socks5hHttpsGet(tempPort, "www.gstatic.com", "/generate_204", 3500, readBody = false)
                ?: return null
            if (baseResp.code !in 200..399) return null
            val pingMs = (System.currentTimeMillis() - pingStart).coerceAtLeast(1L)

            if (cancelFlag.get()) return null

            // 2. Реальная страна выхода
            var countryCode = ""
            val traceResp = socks5hHttpsGet(tempPort, "chatgpt.com", "/cdn-cgi/trace", 2400, readBody = true)
            if (traceResp != null && traceResp.code == 200) {
                val match = Regex("loc=([A-Za-z]{2})").find(traceResp.body)
                if (match != null) countryCode = match.groupValues[1].uppercase()
            }
            val badGeo = countryCode.isNotBlank() && countryCode in BAD_GEO_CODES

            // 3. ChatGPT / OpenAI
            val gptOk = if (badGeo) {
                false
            } else {
                val gptResp = socks5hHttpsGet(tempPort, "api.openai.com", "/v1/models", 2400, readBody = true)
                if (gptResp == null) {
                    false
                } else {
                    val lower = gptResp.body.lowercase()
                    (gptResp.code == 401 || gptResp.code == 200) &&
                        !lower.contains("unsupported_country") &&
                        !lower.contains("cf-mitigated")
                }
            }

            // 4. Gemini / Nano Banana
            val geminiOk = if (badGeo) {
                false
            } else {
                val gemResp = socks5hHttpsGet(tempPort, "gemini.google.com", "/app", 2600, readBody = true)
                if (gemResp == null) {
                    false
                } else {
                    val bodyL = gemResp.body.lowercase()
                    val locL = gemResp.location.lowercase()
                    when {
                        gemResp.code == 200 ->
                            !bodyL.contains("isn't available in your country") &&
                                !bodyL.contains("not available in your country") &&
                                !bodyL.contains("unusual traffic")
                        gemResp.code in 300..399 ->
                            !locL.contains("/faq") &&
                                !locL.contains("/sorry") &&
                                !locL.contains("unsupported") &&
                                !locL.contains("support.google.com")
                        else -> false
                    }
                }
            }

            // 5. Google Flow (labs.google)
            val flowOk = if (badGeo) {
                false
            } else {
                val flowResp = socks5hHttpsGet(tempPort, "labs.google", "/fx/tools/flow", 2600, readBody = true)
                if (flowResp == null) {
                    false
                } else {
                    val bodyL = flowResp.body.lowercase()
                    val locL = flowResp.location.lowercase()
                    when {
                        flowResp.code == 200 ->
                            !bodyL.contains("isn't available in your country") &&
                                !bodyL.contains("not available in your country") &&
                                !bodyL.contains("country_not_supported")
                        flowResp.code in 300..399 ->
                            !locL.contains("/sorry") &&
                                !locL.contains("unsupported") &&
                                !locL.contains("support.google.com")
                        else -> false
                    }
                }
            }

            // 6. YouTube
            val ytResp = socks5hHttpsGet(tempPort, "www.youtube.com", "/generate_204", 2300, readBody = false)
            val ytOk = ytResp != null && (
                ytResp.code == 204 ||
                    (ytResp.code in 200..399 && !ytResp.location.lowercase().contains("/sorry"))
                )

            // 7. Meta (Instagram / Threads / Facebook)
            val metaResp = socks5hHttpsGet(tempPort, "www.instagram.com", "/", 2500, readBody = false)
            val metaOk = metaResp != null && metaResp.code in 200..399

            // 8. Telegram
            val tgResp = socks5hHttpsGet(tempPort, "api.telegram.org", "/", 2300, readBody = false)
            val tgOk = tgResp != null && tgResp.code in 200..404

            // 9. WhatsApp
            val waResp = socks5hHttpsGet(tempPort, "web.whatsapp.com", "/", 2300, readBody = false)
            val waOk = waResp != null && waResp.code in 200..399

            TestedNode(
                node = node,
                pingMs = pingMs,
                countryCode = countryCode,
                geminiOk = geminiOk,
                flowOk = flowOk,
                gptOk = gptOk,
                ytOk = ytOk,
                metaOk = metaOk,
                tgOk = tgOk,
                waOk = waOk
            )
        } catch (_: Exception) {
            null
        } finally {
            try { proc?.destroy() } catch (_: Exception) {}
            try { proc?.destroyForcibly() } catch (_: Exception) {}
            try { testConfigFile.delete() } catch (_: Exception) {}
        }
    }

    private data class SimpleHttpResponse(
        val code: Int,
        val location: String,
        val body: String
    )

    private fun socks5hHttpsGet(
        socksPort: Int,
        host: String,
        path: String,
        timeoutMs: Int,
        readBody: Boolean
    ): SimpleHttpResponse? {
        val domainBytes = host.toByteArray(Charsets.UTF_8)
        if (domainBytes.size > 255) return null

        var rawSocket: Socket? = null
        var sslSocket: SSLSocket? = null
        return try {
            rawSocket = Socket()
            rawSocket.connect(InetSocketAddress("127.0.0.1", socksPort), timeoutMs)
            rawSocket.soTimeout = timeoutMs

            val rawOut = rawSocket.getOutputStream()
            val rawIn = rawSocket.getInputStream()

            rawOut.write(byteArrayOf(0x05, 0x01, 0x00))
            rawOut.flush()
            val greet = readExact(rawIn, 2) ?: return null
            if (greet[0] != 0x05.toByte() || greet[1] != 0x00.toByte()) return null

            val req = ByteArray(7 + domainBytes.size)
            req[0] = 0x05
            req[1] = 0x01
            req[2] = 0x00
            req[3] = 0x03
            req[4] = domainBytes.size.toByte()
            System.arraycopy(domainBytes, 0, req, 5, domainBytes.size)
            req[5 + domainBytes.size] = (443 shr 8).toByte()
            req[6 + domainBytes.size] = (443 and 0xFF).toByte()
            rawOut.write(req)
            rawOut.flush()

            val head = readExact(rawIn, 4) ?: return null
            if (head[0] != 0x05.toByte() || head[1] != 0x00.toByte()) return null
            when (head[3].toInt() and 0xFF) {
                0x01 -> if (readExact(rawIn, 6) == null) return null
                0x03 -> {
                    val lenBuf = readExact(rawIn, 1) ?: return null
                    val dLen = lenBuf[0].toInt() and 0xFF
                    if (readExact(rawIn, dLen + 2) == null) return null
                }
                0x04 -> if (readExact(rawIn, 18) == null) return null
                else -> return null
            }

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            sslSocket = factory.createSocket(rawSocket, host, 443, true) as SSLSocket
            sslSocket.soTimeout = timeoutMs
            sslSocket.startHandshake()

            val httpReq = "GET $path HTTP/1.1\r\n" +
                "Host: $host\r\n" +
                "User-Agent: Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36\r\n" +
                "Accept-Language: en-US,en;q=0.9\r\n" +
                "Connection: close\r\n\r\n"
            val sslOut = sslSocket.getOutputStream()
            sslOut.write(httpReq.toByteArray(Charsets.UTF_8))
            sslOut.flush()

            val sslIn = sslSocket.getInputStream()
            val headerBytes = ByteArrayOutputStream()
            var prev3 = 0
            var prev2 = 0
            var prev1 = 0
            while (headerBytes.size() < 16384) {
                val b = sslIn.read()
                if (b == -1) break
                headerBytes.write(b)
                if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                    break
                }
                prev3 = prev2
                prev2 = prev1
                prev1 = b
            }

            val headerText = headerBytes.toString("ISO-8859-1")
            val headerLines = headerText.split("\r\n")
            val statusLine = headerLines.firstOrNull().orEmpty()
            val statusParts = statusLine.split(' ')
            if (statusParts.size < 2) return null
            val statusCode = statusParts[1].toIntOrNull() ?: return null

            var location = ""
            var contentLength = -1
            for (i in 1 until headerLines.size) {
                val line = headerLines[i]
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    if (key == "location") location = value
                    if (key == "content-length") contentLength = value.toIntOrNull() ?: -1
                }
            }

            var body = ""
            if (readBody && statusCode != 204 && statusCode !in 300..399) {
                val maxRead = if (contentLength in 0..6144) contentLength else 6144
                val bodyBuf = ByteArrayOutputStream()
                val chunk = ByteArray(1024)
                try {
                    while (bodyBuf.size() < maxRead) {
                        val toRead = minOf(chunk.size, maxRead - bodyBuf.size())
                        val n = sslIn.read(chunk, 0, toRead)
                        if (n <= 0) break
                        bodyBuf.write(chunk, 0, n)
                    }
                } catch (_: SocketTimeoutException) {
                }
                body = bodyBuf.toString("UTF-8")
            }

            SimpleHttpResponse(statusCode, location, body)
        } catch (_: Exception) {
            null
        } finally {
            try { sslSocket?.close() } catch (_: Exception) {}
            try { rawSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun readExact(input: InputStream, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(buf, offset, count - offset)
            if (n <= 0) return null
            offset += n
        }
        return buf
    }

    fun tcpPing(host: String, port: Int, timeoutMs: Int): Long {
        val start = System.currentTimeMillis()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            System.currentTimeMillis() - start
        } catch (_: Exception) {
            -1L
        }
    }

    private fun downloadText(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 12000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "MyBypassXray/3.1")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun notifyProgress(msg: String) {
        onProgressListener?.invoke(msg)
    }
}

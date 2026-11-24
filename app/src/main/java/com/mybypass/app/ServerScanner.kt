package com.mybypass.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
    val isTcpReality: Boolean
        get() = node.security == "reality" && (node.network == "tcp" || node.network == "raw")

    val allWork: Boolean
        get() = geminiOk && flowOk && gptOk && ytOk && metaOk && tgOk && waOk

    fun priorityTier(): Int {
        val fast = pingMs in 1..2200
        val medium = pingMs in 1..5500
        return when {
            fast && isTcpReality && allWork -> 0
            medium && isTcpReality && geminiOk && gptOk && ytOk && metaOk -> 1
            medium && geminiOk && gptOk && ytOk && metaOk -> 2
            medium && geminiOk -> 3
            medium && isTcpReality && (gptOk || flowOk || ytOk || metaOk) -> 4
            medium && (gptOk || flowOk || ytOk || metaOk || tgOk || waOk) -> 5
            else -> 6
        }
    }

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
        return if (tags.isEmpty()) "(Базовый)" else "(${tags.joinToString(", ")})"
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

    fun getDefaultSubText(): String = VlessParser.ALL_FREE_URLS.joinToString("\n")

    fun getSavedSubText(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString("sub_url", "").orEmpty()
        if (saved.isBlank()) return getDefaultSubText()
        if (saved.contains("mahdibland", true) ||
            saved.contains("kort0881", true) ||
            saved.contains("barry-far", true) ||
            saved.contains("Epodonios", true) ||
            saved.contains("WHITE-SNI-RU-all.txt", true)
        ) {
            val fresh = getDefaultSubText()
            prefs.edit().putString("sub_url", fresh).apply()
            return fresh
        }
        val deduped = saved.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return if (deduped.isNotEmpty()) deduped.joinToString("\n") else getDefaultSubText()
    }

    fun isAiFilterEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("ai_country_filter", true)

    fun isSmartBypassEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("smart_bypass", true)

    fun isSplitRuEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("split_ru", true)

    fun isBlockQuicEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("block_quic", true)

    fun isBlockIpv6Enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("block_ipv6", false)

    fun isLocalDpiEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("local_dpi", true)

    fun getDpiProfileIndex(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("dpi_profile", 0)

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
                val gemini = obj.optBoolean("gemini", false)
                val flow = obj.optBoolean("flow", false)
                val gpt = obj.optBoolean("gpt", false)
                val yt = obj.optBoolean("yt", false)
                val meta = obj.optBoolean("meta", false)
                val tg = obj.optBoolean("tg", false)
                val wa = obj.optBoolean("wa", false)
                if (!gemini && !flow && !gpt && !yt && !meta && !tg && !wa) continue
                result.add(
                    TestedNode(
                        node = node,
                        pingMs = obj.optLong("ping", 999L),
                        countryCode = obj.optString("cc", ""),
                        geminiOk = gemini,
                        flowOk = flow,
                        gptOk = gpt,
                        ytOk = yt,
                        metaOk = meta,
                        tgOk = tg,
                        waOk = wa
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
        }
        return list.firstOrNull()
    }

    fun quickLiveCheckNode(context: Context, node: VlessNode, tempPort: Int): Long {
        val xrayBin = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
        if (!xrayBin.exists()) return -1L

        val testConfigFile = File(context.cacheDir, "xray_live_$tempPort.json")
        testConfigFile.writeText(
            VlessParser.buildXrayConfig(node = node, socksPort = tempPort, useLocalDpi = false, isTestMode = true)
        )

        var proc: Process? = null
        return try {
            proc = ProcessBuilder(xrayBin.absolutePath, "run", "-c", testConfigFile.absolutePath)
                .redirectErrorStream(true)
                .start()

            var portReady = false
            for (i in 0 until 10) {
                if (!proc.isAlive) return -1L
                if (tcpPing("127.0.0.1", tempPort, 100) >= 0) {
                    portReady = true
                    break
                }
                Thread.sleep(100)
            }
            if (!portReady) return -1L

            val start = System.currentTimeMillis()
            val (resp, _) = socks5hHttpsGet(tempPort, "www.gstatic.com", "/generate_204", 3500, readBody = false)
            if (resp != null && resp.code in 200..399) {
                (System.currentTimeMillis() - start).coerceAtLeast(1L)
            } else {
                -1L
            }
        } catch (_: Exception) {
            -1L
        } finally {
            try { proc?.destroy() } catch (_: Exception) {}
            try { proc?.destroyForcibly() } catch (_: Exception) {}
            try { testConfigFile.delete() } catch (_: Exception) {}
        }
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
            val lines = rawInput.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val allParsed = LinkedHashMap<String, VlessNode>()

            val urls = lines.filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
            val directUris = lines.filter { it.startsWith("vless://", true) }

            directUris.forEach { uri ->
                VlessParser.parseVlessUri(uri, isCuratedSource = true)?.let { n ->
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
                                val curated = VlessParser.isCuratedUrl(u)
                                val parsed = VlessParser.parseSubscription(txt, curated)
                                val tag = if (curated) " [РФ-база]" else ""
                                BypassVpnService.log("  + ${parsed.size} ключей из ${u.substringAfterLast('/')}$tag")
                                parsed
                            } catch (e: Exception) {
                                BypassVpnService.log("  ! Ошибка ${u.substringAfterLast('/')}: ${e.message}")
                                emptyList<VlessNode>()
                            }
                        })
                    }
                    futures.forEach { f ->
                        f.get().forEach { n ->
                            val key = "${n.host}:${n.port}:${n.uuid}"
                            val prev = allParsed[key]
                            if (prev == null || (n.isCurated && !prev.isCurated)) {
                                allParsed[key] = n
                            }
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

            val curatedNodes = uniqueNodes.filter { it.isCurated }.shuffled()
            val otherNodes = uniqueNodes.filter { !it.isCurated }.shuffled().take(120)
            val candidates = (curatedNodes + otherNodes).take(260)

            notifyProgress("TCP-пинг ${candidates.size} узлов...")
            BypassVpnService.log("Этап 1/2: TCP-пинг ${candidates.size} узлов (${curatedNodes.size} из РФ-баз в приоритете)...")
            val tcpPool = Executors.newFixedThreadPool(16)
            val tcpAlive = try {
                val futures = candidates.map { node ->
                    tcpPool.submit(Callable {
                        if (cancelFlag.get()) return@Callable null
                        val ping = tcpPing(node.host, node.port, 2200)
                        if (ping in 0..2200) node to ping else null
                    })
                }
                futures.mapNotNull {
                    try { it.get() } catch (_: Exception) { null }
                }.sortedBy { (node, ping) ->
                    var score = ping
                    if (!node.isCurated) score += 1000L
                    val isTcpR = node.security == "reality" && (node.network == "tcp" || node.network == "raw")
                    if (!isTcpR) score += 250L
                    score
                }
            } finally {
                tcpPool.shutdownNow()
            }

            if (cancelFlag.get()) return emptyList()
            BypassVpnService.log("Ответили по TCP: ${tcpAlive.size}. Тестирую Gemini (с API), Flow, GPT, YT, Meta...")
            if (tcpAlive.isEmpty()) return emptyList()

            val maxVerify = minOf(tcpAlive.size, 90)
            val toVerify = tcpAlive.take(maxVerify)
            val verified = Collections.synchronizedList(mutableListOf<TestedNode>())
            val gemCount = AtomicInteger(0)
            val doneCount = AtomicInteger(0)
            val portCounter = AtomicInteger(10830)
            val firstCuratedErr = AtomicReference<String?>(null)
            val firstDiagErr = AtomicReference<String?>(null)

            val xrayPool = Executors.newFixedThreadPool(4)
            try {
                val tasks = toVerify.map { (node, _) ->
                    xrayPool.submit {
                        if (cancelFlag.get()) return@submit
                        if (verified.size >= 20 || (gemCount.get() >= 4 && verified.size >= 8)) {
                            return@submit
                        }
                        val tempPort = portCounter.getAndIncrement()
                        val (tested, diagErr) = verifySingleNode(context, node, tempPort)
                        val done = doneCount.incrementAndGet()
                        if (tested != null) {
                            verified.add(tested)
                            if (tested.geminiOk) gemCount.incrementAndGet()
                            val cc = if (tested.countryCode.isNotBlank()) "[${tested.countryCode}] " else ""
                            BypassVpnService.log(
                                "✓ [${verified.size}] ${tested.pingMs}мс | $cc${node.name.take(20)} ${tested.capabilitiesTag()}"
                            )
                            notifyProgress("Найдено: ${verified.size} (Gemini: ${gemCount.get()})")
                        } else {
                            if (!diagErr.isNullOrBlank()) {
                                if (node.isCurated) {
                                    firstCuratedErr.compareAndSet(null, diagErr)
                                }
                                firstDiagErr.compareAndSet(null, diagErr)
                            }
                            if (done % 10 == 0) {
                                notifyProgress("Тест $done/$maxVerify (Gemini: ${gemCount.get()})")
                            }
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
                BypassVpnService.log("Рабочих серверов не найдено. Нажмите поиск ещё раз.")
                (firstCuratedErr.get() ?: firstDiagErr.get())?.let { err ->
                    BypassVpnService.log("Диагностика теста: $err")
                }
                return emptyList()
            }

            val sorted = verified.sortedWith(
                compareBy<TestedNode>({ it.priorityTier() }, { it.pingMs })
            )
            saveWorkingList(context, sorted)
            saveSelectedNode(context, sorted[0])

            val best = sorted[0]
            BypassVpnService.log(
                "ГОТОВО! Найдено ${sorted.size} (Gemini: ${sorted.count { it.geminiOk }}). " +
                    "Лучший: ${best.shortTitle()} • ${best.pingMs}мс"
            )
            return sorted
        } finally {
            scanningFlag.set(false)
            notifyProgress("")
        }
    }

    fun verifySingleNode(context: Context, node: VlessNode, tempPort: Int): Pair<TestedNode?, String?> {
        val xrayBin = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
        if (!xrayBin.exists()) return Pair(null, "не найден libxray.so")

        val testConfigFile = File(context.cacheDir, "xray_test_$tempPort.json")
        testConfigFile.writeText(
            VlessParser.buildXrayConfig(
                node = node,
                socksPort = tempPort,
                useLocalDpi = false,
                isTestMode = true
            )
        )

        var proc: Process? = null
        return try {
            proc = ProcessBuilder(xrayBin.absolutePath, "run", "-c", testConfigFile.absolutePath)
                .redirectErrorStream(true)
                .start()

            var portReady = false
            for (i in 0 until 12) {
                if (cancelFlag.get()) return Pair(null, null)
                if (!proc.isAlive) {
                    val out = try { proc.inputStream.bufferedReader().readText().take(200) } catch (_: Exception) { "" }
                    return Pair(null, "xray завершился: $out")
                }
                if (tcpPing("127.0.0.1", tempPort, 100) >= 0) {
                    portReady = true
                    break
                }
                Thread.sleep(100)
            }
            if (!portReady) return Pair(null, "xray не открыл порт $tempPort")

            val pingStart = System.currentTimeMillis()
            val (baseResp, getErr) = socks5hHttpsGet(tempPort, "www.gstatic.com", "/generate_204", 5500, readBody = false)
            if (baseResp == null || baseResp.code !in 200..399) {
                return Pair(null, "base test (${node.host}): ${getErr ?: "HTTP ${baseResp?.code}"}")
            }
            val pingMs = (System.currentTimeMillis() - pingStart).coerceAtLeast(1L)
            if (pingMs > 5500L || cancelFlag.get()) return Pair(null, "slow (${pingMs}ms)")

            var countryCode = ""
            val (traceResp, _) = socks5hHttpsGet(tempPort, "chatgpt.com", "/cdn-cgi/trace", 2800, readBody = true)
            if (traceResp != null && traceResp.code == 200) {
                val match = Regex("loc=([A-Za-z]{2})").find(traceResp.body)
                if (match != null) countryCode = match.groupValues[1].uppercase()
            }
            val badGeo = countryCode.isNotBlank() && countryCode in BAD_GEO_CODES

            val gptOk = if (badGeo) {
                false
            } else {
                val (gptResp, _) = socks5hHttpsGet(tempPort, "api.openai.com", "/v1/models", 2800, readBody = true)
                if (gptResp == null) {
                    false
                } else {
                    val lower = gptResp.body.lowercase()
                    (gptResp.code == 401 || gptResp.code == 200) &&
                        !lower.contains("unsupported_country") &&
                        !lower.contains("cf-mitigated")
                }
            }

            val geminiOk = if (badGeo) {
                false
            } else {
                val (gemResp, _) = socks5hHttpsGet(tempPort, "gemini.google.com", "/app", 3200, readBody = true)
                if (gemResp == null) {
                    false
                } else {
                    val bodyL = gemResp.body.lowercase()
                    val locL = gemResp.location.lowercase()
                    val pageOk = when {
                        gemResp.code == 200 ->
                            !bodyL.contains("isn't available in your country") &&
                                !bodyL.contains("not available in your country") &&
                                !bodyL.contains("country_not_supported") &&
                                !bodyL.contains("unusual traffic")
                        gemResp.code in 300..399 ->
                            !locL.contains("/faq") &&
                                !locL.contains("/sorry") &&
                                !locL.contains("unsupported") &&
                                !locL.contains("support.google.com")
                        else -> false
                    }
                    if (pageOk) {
                        val (apiResp, _) = socks5hHttpsGet(tempPort, "waa-pa.clients6.google.com", "/", 2600, readBody = false)
                        apiResp != null && apiResp.code in 200..499
                    } else {
                        false
                    }
                }
            }

            val flowOk = if (badGeo) {
                false
            } else {
                val (flowResp, _) = socks5hHttpsGet(tempPort, "labs.google", "/fx/tools/flow", 2800, readBody = true)
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

            val (ytResp, _) = socks5hHttpsGet(tempPort, "www.youtube.com", "/generate_204", 2600, readBody = false)
            val ytOk = ytResp != null && (
                ytResp.code == 204 ||
                    (ytResp.code in 200..399 && !ytResp.location.lowercase().contains("/sorry"))
                )

            val (metaResp, _) = socks5hHttpsGet(tempPort, "www.instagram.com", "/", 2600, readBody = false)
            val metaOk = metaResp != null && metaResp.code in 200..399

            val (tgResp, _) = socks5hHttpsGet(tempPort, "api.telegram.org", "/", 2400, readBody = false)
            val tgOk = tgResp != null && tgResp.code in 200..404

            val (waResp, _) = socks5hHttpsGet(tempPort, "web.whatsapp.com", "/", 2400, readBody = false)
            val waOk = waResp != null && waResp.code in 200..399

            if (!geminiOk && !flowOk && !gptOk && !ytOk && !metaOk && !tgOk && !waOk) {
                return Pair(null, "no services open on ${node.host}")
            }

            Pair(
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
                ),
                null
            )
        } catch (e: Exception) {
            Pair(null, "${e.javaClass.simpleName}: ${e.message}")
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
    ): Pair<SimpleHttpResponse?, String?> {
        val domainBytes = host.toByteArray(Charsets.UTF_8)
        if (domainBytes.size > 255) return Pair(null, "host too long")

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
            val greet = readExact(rawIn, 2) ?: return Pair(null, "socks greet eof")
            if (greet[0] != 0x05.toByte() || greet[1] != 0x00.toByte()) {
                return Pair(null, "socks greet failed")
            }

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

            val head = readExact(rawIn, 4) ?: return Pair(null, "socks reply eof")
            if (head[0] != 0x05.toByte() || head[1] != 0x00.toByte()) {
                return Pair(null, "socks reply code=${head[1]}")
            }
            when (head[3].toInt() and 0xFF) {
                0x01 -> if (readExact(rawIn, 6) == null) return Pair(null, "socks addr4 eof")
                0x03 -> {
                    val lenBuf = readExact(rawIn, 1) ?: return Pair(null, "socks domlen eof")
                    val dLen = lenBuf[0].toInt() and 0xFF
                    if (readExact(rawIn, dLen + 2) == null) return Pair(null, "socks dom eof")
                }
                0x04 -> if (readExact(rawIn, 18) == null) return Pair(null, "socks addr6 eof")
                else -> return Pair(null, "socks bad atyp")
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
            if (statusParts.size < 2) return Pair(null, "empty http status")
            val statusCode = statusParts[1].toIntOrNull() ?: return Pair(null, "bad http status")

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

            Pair(SimpleHttpResponse(statusCode, location, body), null)
        } catch (e: Exception) {
            Pair(null, "${e.javaClass.simpleName}: ${e.message}")
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
        return try {
            val addr = InetAddress.getByName(host)
            val start = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(addr, port), timeoutMs)
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
            setRequestProperty("User-Agent", "MyBypassXray/4.2")
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

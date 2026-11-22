package com.mybypass.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var selectedServerView: TextView
    private lateinit var toggleBtn: Button
    private lateinit var findBtn: Button
    private lateinit var listBtn: Button
    private lateinit var subInput: EditText
    private lateinit var aiCountryCheck: CheckBox
    private lateinit var splitRuCheck: CheckBox
    private lateinit var blockQuicCheck: CheckBox
    private lateinit var blockIpv6Check: CheckBox
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private val isSearching = AtomicBoolean(false)
    private var workingNodes: MutableList<Pair<VlessNode, Long>> = mutableListOf()
    private var currentIndex: Int = 0
    private var selectedNode: VlessNode? = null

    private val allFreeUrls = listOf(
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS.txt",
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS_mobile.txt",
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
        "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/githubmirror/clean/vless.txt",
        "https://raw.githubusercontent.com/patterniha/Free-Configs/main/configs.txt"
    )

    private val subPresets = listOf(
        "ВСЕ сразу (5 баз)" to allFreeUrls.joinToString("\n"),
        "Igareck Полная" to allFreeUrls[0],
        "Igareck Мобильная" to allFreeUrls[1],
        "Reality Белые списки" to allFreeUrls[2],
        "Kort0881 (1000+)" to allFreeUrls[3],
        "Patterniha Топ" to allFreeUrls[4]
    )

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            BypassVpnService.log("Пользователь отклонил разрешение на VPN.")
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BypassVpnService.initLogger(this)

        val prefs = getSharedPreferences("xray_prefs", MODE_PRIVATE)
        val savedSub = prefs.getString("sub_url", subPresets[0].second) ?: subPresets[0].second
        val savedVless = prefs.getString("selected_vless", "").orEmpty()
        val savedAiFilter = prefs.getBoolean("ai_country_filter", true)
        val savedSplitRu = prefs.getBoolean("split_ru", true)
        val savedBlockQuic = prefs.getBoolean("block_quic", true)
        val savedBlockIpv6 = prefs.getBoolean("block_ipv6", true)

        loadSavedWorkingList(prefs)
        if (savedVless.isNotBlank()) {
            selectedNode = VlessParser.parseVlessUri(savedVless)
            val idx = workingNodes.indexOfFirst { it.first.rawUri == savedVless }
            if (idx >= 0) currentIndex = idx
        } else if (workingNodes.isNotEmpty()) {
            selectedNode = workingNodes[0].first
            currentIndex = 0
        }

        val outerScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(36), dp(16), dp(24))
        }
        outerScroll.addView(root)

        val title = TextView(this).apply {
            text = "MyBypass Xray (VLESS)"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        root.addView(title)

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(4), 0, dp(6))
        }
        root.addView(statusView)

        selectedServerView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#B0BEC5"))
            background = roundedBg("#1B1F24", dp(8).toFloat())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { showServerPickerDialog() }
        }
        root.addView(selectedServerView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        toggleBtn = Button(this).apply {
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener {
                if (BypassVpnService.isRunning) {
                    stopVpnService()
                } else {
                    savePrefs()
                    val input = subInput.text.toString().trim()
                    if (input.startsWith("vless://", ignoreCase = true) && !input.contains('\n')) {
                        val parsed = VlessParser.parseVlessUri(input)
                        if (parsed != null) {
                            setSelectedNode(parsed, -1)
                            requestVpnAndStart()
                        } else {
                            Toast.makeText(this@MainActivity, "Неверный формат ссылки vless://", Toast.LENGTH_SHORT).show()
                        }
                    } else if (selectedNode != null) {
                        requestVpnAndStart()
                    } else {
                        findAndSelectBestServers(autoStart = true)
                    }
                }
            }
        }
        root.addView(toggleBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(sectionLabel("Базы бесплатных серверов GitHub или свой ключ vless://"))

        val subScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val subRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        subPresets.forEach { (label, url) ->
            subRow.addView(createChip(label) {
                subInput.setText(url)
                savePrefs()
            })
        }
        subScroll.addView(subRow)
        root.addView(subScroll)

        subInput = EditText(this).apply {
            setText(savedSub)
            hint = "Одна или несколько ссылок https://... (по строке) или vless://..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            maxLines = 4
            background = roundedBg("#1E1E1E", dp(8).toFloat())
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addView(subInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(6))
        }

        findBtn = Button(this).apply {
            text = "Найти серверы (до 25)"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.WHITE)
            background = roundedBg("#1565C0", dp(8).toFloat())
            setOnClickListener {
                savePrefs()
                findAndSelectBestServers(autoStart = false)
            }
        }

        listBtn = Button(this).apply {
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.WHITE)
            background = roundedBg("#37474F", dp(8).toFloat())
            setOnClickListener { showServerPickerDialog() }
        }

        actionRow.addView(findBtn, LinearLayout.LayoutParams(0, dp(42), 1.3f).apply { marginEnd = dp(6) })
        actionRow.addView(listBtn, LinearLayout.LayoutParams(0, dp(42), 1.2f).apply { marginStart = dp(6) })
        root.addView(actionRow)

        aiCountryCheck = CheckBox(this).apply {
            text = "Фильтр под ИИ: исключить РФ, РБ, Китай и Гонконг"
            isChecked = savedAiFilter
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 13f
            setOnCheckedChangeListener { _, _ -> savePrefs() }
        }
        root.addView(aiCountryCheck)

        splitRuCheck = CheckBox(this).apply {
            text = "Split Routing: РФ (.ru), банки и Госуслуги напрямую"
            isChecked = savedSplitRu
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 13f
            setOnCheckedChangeListener { _, _ -> savePrefs() }
        }
        root.addView(splitRuCheck)

        blockQuicCheck = CheckBox(this).apply {
            text = "Блокировать QUIC (UDP 443) — стабильный YouTube"
            isChecked = savedBlockQuic
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 13f
            setOnCheckedChangeListener { _, _ -> savePrefs() }
        }
        root.addView(blockQuicCheck)

        blockIpv6Check = CheckBox(this).apply {
            text = "Блокировать утечки IPv6 (для Gemini / ИИ)"
            isChecked = savedBlockIpv6
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 13f
            setOnCheckedChangeListener { _, _ -> savePrefs() }
        }
        root.addView(blockIpv6Check)

        val logHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(6))
        }
        val logTitle = TextView(this).apply {
            text = "Лог работы:"
            textSize = 13f
            setTextColor(Color.parseColor("#AAAAAA"))
        }
        val copyBtn = Button(this).apply {
            text = "Копировать лог"
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.WHITE)
            background = roundedBg("#333333", dp(6).toFloat())
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("MyBypassXray Log", BypassVpnService.getDiagnosticsReport(this@MainActivity)))
                Toast.makeText(this@MainActivity, "Лог скопирован", Toast.LENGTH_SHORT).show()
            }
        }
        logHeaderRow.addView(logTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        logHeaderRow.addView(copyBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)))
        root.addView(logHeaderRow)

        logView = TextView(this).apply {
            text = BypassVpnService.getDiagnosticsReport(this@MainActivity)
            setTextColor(Color.parseColor("#9CDCFE"))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        logScroll = ScrollView(this).apply {
            background = roundedBg("#0D0D0D", dp(8).toFloat())
            addView(logView)
        }
        root.addView(logScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(220)
        ))

        setContentView(outerScroll)
        val initialPing = workingNodes.getOrNull(currentIndex)?.second ?: -1L
        refreshSelectedLabel(initialPing)
        updateUi(BypassVpnService.isRunning)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showServerPickerDialog() {
        if (workingNodes.isEmpty()) {
            Toast.makeText(this, "Список пуст. Нажмите «Найти серверы (до 25)»", Toast.LENGTH_SHORT).show()
            return
        }
        val items = workingNodes.mapIndexed { idx, (node, ping) ->
            val mark = if (node.rawUri == selectedNode?.rawUri) "▶ " else ""
            "$mark#${idx + 1} • ${ping}мс • ${node.name.take(32)} (${node.security}/${node.network})"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Рабочие серверы (${workingNodes.size})")
            .setItems(items) { _, which ->
                currentIndex = which
                val chosen = workingNodes[which]
                setSelectedNode(chosen.first, chosen.second)
                BypassVpnService.log("Выбран сервер #${which + 1}: ${chosen.first.name} (${chosen.second} мс)")
                if (BypassVpnService.isRunning) {
                    stopVpnService()
                    logScroll.postDelayed({ requestVpnAndStart() }, 600)
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun findAndSelectBestServers(autoStart: Boolean) {
        if (!isSearching.compareAndSet(false, true)) return

        val rawInput = subInput.text.toString().trim()
        if (rawInput.isBlank()) {
            isSearching.set(false)
            Toast.makeText(this, "Укажите ссылки на подписки или vless://", Toast.LENGTH_SHORT).show()
            return
        }

        val filterAiCountries = aiCountryCheck.isChecked

        runOnUiThread {
            findBtn.isEnabled = false
            findBtn.text = "Сканирование..."
        }

        Thread {
            try {
                if (BypassVpnService.isRunning) {
                    BypassVpnService.log("Останавливаю VPN перед поиском серверов...")
                    stopVpnService()
                    Thread.sleep(600)
                }

                val lines = rawInput.lines().map { it.trim() }.filter { it.isNotEmpty() }
                val allParsed = LinkedHashMap<String, VlessNode>()

                // 1. Загружаем все указанные URL и/или прямые vless:// ключи
                val urls = lines.filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
                val directUris = lines.filter { it.startsWith("vless://", true) }

                directUris.forEach { uri ->
                    VlessParser.parseVlessUri(uri)?.let { n ->
                        allParsed["${n.host}:${n.port}:${n.uuid}"] = n
                    }
                }

                if (urls.isNotEmpty()) {
                    BypassVpnService.log("Загрузка ${urls.size} баз(ы) с GitHub...")
                    val dlPool = Executors.newFixedThreadPool(minOf(urls.size, 6))
                    try {
                        val futures = urls.map { u ->
                            dlPool.submit(Callable {
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

                var uniqueNodes = allParsed.values.toList()
                BypassVpnService.log("Всего уникальных VLESS: ${uniqueNodes.size}")

                if (filterAiCountries) {
                    val before = uniqueNodes.size
                    uniqueNodes = uniqueNodes.filterNot { VlessParser.isGeoBlockedForAi(it) }
                    BypassVpnService.log("После фильтра стран для ИИ: ${uniqueNodes.size} (отсеяно ${before - uniqueNodes.size})")
                }

                if (uniqueNodes.isEmpty()) {
                    BypassVpnService.log("ОШИБКА: подходящих VLESS-серверов не найдено.")
                    return@Thread
                }

                // Приоритет протоколу Reality (быстрее и стабильнее WebSocket), перемешиваем для свежести
                val realityList = uniqueNodes.filter { it.security == "reality" }.shuffled()
                val otherList = uniqueNodes.filter { it.security != "reality" }.shuffled()
                val candidates = (realityList.take(240) + otherList.take(80)).take(300)

                // Этап 1: быстрый параллельный TCP-пинг (32 потока)
                BypassVpnService.log("Этап 1/2: TCP-пинг ${candidates.size} серверов (Reality в приоритете)...")
                val tcpPool = Executors.newFixedThreadPool(32)
                val tcpAlive = try {
                    val futures = candidates.map { node ->
                        tcpPool.submit(Callable {
                            val ping = tcpPing(node.host, node.port, 1400)
                            if (ping >= 0) node to ping else null
                        })
                    }
                    futures.mapNotNull {
                        try { it.get() } catch (_: Exception) { null }
                    }.sortedBy { (node, ping) ->
                        // Небольшой бонус для Reality при сортировке кандидатов
                        if (node.security == "reality") ping else ping + 120
                    }
                } finally {
                    tcpPool.shutdownNow()
                }

                BypassVpnService.log("Ответили по TCP: ${tcpAlive.size}. Запускаю реальный тест Xray...")
                if (tcpAlive.isEmpty()) {
                    BypassVpnService.log("ОШИБКА: ни один сервер не ответил по TCP.")
                    return@Thread
                }

                // Этап 2: параллельная проверка до 60 узлов в 6 потоков через Xray -> Google
                val targetCount = 25
                val toVerify = tcpAlive.take(60)
                val verified = Collections.synchronizedList(mutableListOf<Pair<VlessNode, Long>>())
                val portCounter = AtomicInteger(10820)
                val testedCounter = AtomicInteger(0)

                val xrayPool = Executors.newFixedThreadPool(6)
                try {
                    val tasks = toVerify.map { (node, _) ->
                        xrayPool.submit {
                            if (verified.size >= targetCount) return@submit
                            val tempPort = portCounter.getAndIncrement()
                            val httpMs = testNodeThroughXray(node, tempPort)
                            val done = testedCounter.incrementAndGet()
                            if (httpMs >= 0) {
                                verified.add(node to httpMs)
                                BypassVpnService.log("✓ [${verified.size}] ${httpMs}мс | ${node.name.take(30)} (${node.security}/${node.network})")
                                runOnUiThread {
                                    findBtn.text = "Найдено: ${verified.size}/$targetCount..."
                                }
                            } else if (done % 10 == 0) {
                                BypassVpnService.log("...проверено $done/${toVerify.size}, найдено ${verified.size}")
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
                    BypassVpnService.log("Ни один из ${toVerify.size} серверов не открыл Google. Попробуйте ещё раз (список перемешается).")
                    return@Thread
                }

                val sorted = verified.sortedBy { it.second }.toMutableList()
                workingNodes = sorted
                currentIndex = 0
                saveWorkingList(sorted)

                val best = sorted[0]
                BypassVpnService.log("ГОТОВО! Найдено ${sorted.size} рабочих серверов. Лучший: ${best.first.name} (${best.second} мс)")

                runOnUiThread {
                    setSelectedNode(best.first, best.second)
                    Toast.makeText(
                        this@MainActivity,
                        "Найдено ${sorted.size} серверов! Лучший: ${best.second} мс",
                        Toast.LENGTH_LONG
                    ).show()
                    if (autoStart) {
                        requestVpnAndStart()
                    }
                }
            } catch (e: Exception) {
                BypassVpnService.log("ОШИБКА поиска: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                isSearching.set(false)
                runOnUiThread {
                    findBtn.isEnabled = true
                    findBtn.text = "Найти серверы (до 25)"
                    updateListButtonText()
                }
            }
        }.start()
    }

    private fun testNodeThroughXray(node: VlessNode, tempPort: Int): Long {
        val xrayBin = File(applicationInfo.nativeLibraryDir, "libxray.so")
        if (!xrayBin.exists()) return -1L

        val testConfigFile = File(cacheDir, "xray_test_$tempPort.json")
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
            for (i in 0 until 8) {
                if (!proc.isAlive) return -1L
                if (tcpPing("127.0.0.1", tempPort, 100) >= 0) {
                    portReady = true
                    break
                }
                Thread.sleep(100)
            }
            if (!portReady) return -1L

            val start = System.currentTimeMillis()
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", tempPort))
            val conn = (URL("https://www.gstatic.com/generate_204").openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                instanceFollowRedirects = false
                requestMethod = "GET"
            }
            val code = conn.responseCode
            conn.disconnect()
            val elapsed = System.currentTimeMillis() - start
            if (code in 200..399) elapsed else -1L
        } catch (_: Exception) {
            -1L
        } finally {
            try { proc?.destroy() } catch (_: Exception) {}
            try { proc?.destroyForcibly() } catch (_: Exception) {}
            try { testConfigFile.delete() } catch (_: Exception) {}
        }
    }

    private fun tcpPing(host: String, port: Int, timeoutMs: Int): Long {
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
            setRequestProperty("User-Agent", "MyBypassXray/2.0")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun saveWorkingList(list: List<Pair<VlessNode, Long>>) {
        val serialized = list.joinToString("\n") { (node, ping) -> "$ping\t${node.rawUri}" }
        getSharedPreferences("xray_prefs", MODE_PRIVATE).edit()
            .putString("working_list", serialized)
            .apply()
    }

    private fun loadSavedWorkingList(prefs: android.content.SharedPreferences) {
        val raw = prefs.getString("working_list", "").orEmpty()
        if (raw.isBlank()) return
        val restored = mutableListOf<Pair<VlessNode, Long>>()
        for (line in raw.lines()) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val ping = line.substring(0, tab).toLongOrNull() ?: continue
            val node = VlessParser.parseVlessUri(line.substring(tab + 1)) ?: continue
            restored.add(node to ping)
        }
        workingNodes = restored
    }

    private fun setSelectedNode(node: VlessNode, pingMs: Long) {
        selectedNode = node
        getSharedPreferences("xray_prefs", MODE_PRIVATE).edit()
            .putString("selected_vless", node.rawUri)
            .apply()
        refreshSelectedLabel(pingMs)
    }

    private fun refreshSelectedLabel(pingMs: Long) {
        val node = selectedNode
        selectedServerView.text = if (node == null) {
            "Сервер не выбран. Нажмите «Найти серверы (до 25)»."
        } else {
            val pingStr = if (pingMs >= 0) " • ${pingMs} мс" else ""
            val poolStr = if (workingNodes.isNotEmpty()) " [${currentIndex + 1}/${workingNodes.size}]" else ""
            "Сервер$poolStr (нажмите для выбора): ${node.name}\n${node.host}:${node.port} (${node.security}/${node.network})$pingStr"
        }
        updateListButtonText()
    }

    private fun updateListButtonText() {
        listBtn.text = "Список (${workingNodes.size})"
    }

    private fun savePrefs() {
        getSharedPreferences("xray_prefs", MODE_PRIVATE).edit()
            .putString("sub_url", subInput.text.toString().trim())
            .putBoolean("ai_country_filter", aiCountryCheck.isChecked)
            .putBoolean("split_ru", splitRuCheck.isChecked)
            .putBoolean("block_quic", blockQuicCheck.isChecked)
            .putBoolean("block_ipv6", blockIpv6Check.isChecked)
            .apply()
    }

    override fun onStart() {
        super.onStart()
        logView.text = BypassVpnService.getDiagnosticsReport(this)
        BypassVpnService.onStateListener = { running ->
            runOnUiThread { updateUi(running) }
        }
        BypassVpnService.onLogListener = { fullLog ->
            runOnUiThread {
                logView.text = fullLog
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
        updateUi(BypassVpnService.isRunning)
    }

    override fun onStop() {
        BypassVpnService.onStateListener = null
        BypassVpnService.onLogListener = null
        super.onStop()
    }

    private fun requestVpnAndStart() {
        if (selectedNode == null) {
            Toast.makeText(this, "Сначала найдите серверы", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val node = selectedNode ?: return
        val configJson = VlessParser.buildXrayConfig(
            node = node,
            socksPort = 1080,
            splitRu = splitRuCheck.isChecked,
            blockQuic = blockQuicCheck.isChecked,
            isTestMode = false
        )
        val intent = Intent(this, BypassVpnService::class.java).apply {
            action = BypassVpnService.ACTION_START
            putExtra(BypassVpnService.EXTRA_CONFIG_JSON, configJson)
            putExtra(BypassVpnService.EXTRA_SERVER_NAME, node.name)
            putExtra(BypassVpnService.EXTRA_BLOCK_IPV6, blockIpv6Check.isChecked)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopVpnService() {
        val intent = Intent(this, BypassVpnService::class.java).apply {
            action = BypassVpnService.ACTION_STOP
        }
        startService(intent)
    }

    private fun updateUi(running: Boolean) {
        if (running) {
            val srv = BypassVpnService.activeServerName.ifBlank { selectedNode?.name.orEmpty() }
            statusView.text = "● Подключено (Xray VLESS: $srv)"
            statusView.setTextColor(Color.parseColor("#4CAF50"))
            toggleBtn.text = "ОСТАНОВИТЬ VPN"
            toggleBtn.background = roundedBg("#C62828", dp(12).toFloat())
        } else {
            statusView.text = "○ Отключено"
            statusView.setTextColor(Color.parseColor("#9E9E9E"))
            toggleBtn.text = "ЗАПУСТИТЬ VPN"
            toggleBtn.background = roundedBg("#2E7D32", dp(12).toFloat())
        }
        toggleBtn.gravity = Gravity.CENTER
        splitRuCheck.isEnabled = !running
        blockQuicCheck.isEnabled = !running
        blockIpv6Check.isEnabled = !running
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#AAAAAA"))
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun createChip(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setTextColor(Color.WHITE)
        background = roundedBg("#262626", dp(8).toFloat())
        setPadding(dp(12), dp(6), dp(12), dp(6))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun roundedBg(hex: String, radius: Float): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor(hex))
        cornerRadius = radius
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

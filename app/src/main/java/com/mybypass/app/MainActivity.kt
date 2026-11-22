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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var selectedServerView: TextView
    private lateinit var toggleBtn: Button
    private lateinit var findBtn: Button
    private lateinit var nextBtn: Button
    private lateinit var subInput: EditText
    private lateinit var splitRuCheck: CheckBox
    private lateinit var blockQuicCheck: CheckBox
    private lateinit var blockIpv6Check: CheckBox
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private val isSearching = AtomicBoolean(false)
    private var workingNodes: MutableList<Pair<VlessNode, Long>> = mutableListOf()
    private var currentIndex: Int = 0
    private var selectedNode: VlessNode? = null

    private val subPresets = listOf(
        "Igareck РФ" to "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS.txt",
        "Igareck Мобильный" to "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS_mobile.txt",
        "Patterniha (Топ)" to "https://raw.githubusercontent.com/patterniha/Free-Configs/main/configs.txt"
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
        val savedSplitRu = prefs.getBoolean("split_ru", true)
        val savedBlockQuic = prefs.getBoolean("block_quic", true)
        val savedBlockIpv6 = prefs.getBoolean("block_ipv6", true)

        if (savedVless.isNotBlank()) {
            selectedNode = VlessParser.parseVlessUri(savedVless)
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
                    if (input.startsWith("vless://", ignoreCase = true)) {
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
                        findAndSelectBestServer(autoStart = true)
                    }
                }
            }
        }
        root.addView(toggleBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // --- Подписки GitHub ---
        root.addView(sectionLabel("Бесплатные подписки GitHub или свой ключ vless://"))

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
            hint = "https://... или vless://..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            maxLines = 3
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
            text = "Найти живой сервер (Авто)"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.WHITE)
            background = roundedBg("#1565C0", dp(8).toFloat())
            setOnClickListener {
                savePrefs()
                findAndSelectBestServer(autoStart = false)
            }
        }

        nextBtn = Button(this).apply {
            text = "Следующий"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.WHITE)
            background = roundedBg("#37474F", dp(8).toFloat())
            setOnClickListener { switchToNextWorkingServer() }
        }

        actionRow.addView(findBtn, LinearLayout.LayoutParams(0, dp(42), 1.8f).apply { marginEnd = dp(6) })
        actionRow.addView(nextBtn, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(6) })
        root.addView(actionRow)

        // --- Настройки маршрутизации ---
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

        val hintNote = TextView(this).apply {
            text = "Важно: верните в настройках Android «Частный DNS» в Авто или Откл. (уберите dns.comss.one)."
            textSize = 11f
            setTextColor(Color.parseColor("#FFB74D"))
            setPadding(0, dp(6), 0, dp(8))
        }
        root.addView(hintNote)

        val logHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(6))
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
        refreshSelectedLabel(-1)
        updateUi(BypassVpnService.isRunning)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun findAndSelectBestServer(autoStart: Boolean) {
        if (!isSearching.compareAndSet(false, true)) return

        val source = subInput.text.toString().trim()
        if (source.isBlank()) {
            isSearching.set(false)
            Toast.makeText(this, "Укажите ссылку на подписку или ключ vless://", Toast.LENGTH_SHORT).show()
            return
        }

        runOnUiThread {
            findBtn.isEnabled = false
            findBtn.text = "Поиск живого сервера..."
        }

        Thread {
            try {
                if (BypassVpnService.isRunning) {
                    BypassVpnService.log("Останавливаю текущий VPN перед подбором...")
                    stopVpnService()
                    Thread.sleep(600)
                }

                val nodes: List<VlessNode> = if (source.startsWith("vless://", ignoreCase = true)) {
                    listOfNotNull(VlessParser.parseVlessUri(source))
                } else {
                    BypassVpnService.log("Скачиваю подписку: $source")
                    val text = downloadText(source)
                    val parsed = VlessParser.parseSubscription(text)
                    BypassVpnService.log("Найдено VLESS-ключей: ${parsed.size}")
                    parsed
                }

                if (nodes.isEmpty()) {
                    BypassVpnService.log("ОШИБКА: не найдено подходящих VLESS-серверов. Попробуйте другую подписку.")
                    return@Thread
                }

                // Этап 1: быстрый параллельный TCP-пинг (отсеивает мёртвые и заблокированные IP)
                val candidates = nodes.take(100)
                BypassVpnService.log("Этап 1/2: TCP-пинг ${candidates.size} серверов...")
                val pool = Executors.newFixedThreadPool(16)
                val tcpAlive = try {
                    val futures = candidates.map { node ->
                        pool.submit(Callable {
                            val ping = tcpPing(node.host, node.port, 1500)
                            if (ping >= 0) node to ping else null
                        })
                    }
                    futures.mapNotNull {
                        try { it.get() } catch (_: Exception) { null }
                    }.sortedBy { it.second }
                } finally {
                    pool.shutdownNow()
                }

                BypassVpnService.log("Доступны по TCP: ${tcpAlive.size} серверов.")
                if (tcpAlive.isEmpty()) {
                    BypassVpnService.log("ОШИБКА: ни один сервер не ответил по TCP. Попробуйте «Igareck Мобильный» или «Patterniha».")
                    return@Thread
                }

                // Этап 2: реальная проверка через Xray запросом к Google (gstatic.com/generate_204)
                val topToVerify = tcpAlive.take(12)
                BypassVpnService.log("Этап 2/2: проверка доступа к Google через Xray (до ${topToVerify.size} узлов)...")
                val verified = mutableListOf<Pair<VlessNode, Long>>()

                for ((idx, pair) in topToVerify.withIndex()) {
                    val (node, tcpMs) = pair
                    val httpMs = testNodeThroughXray(node, 10815 + idx)
                    if (httpMs >= 0) {
                        BypassVpnService.log("✓ РАБОТАЕТ: ${node.name.take(36)} (TCP ${tcpMs}мс / Google ${httpMs}мс)")
                        verified.add(node to httpMs)
                        // Собираем до 5 рабочих узлов для быстрого переключения кнопкой «Следующий»
                        if (verified.size >= 5) break
                    } else {
                        BypassVpnService.log("✗ Не прошёл тест: ${node.name.take(36)} (TCP ${tcpMs}мс)")
                    }
                }

                if (verified.isEmpty()) {
                    BypassVpnService.log("Ни один из ${topToVerify.size} серверов не открыл Google. Попробуйте другую подписку сверху.")
                    return@Thread
                }

                verified.sortBy { it.second }
                workingNodes = verified
                currentIndex = 0
                val best = verified[0]

                runOnUiThread {
                    setSelectedNode(best.first, best.second)
                    Toast.makeText(
                        this@MainActivity,
                        "Найден рабочий сервер (${best.second} мс)!",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (autoStart) {
                        requestVpnAndStart()
                    }
                }
            } catch (e: Exception) {
                BypassVpnService.log("ОШИБКА подбора: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                isSearching.set(false)
                runOnUiThread {
                    findBtn.isEnabled = true
                    findBtn.text = "Найти живой сервер (Авто)"
                }
            }
        }.start()
    }

    private fun switchToNextWorkingServer() {
        if (workingNodes.size <= 1) {
            Toast.makeText(this, "Сначала нажмите «Найти живой сервер (Авто)»", Toast.LENGTH_SHORT).show()
            return
        }
        currentIndex = (currentIndex + 1) % workingNodes.size
        val next = workingNodes[currentIndex]
        setSelectedNode(next.first, next.second)
        BypassVpnService.log("Переключено на сервер #${currentIndex + 1}: ${next.first.name}")

        if (BypassVpnService.isRunning) {
            stopVpnService()
            logScroll.postDelayed({ requestVpnAndStart() }, 600)
        }
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

            // Ждём открытия локального SOCKS5 порта (до 800 мс)
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
                connectTimeout = 4500
                readTimeout = 4500
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
            setRequestProperty("User-Agent", "MyBypassXray/1.0")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
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
            "Сервер не выбран. Нажмите «Найти живой сервер (Авто)»."
        } else {
            val pingStr = if (pingMs >= 0) " • ${pingMs} мс" else ""
            val poolStr = if (workingNodes.size > 1) " [${currentIndex + 1}/${workingNodes.size}]" else ""
            "Сервер$poolStr: ${node.name}\n${node.host}:${node.port} (${node.security}/${node.network})$pingStr"
        }
    }

    private fun savePrefs() {
        getSharedPreferences("xray_prefs", MODE_PRIVATE).edit()
            .putString("sub_url", subInput.text.toString().trim())
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
            Toast.makeText(this, "Сначала выберите сервер", Toast.LENGTH_SHORT).show()
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

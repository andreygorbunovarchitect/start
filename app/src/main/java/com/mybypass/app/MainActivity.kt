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

    private var workingNodes: MutableList<TestedNode> = mutableListOf()
    private var selectedTested: TestedNode? = null
    private var pendingServiceAction: String? = null

    private val subPresets = listOf(
        "ВСЕ сразу (5 баз)" to ServerScanner.ALL_FREE_URLS.joinToString("\n"),
        "Igareck Полная" to ServerScanner.ALL_FREE_URLS[0],
        "Igareck Мобильная" to ServerScanner.ALL_FREE_URLS[1],
        "Reality Белые списки" to ServerScanner.ALL_FREE_URLS[2],
        "Kort0881 (1000+)" to ServerScanner.ALL_FREE_URLS[3],
        "Patterniha Топ" to ServerScanner.ALL_FREE_URLS[4]
    )

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = pendingServiceAction
        pendingServiceAction = null
        if (result.resultCode == Activity.RESULT_OK) {
            when (action) {
                BypassVpnService.ACTION_AUTO_CONNECT,
                BypassVpnService.ACTION_RESCAN_AND_CONNECT -> sendServiceAction(action)
                else -> startSelectedVpn()
            }
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

        workingNodes = ServerScanner.loadWorkingList(this)
        selectedTested = ServerScanner.loadSelectedNode(this)

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
                if (BypassVpnService.isRunning || BypassVpnService.isAutoSearching) {
                    stopVpnService()
                } else {
                    savePrefs()
                    val input = subInput.text.toString().trim()
                    if (input.startsWith("vless://", ignoreCase = true) && !input.contains('\n')) {
                        val parsed = VlessParser.parseVlessUri(input)
                        if (parsed != null) {
                            val manual = TestedNode(node = parsed, pingMs = -1L)
                            selectedTested = manual
                            ServerScanner.saveSelectedNode(this@MainActivity, manual)
                            refreshSelectedLabel()
                            requestVpnAndRun(BypassVpnService.ACTION_START)
                        } else {
                            Toast.makeText(this@MainActivity, "Неверный формат ссылки vless://", Toast.LENGTH_SHORT).show()
                        }
                    } else if (selectedTested != null) {
                        requestVpnAndRun(BypassVpnService.ACTION_START)
                    } else {
                        requestVpnAndRun(BypassVpnService.ACTION_RESCAN_AND_CONNECT)
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
            setText(ServerScanner.getSavedSubText(this@MainActivity))
            hint = "Одна или несколько ссылок https://... или vless://..."
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
            text = "Автопоиск + Подкл."
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.WHITE)
            background = roundedBg("#1565C0", dp(8).toFloat())
            setOnClickListener {
                savePrefs()
                requestVpnAndRun(BypassVpnService.ACTION_RESCAN_AND_CONNECT)
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
            isChecked = ServerScanner.isAiFilterEnabled(this@MainActivity)
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 13f
            setOnCheckedChangeListener { _, _ -> savePrefs() }
        }
        root.addView(aiCountryCheck)

        splitRuCheck = CheckBox(this).apply {
            text = "Split Routing: РФ (.ru), банки и Госуслуги напрямую"
            isChecked = ServerScanner.isSplitRuEnabled(this@MainActivity)
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 13f
            setOnCheckedChangeListener { _, _ -> savePrefs() }
        }
        root.addView(splitRuCheck)

        blockQuicCheck = CheckBox(this).apply {
            text = "Блокировать QUIC — стабильные YouTube, Meta и звонки"
            isChecked = ServerScanner.isBlockQuicEnabled(this@MainActivity)
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 13f
            setOnCheckedChangeListener { _, _ -> savePrefs() }
        }
        root.addView(blockQuicCheck)

        blockIpv6Check = CheckBox(this).apply {
            text = "Блокировать утечки IPv6 (включать только на мобильной сети)"
            isChecked = ServerScanner.isBlockIpv6Enabled(this@MainActivity)
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
        refreshSelectedLabel()
        updateUi()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        handleTileAutoAction(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTileAutoAction(intent)
    }

    private fun handleTileAutoAction(intent: Intent?) {
        val autoAction = intent?.getStringExtra(EXTRA_AUTO_ACTION) ?: return
        intent.removeExtra(EXTRA_AUTO_ACTION)
        requestVpnAndRun(autoAction)
    }

    private fun showServerPickerDialog() {
        workingNodes = ServerScanner.loadWorkingList(this)
        if (workingNodes.isEmpty()) {
            Toast.makeText(this, "Список пуст. Нажмите «Автопоиск + Подкл.»", Toast.LENGTH_SHORT).show()
            return
        }
        val items = workingNodes.mapIndexed { idx, tested ->
            tested.listItemLabel(idx, tested.node.rawUri == selectedTested?.node?.rawUri)
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Серверы (${workingNodes.size}) — сначала ВСЕ сервисы")
            .setItems(items) { _, which ->
                val chosen = workingNodes[which]
                selectedTested = chosen
                ServerScanner.saveSelectedNode(this, chosen)
                refreshSelectedLabel()
                BypassVpnService.log("Выбран сервер #${which + 1}: ${chosen.shortTitle()} (${chosen.pingMs} мс)")
                requestVpnAndRun(BypassVpnService.ACTION_START)
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun refreshSelectedLabel() {
        val current = selectedTested
        val idx = workingNodes.indexOfFirst { it.node.rawUri == current?.node?.rawUri }
        selectedServerView.text = if (current == null) {
            "Сервер не выбран. Нажмите «Автопоиск + Подкл.»."
        } else {
            val poolStr = if (idx >= 0 && workingNodes.isNotEmpty()) " [${idx + 1}/${workingNodes.size}]" else ""
            val pingStr = if (current.pingMs >= 0) " • ${current.pingMs} мс" else ""
            val cc = if (current.countryCode.isNotBlank()) "[${current.countryCode}] " else ""
            "Сервер$poolStr: $cc${current.node.name}\n" +
                "${current.capabilitiesTag()} • ${current.node.security}/${current.node.network}$pingStr"
        }
        val fullCount = workingNodes.count { it.allWork }
        listBtn.text = if (fullCount > 0) {
            "Список (${workingNodes.size} | ВСЕ: $fullCount)"
        } else {
            "Список (${workingNodes.size})"
        }
    }

    private fun savePrefs() {
        getSharedPreferences(ServerScanner.PREFS_NAME, MODE_PRIVATE).edit()
            .putString("sub_url", subInput.text.toString().trim())
            .putBoolean("ai_country_filter", aiCountryCheck.isChecked)
            .putBoolean("split_ru", splitRuCheck.isChecked)
            .putBoolean("block_quic", blockQuicCheck.isChecked)
            .putBoolean("block_ipv6", blockIpv6Check.isChecked)
            .apply()
    }

    override fun onStart() {
        super.onStart()
        workingNodes = ServerScanner.loadWorkingList(this)
        selectedTested = ServerScanner.loadSelectedNode(this)
        logView.text = BypassVpnService.getDiagnosticsReport(this)

        BypassVpnService.onStateListener = {
            runOnUiThread {
                workingNodes = ServerScanner.loadWorkingList(this)
                selectedTested = ServerScanner.loadSelectedNode(this)
                refreshSelectedLabel()
                updateUi()
            }
        }
        BypassVpnService.onLogListener = { fullLog ->
            runOnUiThread {
                logView.text = fullLog
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
        ServerScanner.onProgressListener = { progress ->
            runOnUiThread {
                if (progress.isNotBlank()) {
                    findBtn.text = progress
                } else {
                    findBtn.text = "Автопоиск + Подкл."
                }
            }
        }
        ServerScanner.onListUpdatedListener = { updated ->
            runOnUiThread {
                workingNodes = updated.toMutableList()
                selectedTested = ServerScanner.loadSelectedNode(this)
                refreshSelectedLabel()
            }
        }
        refreshSelectedLabel()
        updateUi()
    }

    override fun onStop() {
        BypassVpnService.onStateListener = null
        BypassVpnService.onLogListener = null
        ServerScanner.onProgressListener = null
        ServerScanner.onListUpdatedListener = null
        super.onStop()
    }

    private fun requestVpnAndRun(action: String) {
        val prepIntent = VpnService.prepare(this)
        if (prepIntent != null) {
            pendingServiceAction = action
            vpnPermissionLauncher.launch(prepIntent)
        } else {
            when (action) {
                BypassVpnService.ACTION_AUTO_CONNECT,
                BypassVpnService.ACTION_RESCAN_AND_CONNECT -> sendServiceAction(action)
                else -> startSelectedVpn()
            }
        }
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, BypassVpnService::class.java).apply {
            this.action = action
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun startSelectedVpn() {
        val tested = selectedTested ?: run {
            sendServiceAction(BypassVpnService.ACTION_RESCAN_AND_CONNECT)
            return
        }
        val configJson = VlessParser.buildXrayConfig(
            node = tested.node,
            socksPort = 1080,
            splitRu = splitRuCheck.isChecked,
            blockQuic = blockQuicCheck.isChecked,
            isTestMode = false
        )
        val intent = Intent(this, BypassVpnService::class.java).apply {
            action = BypassVpnService.ACTION_START
            putExtra(BypassVpnService.EXTRA_CONFIG_JSON, configJson)
            putExtra(BypassVpnService.EXTRA_SERVER_NAME, tested.shortTitle())
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

    private fun updateUi() {
        val running = BypassVpnService.isRunning
        val searching = BypassVpnService.isAutoSearching

        when {
            searching -> {
                statusView.text = "● Автопоиск: ${BypassVpnService.searchStatusText.ifBlank { "сканирование..." }}"
                statusView.setTextColor(Color.parseColor("#FFB74D"))
                toggleBtn.text = "ОТМЕНИТЬ ПОИСК"
                toggleBtn.background = roundedBg("#E65100", dp(12).toFloat())
                findBtn.isEnabled = false
            }
            running -> {
                val srv = BypassVpnService.activeServerName.ifBlank { selectedTested?.shortTitle().orEmpty() }
                statusView.text = "● Подключено: $srv"
                statusView.setTextColor(Color.parseColor("#4CAF50"))
                toggleBtn.text = "ОСТАНОВИТЬ VPN"
                toggleBtn.background = roundedBg("#C62828", dp(12).toFloat())
                findBtn.isEnabled = true
                findBtn.text = "Автопоиск + Подкл."
            }
            else -> {
                statusView.text = "○ Отключено"
                statusView.setTextColor(Color.parseColor("#9E9E9E"))
                toggleBtn.text = "ЗАПУСТИТЬ VPN"
                toggleBtn.background = roundedBg("#2E7D32", dp(12).toFloat())
                findBtn.isEnabled = true
                findBtn.text = "Автопоиск + Подкл."
            }
        }
        toggleBtn.gravity = Gravity.CENTER
        val editable = !running && !searching
        splitRuCheck.isEnabled = editable
        blockQuicCheck.isEnabled = editable
        blockIpv6Check.isEnabled = editable
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

    companion object {
        const val EXTRA_AUTO_ACTION = "extra_auto_action"
    }
}

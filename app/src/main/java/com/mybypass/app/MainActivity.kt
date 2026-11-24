package com.mybypass.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var selectedServerView: TextView
    private lateinit var toggleBtn: Button
    private lateinit var findBtn: Button
    private lateinit var nextBtn: Button
    private lateinit var listBtn: Button
    private lateinit var subInput: EditText
    private lateinit var warpCheck: CheckBox
    private lateinit var localDpiCheck: CheckBox
    private lateinit var dpiProfileSpinner: Spinner
    private lateinit var tgDirectCheck: CheckBox
    private lateinit var smartBypassCheck: CheckBox
    private lateinit var aiCountryCheck: CheckBox
    private lateinit var splitRuCheck: CheckBox
    private lateinit var blockQuicCheck: CheckBox
    private lateinit var blockIpv6Check: CheckBox
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private var workingNodes: MutableList<TestedNode> = mutableListOf()
    private var selectedTested: TestedNode? = null
    private var pendingServiceAction: String? = null
    private var pendingSkipCheck: Boolean = false

    private val subPresets = listOf(
        "ВСЕ (13 баз)" to VlessParser.ALL_FREE_URLS.joinToString("\n"),
        "Зарубежные (5)" to VlessParser.FOREIGN_FREE_URLS.joinToString("\n"),
        "РФ-совмест. (8)" to VlessParser.RU_COMPATIBLE_URLS.joinToString("\n"),
        "Reality Белые" to VlessParser.RU_COMPATIBLE_URLS[2],
        "Zieng2 Lite" to VlessParser.RU_COMPATIBLE_URLS[4]
    )

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = pendingServiceAction
        val skip = pendingSkipCheck
        pendingServiceAction = null
        pendingSkipCheck = false
        if (result.resultCode == Activity.RESULT_OK) {
            when (action) {
                BypassVpnService.ACTION_AUTO_CONNECT,
                BypassVpnService.ACTION_RESCAN_AND_CONNECT -> sendServiceAction(action)
                else -> startSelectedVpn(skip)
            }
        } else {
            BypassVpnService.log("Пользователь отклонил разрешение на VPN.")
        }
    }

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
            text = "VPN дяди Андрея"
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
                    val isSingleKey = !input.contains('\n') && (
                        input.startsWith("vless://", true) ||
                            input.startsWith("trojan://", true) ||
                            input.startsWith("ss://", true)
                        )
                    if (isSingleKey) {
                        val parsed = VlessParser.parseVlessUri(input, isCuratedSource = true)
                        if (parsed != null) {
                            val manual = TestedNode(node = parsed, pingMs = -1L)
                            selectedTested = manual
                            ServerScanner.saveSelectedNode(this@MainActivity, manual)
                            refreshSelectedLabel()
                            requestVpnAndRun(BypassVpnService.ACTION_START, skipCheck = true)
                        } else {
                            Toast.makeText(this@MainActivity, "Неверный формат ключа", Toast.LENGTH_SHORT).show()
                        }
                    } else if (selectedTested != null) {
                        requestVpnAndRun(BypassVpnService.ACTION_START, skipCheck = false)
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

        root.addView(sectionLabel("Базы бесплатных серверов GitHub (13 баз) или свой ключ"))

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
            hint = "Ссылки https://... или vless:// / trojan:// / ss://"
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
            text = "Автопоиск"
            isAllCaps = false
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = roundedBg("#1565C0", dp(8).toFloat())
            setOnClickListener {
                savePrefs()
                requestVpnAndRun(BypassVpnService.ACTION_RESCAN_AND_CONNECT)
            }
        }

        nextBtn = Button(this).apply {
            text = "Следующий"
            isAllCaps = false
            textSize = 12.5f
            setTextColor(Color.WHITE)
            background = roundedBg("#455A64", dp(8).toFloat())
            setOnClickListener { switchToNextServer() }
        }

        listBtn = Button(this).apply {
            isAllCaps = false
            textSize = 12.5f
            setTextColor(Color.WHITE)
            background = roundedBg("#37474F", dp(8).toFloat())
            setOnClickListener { showServerPickerDialog() }
        }

        actionRow.addView(findBtn, LinearLayout.LayoutParams(0, dp(42), 1.15f).apply { marginEnd = dp(4) })
        actionRow.addView(nextBtn, LinearLayout.LayoutParams(0, dp(42), 0.95f).apply { marginStart = dp(4); marginEnd = dp(4) })
        actionRow.addView(listBtn, LinearLayout.LayoutParams(0, dp(42), 1.25f).apply { marginStart = dp(4) })
        root.addView(actionRow)

        warpCheck = CheckBox(this).apply {
            text = "Поток #1 ИИ: Cloudflare WARP поверх прокси (чистый IP для Gemini)"
            isChecked = WarpManager.isWarpEnabled(this@MainActivity)
            setTextColor(Color.parseColor("#FFCC80"))
            textSize = 13f
            setOnCheckedChangeListener { _, _ -> savePrefs() }
        }
        root.addView(warpCheck)

        localDpiCheck = CheckBox(this).apply {
            text = "Поток #2 YouTube: нативный ByeDPI (быстрое видео без нагрузки на прокси)"
            isChecked = ServerScanner.isLocalDpiEnabled(this@MainActivity)
            setTextColor(Color.parseColor("#81D4FA"))
            textSize = 13f
            setOnCheckedChangeListener { _, _ -> savePrefs() }
        }
        root.addView(localDpiCheck)

        dpiProfileSpinner = Spinner(this).apply {
            val adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                LocalDpiProxy.PROFILE_NAMES
            )
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            this.adapter = adapter
            setSelection(ServerScanner.getDpiProfileIndex(this@MainActivity).coerceIn(0, LocalDpiProxy.PROFILE_NAMES.lastIndex))
            background = roundedBg("#25282C", dp(8).toFloat())
            setPadding(dp(8), dp(6), dp(8), dp(6))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    (view as? TextView)?.setTextColor(Color.WHITE)
                    savePrefs()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        root.addView(dpiProfileSpinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(42)
        ).apply { topMargin = dp(4); bottomMargin = dp(4) })

        tgDirectCheck = CheckBox(this).apply {
            text = "Поток #3 Telegram: напрямую (выкл. = отдельный сервер #3)"
            isChecked = ServerScanner.isTgDirectEnabled(this@MainActivity)
            setTextColor(Color.parseColor("#CE93D8"))
            textSize = 13f
            setOnCheckedChangeListener { _, _ -> savePrefs() }
        }
        root.addView(tgDirectCheck)

        smartBypassCheck = CheckBox(this).apply {
            text = "Разделение на 3 потока + FakeDNS (ИИ #1, YT #2, TG #3)"
            isChecked = ServerScanner.isSmartBypassEnabled(this@MainActivity)
            setTextColor(Color.parseColor("#A5D6A7"))
            textSize = 13f
            setOnCheckedChangeListener { _, _ -> savePrefs() }
        }
        root.addView(smartBypassCheck)

        aiCountryCheck = CheckBox(this).apply {
            text = "Фильтр под ИИ: исключить РФ, РБ, Китай, Иран и РФ-хостеров"
            isChecked = ServerScanner.isAiFilterEnabled(this@MainActivity)
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 13f
            setOnCheckedChangeListener { _, _ -> savePrefs() }
        }
        root.addView(aiCountryCheck)

        splitRuCheck = CheckBox(this).apply {
            text = "Поток #4 Direct: РФ (.ru), банки и Госуслуги напрямую"
            isChecked = ServerScanner.isSplitRuEnabled(this@MainActivity)
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 13f
            setOnCheckedChangeListener { _, _ -> savePrefs() }
        }
        root.addView(splitRuCheck)

        blockQuicCheck = CheckBox(this).apply {
            text = "Блокировать QUIC для Google и YouTube (стабильный TCP)"
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
                cm.setPrimaryClip(ClipData.newPlainText("VPN дяди Андрея Log", BypassVpnService.getDiagnosticsReport(this@MainActivity)))
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

    private fun switchToNextServer() {
        workingNodes = ServerScanner.loadWorkingList(this)
        if (workingNodes.size <= 1) {
            savePrefs()
            requestVpnAndRun(BypassVpnService.ACTION_RESCAN_AND_CONNECT)
            return
        }
        val idx = workingNodes.indexOfFirst { it.node.rawUri == selectedTested?.node?.rawUri }
        val nextIdx = if (idx >= 0) (idx + 1) % workingNodes.size else 0
        val chosen = workingNodes[nextIdx]
        selectedTested = chosen
        ServerScanner.saveSelectedNode(this, chosen)
        refreshSelectedLabel()
        BypassVpnService.log("Переключение потока #1 на #${nextIdx + 1}: ${chosen.shortTitle()} (${chosen.pingMs} мс)")
        requestVpnAndRun(BypassVpnService.ACTION_START, skipCheck = false)
    }

    private fun showServerPickerDialog() {
        workingNodes = ServerScanner.loadWorkingList(this)
        if (workingNodes.isEmpty()) {
            Toast.makeText(this, "Список пуст. Нажмите «Автопоиск»", Toast.LENGTH_SHORT).show()
            return
        }
        val items = workingNodes.mapIndexed { idx, tested ->
            tested.listItemLabel(idx, tested.node.rawUri == selectedTested?.node?.rawUri)
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Серверы (${workingNodes.size}) — #1 ИИ, #2 YT, #3 TG")
            .setItems(items) { _, which ->
                val chosen = workingNodes[which]
                selectedTested = chosen
                ServerScanner.saveSelectedNode(this, chosen)
                refreshSelectedLabel()
                BypassVpnService.log("Выбран поток #1 (${which + 1}): ${chosen.shortTitle()} (${chosen.pingMs} мс)")
                requestVpnAndRun(BypassVpnService.ACTION_START, skipCheck = false)
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun refreshSelectedLabel() {
        val current = selectedTested
        val idx = workingNodes.indexOfFirst { it.node.rawUri == current?.node?.rawUri }
        selectedServerView.text = if (current == null) {
            "Сервер не выбран. Нажмите «Автопоиск»."
        } else {
            val poolStr = if (idx >= 0 && workingNodes.isNotEmpty()) " [${idx + 1}/${workingNodes.size}]" else ""
            val pingStr = if (current.pingMs >= 0) " • ${current.pingMs} мс" else ""
            val cc = if (current.countryCode.isNotBlank()) "[${current.countryCode}] " else ""
            "Поток #1 (ИИ)$poolStr: $cc${current.node.name}\n" +
                "${current.capabilitiesTag()}${current.speedLabel()} • ${current.node.protocol}/${current.node.security}/${current.node.network}$pingStr"
        }
        val gemCount = workingNodes.count { it.hasGeminiAccess }
        listBtn.text = if (workingNodes.isNotEmpty()) {
            "Список (${workingNodes.size} | Gem:$gemCount)"
        } else {
            "Список (0)"
        }
        nextBtn.isEnabled = workingNodes.size > 1 && !BypassVpnService.isAutoSearching
    }

    private fun savePrefs() {
        getSharedPreferences(ServerScanner.PREFS_NAME, MODE_PRIVATE).edit()
            .putString("sub_url", subInput.text.toString().trim())
            .putBoolean("use_cf_warp", warpCheck.isChecked)
            .putBoolean("local_dpi", localDpiCheck.isChecked)
            .putInt("dpi_profile", dpiProfileSpinner.selectedItemPosition.coerceAtLeast(0))
            .putBoolean("tg_direct", tgDirectCheck.isChecked)
            .putBoolean("smart_bypass", smartBypassCheck.isChecked)
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
                    findBtn.text = "Автопоиск"
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

    private fun requestVpnAndRun(action: String, skipCheck: Boolean = false) {
        val prepIntent = VpnService.prepare(this)
        if (prepIntent != null) {
            pendingServiceAction = action
            pendingSkipCheck = skipCheck
            vpnPermissionLauncher.launch(prepIntent)
        } else {
            when (action) {
                BypassVpnService.ACTION_AUTO_CONNECT,
                BypassVpnService.ACTION_RESCAN_AND_CONNECT -> sendServiceAction(action)
                else -> startSelectedVpn(skipCheck)
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

    private fun startSelectedVpn(skipCheck: Boolean) {
        val tested = selectedTested ?: run {
            sendServiceAction(BypassVpnService.ACTION_RESCAN_AND_CONNECT)
            return
        }
        val intent = Intent(this, BypassVpnService::class.java).apply {
            action = BypassVpnService.ACTION_START
            putExtra(BypassVpnService.EXTRA_VLESS_URI, tested.node.rawUri)
            putExtra(BypassVpnService.EXTRA_SKIP_CHECK, skipCheck)
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
                statusView.text = "● ${BypassVpnService.searchStatusText.ifBlank { "поиск и проверка..." }}"
                statusView.setTextColor(Color.parseColor("#FFB74D"))
                toggleBtn.text = "ОТМЕНИТЬ"
                toggleBtn.background = roundedBg("#E65100", dp(12).toFloat())
                findBtn.isEnabled = false
                nextBtn.isEnabled = false
            }
            running -> {
                val srv = BypassVpnService.activeServerName.ifBlank { selectedTested?.shortTitle().orEmpty() }
                statusView.text = "● Подключено: $srv"
                statusView.setTextColor(Color.parseColor("#4CAF50"))
                toggleBtn.text = "ОСТАНОВИТЬ VPN"
                toggleBtn.background = roundedBg("#C62828", dp(12).toFloat())
                findBtn.isEnabled = true
                findBtn.text = "Автопоиск"
                nextBtn.isEnabled = workingNodes.size > 1
            }
            else -> {
                statusView.text = "○ Отключено"
                statusView.setTextColor(Color.parseColor("#9E9E9E"))
                toggleBtn.text = "ЗАПУСТИТЬ VPN"
                toggleBtn.background = roundedBg("#2E7D32", dp(12).toFloat())
                findBtn.isEnabled = true
                findBtn.text = "Автопоиск"
                nextBtn.isEnabled = workingNodes.size > 1
            }
        }
        toggleBtn.gravity = Gravity.CENTER
        val editable = !running && !searching
        warpCheck.isEnabled = editable
        localDpiCheck.isEnabled = editable
        dpiProfileSpinner.isEnabled = editable
        tgDirectCheck.isEnabled = editable
        smartBypassCheck.isEnabled = editable
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

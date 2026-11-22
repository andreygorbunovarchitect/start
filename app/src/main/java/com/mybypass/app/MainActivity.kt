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
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var toggleBtn: Button
    private lateinit var dns1Input: EditText
    private lateinit var dns2Input: EditText
    private lateinit var ipv6Check: CheckBox
    private lateinit var argsInput: EditText
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private val dnsPresets = listOf(
        Triple("Comss (ИИ+Google)", "83.220.169.155", "212.109.195.93"),
        Triple("Резерв ИИ", "176.99.11.77", "80.78.247.254"),
        Triple("Cloudflare (Обычный)", "1.1.1.1", "1.0.0.1")
    )

    private val dpiPresets = listOf(
        "SmartDNS + ТСПУ" to "-o1 -Ar -o1 -At -f-1 -r1+s -As -s1",
        "Split + TLS" to "-s1 -r1+s",
        "OOB + Disorder" to "-o1 -d1 -r1+s",
        "ТСПУ + UDP Fake" to "-o1 -Ar -o1 -At -f-1 -r1+s -As -s1 -a1"
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

        val prefs = getSharedPreferences("bypass_prefs", MODE_PRIVATE)
        val savedArgs = prefs.getString("args", dpiPresets[0].second) ?: dpiPresets[0].second
        val savedDns1 = prefs.getString("dns1", dnsPresets[0].second) ?: dnsPresets[0].second
        val savedDns2 = prefs.getString("dns2", dnsPresets[0].third) ?: dnsPresets[0].third
        val savedBlockIpv6 = prefs.getBoolean("block_ipv6", true)

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
            text = "MyBypassDPI + Smart DNS"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        root.addView(title)

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(4), 0, dp(14))
        }
        root.addView(statusView)

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
                    requestVpnAndStart()
                }
            }
        }
        root.addView(toggleBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(sectionLabel("Smart DNS (для Gemini, Flow, Nano Banana, GPT):"))

        val dnsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val dnsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dnsPresets.forEach { (name, d1, d2) ->
            dnsRow.addView(createChip(name) {
                dns1Input.setText(d1)
                dns2Input.setText(d2)
                savePrefs()
            })
        }
        dnsScroll.addView(dnsRow)
        root.addView(dnsScroll)

        val dnsInputsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        dns1Input = createInput(savedDns1, "Основной DNS")
        dns2Input = createInput(savedDns2, "Запасной DNS")

        dnsInputsRow.addView(dns1Input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(6)
        })
        dnsInputsRow.addView(dns2Input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6)
        })
        root.addView(dnsInputsRow)

        ipv6Check = CheckBox(this).apply {
            text = "Блокировать утечки IPv6 (обязательно для Google / ИИ)"
            isChecked = savedBlockIpv6
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 13f
            setPadding(0, dp(6), 0, dp(4))
            setOnCheckedChangeListener { _, _ -> savePrefs() }
        }
        root.addView(ipv6Check)

        root.addView(sectionLabel("Стратегия обхода DPI (ciadpi):"))

        val dpiScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val dpiRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dpiPresets.forEach { (name, cmd) ->
            dpiRow.addView(createChip(name) {
                argsInput.setText(cmd)
                savePrefs()
            })
        }
        dpiScroll.addView(dpiRow)
        root.addView(dpiScroll)

        argsInput = createInput(savedArgs, "-o1 -Ar -o1 -At -f-1 -r1+s -As -s1")
        root.addView(argsInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val toolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, dp(8))
        }

        val testBtn = Button(this).apply {
            text = "Тест ИИ / DNS"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.WHITE)
            background = roundedBg("#1565C0", dp(8).toFloat())
            setOnClickListener { runAiCheck() }
        }

        val copyBtn = Button(this).apply {
            text = "Копировать лог"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.WHITE)
            background = roundedBg("#333333", dp(8).toFloat())
            setOnClickListener {
                val report = BypassVpnService.getDiagnosticsReport(this@MainActivity)
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("MyBypassDPI Log", report))
                Toast.makeText(this@MainActivity, "Лог скопирован", Toast.LENGTH_SHORT).show()
            }
        }

        toolsRow.addView(testBtn, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(6) })
        toolsRow.addView(copyBtn, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(6) })
        root.addView(toolsRow)

        val hintNote = TextView(this).apply {
            text = "Важно: в настройках Android «Частный DNS» должен быть Откл. (или dns.comss.one)."
            textSize = 11f
            setTextColor(Color.parseColor("#FFB74D"))
            setPadding(0, dp(2), 0, dp(8))
        }
        root.addView(hintNote)

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
            dp(260)
        ))

        setContentView(outerScroll)
        updateUi(BypassVpnService.isRunning)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun runAiCheck() {
        BypassVpnService.log("--- Проверка доступности ИИ ---")
        val targets = listOf(
            "gemini.google.com",
            "labs.google",
            "chatgpt.com"
        )
        Thread {
            for (host in targets) {
                try {
                    val ips = InetAddress.getAllByName(host).joinToString(",") { it.hostAddress ?: "?" }
                    val conn = (URL("https://$host/").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5000
                        readTimeout = 5000
                        instanceFollowRedirects = false
                        requestMethod = "HEAD"
                        setRequestProperty("User-Agent", "Mozilla/5.0")
                    }
                    val code = conn.responseCode
                    conn.disconnect()
                    BypassVpnService.log("OK $host -> HTTP $code (IP: $ips)")
                } catch (e: Exception) {
                    BypassVpnService.log("FAIL $host -> ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }.start()
    }

    private fun savePrefs() {
        getSharedPreferences("bypass_prefs", MODE_PRIVATE).edit()
            .putString("args", argsInput.text.toString())
            .putString("dns1", dns1Input.text.toString().trim())
            .putString("dns2", dns2Input.text.toString().trim())
            .putBoolean("block_ipv6", ipv6Check.isChecked)
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
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, BypassVpnService::class.java).apply {
            action = BypassVpnService.ACTION_START
            putExtra(BypassVpnService.EXTRA_ARGS, argsInput.text.toString())
            putExtra(BypassVpnService.EXTRA_DNS1, dns1Input.text.toString().trim())
            putExtra(BypassVpnService.EXTRA_DNS2, dns2Input.text.toString().trim())
            putExtra(BypassVpnService.EXTRA_BLOCK_IPV6, ipv6Check.isChecked)
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
            statusView.text = "● Подключено (ByeDPI + Smart DNS)"
            statusView.setTextColor(Color.parseColor("#4CAF50"))
            toggleBtn.text = "ОСТАНОВИТЬ"
            toggleBtn.background = roundedBg("#C62828", dp(12).toFloat())
        } else {
            statusView.text = "○ Отключено"
            statusView.setTextColor(Color.parseColor("#9E9E9E"))
            toggleBtn.text = "ЗАПУСТИТЬ ОБХОД"
            toggleBtn.background = roundedBg("#2E7D32", dp(12).toFloat())
        }
        toggleBtn.gravity = Gravity.CENTER
        dns1Input.isEnabled = !running
        dns2Input.isEnabled = !running
        ipv6Check.isEnabled = !running
        argsInput.isEnabled = !running
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#AAAAAA"))
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun createInput(initial: String, hintText: String): EditText = EditText(this).apply {
        setText(initial)
        hint = hintText
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        typeface = Typeface.MONOSPACE
        textSize = 13f
        isSingleLine = true
        background = roundedBg("#1E1E1E", dp(8).toFloat())
        setPadding(dp(10), dp(10), dp(10), dp(10))
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

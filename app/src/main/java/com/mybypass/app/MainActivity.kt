package com.mybypass.app

import android.Manifest
import android.app.Activity
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
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var toggleBtn: Button
    private lateinit var argsInput: EditText
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private val presets = listOf(
        "Split + TLS" to "-s1 -r1+s -a1",
        "OOB + Disorder" to "-o1 -d1 -r1+s -a1",
        "Auto ТСПУ" to "-o1 -Ar -o1 -At -f-1 -r1+s -As -s1 -a1",
        "Fake TTL 5" to "-f-1 -t5 -s1 -r1+s -a2"
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
    ) { /* Работает даже при отказе, но уведомление удобнее видеть */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("bypass_prefs", MODE_PRIVATE)
        val savedArgs = prefs.getString("args", presets[2].second) ?: presets[2].second

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(dp(16), dp(36), dp(16), dp(16))
        }

        val title = TextView(this).apply {
            text = "MyBypassDPI"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        root.addView(title)

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(4), 0, dp(16))
        }
        root.addView(statusView)

        toggleBtn = Button(this).apply {
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener {
                if (BypassVpnService.isRunning) {
                    stopVpnService()
                } else {
                    prefs.edit().putString("args", argsInput.text.toString()).apply()
                    requestVpnAndStart()
                }
            }
        }
        root.addView(toggleBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val presetsLabel = TextView(this).apply {
            text = "Пресеты стратегий:"
            textSize = 13f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, dp(18), 0, dp(6))
        }
        root.addView(presetsLabel)

        val presetScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presets.forEach { (name, cmd) ->
            val chip = Button(this).apply {
                text = name
                isAllCaps = false
                textSize = 12f
                setTextColor(Color.WHITE)
                background = roundedBg("#262626", dp(8).toFloat())
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener {
                    argsInput.setText(cmd)
                    prefs.edit().putString("args", cmd).apply()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            presetRow.addView(chip, lp)
        }
        presetScroll.addView(presetRow)
        root.addView(presetScroll)

        val argsLabel = TextView(this).apply {
            text = "Аргументы ByeDPI (ciadpi):"
            textSize = 13f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, dp(14), 0, dp(6))
        }
        root.addView(argsLabel)

        argsInput = EditText(this).apply {
            setText(savedArgs)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "-s1 -r1+s -a1"
            typeface = Typeface.MONOSPACE
            textSize = 13f
            background = roundedBg("#1E1E1E", dp(8).toFloat())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(argsInput)

        val logLabel = TextView(this).apply {
            text = "Лог работы:"
            textSize = 13f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, dp(16), 0, dp(6))
        }
        root.addView(logLabel)

        logView = TextView(this).apply {
            text = BypassVpnService.getLogs().ifEmpty { "Готово к запуску." }
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
            0,
            1f
        ))

        setContentView(root)
        updateUi(BypassVpnService.isRunning)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
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
            statusView.text = "● Подключено (локальный туннель)"
            statusView.setTextColor(Color.parseColor("#4CAF50"))
            toggleBtn.text = "ОСТАНОВИТЬ"
            toggleBtn.background = roundedBg("#C62828", dp(12).toFloat())
            argsInput.isEnabled = false
        } else {
            statusView.text = "○ Отключено"
            statusView.setTextColor(Color.parseColor("#9E9E9E"))
            toggleBtn.text = "ЗАПУСТИТЬ ОБХОД"
            toggleBtn.background = roundedBg("#2E7D32", dp(12).toFloat())
            argsInput.isEnabled = true
        }
        toggleBtn.gravity = Gravity.CENTER
    }

    private fun roundedBg(hex: String, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(hex))
            cornerRadius = radius
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

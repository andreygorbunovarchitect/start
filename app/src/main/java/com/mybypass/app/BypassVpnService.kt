package com.mybypass.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import hev.htproxy.TProxyService
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BypassVpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null
    private var dpiProcess: Process? = null
    private var tproxyStarted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val rawArgs = intent.getStringExtra(EXTRA_ARGS).orEmpty()
                startTunnel(rawArgs)
            }
            ACTION_STOP -> stopTunnel()
        }
        return START_STICKY
    }

    private fun startTunnel(rawArgs: String) {
        if (isRunning) return

        startForegroundNotification()
        log("Запуск локального прокси ByeDPI...")

        val binFile = File(applicationInfo.nativeLibraryDir, "libciadpi.so")
        if (!binFile.exists()) {
            log("ОШИБКА: не найден бинарник ${binFile.absolutePath}")
            stopTunnel()
            return
        }

        val userArgs = rawArgs.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val command = mutableListOf(
            binFile.absolutePath,
            "--ip", "127.0.0.1",
            "--port", "1080"
        )
        command.addAll(userArgs)
        log("Команда: ciadpi ${userArgs.joinToString(" ")}")

        try {
            dpiProcess = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            // Обязательно читаем поток вывода, чтобы буфер процесса не переполнился
            Thread {
                try {
                    BufferedReader(InputStreamReader(dpiProcess!!.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            log("[ciadpi] $line")
                        }
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true }.start()

            Thread.sleep(250)
            if (dpiProcess?.isAlive != true) {
                val code = dpiProcess?.exitValue()
                log("ОШИБКА: ciadpi завершился сразу (код $code). Проверьте аргументы.")
                stopTunnel()
                return
            }
        } catch (e: Exception) {
            log("ОШИБКА запуска ciadpi: ${e.message}")
            stopTunnel()
            return
        }

        val builder = Builder()
            .setSession("MyBypassDPI")
            .setMtu(8500)
            .addAddress("10.10.10.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")

        try {
            // Исключаем себя из VPN, чтобы избежать петли трафика
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            log("Предупреждение addDisallowedApplication: ${e.message}")
        }

        tunInterface = builder.establish()
        if (tunInterface == null) {
            log("ОШИБКА: система не выдала TUN-интерфейс")
            stopTunnel()
            return
        }

        val configFile = File(cacheDir, "tproxy.yml")
        configFile.writeText(
            """
            tunnel:
              mtu: 8500
            socks5:
              port: 1080
              address: 127.0.0.1
              udp: 'udp'
            misc:
              task-stack-size: 81920
              connect-timeout: 5000
              read-write-timeout: 60000
              log-level: warn
            """.trimIndent()
        )

        try {
            TProxyService.TProxyStartService(configFile.absolutePath, tunInterface!!.fd)
            tproxyStarted = true
            isRunning = true
            notifyState()
            log("Туннель активен (127.0.0.1:1080 -> TUN).")
        } catch (e: Throwable) {
            log("ОШИБКА hev-socks5-tunnel: ${e.message}")
            stopTunnel()
        }
    }

    private fun stopTunnel() {
        if (tproxyStarted) {
            try {
                TProxyService.TProxyStopService()
            } catch (_: Throwable) {
            }
            tproxyStarted = false
        }

        try {
            dpiProcess?.destroy()
        } catch (_: Exception) {
        }
        dpiProcess = null

        try {
            tunInterface?.close()
        } catch (_: Exception) {
        }
        tunInterface = null

        isRunning = false
        notifyState()
        log("Туннель остановлен.")

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundNotification() {
        val channelId = "mybypass_vpn_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "MyBypassDPI",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = notificationBuilder
            .setContentTitle("MyBypassDPI активен")
            .setContentText("Локальный обход DPI работает")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onRevoke() {
        log("VPN отозван системой.")
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (isRunning || tunInterface != null || dpiProcess != null) {
            stopTunnel()
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.mybypass.app.START"
        const val ACTION_STOP = "com.mybypass.app.STOP"
        const val EXTRA_ARGS = "extra_args"

        @Volatile
        var isRunning: Boolean = false
            private set

        private val logLines = ArrayDeque<String>()
        var onStateListener: ((Boolean) -> Unit)? = null
        var onLogListener: ((String) -> Unit)? = null

        @Synchronized
        fun getLogs(): String = logLines.joinToString("\n")

        @Synchronized
        fun log(msg: String) {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val entry = "[$time] $msg"
            logLines.addLast(entry)
            while (logLines.size > 80) logLines.removeFirst()
            onLogListener?.invoke(getLogs())
        }

        private fun notifyState() {
            onStateListener?.invoke(isRunning)
        }
    }
}

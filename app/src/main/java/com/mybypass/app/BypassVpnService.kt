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
import java.util.concurrent.Executors

class BypassVpnService : VpnService() {

    private val executor = Executors.newSingleThreadExecutor()
    private var tunInterface: ParcelFileDescriptor? = null
    private var dpiProcess: Process? = null
    private var tproxyStarted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val rawArgs = intent.getStringExtra(EXTRA_ARGS).orEmpty()
                val dns1 = intent.getStringExtra(EXTRA_DNS1).orEmpty().trim()
                val dns2 = intent.getStringExtra(EXTRA_DNS2).orEmpty().trim()
                val blockIpv6 = intent.getBooleanExtra(EXTRA_BLOCK_IPV6, true)

                startForegroundNotification(dns1.ifEmpty { DEFAULT_DNS1 })
                executor.execute {
                    startTunnel(rawArgs, dns1, dns2, blockIpv6)
                }
            }
            ACTION_STOP -> {
                executor.execute {
                    stopTunnel()
                }
            }
        }
        return START_STICKY
    }

    private fun startTunnel(rawArgs: String, dns1: String, dns2: String, blockIpv6: Boolean) {
        if (isRunning) return

        log("Запуск ByeDPI + Smart DNS...")

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
            val proc = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            dpiProcess = proc

            Thread {
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            log("[ciadpi] $line")
                        }
                    }
                    val exitCode = proc.waitFor()
                    if (isRunning) {
                        log("ВНИМАНИЕ: ciadpi неожиданно завершился (код $exitCode)")
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true }.start()

            Thread.sleep(300)
            if (!proc.isAlive) {
                log("ОШИБКА: ciadpi закрылся при старте (код ${proc.exitValue()}). Проверьте флаги.")
                stopTunnel()
                return
            }
        } catch (e: Throwable) {
            log("ОШИБКА запуска ciadpi: ${e.javaClass.simpleName}: ${e.message}")
            stopTunnel()
            return
        }

        val builder = Builder()
            .setSession("MyBypassDPI")
            .setMtu(8500)
            .addAddress("10.10.10.2", 32)
            .addRoute("0.0.0.0", 0)

        // Захватываем IPv6 в туннель без IPv6-выхода -> приложения Google падают в IPv4 Smart DNS
        if (blockIpv6) {
            try {
                builder.addAddress("fd00:10:10::2", 128)
                builder.addRoute("::", 0)
                log("IPv6 leak-block: ВКЛ")
            } catch (e: Exception) {
                log("Предупреждение IPv6: ${e.message}")
            }
        }

        val primaryDns = dns1.ifEmpty { DEFAULT_DNS1 }
        var dnsAdded = false
        for (dns in listOf(primaryDns, dns2).filter { it.isNotEmpty() }.distinct()) {
            try {
                builder.addDnsServer(dns)
                dnsAdded = true
                log("Smart DNS: $dns")
            } catch (e: Exception) {
                log("Некорректный DNS '$dns': ${e.message}")
            }
        }
        if (!dnsAdded) {
            builder.addDnsServer(DEFAULT_DNS1)
            log("Smart DNS (fallback): $DEFAULT_DNS1")
        }

        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            log("Предупреждение addDisallowedApplication: ${e.message}")
        }

        tunInterface = try {
            builder.establish()
        } catch (e: Throwable) {
            log("ОШИБКА builder.establish(): ${e.javaClass.simpleName}: ${e.message}")
            null
        }

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
              ipv4: 10.10.10.2
            socks5:
              port: 1080
              address: 127.0.0.1
              udp: 'udp'
            misc:
              task-stack-size: 86016
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
            log("Туннель активен! (TUN fd=${tunInterface!!.fd} -> 127.0.0.1:1080)")
        } catch (e: Throwable) {
            log("ОШИБКА hev-socks5-tunnel: ${e.javaClass.simpleName}: ${e.message}")
            stopTunnel()
        }
    }

    private fun stopTunnel() {
        if (tproxyStarted) {
            try {
                TProxyService.TProxyStopService()
            } catch (e: Throwable) {
                log("Ошибка остановки TProxy: ${e.message}")
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

    private fun startForegroundNotification(activeDns: String) {
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
            .setContentText("Smart DNS: $activeDns")
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
        log("VPN отозван системой (возможно, активен другой VPN).")
        executor.execute { stopTunnel() }
        super.onRevoke()
    }

    override fun onDestroy() {
        executor.execute {
            if (isRunning || tunInterface != null || dpiProcess != null) {
                stopTunnel()
            }
        }
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.mybypass.app.START"
        const val ACTION_STOP = "com.mybypass.app.STOP"
        const val EXTRA_ARGS = "extra_args"
        const val EXTRA_DNS1 = "extra_dns1"
        const val EXTRA_DNS2 = "extra_dns2"
        const val EXTRA_BLOCK_IPV6 = "extra_block_ipv6"
        const val DEFAULT_DNS1 = "83.220.169.155"

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
            while (logLines.size > 100) logLines.removeFirst()
            onLogListener?.invoke(getLogs())
        }

        private fun notifyState() {
            onStateListener?.invoke(isRunning)
        }
    }
}

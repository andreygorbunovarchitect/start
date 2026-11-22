package com.mybypass.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import hev.htproxy.TProxyService
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class BypassVpnService : VpnService() {

    private val executor = Executors.newSingleThreadExecutor()
    private var tunInterface: ParcelFileDescriptor? = null
    private var dpiProcess: Process? = null
    private var tproxyStarted = false

    override fun onCreate() {
        super.onCreate()
        initLogger(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        initLogger(applicationContext)
        when (intent?.action) {
            ACTION_START -> {
                val rawArgs = intent.getStringExtra(EXTRA_ARGS).orEmpty()
                val dns1 = intent.getStringExtra(EXTRA_DNS1).orEmpty().trim()
                val dns2 = intent.getStringExtra(EXTRA_DNS2).orEmpty().trim()
                val blockIpv6 = intent.getBooleanExtra(EXTRA_BLOCK_IPV6, true)

                clearLog()
                log("[STEP 0] Получена команда START, поднимаю ForegroundService...")
                try {
                    startForegroundNotification(dns1.ifEmpty { DEFAULT_DNS1 })
                } catch (e: Throwable) {
                    log("ОШИБКА startForeground: ${e.javaClass.simpleName}: ${e.message}")
                    stopSelf()
                    return START_NOT_STICKY
                }

                executor.execute {
                    try {
                        startTunnel(rawArgs, dns1, dns2, blockIpv6)
                    } catch (e: Throwable) {
                        log("ОШИБКА в startTunnel: ${Log.getStackTraceString(e)}")
                        stopTunnel()
                    }
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

        log("[STEP 1] Проверка бинарника libciadpi.so...")
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
        log("[STEP 2] Запуск ciadpi: ${userArgs.joinToString(" ")}")

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
                        log("ВНИМАНИЕ: ciadpi завершился (код $exitCode)")
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true }.start()

            Thread.sleep(300)
            if (!proc.isAlive) {
                log("ОШИБКА: ciadpi закрылся сразу (код ${proc.exitValue()}).")
                stopTunnel()
                return
            }
            log("[STEP 2 OK] ciadpi запущен на 127.0.0.1:1080")
        } catch (e: Throwable) {
            log("ОШИБКА запуска ciadpi: ${e.javaClass.simpleName}: ${e.message}")
            stopTunnel()
            return
        }

        log("[STEP 3] Настройка VpnService.Builder (blockIpv6=$blockIpv6)...")
        val builder = Builder()
            .setSession("MyBypassDPI")
            .setMtu(8500)
            .addAddress("10.10.10.2", 32)
            .addRoute("0.0.0.0", 0)

        if (blockIpv6) {
            try {
                builder.addAddress("fc00::1", 128)
                builder.addRoute("::", 0)
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

        val tunFd = tunInterface!!.fd
        log("[STEP 3 OK] TUN создан (fd=$tunFd)")

        log("[STEP 4] Запись tproxy.yml (task-stack-size=131072)...")
        val tproxyLogFile = File(filesDir, TPROXY_LOG_NAME)
        try {
            tproxyLogFile.writeText("")
        } catch (_: Exception) {
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
              task-stack-size: 131072
              connect-timeout: 5000
              read-write-timeout: 60000
              log-file: ${tproxyLogFile.absolutePath}
              log-level: debug
            """.trimIndent()
        )

        log("[STEP 5] Вызов TProxyService.TProxyStartService(${configFile.absolutePath}, $tunFd)...")
        try {
            TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
            tproxyStarted = true
            isRunning = true
            notifyState()
            log("[STEP 6 OK] Туннель активен! (TUN fd=$tunFd -> 127.0.0.1:1080)")
        } catch (e: Throwable) {
            log("ОШИБКА hev-socks5-tunnel: ${Log.getStackTraceString(e)}")
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

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
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
        log("VPN отозван системой.")
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

        private const val LOG_FILE_NAME = "last_log.txt"
        private const val TPROXY_LOG_NAME = "tproxy.log"

        @Volatile
        var isRunning: Boolean = false
            private set

        private var appFilesDir: File? = null
        private val crashHandlerInstalled = AtomicBoolean(false)
        private val logLines = ArrayDeque<String>()

        var onStateListener: ((Boolean) -> Unit)? = null
        var onLogListener: ((String) -> Unit)? = null

        @Synchronized
        fun initLogger(context: Context) {
            val dir = context.applicationContext.filesDir
            if (appFilesDir == null) {
                appFilesDir = dir
                val file = File(dir, LOG_FILE_NAME)
                if (file.exists() && logLines.isEmpty()) {
                    try {
                        file.readLines().takeLast(120).forEach { logLines.addLast(it) }
                    } catch (_: Exception) {
                    }
                }
            }
            if (crashHandlerInstalled.compareAndSet(false, true)) {
                val prev = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                    try {
                        log("FATAL JAVA CRASH [${thread.name}]:\n${Log.getStackTraceString(throwable)}")
                    } catch (_: Throwable) {
                    }
                    prev?.uncaughtException(thread, throwable)
                }
            }
        }

        @Synchronized
        fun clearLog() {
            logLines.clear()
            try {
                appFilesDir?.let { File(it, LOG_FILE_NAME).writeText("") }
            } catch (_: Exception) {
            }
            onLogListener?.invoke("")
        }

        @Synchronized
        fun getLogs(): String = logLines.joinToString("\n")

        @Synchronized
        fun getDiagnosticsReport(context: Context): String {
            initLogger(context)
            val sb = StringBuilder()
            val saved = getLogs()
            if (saved.isNotEmpty()) {
                sb.append(saved).append("\n")
            }

            // Внутренний лог hev-socks5-tunnel (если успел записаться)
            val tproxyFile = File(context.filesDir, TPROXY_LOG_NAME)
            if (tproxyFile.exists()) {
                val tContent = try { tproxyFile.readText().trim() } catch (_: Exception) { "" }
                if (tContent.isNotEmpty()) {
                    sb.append("\n--- tproxy.log ---\n")
                    sb.append(tContent.takeLast(1500)).append("\n")
                }
            }

            // Системная причина последнего вылета Android (включая нативные SIGSEGV/SIGABRT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, 3)
                    val crashInfo = reasons.firstOrNull {
                        it.reason == ApplicationExitInfo.REASON_CRASH ||
                            it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                            it.reason == ApplicationExitInfo.REASON_ANR ||
                            it.reason == ApplicationExitInfo.REASON_SIGNALED
                    }
                    if (crashInfo != null) {
                        val reasonName = when (crashInfo.reason) {
                            ApplicationExitInfo.REASON_CRASH -> "JAVA_CRASH"
                            ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
                            ApplicationExitInfo.REASON_ANR -> "ANR"
                            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                            else -> "CODE_${crashInfo.reason}"
                        }
                        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                            .format(Date(crashInfo.timestamp))
                        sb.append("\n--- Android ExitInfo ($timeStr) ---\n")
                        sb.append("Reason: $reasonName | status=${crashInfo.status}\n")
                        sb.append("Desc: ${crashInfo.description}\n")
                        try {
                            crashInfo.traceInputStream?.bufferedReader()?.use { reader ->
                                val trace = reader.readText().take(1200)
                                if (trace.isNotBlank()) {
                                    sb.append("Trace:\n").append(trace).append("\n")
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                }
            }

            return sb.toString().trim().ifEmpty { "Готово к запуску." }
        }

        @Synchronized
        fun log(msg: String) {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val entry = "[$time] $msg"
            logLines.addLast(entry)
            while (logLines.size > 120) logLines.removeFirst()

            // Синхронно сбрасываем на диск, чтобы лог выжил даже при SIGSEGV/SIGABRT
            try {
                appFilesDir?.let { dir ->
                    FileOutputStream(File(dir, LOG_FILE_NAME), true).use { fos ->
                        fos.write("$entry\n".toByteArray(Charsets.UTF_8))
                        fos.flush()
                        fos.fd.sync()
                    }
                }
            } catch (_: Exception) {
            }

            onLogListener?.invoke(getLogs())
        }

        private fun notifyState() {
            onStateListener?.invoke(isRunning)
        }
    }
}

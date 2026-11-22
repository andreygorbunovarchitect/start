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
    private var xrayProcess: Process? = null
    private var tproxyStarted = false

    override fun onCreate() {
        super.onCreate()
        initLogger(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        initLogger(applicationContext)
        when (intent?.action) {
            ACTION_START -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON).orEmpty()
                val serverName = intent.getStringExtra(EXTRA_SERVER_NAME).orEmpty().ifBlank { "VLESS" }
                val blockIpv6 = intent.getBooleanExtra(EXTRA_BLOCK_IPV6, false)

                if (!ensureForeground("Подключение: $serverName")) return START_NOT_STICKY
                executor.execute {
                    try {
                        stopTunnelInternal(stopService = false)
                        startTunnel(configJson, serverName, blockIpv6)
                    } catch (e: Throwable) {
                        log("ОШИБКА startTunnel: ${Log.getStackTraceString(e)}")
                        stopTunnelInternal(stopService = true)
                    }
                }
            }

            ACTION_AUTO_CONNECT -> {
                if (!ensureForeground("Автоподбор лучшего сервера...")) return START_NOT_STICKY
                isAutoSearching = true
                searchStatusText = "Проверка сервера..."
                notifyState(applicationContext)

                executor.execute {
                    handleAutoConnect(forceRescan = false)
                }
            }

            ACTION_RESCAN_AND_CONNECT -> {
                if (!ensureForeground("Поиск лучшего сервера (ИИ + все приложения)...")) return START_NOT_STICKY
                isAutoSearching = true
                searchStatusText = "Загрузка баз..."
                notifyState(applicationContext)

                executor.execute {
                    handleAutoConnect(forceRescan = true)
                }
            }

            ACTION_STOP -> {
                ServerScanner.cancelScan()
                executor.execute {
                    isAutoSearching = false
                    searchStatusText = ""
                    stopTunnelInternal(stopService = true)
                }
            }
        }
        return START_STICKY
    }

    private fun handleAutoConnect(forceRescan: Boolean) {
        try {
            stopTunnelInternal(stopService = false)

            var chosen: TestedNode? = null

            if (!forceRescan) {
                val savedList = ServerScanner.loadWorkingList(applicationContext)
                val preferred = ServerScanner.loadSelectedNode(applicationContext) ?: savedList.firstOrNull()
                if (preferred != null) {
                    searchStatusText = "Проверка ${preferred.node.name.take(14)}..."
                    updateNotification("Проверка: ${preferred.node.name.take(24)}")
                    notifyState(applicationContext)
                    log("Быстрая проверка сохранённого сервера: ${preferred.node.name}...")

                    val rechecked = ServerScanner.verifySingleNode(applicationContext, preferred.node, 10825)
                    if (rechecked != null && rechecked.priorityTier() <= 1) {
                        log("Сервер жив и поддерживает ИИ+приложения: ${rechecked.shortTitle()} (${rechecked.pingMs}мс)")
                        chosen = rechecked
                        ServerScanner.saveSelectedNode(applicationContext, rechecked)
                    } else {
                        log("Сохранённый сервер не прошёл проверку -> запускаю свежий автопоиск...")
                    }
                }
            }

            if (chosen == null) {
                val prevListener = ServerScanner.onProgressListener
                ServerScanner.onProgressListener = { progress ->
                    searchStatusText = progress
                    if (progress.isNotBlank()) {
                        updateNotification(progress)
                    }
                    notifyState(applicationContext)
                    prevListener?.invoke(progress)
                }
                val found = try {
                    ServerScanner.scanAll(applicationContext)
                } finally {
                    ServerScanner.onProgressListener = prevListener
                }
                chosen = found.firstOrNull()
            }

            isAutoSearching = false
            searchStatusText = ""

            if (chosen == null) {
                log("Автопоиск не нашёл рабочих серверов.")
                stopTunnelInternal(stopService = true)
                return
            }

            val configJson = VlessParser.buildXrayConfig(
                node = chosen.node,
                socksPort = 1080,
                splitRu = ServerScanner.isSplitRuEnabled(applicationContext),
                blockQuic = ServerScanner.isBlockQuicEnabled(applicationContext),
                isTestMode = false
            )
            val label = chosen.shortTitle()
            updateNotification(label)
            startTunnel(configJson, label, ServerScanner.isBlockIpv6Enabled(applicationContext))
        } catch (e: Throwable) {
            isAutoSearching = false
            searchStatusText = ""
            log("ОШИБКА автоподключения: ${Log.getStackTraceString(e)}")
            stopTunnelInternal(stopService = true)
        }
    }

    private fun startTunnel(configJson: String, serverName: String, blockIpv6: Boolean) {
        if (configJson.isBlank()) {
            log("ОШИБКА: пустая конфигурация Xray.")
            stopTunnelInternal(stopService = true)
            return
        }

        val xrayBin = File(applicationInfo.nativeLibraryDir, "libxray.so")
        if (!xrayBin.exists()) {
            log("ОШИБКА: не найден бинарник ${xrayBin.absolutePath}")
            stopTunnelInternal(stopService = true)
            return
        }

        val xrayConfigFile = File(cacheDir, "xray_config.json")
        xrayConfigFile.writeText(configJson)

        try {
            val proc = ProcessBuilder(
                xrayBin.absolutePath,
                "run",
                "-c",
                xrayConfigFile.absolutePath
            )
                .redirectErrorStream(true)
                .start()
            xrayProcess = proc

            Thread {
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            log("[xray] $line")
                        }
                    }
                    val exitCode = proc.waitFor()
                    if (isRunning) {
                        log("ВНИМАНИЕ: Xray завершился (код $exitCode)")
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true }.start()

            Thread.sleep(350)
            if (!proc.isAlive) {
                log("ОШИБКА: Xray не запустился (код ${proc.exitValue()}).")
                stopTunnelInternal(stopService = true)
                return
            }
        } catch (e: Throwable) {
            log("ОШИБКА запуска Xray: ${e.javaClass.simpleName}: ${e.message}")
            stopTunnelInternal(stopService = true)
            return
        }

        // Стандартный MTU 1500 для стабильной работы UDP-звонков в Telegram и WhatsApp
        val builder = Builder()
            .setSession("MyBypassXray")
            .setMtu(1500)
            .addAddress("10.10.10.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")

        if (blockIpv6) {
            try {
                builder.addAddress("fc00::1", 128)
                builder.addRoute("::", 0)
            } catch (e: Exception) {
                log("Предупреждение IPv6: ${e.message}")
            }
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
            stopTunnelInternal(stopService = true)
            return
        }

        val tunFd = tunInterface!!.fd
        val tproxyConfig = File(cacheDir, "tproxy.yml")
        tproxyConfig.writeText(
            """
            tunnel:
              mtu: 1500
              ipv4: 10.10.10.2
            socks5:
              port: 1080
              address: 127.0.0.1
              udp: 'udp'
            misc:
              task-stack-size: 131072
              udp-copy-buffer-nums: 20
              connect-timeout: 10000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 120000
              log-level: warn
            """.trimIndent()
        )

        try {
            TProxyService.TProxyStartService(tproxyConfig.absolutePath, tunFd)
            tproxyStarted = true
            isRunning = true
            activeServerName = serverName
            updateNotification(serverName)
            notifyState(applicationContext)
            log("VPN активен! Сервер: $serverName")
        } catch (e: Throwable) {
            log("ОШИБКА hev-socks5-tunnel: ${Log.getStackTraceString(e)}")
            stopTunnelInternal(stopService = true)
        }
    }

    private fun stopTunnelInternal(stopService: Boolean) {
        if (tproxyStarted) {
            try {
                TProxyService.TProxyStopService()
            } catch (_: Throwable) {
            }
            tproxyStarted = false
        }

        try {
            xrayProcess?.destroy()
        } catch (_: Exception) {
        }
        xrayProcess = null

        try {
            tunInterface?.close()
        } catch (_: Exception) {
        }
        tunInterface = null

        val wasRunning = isRunning
        isRunning = false
        activeServerName = ""

        if (stopService) {
            isAutoSearching = false
            searchStatusText = ""
            if (wasRunning) log("VPN остановлен.")
            notifyState(applicationContext)
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
            }
            stopSelf()
        } else {
            notifyState(applicationContext)
        }
    }

    private fun ensureForeground(text: String): Boolean {
        return try {
            val notification = buildNotification(text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notification)
            }
            true
        } catch (e: Throwable) {
            log("ОШИБКА startForeground: ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
            false
        }
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(1, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(text: String): Notification {
        val channelId = "mybypass_vpn_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "MyBypassXray",
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

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(if (isAutoSearching) "MyBypass — Автоподбор" else "MyBypass Xray активен")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    override fun onRevoke() {
        log("VPN отозван системой.")
        ServerScanner.cancelScan()
        executor.execute { stopTunnelInternal(stopService = true) }
        super.onRevoke()
    }

    override fun onDestroy() {
        ServerScanner.cancelScan()
        executor.execute {
            if (isRunning || tunInterface != null || xrayProcess != null) {
                stopTunnelInternal(stopService = false)
            }
        }
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.mybypass.app.START"
        const val ACTION_STOP = "com.mybypass.app.STOP"
        const val ACTION_AUTO_CONNECT = "com.mybypass.app.AUTO_CONNECT"
        const val ACTION_RESCAN_AND_CONNECT = "com.mybypass.app.RESCAN_AND_CONNECT"
        const val EXTRA_CONFIG_JSON = "extra_config_json"
        const val EXTRA_SERVER_NAME = "extra_server_name"
        const val EXTRA_BLOCK_IPV6 = "extra_block_ipv6"

        private const val LOG_FILE_NAME = "last_log.txt"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isAutoSearching: Boolean = false
            private set

        @Volatile
        var searchStatusText: String = ""
            private set

        @Volatile
        var activeServerName: String = ""
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
        fun getLogs(): String = logLines.joinToString("\n")

        @Synchronized
        fun getDiagnosticsReport(context: Context): String {
            initLogger(context)
            val sb = StringBuilder()
            val saved = getLogs()
            if (saved.isNotEmpty()) sb.append(saved).append("\n")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, 2)
                    val crashInfo = reasons.firstOrNull {
                        it.reason == ApplicationExitInfo.REASON_CRASH ||
                            it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                    }
                    if (crashInfo != null) {
                        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                            .format(Date(crashInfo.timestamp))
                        sb.append("\n--- ExitInfo ($timeStr): status=${crashInfo.status} ${crashInfo.description} ---\n")
                    }
                } catch (_: Exception) {
                }
            }
            return sb.toString().trim().ifEmpty { "Нажмите «Автопоиск + Подкл.», чтобы подобрать самый быстрый сервер со всеми ИИ и приложениями." }
        }

        @Synchronized
        fun log(msg: String) {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val entry = "[$time] $msg"
            logLines.addLast(entry)
            while (logLines.size > 120) logLines.removeFirst()

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

        private fun notifyState(context: Context) {
            onStateListener?.invoke(isRunning)
            BypassTileService.requestAllTilesUpdate(context)
        }
    }
}

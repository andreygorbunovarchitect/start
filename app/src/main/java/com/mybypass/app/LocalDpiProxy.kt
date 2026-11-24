package com.mybypass.app

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object LocalDpiProxy {

    val PROFILE_NAMES = listOf(
        "DPI 1: Нативный ByeDPI Split + TLS-Rec (рекомендуется)",
        "DPI 2: Нативный ByeDPI Disorder + OOB",
        "DPI 3: Нативный ByeDPI Агрессивный (Fake + Split)",
        "DPI 4: Через зарубежный прокси-поток #2 (без DPI)"
    )

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var nativeProcess: Process? = null
    private val pool = Executors.newCachedThreadPool()

    var currentProfile: Int = 0
        private set

    var isUsingNativeBinary: Boolean = false
        private set

    fun isRunning(): Boolean = running.get()

    fun start(context: Context, port: Int = 1081, profileIndex: Int = 0): Boolean {
        stop()
        currentProfile = profileIndex.coerceIn(0, PROFILE_NAMES.lastIndex)
        if (currentProfile == 3) return false

        val nativeBin = File(context.applicationInfo.nativeLibraryDir, "libciadpi.so")
        if (nativeBin.exists() && startNativeCiadpi(nativeBin, port, currentProfile)) {
            isUsingNativeBinary = true
            running.set(true)
            return true
        }

        isUsingNativeBinary = false
        return startJavaFallback(port)
    }

    private fun startNativeCiadpi(bin: File, port: Int, profile: Int): Boolean {
        val args = when (profile) {
            0 -> listOf(
                bin.absolutePath, "-i", "127.0.0.1", "-p", port.toString(),
                "--split", "1", "--tlsrec", "1+s", "--auto=torst"
            )
            1 -> listOf(
                bin.absolutePath, "-i", "127.0.0.1", "-p", port.toString(),
                "--disorder", "1", "--oob", "1", "--auto=torst"
            )
            else -> listOf(
                bin.absolutePath, "-i", "127.0.0.1", "-p", port.toString(),
                "--fake", "-1", "--ttl", "8", "--split", "1+s", "--disorder", "3",
                "--auto=torst,redirect,ssl_err"
            )
        }

        return try {
            val proc = ProcessBuilder(args)
                .redirectErrorStream(true)
                .start()
            nativeProcess = proc

            for (i in 0 until 10) {
                if (!proc.isAlive) return false
                if (ServerScanner.tcpPing("127.0.0.1", port, 80) >= 0) {
                    return true
                }
                Thread.sleep(60)
            }
            stopNativeProcess()
            false
        } catch (_: Exception) {
            stopNativeProcess()
            false
        }
    }

    private fun startJavaFallback(port: Int): Boolean {
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("127.0.0.1", port), 128)
            serverSocket = ss
            running.set(true)

            pool.execute {
                while (running.get()) {
                    try {
                        val client = ss.accept()
                        client.tcpNoDelay = true
                        pool.execute { handleClient(client) }
                    } catch (_: Exception) {
                        if (!running.get()) break
                    }
                }
            }
            true
        } catch (e: Exception) {
            BypassVpnService.log("ОШИБКА запуска локального DPI-прокси: ${e.message}")
            stop()
            false
        }
    }

    fun stop() {
        running.set(false)
        isUsingNativeBinary = false
        stopNativeProcess()
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    private fun stopNativeProcess() {
        val p = nativeProcess
        nativeProcess = null
        if (p != null) {
            try { p.destroy() } catch (_: Exception) {}
            try {
                if (!p.waitFor(120, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly()
                }
            } catch (_: Exception) {
                try { p.destroyForcibly() } catch (_: Exception) {}
            }
        }
    }

    private fun handleClient(client: Socket) {
        var remote: Socket? = null
        try {
            client.soTimeout = 20000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val firstByte = input.read()
            if (firstByte == -1) return

            if (firstByte == 0x05) {
                handleSocks5(input, output) { sock -> remote = sock }
            }
        } catch (_: Exception) {
        } finally {
            try { remote?.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handleSocks5(
        input: InputStream,
        output: OutputStream,
        onConnected: (Socket) -> Unit
    ) {
        val nMethods = input.read()
        if (nMethods <= 0) return
        val methods = ByteArray(nMethods)
        if (!readFully(input, methods, nMethods)) return

        output.write(byteArrayOf(0x05, 0x00))
        output.flush()

        val header = ByteArray(4)
        if (!readFully(input, header, 4)) return
        val cmd = header[1].toInt() and 0xFF
        val atyp = header[3].toInt() and 0xFF

        if (cmd != 0x01) {
            output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            return
        }

        val targetHost: String = when (atyp) {
            0x01 -> {
                val ip = ByteArray(4)
                if (!readFully(input, ip, 4)) return
                InetAddress.getByAddress(ip).hostAddress.orEmpty()
            }
            0x03 -> {
                val lenByte = input.read()
                if (lenByte <= 0) return
                val dom = ByteArray(lenByte)
                if (!readFully(input, dom, lenByte)) return
                String(dom, Charsets.UTF_8)
            }
            0x04 -> {
                val ip6 = ByteArray(16)
                if (!readFully(input, ip6, 16)) return
                InetAddress.getByAddress(ip6).hostAddress.orEmpty()
            }
            else -> return
        }

        val portBuf = ByteArray(2)
        if (!readFully(input, portBuf, 2)) return
        val targetPort = ((portBuf[0].toInt() and 0xFF) shl 8) or (portBuf[1].toInt() and 0xFF)

        if (targetHost.startsWith("198.18.") || targetHost.startsWith("198.19.")) {
            output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            return
        }

        val remote = try {
            val addr = InetAddress.getByName(targetHost)
            Socket().apply {
                tcpNoDelay = true
                soTimeout = 25000
                connect(InetSocketAddress(addr, targetPort), 6000)
            }
        } catch (_: Exception) {
            null
        }

        if (remote == null) {
            output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            return
        }
        onConnected(remote)

        output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0))
        output.flush()

        val rIn = remote.getInputStream()
        val rOut = remote.getOutputStream()

        val upThread = Thread {
            try {
                val firstBuf = ByteArray(4096)
                val firstRead = input.read(firstBuf)
                if (firstRead > 2) {
                    rOut.write(firstBuf, 0, 2)
                    rOut.flush()
                    Thread.sleep(6)
                    rOut.write(firstBuf, 2, firstRead - 2)
                    rOut.flush()
                } else if (firstRead > 0) {
                    rOut.write(firstBuf, 0, firstRead)
                    rOut.flush()
                }
                val buf = ByteArray(16384)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    rOut.write(buf, 0, n)
                    rOut.flush()
                }
            } catch (_: Exception) {
            } finally {
                try { remote.shutdownOutput() } catch (_: Exception) {}
            }
        }.apply { isDaemon = true }

        val downThread = Thread {
            try {
                val buf = ByteArray(16384)
                while (true) {
                    val n = rIn.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                try { output.flush() } catch (_: Exception) {}
            }
        }.apply { isDaemon = true }

        upThread.start()
        downThread.start()
        upThread.join(60000)
        downThread.join(60000)
    }

    private fun readFully(input: InputStream, buf: ByteArray, length: Int): Boolean {
        var total = 0
        while (total < length) {
            val n = input.read(buf, total, length - total)
            if (n <= 0) return false
            total += n
        }
        return true
    }
}

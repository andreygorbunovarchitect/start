package com.mybypass.app

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object LocalDpiProxy {

    val PROFILE_NAMES = listOf(
        "DPI 1: Split на границе SNI (рекомендуется)",
        "DPI 2: Split + Disorder (обратный порядок)",
        "DPI 3: Фрагментация по 2 байта",
        "DPI 4: Через VLESS-прокси (если DPI режет фрагментацию)"
    )

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    var currentProfile: Int = 0
        private set

    fun isRunning(): Boolean = running.get()

    fun start(port: Int = 1081, profileIndex: Int = 0): Boolean {
        stop()
        currentProfile = profileIndex.coerceIn(0, PROFILE_NAMES.lastIndex)
        if (currentProfile == 3) return false

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
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
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
            } else {
                handleHttpConnect(firstByte, input, output) { sock -> remote = sock }
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

        val remote = connectDirect(targetHost, targetPort)
        if (remote == null) {
            output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            return
        }
        onConnected(remote)

        output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0))
        output.flush()

        relayWithDesync(input, output, remote, currentProfile)
    }

    private fun handleHttpConnect(
        firstByte: Int,
        input: InputStream,
        output: OutputStream,
        onConnected: (Socket) -> Unit
    ) {
        val sb = StringBuilder()
        sb.append(firstByte.toChar())
        var prev = firstByte
        while (sb.length < 8192) {
            val b = input.read()
            if (b == -1) break
            sb.append(b.toChar())
            if (prev == '\n'.code && b == '\n'.code) break
            if (sb.endsWith("\r\n\r\n")) break
            prev = b
        }

        val firstLine = sb.toString().lineSequence().firstOrNull().orEmpty()
        val parts = firstLine.split(' ')
        if (parts.size < 2 || !parts[0].equals("CONNECT", true)) return

        val authority = parts[1].substringBefore('/')
        val colonIdx = authority.lastIndexOf(':')
        val targetHost = if (colonIdx > 0) authority.substring(0, colonIdx) else authority
        val targetPort = if (colonIdx > 0) authority.substring(colonIdx + 1).toIntOrNull() ?: 443 else 443

        val remote = connectDirect(targetHost, targetPort)
        if (remote == null) {
            output.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
            return
        }
        onConnected(remote)

        output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        output.flush()

        relayWithDesync(input, output, remote, currentProfile)
    }

    private fun connectDirect(host: String, port: Int): Socket? {
        if (host.startsWith("198.18.") || host.startsWith("198.19.")) return null
        return try {
            val addr = InetAddress.getByName(host)
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.soTimeout = 25000
            sock.connect(InetSocketAddress(addr, port), 6000)
            sock
        } catch (_: Exception) {
            null
        }
    }

    private fun relayWithDesync(
        cIn: InputStream,
        cOut: OutputStream,
        remote: Socket,
        profile: Int
    ) {
        val rIn = remote.getInputStream()
        val rOut = remote.getOutputStream()

        val upThread = Thread {
            try {
                val firstBuf = ByteArray(4096)
                val firstRead = cIn.read(firstBuf)
                if (firstRead > 0) {
                    writeDesyncedChunk(rOut, firstBuf, firstRead, profile)
                }

                val buf = ByteArray(16384)
                while (true) {
                    val n = cIn.read(buf)
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
                    cOut.write(buf, 0, n)
                    cOut.flush()
                }
            } catch (_: Exception) {
            } finally {
                try { cOut.flush() } catch (_: Exception) {}
            }
        }.apply { isDaemon = true }

        upThread.start()
        downThread.start()
        upThread.join(60000)
        downThread.join(60000)
    }

    private fun writeDesyncedChunk(out: OutputStream, data: ByteArray, len: Int, profile: Int) {
        if (len <= 6) {
            out.write(data, 0, len)
            out.flush()
            return
        }

        when (profile) {
            0 -> {
                val sniPos = findSniOffset(data, len)
                if (sniPos > 5 && sniPos + 3 < len) {
                    out.write(data, 0, 2)
                    out.flush()
                    Thread.sleep(6)
                    out.write(data, 2, sniPos - 2)
                    out.flush()
                    Thread.sleep(6)
                    out.write(data, sniPos, len - sniPos)
                    out.flush()
                } else {
                    val splitAt = minOf(len / 3, 24).coerceAtLeast(2)
                    out.write(data, 0, splitAt)
                    out.flush()
                    Thread.sleep(8)
                    out.write(data, splitAt, len - splitAt)
                    out.flush()
                }
            }
            1 -> {
                val splitAt = minOf(5, len / 2)
                out.write(data, splitAt, len - splitAt)
                out.flush()
                Thread.sleep(8)
                out.write(data, 0, splitAt)
                out.flush()
            }
            else -> {
                var offset = 0
                while (offset < len) {
                    val step = minOf(2, len - offset)
                    out.write(data, offset, step)
                    out.flush()
                    offset += step
                    if (offset < len && offset < 96) Thread.sleep(3)
                }
            }
        }
    }

    private fun findSniOffset(data: ByteArray, len: Int): Int {
        if (len < 45 || (data[0].toInt() and 0xFF) != 0x16 || (data[1].toInt() and 0xFF) != 0x03) {
            return -1
        }
        for (i in 40 until len - 9) {
            if (data[i] == 0x00.toByte() && data[i + 1] == 0x00.toByte()) {
                val nameType = data[i + 6].toInt() and 0xFF
                val nameLen = ((data[i + 7].toInt() and 0xFF) shl 8) or (data[i + 8].toInt() and 0xFF)
                if (nameType == 0x00 && nameLen in 4..253 && i + 9 + nameLen <= len) {
                    return i + 9 + (nameLen / 2)
                }
            }
        }
        return -1
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

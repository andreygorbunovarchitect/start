package com.mybypass.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

data class VlessNode(
    val rawUri: String,
    val name: String,
    val uuid: String,
    val host: String,
    val port: Int,
    val encryption: String,
    val flow: String,
    val security: String,
    val network: String,
    val sni: String,
    val fingerprint: String,
    val publicKey: String,
    val shortId: String,
    val spiderX: String,
    val path: String,
    val headerHost: String,
    val headerType: String,
    val serviceName: String,
    val mode: String,
    val alpn: String,
    val allowInsecure: Boolean
)

object VlessParser {

    private val SUPPORTED_NETWORKS = setOf("tcp", "raw", "ws", "grpc", "xhttp", "splithttp", "httpupgrade")

    private val RU_DIRECT_DOMAINS = listOf(
        "domain:ru",
        "domain:su",
        "domain:xn--p1ai",
        "domain:vk.com",
        "domain:vk.me",
        "domain:ok.ru",
        "domain:yandex.net",
        "domain:yastatic.net",
        "domain:2gis.com",
        "domain:ozonusercontent.com",
        "domain:wb.ru",
        "domain:wildberries.ru",
        "domain:gosuslugi.ru",
        "domain:sberbank.ru",
        "domain:tbank.ru",
        "domain:tinkoff.ru",
        "domain:alfabank.ru",
        "domain:vtb.ru"
    )

    fun parseSubscription(rawText: String): List<VlessNode> {
        var text = rawText.trim()
        if (text.isEmpty()) return emptyList()

        // Если подписка закодирована в Base64 — декодируем
        if (!text.contains("vless://", ignoreCase = true)) {
            val cleaned = text.replace("\\s".toRegex(), "")
            val decoded = try {
                String(Base64.decode(cleaned, Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Exception) {
                try {
                    String(Base64.decode(cleaned, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
                } catch (_: Exception) {
                    ""
                }
            }
            if (decoded.contains("vless://", ignoreCase = true)) {
                text = decoded
            }
        }

        val seen = HashSet<String>()
        val result = ArrayList<VlessNode>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("vless://", ignoreCase = true)) continue
            val node = parseVlessUri(trimmed) ?: continue
            val key = "${node.host}:${node.port}:${node.uuid}:${node.network}:${node.security}"
            if (seen.add(key)) {
                result.add(node)
            }
        }
        return result
    }

    fun parseVlessUri(uri: String): VlessNode? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith("vless://", ignoreCase = true)) return null
        val body = trimmed.substring(8)

        val hashIdx = body.indexOf('#')
        val beforeHash = if (hashIdx >= 0) body.substring(0, hashIdx) else body
        val rawName = if (hashIdx >= 0) body.substring(hashIdx + 1) else ""

        val qIdx = beforeHash.indexOf('?')
        val authority = if (qIdx >= 0) beforeHash.substring(0, qIdx) else beforeHash
        val queryStr = if (qIdx >= 0) beforeHash.substring(qIdx + 1) else ""

        val atIdx = authority.indexOf('@')
        if (atIdx <= 0) return null
        val uuid = safeDecode(authority.substring(0, atIdx)).trim()
        val hostPort = authority.substring(atIdx + 1).trim()
        if (uuid.isEmpty() || hostPort.isEmpty()) return null

        val host: String
        val port: Int
        if (hostPort.startsWith("[")) {
            val closeBracket = hostPort.indexOf(']')
            if (closeBracket <= 1 || closeBracket + 2 > hostPort.length || hostPort[closeBracket + 1] != ':') {
                return null
            }
            host = hostPort.substring(1, closeBracket)
            port = hostPort.substring(closeBracket + 2).toIntOrNull() ?: return null
        } else {
            val colonIdx = hostPort.lastIndexOf(':')
            if (colonIdx <= 0) return null
            host = hostPort.substring(0, colonIdx)
            port = hostPort.substring(colonIdx + 1).toIntOrNull() ?: return null
        }

        if (host.isBlank() || port !in 1..65535) return null

        val params = HashMap<String, String>()
        if (queryStr.isNotEmpty()) {
            for (pair in queryStr.split('&')) {
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val k = safeDecode(pair.substring(0, eq)).lowercase()
                    val v = safeDecode(pair.substring(eq + 1))
                    params[k] = v
                }
            }
        }

        val network = (params["type"] ?: "tcp").lowercase().let { if (it == "raw") "tcp" else it }
        if (network !in SUPPORTED_NETWORKS) return null

        val security = (params["security"] ?: "none").lowercase()
        val pbk = params["pbk"] ?: ""
        if (security == "reality" && pbk.isBlank()) return null

        val name = safeDecode(rawName).trim().ifEmpty { "$host:$port" }
        val allowInsecure = params["allowinsecure"] == "1" ||
            params["allowinsecure"].equals("true", true) ||
            params["insecure"] == "1"

        return VlessNode(
            rawUri = trimmed,
            name = name,
            uuid = uuid,
            host = host,
            port = port,
            encryption = params["encryption"].orEmpty().ifBlank { "none" },
            flow = params["flow"].orEmpty(),
            security = security,
            network = network,
            sni = params["sni"] ?: params["servername"] ?: "",
            fingerprint = params["fp"].orEmpty().ifBlank { "chrome" },
            publicKey = pbk,
            shortId = params["sid"].orEmpty(),
            spiderX = params["spx"].orEmpty(),
            path = params["path"].orEmpty().ifBlank { "/" },
            headerHost = params["host"].orEmpty(),
            headerType = params["headertype"].orEmpty().lowercase(),
            serviceName = params["servicename"].orEmpty(),
            mode = params["mode"].orEmpty(),
            alpn = params["alpn"].orEmpty(),
            allowInsecure = allowInsecure
        )
    }

    fun buildXrayConfig(
        node: VlessNode,
        socksPort: Int = 1080,
        splitRu: Boolean = true,
        blockQuic: Boolean = true,
        isTestMode: Boolean = false
    ): String {
        val root = JSONObject()

        root.put("log", JSONObject().apply {
            put("loglevel", if (isTestMode) "error" else "warning")
        })

        // Встроенный DNS Xray (защищает от подмены провайдером и даёт зарубежный GeoDNS для ИИ)
        if (!isTestMode) {
            val dnsServers = JSONArray()

            // Если адрес самого VLESS-сервера задан доменом — резолвим его локально, чтобы избежать петли
            if (!isIpAddress(node.host)) {
                dnsServers.put(JSONObject().apply {
                    put("address", "localhost")
                    put("domains", JSONArray().put("full:${node.host}"))
                })
            }

            if (splitRu) {
                dnsServers.put(JSONObject().apply {
                    put("address", "localhost")
                    put("domains", JSONArray(RU_DIRECT_DOMAINS))
                })
            }

            // Основные DNS-запросы идут по TCP через VLESS (работает даже без UDP на сервере)
            dnsServers.put("tcp://1.1.1.1")
            dnsServers.put("tcp://8.8.8.8")

            root.put("dns", JSONObject().apply {
                put("servers", dnsServers)
                put("queryStrategy", "UseIPv4")
            })
        }

        // Локальный SOCKS5 вход (сюда шлёт трафик hev-socks5-tunnel)
        val inbound = JSONObject().apply {
            put("tag", "socks-in")
            put("listen", "127.0.0.1")
            put("port", socksPort)
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
            })
            if (!isTestMode) {
                put("sniffing", JSONObject().apply {
                    put("enabled", true)
                    put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                    put("routeOnly", true)
                })
            }
        }
        root.put("inbounds", JSONArray().put(inbound))

        // Исходящие: первым обязательно идёт proxy (дефолт для всего трафика и TCP DNS)
        val outbounds = JSONArray()
        outbounds.put(buildVlessOutbound(node))
        outbounds.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
        })
        outbounds.put(JSONObject().apply {
            put("tag", "dns-out")
            put("protocol", "dns")
        })
        outbounds.put(JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
        })
        root.put("outbounds", outbounds)

        // Маршрутизация (Split Routing)
        val rules = JSONArray()
        if (!isTestMode) {
            // 1. Перехват всех DNS-запросов (UDP/TCP 53) во встроенный DNS Xray
            rules.put(JSONObject().apply {
                put("type", "field")
                put("inboundTag", JSONArray().put("socks-in"))
                put("port", "53")
                put("outboundTag", "dns-out")
            })

            // 2. Блокировка QUIC (UDP 443) -> YouTube и Chrome сразу идут по стабильному TCP
            if (blockQuic) {
                rules.put(JSONObject().apply {
                    put("type", "field")
                    put("inboundTag", JSONArray().put("socks-in"))
                    put("network", "udp")
                    put("port", "443")
                    put("outboundTag", "block")
                })
            }

            // 3. Локальные сети напрямую
            rules.put(JSONObject().apply {
                put("type", "field")
                put("ip", JSONArray().apply {
                    put("10.0.0.0/8")
                    put("127.0.0.0/8")
                    put("172.16.0.0/12")
                    put("192.168.0.0/16")
                })
                put("outboundTag", "direct")
            })

            // 4. Российские сайты, банки и Госуслуги напрямую
            if (splitRu) {
                rules.put(JSONObject().apply {
                    put("type", "field")
                    put("domain", JSONArray(RU_DIRECT_DOMAINS))
                    put("outboundTag", "direct")
                })
            }
        }

        // 5. Всё остальное (включая Gemini, Flow, Nano Banana, ChatGPT, YouTube, TG, WA) -> VLESS
        rules.put(JSONObject().apply {
            put("type", "field")
            put("network", "tcp,udp")
            put("outboundTag", "proxy")
        })

        root.put("routing", JSONObject().apply {
            put("domainStrategy", "AsIs")
            put("rules", rules)
        })

        return root.toString(2)
    }

    private fun buildVlessOutbound(node: VlessNode): JSONObject {
        val user = JSONObject().apply {
            put("id", node.uuid)
            put("encryption", node.encryption)
            if (node.flow.isNotBlank() && (node.network == "tcp" || node.network == "raw") &&
                (node.security == "reality" || node.security == "tls")
            ) {
                put("flow", node.flow)
            }
        }

        val vnextItem = JSONObject().apply {
            put("address", node.host)
            put("port", node.port)
            put("users", JSONArray().put(user))
        }

        val streamSettings = JSONObject().apply {
            put("network", node.network)
            put("security", node.security)

            when (node.security) {
                "reality" -> {
                    put("realitySettings", JSONObject().apply {
                        put("show", false)
                        put("fingerprint", node.fingerprint)
                        put("serverName", node.sni.ifBlank { node.host })
                        put("publicKey", node.publicKey)
                        put("shortId", node.shortId)
                        if (node.spiderX.isNotBlank()) put("spiderX", node.spiderX)
                    })
                }
                "tls" -> {
                    put("tlsSettings", JSONObject().apply {
                        put("serverName", node.sni.ifBlank { node.headerHost.ifBlank { node.host } })
                        put("fingerprint", node.fingerprint)
                        put("allowInsecure", node.allowInsecure)
                        if (node.alpn.isNotBlank()) {
                            val arr = JSONArray()
                            node.alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { arr.put(it) }
                            if (arr.length() > 0) put("alpn", arr)
                        }
                    })
                }
            }

            when (node.network) {
                "tcp" -> {
                    if (node.headerType == "http") {
                        put("tcpSettings", JSONObject().apply {
                            put("header", JSONObject().apply {
                                put("type", "http")
                                put("request", JSONObject().apply {
                                    put("path", JSONArray().put(node.path))
                                    if (node.headerHost.isNotBlank()) {
                                        put("headers", JSONObject().apply {
                                            put("Host", JSONArray().put(node.headerHost))
                                        })
                                    }
                                })
                            })
                        })
                    }
                }
                "ws" -> {
                    put("wsSettings", JSONObject().apply {
                        put("path", node.path)
                        if (node.headerHost.isNotBlank()) {
                            put("headers", JSONObject().apply {
                                put("Host", node.headerHost)
                            })
                        }
                    })
                }
                "grpc" -> {
                    put("grpcSettings", JSONObject().apply {
                        put("serviceName", node.serviceName)
                        if (node.mode.equals("multi", true)) put("multiMode", true)
                    })
                }
                "xhttp", "splithttp" -> {
                    val key = if (node.network == "xhttp") "xhttpSettings" else "splithttpSettings"
                    put(key, JSONObject().apply {
                        put("path", node.path)
                        if (node.headerHost.isNotBlank()) put("host", node.headerHost)
                        if (node.mode.isNotBlank()) put("mode", node.mode)
                    })
                }
                "httpupgrade" -> {
                    put("httpupgradeSettings", JSONObject().apply {
                        put("path", node.path)
                        if (node.headerHost.isNotBlank()) put("host", node.headerHost)
                    })
                }
            }
        }

        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vless")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().put(vnextItem))
            })
            put("streamSettings", streamSettings)
        }
    }

    private fun isIpAddress(host: String): Boolean {
        if (host.contains(':')) return true
        return Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)
    }

    private fun safeDecode(value: String): String {
        return try {
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            value
        }
    }
}

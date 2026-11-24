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
    val allowInsecure: Boolean,
    val isCurated: Boolean = false
)

object VlessParser {

    private val SUPPORTED_NETWORKS = setOf("tcp", "raw", "ws", "grpc", "xhttp", "splithttp", "httpupgrade")

    private val GEO_BLOCKED_FLAGS = listOf("🇷🇺", "🇧🇾", "🇨🇳", "🇭🇰", "🇮🇷", "🇰🇵")
    private val GEO_BLOCKED_WORDS = Regex(
        "(?i)(^|[^a-z])(ru|rus|russia|moscow|spb|by|belarus|cn|china|hk|hong[\\s_-]?kong|iran)([^a-z]|$)"
    )

    val ALL_FREE_URLS = listOf(
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS.txt",
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS_mobile.txt",
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
        "https://raw.githubusercontent.com/patterniha/Free-Configs/main/configs.txt",
        "https://raw.githubusercontent.com/zieng2/wl/main/vless_lite.txt",
        "https://raw.githubusercontent.com/aviamastersgh/vpn-free-russia/main/verified_configs.txt",
        "https://raw.githubusercontent.com/Hidashimora/free-vpn-anti-rkn/main/configs/26.1.txt",
        "https://raw.githubusercontent.com/Hidashimora/free-vpn-anti-rkn/main/configs/29.txt"
    )

    fun isCuratedUrl(url: String): Boolean {
        return url.contains("igareck/", ignoreCase = true) ||
            url.contains("patterniha/", ignoreCase = true) ||
            url.contains("zieng2/wl/", ignoreCase = true) ||
            url.contains("aviamastersgh/vpn-free-russia/", ignoreCase = true) ||
            url.contains("Hidashimora/free-vpn-anti-rkn/", ignoreCase = true)
    }

    val YOUTUBE_DOMAINS = listOf(
        "domain:youtube.com",
        "domain:youtu.be",
        "domain:googlevideo.com",
        "domain:ytimg.com",
        "domain:youtube-nocookie.com",
        "domain:yt.be",
        "domain:ggpht.com"
    )

    val TELEGRAM_DOMAINS = listOf(
        "domain:telegram.org",
        "domain:t.me",
        "domain:tdesktop.com",
        "domain:telegra.ph",
        "domain:telesco.pe",
        "domain:tx.me",
        "domain:telegram.me",
        "domain:telegram.dog",
        "domain:cdn-telegram.org",
        "domain:fragment.com"
    )

    val TELEGRAM_IP_CIDRS = listOf(
        "91.108.4.0/22",
        "91.108.8.0/22",
        "91.108.12.0/22",
        "91.108.16.0/22",
        "91.108.20.0/22",
        "91.108.56.0/22",
        "91.105.192.0/23",
        "149.154.160.0/20",
        "185.76.151.0/24",
        "95.161.64.0/20"
    )

    val AI_AND_SOCIAL_DOMAINS = listOf(
        "domain:google.com",
        "domain:google.ru",
        "domain:googleapis.com",
        "domain:gstatic.com",
        "domain:googleusercontent.com",
        "domain:google.dev",
        "domain:g.co",
        "domain:goo.gl",
        "domain:withgoogle.com",
        "domain:labs.google",
        "domain:notebooklm.google",
        "domain:deepmind.google",
        "domain:deepmind.com",
        "domain:firebaseapp.com",
        "domain:appspot.com",
        "domain:recaptcha.net",
        "domain:1e100.net",
        "domain:gvt1.com",
        "domain:gvt2.com",
        "domain:gvt3.com",
        "domain:openai.com",
        "domain:chatgpt.com",
        "domain:oaistatic.com",
        "domain:oaiusercontent.com",
        "domain:sora.com",
        "domain:auth0.com",
        "domain:claude.ai",
        "domain:anthropic.com",
        "domain:perplexity.ai",
        "domain:copilot.microsoft.com",
        "domain:midjourney.com",
        "domain:grok.com",
        "domain:x.ai",
        "domain:huggingface.co",
        "domain:civitai.com",
        "domain:cursor.com",
        "domain:cursor.sh",
        "domain:elevenlabs.io",
        "domain:suno.com",
        "domain:runwayml.com",
        "domain:klingai.com",
        "domain:lumalabs.ai",
        "domain:recraft.ai",
        "domain:ideogram.ai",
        "domain:discord.com",
        "domain:discord.gg",
        "domain:discordapp.com",
        "domain:discordapp.net",
        "domain:discord.media",
        "domain:discordcdn.com",
        "domain:hcaptcha.com",
        "domain:challenges.cloudflare.com",
        "domain:instagram.com",
        "domain:cdninstagram.com",
        "domain:threads.net",
        "domain:threads.com",
        "domain:facebook.com",
        "domain:fb.com",
        "domain:fb.me",
        "domain:fbcdn.net",
        "domain:fbsbx.com",
        "domain:messenger.com",
        "domain:meta.com",
        "domain:meta.ai",
        "domain:whatsapp.com",
        "domain:whatsapp.net",
        "domain:wa.me",
        "domain:x.com",
        "domain:twitter.com",
        "domain:twimg.com",
        "domain:t.co",
        "domain:linkedin.com",
        "domain:licdn.com",
        "domain:spotify.com",
        "domain:scdn.co",
        "domain:notion.so",
        "domain:figma.com",
        "domain:soundcloud.com",
        "domain:patreon.com",
        "domain:medium.com",
        "domain:2ip.io",
        "domain:whoer.net",
        "domain:ifconfig.me",
        "domain:ipinfo.io"
    )

    val QUIC_BLOCK_DOMAINS = listOf(
        "domain:google.com",
        "domain:google.ru",
        "domain:googleapis.com",
        "domain:gstatic.com",
        "domain:youtube.com",
        "domain:googlevideo.com",
        "domain:ytimg.com"
    )

    val DIRECT_DOMAINS = listOf(
        "domain:ru",
        "domain:su",
        "domain:xn--p1ai",
        "domain:vk.com",
        "domain:vk.me",
        "domain:vkplay.ru",
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
        "domain:vtb.ru",
        "domain:steampowered.com",
        "domain:steamcommunity.com",
        "domain:steamstatic.com"
    )

    val VOICE_IP_CIDRS = listOf(
        "66.22.192.0/18",
        "138.128.136.0/21",
        "162.159.128.0/20"
    )

    fun isGeoBlockedForAi(node: VlessNode): Boolean {
        val label = node.name
        if (GEO_BLOCKED_FLAGS.any { label.contains(it) }) return true
        return GEO_BLOCKED_WORDS.containsMatchIn(label)
    }

    fun parseSubscription(rawText: String, isCuratedSource: Boolean = false): List<VlessNode> {
        var text = rawText.trim()
        if (text.isEmpty()) return emptyList()

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
            val node = parseVlessUri(trimmed, isCuratedSource) ?: continue
            val key = "${node.host}:${node.port}:${node.uuid}:${node.network}:${node.security}"
            if (seen.add(key)) {
                result.add(node)
            }
        }
        return result
    }

    fun parseVlessUri(uri: String, isCuratedSource: Boolean = false): VlessNode? {
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
        val uuid = safeDecode(authority.substring(0, atIdx), plusAsSpace = false).trim()
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
                    val k = safeDecode(pair.substring(0, eq), plusAsSpace = false).lowercase()
                    val v = safeDecode(pair.substring(eq + 1), plusAsSpace = false)
                    params[k] = v
                }
            }
        }

        val network = (params["type"] ?: "tcp").lowercase().let { if (it == "raw") "tcp" else it }
        if (network !in SUPPORTED_NETWORKS) return null

        val security = (params["security"] ?: "none").lowercase()
        val pbk = params["pbk"] ?: ""
        if (security == "reality" && pbk.isBlank()) return null

        val name = safeDecode(rawName, plusAsSpace = true).trim().ifEmpty { "$host:$port" }
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
            allowInsecure = allowInsecure,
            isCurated = isCuratedSource
        )
    }

    fun buildXrayConfig(
        node: VlessNode,
        mediaNode: VlessNode? = null,
        socksPort: Int = 1080,
        smartBypass: Boolean = true,
        splitRu: Boolean = true,
        blockQuic: Boolean = true,
        useLocalDpi: Boolean = false,
        dpiPort: Int = 1081,
        tgDirect: Boolean = true,
        isTestMode: Boolean = false
    ): String {
        val root = JSONObject()

        root.put("log", JSONObject().apply {
            put("loglevel", if (isTestMode) "error" else "warning")
        })

        if (isTestMode) {
            root.put("dns", JSONObject().apply {
                put("servers", JSONArray().put("77.88.8.8").put("77.88.8.1").put("1.1.1.1"))
                put("queryStrategy", "UseIPv4")
            })
        } else {
            root.put("policy", JSONObject().apply {
                put("levels", JSONObject().apply {
                    put("0", JSONObject().apply {
                        put("handshake", 4)
                        put("connIdle", 300)
                        put("uplinkOnly", 2)
                        put("downlinkOnly", 5)
                    })
                })
            })

            root.put("fakedns", JSONArray().put(JSONObject().apply {
                put("ipPool", "198.18.0.0/15")
                put("poolSize", 65535)
            }))

            val dnsServers = JSONArray()
            if (!isIpAddress(node.host)) {
                dnsServers.put(JSONObject().apply {
                    put("address", "77.88.8.8")
                    put("domains", JSONArray().put("full:${node.host}"))
                    put("skipFallback", true)
                })
            }
            if (mediaNode != null && mediaNode.host != node.host && !isIpAddress(mediaNode.host)) {
                dnsServers.put(JSONObject().apply {
                    put("address", "77.88.8.8")
                    put("domains", JSONArray().put("full:${mediaNode.host}"))
                    put("skipFallback", true)
                })
            }

            if (useLocalDpi) {
                dnsServers.put(JSONObject().apply {
                    put("address", "77.88.8.8")
                    put("domains", JSONArray(YOUTUBE_DOMAINS))
                    put("skipFallback", true)
                })
            }

            if (tgDirect) {
                dnsServers.put(JSONObject().apply {
                    put("address", "77.88.8.8")
                    put("domains", JSONArray(TELEGRAM_DOMAINS))
                    put("skipFallback", true)
                })
            }

            if (smartBypass) {
                val fakeTargets = ArrayList<String>()
                fakeTargets.addAll(AI_AND_SOCIAL_DOMAINS)
                if (!useLocalDpi) fakeTargets.addAll(YOUTUBE_DOMAINS)
                if (!tgDirect) fakeTargets.addAll(TELEGRAM_DOMAINS)

                dnsServers.put(JSONObject().apply {
                    put("address", "fakedns")
                    put("domains", JSONArray(fakeTargets))
                })
                dnsServers.put("77.88.8.8")
            } else {
                dnsServers.put(JSONObject().apply {
                    put("address", "fakedns")
                    put("domains", JSONArray().put("domain:google.ru"))
                })
                if (splitRu) {
                    dnsServers.put(JSONObject().apply {
                        put("address", "77.88.8.8")
                        put("domains", JSONArray(DIRECT_DOMAINS))
                        put("skipFallback", true)
                    })
                }
                dnsServers.put("fakedns")
            }

            root.put("dns", JSONObject().apply {
                put("servers", dnsServers)
                put("queryStrategy", "UseIPv4")
            })
        }

        val inbound = JSONObject().apply {
            put("tag", "socks-in")
            put("listen", "127.0.0.1")
            put("port", socksPort)
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
                put("ip", "127.0.0.1")
            })
            if (!isTestMode) {
                put("sniffing", JSONObject().apply {
                    put("enabled", true)
                    put("destOverride", JSONArray().put("http").put("tls").put("quic").put("fakedns"))
                    put("routeOnly", false)
                })
            }
        }
        root.put("inbounds", JSONArray().put(inbound))

        val outbounds = JSONArray()
        outbounds.put(buildVlessOutbound(node, "proxy"))

        val hasSeparateMedia = !isTestMode && mediaNode != null && mediaNode.rawUri != node.rawUri
        if (hasSeparateMedia) {
            outbounds.put(buildVlessOutbound(mediaNode!!, "media-proxy"))
        }

        if (!isTestMode && useLocalDpi) {
            outbounds.put(JSONObject().apply {
                put("tag", "dpi-out")
                put("protocol", "socks")
                put("settings", JSONObject().apply {
                    put("servers", JSONArray().put(JSONObject().apply {
                        put("address", "127.0.0.1")
                        put("port", dpiPort)
                    }))
                })
            })
        }

        outbounds.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject().apply {
                put("domainStrategy", "UseIPv4")
            })
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

        val rules = JSONArray()
        if (!isTestMode) {
            rules.put(JSONObject().apply {
                put("type", "field")
                put("inboundTag", JSONArray().put("socks-in"))
                put("port", "53")
                put("outboundTag", "dns-out")
            })

            rules.put(JSONObject().apply {
                put("type", "field")
                put("ip", JSONArray().put("77.88.8.8/32").put("77.88.8.1/32"))
                put("outboundTag", "direct")
            })

            rules.put(JSONObject().apply {
                put("type", "field")
                put("ip", JSONArray().put("1.1.1.1/32").put("8.8.8.8/32"))
                put("port", "443")
                put("outboundTag", "proxy")
            })

            if (blockQuic) {
                rules.put(JSONObject().apply {
                    put("type", "field")
                    put("inboundTag", JSONArray().put("socks-in"))
                    put("protocol", JSONArray().put("quic"))
                    put("domain", JSONArray(QUIC_BLOCK_DOMAINS))
                    put("outboundTag", "block")
                })
            }

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

            val ytTag = when {
                useLocalDpi -> "dpi-out"
                hasSeparateMedia -> "media-proxy"
                else -> "proxy"
            }
            rules.put(JSONObject().apply {
                put("type", "field")
                put("domain", JSONArray(YOUTUBE_DOMAINS))
                put("outboundTag", ytTag)
            })

            val tgTag = when {
                tgDirect -> "direct"
                hasSeparateMedia -> "media-proxy"
                else -> "proxy"
            }
            rules.put(JSONObject().apply {
                put("type", "field")
                put("domain", JSONArray(TELEGRAM_DOMAINS))
                put("outboundTag", tgTag)
            })
            rules.put(JSONObject().apply {
                put("type", "field")
                put("ip", JSONArray(TELEGRAM_IP_CIDRS))
                put("outboundTag", tgTag)
            })

            rules.put(JSONObject().apply {
                put("type", "field")
                put("domain", JSONArray(AI_AND_SOCIAL_DOMAINS))
                put("outboundTag", "proxy")
            })

            rules.put(JSONObject().apply {
                put("type", "field")
                put("ip", JSONArray().put("198.18.0.0/15"))
                put("outboundTag", "proxy")
            })

            if (smartBypass) {
                rules.put(JSONObject().apply {
                    put("type", "field")
                    put("domain", JSONArray(DIRECT_DOMAINS))
                    put("outboundTag", "direct")
                })

                rules.put(JSONObject().apply {
                    put("type", "field")
                    put("ip", JSONArray(VOICE_IP_CIDRS))
                    put("outboundTag", "proxy")
                })
                rules.put(JSONObject().apply {
                    put("type", "field")
                    put("inboundTag", JSONArray().put("socks-in"))
                    put("network", "udp")
                    put("port", "50000-65535,19294-19344")
                    put("outboundTag", "proxy")
                })

                rules.put(JSONObject().apply {
                    put("type", "field")
                    put("network", "tcp,udp")
                    put("outboundTag", "direct")
                })
            } else {
                if (splitRu) {
                    rules.put(JSONObject().apply {
                        put("type", "field")
                        put("domain", JSONArray(DIRECT_DOMAINS))
                        put("outboundTag", "direct")
                    })
                }
                rules.put(JSONObject().apply {
                    put("type", "field")
                    put("network", "tcp,udp")
                    put("outboundTag", "proxy")
                })
            }
        } else {
            rules.put(JSONObject().apply {
                put("type", "field")
                put("ip", JSONArray().put("77.88.8.8/32").put("77.88.8.1/32").put("1.1.1.1/32").put("8.8.8.8/32"))
                put("outboundTag", "direct")
            })
            rules.put(JSONObject().apply {
                put("type", "field")
                put("network", "tcp,udp")
                put("outboundTag", "proxy")
            })
        }

        root.put("routing", JSONObject().apply {
            put("domainStrategy", "AsIs")
            put("rules", rules)
        })

        return root.toString(2)
    }

    private fun buildVlessOutbound(node: VlessNode, tagName: String): JSONObject {
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
            put("sockopt", JSONObject().apply {
                put("tcpNoDelay", true)
                put("tcpKeepAliveInterval", 30)
                put("domainStrategy", "UseIPv4")
            })

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
                            put("host", node.headerHost)
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
            put("tag", tagName)
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

    private fun safeDecode(value: String, plusAsSpace: Boolean): String {
        return try {
            val src = if (plusAsSpace) value.replace("+", "%20") else value
            URLDecoder.decode(src, "UTF-8")
        } catch (_: Exception) {
            value
        }
    }
}

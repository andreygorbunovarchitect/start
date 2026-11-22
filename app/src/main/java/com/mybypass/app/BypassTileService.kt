package com.mybypass.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Основная плитка в шторке: Вкл/Выкл VPN + автопоиск лучшего сервера при необходимости */
class BypassTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (BypassVpnService.isRunning || BypassVpnService.isAutoSearching) {
            val stopIntent = Intent(this, BypassVpnService::class.java).apply {
                action = BypassVpnService.ACTION_STOP
            }
            startService(stopIntent)
            refreshTile()
            return
        }

        if (VpnService.prepare(this) != null) {
            val actIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(MainActivity.EXTRA_AUTO_ACTION, BypassVpnService.ACTION_AUTO_CONNECT)
            }
            launchActivitySafe(actIntent, 101)
            return
        }

        val startIntent = Intent(this, BypassVpnService::class.java).apply {
            action = BypassVpnService.ACTION_AUTO_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(startIntent)
        } else {
            startService(startIntent)
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        when {
            BypassVpnService.isAutoSearching -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Поиск VPN..."
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = BypassVpnService.searchStatusText.ifBlank { "Ищу ИИ+YT+Meta" }
                }
            }
            BypassVpnService.isRunning -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "MyBypass VPN"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = BypassVpnService.activeServerName.ifBlank { "Подключено" }
                }
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "MyBypass VPN"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Выкл (автоподбор)"
                }
            }
        }
        tile.updateTile()
    }

    private fun launchActivitySafe(intent: Intent, reqCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this,
                reqCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun requestAllTilesUpdate(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, BypassTileService::class.java))
                requestListeningState(context, ComponentName(context, RescanTileService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}

/** Вторая плитка в шторке: принудительный свежий автопоиск лучшего сервера и автоподключение */
class RescanTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (BypassVpnService.isAutoSearching) {
            ServerScanner.cancelScan()
            refreshTile()
            return
        }

        if (VpnService.prepare(this) != null) {
            val actIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(MainActivity.EXTRA_AUTO_ACTION, BypassVpnService.ACTION_RESCAN_AND_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(
                    this,
                    102,
                    actIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(actIntent)
            }
            return
        }

        val intent = Intent(this, BypassVpnService::class.java).apply {
            action = BypassVpnService.ACTION_RESCAN_AND_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        if (BypassVpnService.isAutoSearching) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Сканирую..."
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = BypassVpnService.searchStatusText.ifBlank { "Ищу лучший ИИ" }
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Найти лучший VPN"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Обновить и вкл."
            }
        }
        tile.updateTile()
    }
}

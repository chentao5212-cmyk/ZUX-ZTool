package com.qimian233.ztool.tile

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.qimian233.ztool.R
import com.qimian233.ztool.XposedServiceBridge
import com.qimian233.ztool.data.keys.PreferenceKeys

/**
 * 「强制全局横屏」控制中心磁贴。
 *
 * 状态读写通过 XposedServiceBridge 获取 LSPosed 模块共享的
 * xposed_module_config SharedPreferences，与 BaseHookModule.isEnabled()
 * 读的是同一份数据。
 *
 * 点击切换后，system_server 里的 ForceLandscape hook lambda 在下一次
 * DisplayRotation.rotationForOrientation() 调用时即时生效，无需重启。
 */
class ForceLandscapeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val prefs = remotePrefs() ?: return
        val current = try {
            prefs.getBoolean(PreferenceKeys.FORCE_LANDSCAPE.name, false)
        } catch (_: Throwable) {
            false
        }
        prefs.edit().putBoolean(PreferenceKeys.FORCE_LANDSCAPE.name, !current).apply()
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val enabled = try {
            remotePrefs()?.getBoolean(PreferenceKeys.FORCE_LANDSCAPE.name, false) ?: false
        } catch (_: Throwable) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = getString(R.string.force_landscape_title)
            tile.updateTile()
            return
        }
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.force_landscape_title)
        tile.subtitle = if (enabled) {
            getString(R.string.force_landscape_tile_on)
        } else {
            getString(R.string.force_landscape_tile_off)
        }
        val iconRes = if (enabled) R.drawable.ic_force_landscape_on else R.drawable.ic_force_landscape_off
        tile.icon = Icon.createWithResource(this, iconRes)
        tile.updateTile()
    }

    private fun remotePrefs() = try {
        XposedServiceBridge.currentService?.getRemotePreferences(PREFS_NAME)
    } catch (_: Throwable) {
        null
    }

    companion object {
        // 与 BaseHookModule.PREFS_NAME 保持一致
        private const val PREFS_NAME = "xposed_module_config"
    }
}

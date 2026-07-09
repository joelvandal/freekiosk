package com.freekiosk

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.provider.Settings

/**
 * Best-effort removal of an OEM on-screen navigation/status bar that ignores the
 * framework immersive flags.
 *
 * Some panels — notably Rockchip (RK32xx/RK35xx) industrial/POE tablets — ship a
 * customized SystemUI that draws its own extended navigation bar (back/home/recents
 * + volume). That bar is NOT governed by SYSTEM_UI_FLAG_IMMERSIVE_STICKY /
 * WindowInsetsController.hide(navigationBars()), so MainActivity.hideSystemUI() alone
 * cannot remove it. The vendor SystemUI instead listens for broadcast intents whose
 * exact action name varies per firmware build.
 *
 * This helper applies every documented lever in sequence, each guarded so a rejected
 * broadcast / permission never interrupts kiosk startup, and logs what it attempted so
 * the working strategy can be identified on a given panel (no ADB required).
 *
 * NOTE: framework immersive mode is handled separately by MainActivity.hideSystemUI();
 * this helper only covers the OEM-specific / Device-Owner levers.
 */
object SystemBarHelper {
    private const val TAG = "SystemBarHelper"
    const val KEY_HIDE_NAVBAR = "@kiosk_hide_navbar"

    // Rockchip / generic OEM SystemUI broadcast actions. Names differ per firmware, so
    // we fire all of them; an action with no registered receiver is a harmless no-op.
    private val HIDE_ACTIONS = listOf(
        "android.intent.action.HIDE_NAVIGATION_BAR",
        "com.systemui.navigationbar.hide",
        "com.systemui.statusbar.hide",
        "SYSTEM_BAR_HIDE"
    )
    private val SHOW_ACTIONS = listOf(
        "android.intent.action.SHOW_NAVIGATION_BAR",
        "com.systemui.navigationbar.show",
        "com.systemui.statusbar.show",
        "SYSTEM_BAR_SHOW"
    )

    /** Whether the user enabled the "hide navigation bar" kiosk setting. */
    fun isHideNavBarEnabled(context: Context): Boolean =
        readAsyncStorageValue(context, KEY_HIDE_NAVBAR, "false") == "true"

    /**
     * Apply (hide=true) or reverse (hide=false) the OEM navigation-bar hiding.
     * Safe to call repeatedly; every step is best-effort.
     */
    fun applyHideNavigationBar(context: Context, hide: Boolean) {
        // 1. Device Owner: lock down the status bar (stops the shade being pulled down).
        //    Does not remove the OEM nav bar itself, but addresses the related concern.
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, DeviceAdminReceiver::class.java)
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                dpm.setStatusBarDisabled(admin, hide)
                DebugLog.d(TAG, "setStatusBarDisabled($hide) applied")
            }
        } catch (e: Exception) {
            DebugLog.d(TAG, "setStatusBarDisabled failed: ${e.message}")
        }

        // 2. OEM SystemUI broadcasts (firmware-specific; fire all candidates).
        val actions = if (hide) HIDE_ACTIONS else SHOW_ACTIONS
        for (action in actions) {
            sendBar(context, action, null, null)
        }
        // RK3288-style single action carrying a boolean extra (show=false to hide).
        sendBar(context, "android.intent.action.SHOW_NAVIGATION_BAR", "show", !hide)

        // 3. Rockchip firmware key. RK3399 (and similar) MID panels honor a persistent
        //    Settings.System flag "hide_system_bar" that REMOVES the system bar entirely —
        //    not immersive, so it cannot be revealed by a swipe/touch. Settable directly with
        //    `adb shell settings put system hide_system_bar 1`. Writing it from the app needs
        //    WRITE_SETTINGS (grant once via `adb shell appops set com.freekiosk
        //    android:write_settings allow`); best-effort otherwise.
        try {
            Settings.System.putInt(context.contentResolver, "hide_system_bar", if (hide) 1 else 0)
            DebugLog.d(TAG, "hide_system_bar set to ${if (hide) 1 else 0}")
        } catch (e: Exception) {
            DebugLog.d(TAG, "hide_system_bar write blocked (needs WRITE_SETTINGS): ${e.message}")
        }

        // 4. Persistent global overrides. Both live in Settings.Global and therefore need
        //    WRITE_SECURE_SETTINGS (grantable once via ADB: `adb shell pm grant
        //    com.freekiosk android.permission.WRITE_SECURE_SETTINGS`). When held, these make
        //    the navigation bar truly ABSENT — it can no longer be revealed by a swipe/touch,
        //    unlike immersive mode. Harmless SecurityException when the permission is missing.
        try {
            // navigationbar_is_min=1 removes the nav bar on many AOSP/OEM builds (survives reboot).
            Settings.Global.putInt(context.contentResolver, "navigationbar_is_min", if (hide) 1 else 0)
            DebugLog.d(TAG, "navigationbar_is_min set to ${if (hide) 1 else 0}")
        } catch (e: Exception) {
            DebugLog.d(TAG, "navigationbar_is_min write blocked (no WRITE_SECURE_SETTINGS): ${e.message}")
        }
        try {
            val value = if (hide) "immersive.full=*" else ""
            Settings.Global.putString(context.contentResolver, "policy_control", value)
            DebugLog.d(TAG, "policy_control set to '$value'")
        } catch (e: Exception) {
            DebugLog.d(TAG, "policy_control write blocked (no WRITE_SECURE_SETTINGS): ${e.message}")
        }
    }

    private fun sendBar(context: Context, action: String, extraKey: String?, extraVal: Boolean?) {
        try {
            val intent = Intent(action)
            if (extraKey != null && extraVal != null) intent.putExtra(extraKey, extraVal)
            context.sendBroadcast(intent)
            DebugLog.d(TAG, "broadcast sent: $action")
        } catch (e: Exception) {
            DebugLog.d(TAG, "broadcast $action failed: ${e.message}")
        }
    }

    private fun readAsyncStorageValue(context: Context, key: String, default: String): String {
        return try {
            val dbPath = context.getDatabasePath("RKStorage").absolutePath
            val db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = db.rawQuery(
                "SELECT value FROM catalystLocalStorage WHERE key = ?", arrayOf(key))
            val value = if (cursor.moveToFirst()) cursor.getString(0) ?: default else default
            cursor.close()
            db.close()
            value
        } catch (e: Exception) {
            default
        }
    }
}

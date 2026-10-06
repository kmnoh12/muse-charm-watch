package muse.charm.watch

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import muse.gadget.NetworkInfo
import org.json.JSONObject

/** "Is the watch online" for the setup flow: the phone app skips Wi-Fi setup when the device already is. */
class AndroidNetwork(context: Context) : NetworkInfo {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun isOnline(): Boolean {
        val caps = connectivity?.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** One scan entry marked open, so the app skips the password field (the device ignores credentials). */
    override fun currentConnectionEntry(): JSONObject =
        JSONObject().put("ssid", "Current connection").put("rssi", -40).put("secure", false)
}

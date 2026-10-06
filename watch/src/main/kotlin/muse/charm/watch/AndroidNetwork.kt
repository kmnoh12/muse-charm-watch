package muse.charm.watch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import muse.gadget.NetworkInfo
import org.json.JSONObject

/** "Is the watch online" for the setup flow: the phone app skips Wi-Fi setup when the device already is. */
class AndroidNetwork(private val context: Context) : NetworkInfo {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    companion object {
        /** What the Linux SDK offers when it cannot read the network's name. */
        const val CURRENT_CONNECTION_LABEL = "Use current connection"
    }

    /** The Wi-Fi network's real name, if the location permission lets us read it. */
    @Suppress("DEPRECATION")
    fun wifiSsid(): String? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val ssid = context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid ?: return null
        return ssid.trim('"').takeIf { it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID }
    }

    override fun isOnline(): Boolean {
        val caps = connectivity?.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * One scan entry marked open, so the app skips the password field (the device ignores credentials).
     * The real network name lets the phone preselect it when it is on the same network.
     */
    override fun currentConnectionEntry(): JSONObject =
        JSONObject().put("ssid", wifiSsid() ?: CURRENT_CONNECTION_LABEL).put("rssi", -40).put("secure", false)
}

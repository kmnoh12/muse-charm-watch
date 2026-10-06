package muse.gadget

import java.security.SecureRandom

private const val NODE_ID_PREFIX = "homelink-"
private const val BLE_NAME_PREFIX = "MuseGadget"
private val MAC_RE = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")

/**
 * Stable device identity (port of identity.py): a random, locally administered MAC-shaped
 * value generated once and persisted by the app. The node id and the BLE name end in the
 * same six hex digits, which the Muse app relies on.
 */
data class Identity(val mac: String) {
    val suffix: String get() = mac.replace(":", "").takeLast(6)
    val nodeId: String get() = NODE_ID_PREFIX + suffix
    val deviceId: String get() = "hatch-link:$mac"
    val bleName: String get() = BLE_NAME_PREFIX + suffix.uppercase()

    companion object {
        fun isValidMac(mac: String) = MAC_RE.matches(mac)

        fun generate(random: (Int) -> ByteArray = { n -> ByteArray(n).also { SecureRandom().nextBytes(it) } }): Identity {
            val octets = random(6)
            octets[0] = ((octets[0].toInt() and 0xFC) or 0x02).toByte() // unicast, locally administered
            return Identity(octets.joinToString(":") { "%02x".format(it) })
        }
    }
}

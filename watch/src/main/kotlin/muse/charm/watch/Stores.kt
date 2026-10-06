package muse.charm.watch

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import muse.gadget.Identity
import muse.gadget.PairingRecord
import muse.gadget.PairingStore

/** The pairing (device tokens) in app-private storage. Writes are synchronous: pairing waits for them. */
class PrefsPairingStore(context: Context) : PairingStore {
    private val prefs = context.getSharedPreferences("muse_pairing", Context.MODE_PRIVATE)

    override fun load(): PairingRecord? {
        val raw = prefs.getString("record", null) ?: return null
        return try { PairingRecord.fromJson(JSONObject(raw)) } catch (e: JSONException) { null }
    }

    override fun save(record: PairingRecord) {
        check(prefs.edit().putString("record", record.toJson().toString()).commit()) { "could not save the pairing" }
    }

    override fun delete() {
        prefs.edit().remove("record").commit()
    }
}

/** The watch's stable Muse identity: generated once, kept across unpairing (like the Linux SDK). */
fun loadIdentity(context: Context): Identity {
    val prefs = context.getSharedPreferences("muse_identity", Context.MODE_PRIVATE)
    val stored = prefs.getString("mac", null)
    if (stored != null && Identity.isValidMac(stored)) return Identity(stored)
    return Identity.generate().also { prefs.edit().putString("mac", it.mac).commit() }
}

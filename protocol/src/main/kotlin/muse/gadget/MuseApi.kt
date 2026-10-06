package muse.gadget

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Client for the Muse device API: leased VM lookup and device token refresh (port of muse_api.py). */

const val API_BASE = "https://api.muse.ai"
const val FETCH_PATH = "/fetch_vms"
const val REFRESH_PATH = "/device_token/refresh"
const val DEFAULT_USER_AGENT = "musegadget-kt/0.1 (Wear OS)"

class HttpResult(val status: Int, val body: String)

/** Blocking HTTP, so apps can plug in OkHttp and tests can plug in a fake. Throws [IOException] on transport failure. */
fun interface HttpTransport {
    fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResult
}

/** Default transport on `HttpURLConnection`, available on both the JVM and Android. */
object UrlConnectionTransport : HttpTransport {
    override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            return HttpResult(status, stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: "")
        } finally {
            conn.disconnect()
        }
    }
}

class Vm(val vmUrl: String, val vmAuthToken: String, val vmName: String, val vmId: String, val isDefault: Boolean)

class DeviceTokens(val accessToken: String, val refreshToken: String)

/** The URL that API paths are appended to: [apiUrlV2], or the Muse API. `api_url` is ignored (older firmware only). */
fun apiRoot(apiUrlV2: String = ""): String = (apiUrlV2.ifEmpty { API_BASE }).trimEnd('/')

/**
 * Leased VMs for the device token, plus the HTTP status if one arrived. A 401 means the device token
 * was rejected, which a retry won't fix; a null status is a transport failure worth retrying.
 */
fun fetchVmsWithStatus(
    accessToken: String,
    root: String = apiRoot(),
    http: HttpTransport = UrlConnectionTransport,
    userAgent: String = DEFAULT_USER_AGENT,
): Pair<List<Vm>, Int?> {
    val headers = mapOf("Authorization" to "Bearer $accessToken", "X-API-Version" to "1.0.0", "User-Agent" to userAgent)
    val result = try {
        http.request("GET", root + FETCH_PATH, headers, null)
    } catch (e: IOException) {
        return emptyList<Vm>() to null
    }
    if (result.status >= 400) return emptyList<Vm>() to result.status
    val data = try { JSONObject(result.body) } catch (e: JSONException) { return emptyList<Vm>() to result.status }
    if (data.optString("error_title").isNotEmpty() || data.optString("backend_error_code").isNotEmpty()) {
        return emptyList<Vm>() to result.status
    }
    val list: JSONArray = data.optJSONArray("vm_list") ?: return emptyList<Vm>() to result.status
    val vms = (0 until list.length()).mapNotNull { i ->
        val entry = list.optJSONObject(i) ?: return@mapNotNull null
        val url = entry.optString("vm_ws_url").ifEmpty { entry.optString("vm_url") }
        val token = entry.optString("vm_auth_token")
        if (url.isEmpty() || token.isEmpty()) null
        else Vm(url, token, entry.optString("vm_name"), entry.optString("vm_id"), entry.optBoolean("default", false))
    }
    return vms to result.status
}

/**
 * Rotates the device token pair with the refresh token. Returns (tokens, status): tokens is null on
 * failure; a 401 means the pairing is gone and the device must be paired again. The access token is
 * never presented, as on the ESP32.
 */
fun refreshDeviceToken(
    refreshToken: String,
    deviceId: String,
    root: String = apiRoot(),
    sdkToken: String? = null,
    http: HttpTransport = UrlConnectionTransport,
    userAgent: String = DEFAULT_USER_AGENT,
): Pair<DeviceTokens?, Int?> {
    // Apps hand over refresh tokens that already carry the hatch_refresh: prefix; doubling it makes the server reject it.
    val rawRefresh = refreshToken.substringAfterLast(':')
    val body = JSONObject().put("device_id", deviceId).also { if (!sdkToken.isNullOrEmpty()) it.put("sdk_token", sdkToken) }
    val headers = mapOf("Authorization" to "Bearer hatch_refresh:$rawRefresh", "Content-Type" to "application/json", "User-Agent" to userAgent)
    val result = try {
        http.request("POST", root + REFRESH_PATH, headers, body.toString().toByteArray())
    } catch (e: IOException) {
        return null to null
    }
    if (result.status >= 400) return null to result.status
    var data = try { JSONObject(result.body) } catch (e: JSONException) { return null to null }
    data.optJSONObject("payload")?.let { data = it }
    val access = data.optString("access_token")
    val refresh = data.optString("refresh_token")
    return if (access.isNotEmpty() && refresh.isNotEmpty()) DeviceTokens(access, refresh) to 200 else null to 200
}

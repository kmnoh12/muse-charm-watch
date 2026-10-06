package muse.gadget

import java.io.IOException
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MuseApiTest {
    private class Recorder(private val reply: () -> HttpResult) : HttpTransport {
        val requests = mutableListOf<Triple<String, Map<String, String>, JSONObject?>>()
        override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResult {
            requests += Triple("$method $url", headers, body?.let { JSONObject(String(it)) })
            return reply()
        }
    }

    private val tokens = """{"access_token":"new-a","refresh_token":"new-r"}"""

    @Test
    fun refreshSendsOnlyTheDeviceIdWithoutAKey() {
        val http = Recorder { HttpResult(200, tokens) }
        val (t, status) = refreshDeviceToken("r", "homelink-abcdef", http = http)
        assertEquals(200, status)
        assertEquals("new-a" to "new-r", t!!.accessToken to t.refreshToken)
        val (call, headers, body) = http.requests.single()
        assertEquals("POST https://api.muse.ai/device_token/refresh", call)
        assertEquals("Bearer hatch_refresh:r", headers["Authorization"])
        assertEquals(setOf("device_id"), body!!.keySet())
    }

    @Test
    fun refreshSendsTheSdkToken() {
        val http = Recorder { HttpResult(200, tokens) }
        refreshDeviceToken("r", "homelink-abcdef", sdkToken = "mgst_token", http = http)
        assertEquals("mgst_token", http.requests.single().third!!.getString("sdk_token"))
    }

    @Test
    fun refreshNeverPresentsTheAccessTokenOrDoublesThePrefix() {
        for (stored in listOf("hatch_refresh:raw-r", "raw-r")) {
            val http = Recorder { HttpResult(200, tokens) }
            refreshDeviceToken(stored, "homelink-abcdef", http = http)
            assertEquals("Bearer hatch_refresh:raw-r", http.requests.single().second["Authorization"])
        }
    }

    @Test
    fun refreshUnwrapsAPayloadEnvelope() {
        val http = Recorder { HttpResult(200, """{"payload":$tokens}""") }
        assertEquals("new-a", refreshDeviceToken("r", "d", http = http).first!!.accessToken)
    }

    @Test
    fun aRejectedRefreshReports401() {
        val http = Recorder { HttpResult(401, "") }
        assertEquals(null to 401, refreshDeviceToken("r", "d", http = http))
    }

    @Test
    fun aTransportFailureHasNoStatus() {
        val http = HttpTransport { _, _, _, _ -> throw IOException("offline") }
        assertEquals(null to null, refreshDeviceToken("r", "d", http = http))
        assertEquals(emptyList<Vm>() to null, fetchVmsWithStatus("t", http = http))
    }

    @Test
    fun fetchVmsParsesTheLeasedVmsAndSkipsIncompleteOnes() {
        val body = """{"vm_list":[
            {"vm_ws_url":"wss://a","vm_auth_token":"ta","vm_name":"alpha","vm_id":"id-a","default":true},
            {"vm_url":"wss://b","vm_auth_token":"tb","vm_name":"beta","vm_id":"id-b"},
            {"vm_url":"wss://c"}, "junk"]}"""
        val http = Recorder { HttpResult(200, body) }
        val (vms, status) = fetchVmsWithStatus("access", http = http)
        assertEquals(200, status)
        assertEquals(listOf("alpha", "beta"), vms.map { it.vmName })
        assertEquals(listOf(true, false), vms.map { it.isDefault })
        assertEquals("wss://b", vms[1].vmUrl)
        val (call, headers, _) = http.requests.single()
        assertEquals("GET https://api.muse.ai/fetch_vms", call)
        assertEquals("Bearer access", headers["Authorization"])
        assertEquals("1.0.0", headers["X-API-Version"])
    }

    @Test
    fun fetchVmsReportsErrorsWithTheirStatus() {
        assertEquals(emptyList<Vm>() to 401, fetchVmsWithStatus("t", http = Recorder { HttpResult(401, "") }))
        val errBody = """{"error_title":"nope","vm_list":[{"vm_url":"u","vm_auth_token":"t"}]}"""
        assertEquals(emptyList<Vm>() to 200, fetchVmsWithStatus("t", http = Recorder { HttpResult(200, errBody) }))
        assertEquals(emptyList<Vm>() to 200, fetchVmsWithStatus("t", http = Recorder { HttpResult(200, "not json") }))
        assertEquals(emptyList<Vm>() to 200, fetchVmsWithStatus("t", http = Recorder { HttpResult(200, "{}") }))
    }

    @Test
    fun apiRootPrefersTheV2UrlAndTrimsTheSlash() {
        assertEquals("https://api.muse.ai", apiRoot())
        assertEquals("https://x.example/v2", apiRoot("https://x.example/v2/"))
        assertFalse(apiRoot().endsWith("/"))
        assertTrue(apiRoot("").startsWith("https://"))
        assertNull(null)
    }
}

package com.orcaphone

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orcaphone.cloud.LoopbackServer
import com.orcaphone.cloud.OrcaCloudClient
import com.orcaphone.cloud.OrcaSession
import com.orcaphone.cloud.SecureStore
import com.orcaphone.core.orca.OrcaCatalog
import com.orcaphone.core.orca.OrcaKind
import com.orcaphone.core.orca.OrcaPresetMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Android-only pieces of the Orca Cloud integration (no network access to Orca's servers needed). */
@RunWith(AndroidJUnit4::class)
class CloudOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun catalogLoadsFromApkAndMaps() {
        val t0 = System.currentTimeMillis()
        val cat = OrcaCatalog.load()
        android.util.Log.i("OrcaPhoneTest", "catalog load ${System.currentTimeMillis() - t0} ms")
        assertTrue(cat.names(OrcaKind.PRINTER).size > 1000)
        val p = OrcaPresetMapper.toPrinter(cat.get(OrcaKind.PRINTER, "Bambu Lab X1 Carbon 0.4 nozzle")!!, "x", "X1C")
        assertEquals(256.0, p.bedWidth, 0.0)
        val content = mapOf("type" to "print", "inherits" to "0.20mm Standard @BBL X1C", "sparse_infill_density" to "40%")
        val proc = OrcaPresetMapper.toProcess(OrcaPresetMapper.resolve(cat, OrcaKind.PROCESS, content), "p", "P")
        assertEquals(40.0, proc.sparseInfillDensity, 0.0)
    }

    @Test
    fun secureStoreRoundTrip() {
        val f = File(ctx.cacheDir, "secure-test.bin")
        val store = SecureStore(f)
        store.write("{\"refresh\":\"abc\"}")
        assertTrue(f.readBytes().toString(Charsets.ISO_8859_1).indexOf("abc") < 0)
        assertEquals("{\"refresh\":\"abc\"}", store.read())
        store.write(null)
        assertNull(store.read())
    }

    @Test
    fun loopbackReceivesRedirect() {
        var got: Map<String, String>? = null
        val server = LoopbackServer { got = it; true }
        val port = server.start(OrcaCloudClient.LOOPBACK_PORT)
        try {
            val c = URL("http://localhost:$port/callback?code=abc%20d&orca_state=xyz").openConnection() as HttpURLConnection
            assertEquals(200, c.responseCode)
            assertTrue(c.inputStream.bufferedReader().readText().contains("Signed in"))
            assertEquals("abc d", got!!["code"]); assertEquals("xyz", got!!["orca_state"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun pkceChallengeIsS256OfVerifier() {
        val client = OrcaCloudClient {}
        val p = client.newPkce(41172)
        val expected = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(p.verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        assertEquals(expected, p.challenge)
        assertTrue(p.verifier.length >= 43)
        assertTrue(client.loginConfig(p).contains("\"redirect_uri\":\"http://localhost:41172/callback\""))
    }
}

/** Pull parsing against a local mock of api.orcaslicer.com. */
@RunWith(AndroidJUnit4::class)
class CloudPullTest {
    @Test
    fun parsesOrcaSlicerPullResponses() {
        val server = okhttp3.mockwebserver.MockWebServer()
        // Format written by OrcaSlicer desktop: content object with serialized values.
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(
            """{"upserts":[
              {"id":"a1","name":"My X1C","updated_time":1700000000,"content":{"name":"My X1C","type":"printer","inherits":"Bambu Lab X1 Carbon 0.4 nozzle","z_hop":"0.6"}},
              {"id":"b2","name":"My PLA","updated_time":1700000001,"content":{"name":"My PLA","inherits":"Bambu PLA Basic @BBL X1C","nozzle_temperature":"215"}},
              {"id":"c3","name":"Fine","updated_time":1700000002,"content":"{\"name\":\"Fine\",\"layer_height\":\"0.12\"}"}
            ],"deletes":["zz"],"next_cursor":1700000002}"""))
        server.start()
        try {
            val client = OrcaCloudClient(server.url("/").toString().trimEnd('/')) {}
            client.restore(OrcaSession("token", "refresh", "user", expiresAt = System.currentTimeMillis() / 1000 + 36000))
            val res = client.pull(0)
            assertEquals(3, res.upserts.size)
            assertEquals(listOf("zz"), res.deletes)
            assertEquals(1700000002L, res.nextCursor)
            val cat = OrcaCatalog.load()
            val kinds = res.upserts.map { OrcaPresetMapper.kindOf(it.content, cat) }
            assertEquals(listOf(OrcaKind.PRINTER, OrcaKind.FILAMENT, OrcaKind.PROCESS), kinds)
            val printer = OrcaPresetMapper.toPrinter(OrcaPresetMapper.resolve(cat, OrcaKind.PRINTER, res.upserts[0].content), "p", "My X1C")
            assertEquals(256.0, printer.bedWidth, 0.0); assertEquals(0.6, printer.zHop, 1e-9)
            val fil = OrcaPresetMapper.toFilament(OrcaPresetMapper.resolve(cat, OrcaKind.FILAMENT, res.upserts[1].content), "f", "My PLA")
            assertEquals(215, fil.nozzleTemp)
            assertEquals("0.12", res.upserts[2].content["layer_height"])
            val req = server.takeRequest()
            assertEquals("/api/v1/sync/pull", req.path)
            assertEquals("Bearer token", req.getHeader("Authorization"))
            assertEquals(OrcaCloudClient.PUB_KEY, req.getHeader("apikey"))
        } finally {
            server.shutdown()
        }
    }
}

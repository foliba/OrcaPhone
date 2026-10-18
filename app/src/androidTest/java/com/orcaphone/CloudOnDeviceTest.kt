package com.orcaphone

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orcaphone.cloud.LoopbackServer
import com.orcaphone.cloud.OrcaCloudClient
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

package com.orcaphone

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orcaphone.core.mesh.MeshLoader
import com.orcaphone.core.models.ModelSourceException
import com.orcaphone.core.models.ModelSources
import com.orcaphone.core.models.RemoteFileKind
import com.orcaphone.models.RemoteModelClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Imports a real Printables model through the app's OkHttp client. Skipped when Printables can't be reached. */
@RunWith(AndroidJUnit4::class)
class PrintablesOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun importBenchy() {
        val client = RemoteModelClient(context)
        val (source, id) = ModelSources.resolve("https://www.printables.com/model/3161-3d-benchy", client.sources)!!
        val model = try {
            source.fetchModel(id)
        } catch (e: ModelSourceException) {
            if (e.reason == ModelSourceException.Reason.NETWORK || e.reason == ModelSourceException.Reason.BLOCKED) assumeNoException(e)
            throw e
        }
        val stl = model.files.first { it.kind == RemoteFileKind.STL }
        val dest = File(context.cacheDir, "benchy-test.stl")
        var last = 0L
        client.download(source.downloadUrl(model, stl), dest, isActive = { true }) { last = it }
        assertEquals(stl.sizeBytes, last)
        val loaded = dest.inputStream().use { MeshLoader.load(stl.name, it) }
        assertTrue(loaded.single().mesh.triangleCount > 1000)
        dest.delete()
        assertNotNull(runBlocking { client.preview(stl.previewUrl!!) })
    }
}

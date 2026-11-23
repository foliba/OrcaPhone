package io.github.foliba.phorca.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Log
import com.caverock.androidsvg.SVG
import io.github.foliba.phorca.core.config.PrinterProfile
import io.github.foliba.phorca.core.mesh.BedModelCodec
import io.github.foliba.phorca.core.mesh.Mesh
import io.github.foliba.phorca.core.orca.OrcaCatalog
import io.github.foliba.phorca.core.orca.OrcaKind
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The printer's own bed, as OrcaSlicer shows it: the plate [model] (centred on the printable area, top at
 * z = 0) and the [texture] (logo and markings) stretched over the printable area. Either can be missing.
 */
class BedGraphics(val key: String, val model: Mesh?, val texture: Bitmap?)

/** The bed files baked into the app (assets/beds, made by tools/gen_bed_assets.py from OrcaSlicer 2.5.2). */
object BedAssets {
    private var index: Map<String, Pair<String?, String?>>? = null

    private fun index(context: Context): Map<String, Pair<String?, String?>> = index ?: run {
        val json = JSONObject(context.assets.open("beds/index.json").bufferedReader().use { it.readText() }).getJSONObject("models")
        val m = HashMap<String, Pair<String?, String?>>()
        for (name in json.keys()) {
            val e = json.getJSONObject(name)
            m[name] = e.optString("model").ifEmpty { null } to e.optString("texture").ifEmpty { null }
        }
        m.also { index = it }
    }

    /**
     * The OrcaSlicer printer model of [p]: stored on the profile, or found through the system preset it was
     * made from (printers added before Phorca 0.5.1, Orca Cloud presets).
     */
    suspend fun modelName(p: PrinterProfile, catalog: suspend () -> OrcaCatalog): String? {
        p.printerModel.takeIf { it.isNotEmpty() }?.let { return it }
        val content = p.cloud?.content
        content?.get("printer_model")?.takeIf { it.isNotEmpty() }?.let { return it }
        val cat = catalog()
        for (name in listOfNotNull(content?.get("inherits"), p.name)) {
            cat.get(OrcaKind.PRINTER, name)?.get("printer_model")?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    /** Loads the bed of printer model [model] for a [bedW] x [bedD] mm printable area, or null if there is none. */
    fun load(context: Context, model: String, bedW: Double, bedD: Double): BedGraphics? {
        val (meshFile, texFile) = index(context)[model] ?: return null
        val mesh = meshFile?.let { f ->
            runCatching { BedModelCodec.decode(context.assets.open("beds/$f").use { it.readBytes() }) }
                .onFailure { Log.w("Phorca", "Bed model $f", it) }.getOrNull()
        }
        val tex = texFile?.let { f ->
            runCatching { texture(context, "beds/$f", bedW, bedD) }.onFailure { Log.w("Phorca", "Bed texture $f", it) }.getOrNull()
        }
        if (mesh == null && tex == null) return null
        return BedGraphics("$model/$bedW/$bedD", mesh, tex)
    }

    private fun texture(context: Context, path: String, bedW: Double, bedD: Double): Bitmap {
        if (path.endsWith(".png", ignoreCase = true)) {
            return context.assets.open(path).use { BitmapFactory.decodeStream(it) } ?: error("unreadable PNG")
        }
        // SVG: drawn at up to 2048 px on the long side, in the bed's proportions (stretched like OrcaSlicer)
        val svg = context.assets.open(path).use { SVG.getFromInputStream(it) }
        val scale = 2048.0 / max(bedW, bedD)
        val w = (bedW * scale).roundToInt().coerceAtLeast(16)
        val h = (bedD * scale).roundToInt().coerceAtLeast(16)
        svg.setDocumentWidth(w.toFloat())
        svg.setDocumentHeight(h.toFloat())
        svg.documentPreserveAspectRatio = com.caverock.androidsvg.PreserveAspectRatio.STRETCH
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(Canvas(bmp))
        return bmp
    }
}

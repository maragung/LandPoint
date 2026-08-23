package com.landpoint.app.map.offline

import android.content.Context
import android.content.res.AssetManager
import com.landpoint.app.map.MapProvider
import com.landpoint.app.map.TileSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

/**
 * Writes the stripped-down style a download is defined against.
 *
 * MapLibre defines an offline region by a style URL, and walks that style to work out
 * which resources to fetch. Handing it the style the map actually draws with would
 * make it fetch things the app already has: the six glyph ranges and the two sprite
 * sheets bundled in the APK, pulled down again and stored a second time in the
 * offline database.
 *
 * So the download is defined against a style holding nothing but the sources — same
 * sources, same tile URLs, no layers, no fonts, no icons. What comes back is exactly
 * the tiles, and because tiles are stored against their own URLs, the full asset
 * style renders from them afterwards without knowing any of this happened.
 *
 * The file has to outlive the process. MapLibre re-reads the style URL when it resumes
 * a part-finished region after a restart, so this goes in `filesDir` and stays there.
 */
class OfflineStyleWriter(
    private val context: Context,
    private val assets: AssetManager
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val styleDir: File get() = File(File(context.filesDir, "maps"), "styles")

    /**
     * A `file://` URL for [provider]'s download style, or null when there is nothing
     * this app is allowed to download from it.
     *
     * Both guards are real. The licence gate is the first thing checked, so a source
     * whose terms forbid caching cannot have a download defined against it even by a
     * caller that forgot to look — and only a vector style has a source worth saving
     * this way, since the raster providers are exactly the ones the gate excludes.
     */
    fun downloadStyleUrl(provider: MapProvider): String? {
        if (!provider.allowsOfflineDownload) return null
        val spec = provider.tiles as? TileSpec.VectorStyle ?: return null

        val sources = json
            .parseToJsonElement(readAsset(spec.lightAsset))
            .let { it as? JsonObject }
            ?.get("sources")
            ?.let { it as? JsonObject }
            ?: return null

        val document = buildJsonObject {
            put("version", 8)
            put("name", "LandPoint download")
            put("sources", sources)
            // Empty on purpose: layers are what would drag in fonts and icons.
            put("layers", buildJsonArray { })
        }.toString()

        return try {
            styleDir.mkdirs()
            val file = File(styleDir, "${provider.mode.key}-download.json")
            // Rewritten every time rather than reused. It costs a few kilobytes of
            // I/O once per download and means an app update that changes the bundled
            // style cannot leave a stale definition behind.
            file.writeText(document)
            "file://${file.absolutePath}"
        } catch (e: IOException) {
            null
        }
    }

    private fun readAsset(path: String): String =
        assets.open(path).bufferedReader().use { it.readText() }
}

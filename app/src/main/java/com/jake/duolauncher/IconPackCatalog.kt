package com.jake.duolauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import org.xmlpull.v1.XmlPullParser

/** A normalized index from an icon-pack appfilter file. Android resource loading stays outside this class. */
internal data class IconPackIndex(
    val componentDrawables: Map<String, String>,
    val packageDrawables: Map<String, String>,
) {
    fun drawableFor(component: String): String? = componentDrawables[normalizeComponent(component)]
        ?: packageDrawables[normalizeComponent(component).substringBefore('/')]

    companion object {
        private const val maxEntries = 10_000

        fun parse(xml: String): IconPackIndex {
            val items = Regex("""<item\s+[^>]*>""", RegexOption.IGNORE_CASE).findAll(xml).map { tag ->
                attribute(tag.value, "component") to attribute(tag.value, "drawable")
            }
            return fromItems(items)
        }

        fun parse(parser: XmlPullParser): IconPackIndex = fromItems(sequence {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name.equals("item", ignoreCase = true)) {
                    yield(parser.getAttributeValue(null, "component") to parser.getAttributeValue(null, "drawable"))
                }
                parser.next()
            }
        })

        private fun fromItems(items: Sequence<Pair<String?, String?>>): IconPackIndex {
            val components = linkedMapOf<String, String>()
            val packages = linkedMapOf<String, String>()
            for ((component, candidateDrawable) in items.take(maxEntries)) {
                component ?: continue
                val drawable = candidateDrawable?.takeIf(::validDrawable) ?: continue
                val normalized = normalizeComponent(component)
                if ('/' !in normalized || normalized.length > 512) continue
                components.putIfAbsent(normalized, drawable)
                packages.putIfAbsent(normalized.substringBefore('/'), drawable)
            }
            return IconPackIndex(components, packages)
        }

        private fun attribute(tag: String, name: String): String? = Regex("""\b$name\s*=\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)?.trim()

        private fun validDrawable(value: String): Boolean = value.length in 1..200 && value.all { it.isLetterOrDigit() || it == '_' }

        internal fun normalizeComponent(value: String): String = value.trim()
            .removePrefix("ComponentInfo{").removeSuffix("}")
            .replace(" ", "")
    }
}

internal data class IconPack(val packageName: String, val label: String, private val resources: android.content.res.Resources,
    private val index: IconPackIndex) {
    fun drawableFor(component: String): Drawable? {
        val name = index.drawableFor(component) ?: return null
        val id = resources.getIdentifier(name, "drawable", packageName)
        return id.takeIf { it != 0 }?.let { resourceId -> runCatching { resources.getDrawable(resourceId, null) }.getOrNull() }
    }
}

/** Discovers conventional icon packs without granting them any data or code execution privileges. */
internal object IconPackCatalog {
    private const val maxAppFilterBytes = 2 * 1024 * 1024
    private val actions = listOf(
        "org.adw.launcher.THEMES",
        "com.gau.go.launcherex.theme",
        "com.novalauncher.THEME",
        "com.teslacoilsw.launcher.THEME",
        "com.anddoes.launcher.THEME",
        "com.dlto.atom.launcher.THEME",
    )

    fun installed(context: Context): List<IconPack> {
        val packageManager = context.packageManager
        val packages = linkedSetOf<String>()
        actions.forEach { action ->
            packageManager.queryIntentActivities(Intent(action), PackageManager.MATCH_ALL)
                .mapTo(packages) { it.activityInfo.packageName }
        }
        listOf("com.fede.launcher.THEME_ICONPACK", "com.anddoes.launcher.THEME").forEach { category ->
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), PackageManager.MATCH_ALL)
                .mapTo(packages) { it.activityInfo.packageName }
        }
        return packages.mapNotNull { packageName -> load(context, packageName) }
            .sortedBy { it.label.lowercase() }
    }

    fun load(context: Context, packageName: String): IconPack? = runCatching {
        val packageManager = context.packageManager
        val appInfo = packageManager.getApplicationInfo(packageName, 0)
        val resources = packageManager.getResourcesForApplication(appInfo)
        val index = runCatching {
            BufferedInputStream(resources.assets.open("appfilter.xml")).use { IconPackIndex.parse(readBoundedText(it)) }
        }.getOrElse {
            val resourceId = resources.getIdentifier("appfilter", "xml", packageName)
            require(resourceId != 0) { "Icon pack does not provide appfilter.xml" }
            resources.getXml(resourceId).use(IconPackIndex::parse)
        }
        IconPack(packageName, packageManager.getApplicationLabel(appInfo).toString(), resources, index)
    }.getOrNull()

    private fun readBoundedText(input: BufferedInputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            require(output.size() + read <= maxAppFilterBytes) { "Icon pack appfilter.xml is too large" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray().decodeToString()
    }
}

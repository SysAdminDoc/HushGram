/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.coexist

import app.morphe.patcher.patch.ResourcePatchContext
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private const val DRAWABLE = "android:drawable"
private val ICON_ATTRIBUTES = listOf("android:icon", "android:roundIcon")
private val ICON_HOLDERS = setOf("application", "activity", "activity-alias")

/** A reference to one of the app's own resources, as the decoded manifest and icons write it. */
private val OWN_RESOURCE = Regex("^@([a-z]+)/([A-Za-z0-9_.$]+)$")

/** The badge drawn over the second copy's icons. */
internal const val CLONE_BADGE = "hushgram_clone_badge"

/** Its one-color version, for the themed icons of Android 13 and newer. */
internal const val CLONE_BADGE_MONO = "hushgram_clone_badge_mono"

/** The layers of an adaptive icon the badge goes over, and the badge each takes. */
private val MARKED_LAYERS = linkedMapOf("foreground" to CLONE_BADGE, "monochrome" to CLONE_BADGE_MONO)

/**
 * The badge: HushGram's H and ribbon, as on the launch screen's mark, on a plum disc with a white
 * edge, toward the icon's lower right. An adaptive icon's layers are 108dp, of which launchers show
 * the middle 72dp cut to their shape and promise only the 66dp circle in the middle. The disc sits
 * 12dp right of and below the middle with 14.5dp to its white edge, 31.5dp from the middle at most,
 * so no launcher's shape cuts it. A vector the size of the layer scales with it, at any icon
 * size.
 */
internal val CLONE_BADGE_XML = """
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FFFFFFFF" android:pathData="M66,51.5a14.5,14.5 0 1,1 0,29a14.5,14.5 0 1,1 0,-29z" />
    <path android:fillColor="#FF3B0A3D" android:pathData="M66,53a13,13 0 1,1 0,26a13,13 0 1,1 0,-26z" />
    <group android:translateX="56" android:translateY="56" android:scaleX="0.2" android:scaleY="0.2">
        <path android:fillColor="#FFFFFFFF" android:pathData="M22,15h15a3,3 0 0 1 3,3v64a3,3 0 0 1 -3,3h-15a3,3 0 0 1 -3,-3v-64a3,3 0 0 1 3,-3z" />
        <path android:fillColor="#FFFFFFFF" android:pathData="M63,15h15a3,3 0 0 1 3,3v64a3,3 0 0 1 -3,3h-15a3,3 0 0 1 -3,-3v-64a3,3 0 0 1 3,-3z" />
        <path android:fillColor="#FFFF7A9E" android:pathData="M11,38C22,36 30,46 42,45C54,44 66,36 80,40C84,41 87,44 88,48L88,58C85,53 82,52 78,52C66,50 54,57 42,57C30,57 22,49 11,50Z" />
    </group>
</vector>
""".trimStart()

/**
 * The badge for a themed icon, where Android draws only a layer's shape, in the theme's color: the
 * same disc with the H cut out of it.
 */
internal val CLONE_BADGE_MONO_XML = """
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="M66,51.5a14.5,14.5 0 1,1 0,29a14.5,14.5 0 1,1 0,-29z M59.8,59h4.2v5.6h4v-5.6h4.2v14h-4.2v-5.6h-4v5.6h-4.2z" />
</vector>
""".trimStart()

/** What marking the icons changes, read before anything changes. */
internal class CloneIcons(
    /** Each adaptive icon the badge goes on, by its path in the decoded app, each once. */
    val files: List<String>,
    /** Why the badge can't go on, or null when it can. */
    val problem: String?,
)

private fun Element.children(tag: String): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

private fun Element.all(): List<Element> =
    getElementsByTagName("*").let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

/**
 * The icons the manifest gives the app and its activities and aliases, Instagram's alternate home
 * screen icons among them, as type/name, each once in the manifest's order. Android's own icons
 * aren't the app's to change, so they're left out.
 */
internal fun manifestIcons(manifest: Document): List<String> =
    (listOf(manifest.documentElement) + manifest.documentElement.all())
        .filter { it.tagName in ICON_HOLDERS }
        .flatMap { element -> ICON_ATTRIBUTES.mapNotNull { OWN_RESOURCE.find(element.getAttribute(it)) } }
        .map { "${it.groupValues[1]}/${it.groupValues[2]}" }
        .distinct()

/** The drawable that puts [layer]'s badge over the drawable [reference] names. */
internal fun markedName(layer: String, reference: String): String =
    "hushgram_${layer}_" + reference.removePrefix("@").replace(Regex("[^A-Za-z0-9_]"), "_")

/**
 * Decodes what [readCloneIcons] reads: every version of each of [icons], and any drawable already
 * holding one of the badge's names, which patcher 2.0 would otherwise leave encoded and unseen.
 */
internal fun ResourcePatchContext.decodeCloneIcons(icons: List<String>) {
    val versions = icons.map { icon -> icon.split('/', limit = 2).let { (type, name) -> "res/$type*/$name.xml" } }
    decode(*(versions + "res/drawable*/hushgram_*.xml").toTypedArray())
}

/**
 * Finds the adaptive version of each of [icons] in [res], the decoded app's res folder, and checks the
 * badge can go on each: an icon with no adaptive version, a foreground or monochrome layer that isn't
 * a reference to one of the app's drawables, or a drawable the badge would add that's already there
 * is a problem, and nothing is changed. Densities' bitmaps are only used by Android 7 and older, which
 * Instagram 450 doesn't run on, so they keep Instagram's art.
 */
internal fun readCloneIcons(res: File, icons: List<String>): CloneIcons {
    fun problem(why: String) = CloneIcons(emptyList(), why)
    val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    val files = LinkedHashSet<String>()
    val added = mutableMapOf(CLONE_BADGE to "", CLONE_BADGE_MONO to "")
    try {
        for (icon in icons) {
            val (type, name) = icon.split('/', limit = 2)
            val versions = res.listFiles { dir -> dir.isDirectory && (dir.name == type || dir.name.startsWith("$type-")) }
                .orEmpty().sortedBy { it.name }.map { File(it, "$name.xml") }.filter { it.isFile }
            val adaptive = versions.filter { builder.parse(it).documentElement.tagName == "adaptive-icon" }
            if (adaptive.isEmpty()) return problem("the icon @$icon has no adaptive version")
            for (file in adaptive) {
                val root = builder.parse(file).documentElement
                if (root.children("foreground").size != 1) return problem("the icon @$icon has no single foreground")
                for (layer in MARKED_LAYERS.keys) for (element in root.children(layer)) {
                    val reference = element.getAttribute(DRAWABLE)
                    if (!OWN_RESOURCE.matches(reference)) return problem("the icon @$icon's $layer isn't a reference to one of the app's drawables")
                    val name = markedName(layer, reference)
                    if (added.put(name, reference).let { it != null && it != reference }) return problem("two layers would be marked as $name")
                }
                files += "res/${file.parentFile.name}/${file.name}"
            }
        }
    } catch (failure: Exception) {
        return problem("an icon couldn't be read: ${failure.message}")
    }
    added.keys.firstOrNull { File(res, "drawable/$it.xml").exists() }?.let { return problem("res/drawable already has a $it") }
    return CloneIcons(files.toList(), null)
}

/** A drawable that draws [reference] and [badge] over it, both the size of the layer. */
internal fun markedXml(reference: String, badge: String): String = """
<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="$reference" />
    <item android:drawable="@drawable/$badge" />
</layer-list>
""".trimStart()

/**
 * Points [icon]'s foreground and monochrome layers at drawables that add the badge over them, and
 * returns those drawables by name. The background stays as it is.
 */
internal fun markAdaptiveIcon(icon: Document): Map<String, String> {
    val added = LinkedHashMap<String, String>()
    for ((layer, badge) in MARKED_LAYERS) for (element in icon.documentElement.children(layer)) {
        val reference = element.getAttribute(DRAWABLE)
        val name = markedName(layer, reference)
        added[name] = markedXml(reference, badge)
        element.setAttribute(DRAWABLE, "@drawable/$name")
    }
    return added
}

/** Puts the badge on each adaptive icon in [files], which [readCloneIcons] found with no problem. */
internal fun ResourcePatchContext.markCloneIcons(files: List<String>) {
    val drawables = File(get("res"), "drawable")
    val added = sortedMapOf(CLONE_BADGE to CLONE_BADGE_XML, CLONE_BADGE_MONO to CLONE_BADGE_MONO_XML)
    for (file in files) document(file).use { added += markAdaptiveIcon(it) }
    drawables.mkdirs()
    for ((name, xml) in added) File(drawables, "$name.xml").writeText(xml)
}

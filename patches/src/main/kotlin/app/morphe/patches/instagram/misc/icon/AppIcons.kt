/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.icon

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

internal const val ICON_PATCH = "Pick the app icon"

private const val NAME = "android:name"
private const val DRAWABLE = "android:drawable"

/** Instagram's launcher alias, which is the home screen icon until another one is picked. */
internal const val INSTAGRAM_ALIAS = "com.instagram.android.activity.MainTabActivity"

/** Each HushGram icon's alias is Instagram's name, then this and the icon's key, as AppIcon in the extension reads it. */
internal const val HUSHGRAM_ALIAS = "$INSTAGRAM_ALIAS.hushgram_"

/** A reference to one of the app's own resources, as the decoded manifest and icons write it. */
private val OWN_RESOURCE = Regex("^@([a-z]+)/([A-Za-z0-9_.$]+)$")

/**
 * One of HushGram's home screen icons, by the key its alias and drawables take (AppIconChoice in the
 * extension has the same keys), with its background drawn by [background]. [instagramGlyph] puts
 * Instagram's own white glyph, the monochrome layer of its icon, in front, and otherwise HushGram's H
 * and ribbon go there. A [glyphTint] draws that glyph in its color instead, from a copy of it.
 */
internal class HushgramIcon(val key: String, val background: String, val instagramGlyph: Boolean, val glyphTint: String? = null) {
    val alias get() = "$HUSHGRAM_ALIAS$key"
    val icon get() = "hushgram_icon_$key"
    val backgroundName get() = "hushgram_icon_${key}_background"
    val glyphName get() = "hushgram_icon_${key}_glyph"
}

/** Plum, the color of HushGram's mark. */
private const val PLUM = "#FF3B0A3D"

private fun solid(color: String) = """
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="$color" />
</shape>
""".trimStart()

/** The pink to plum of the launch screen's tile, corner to corner. */
private val GRADIENT = """
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <gradient android:type="linear" android:angle="315" android:startColor="#FFF0186C" android:endColor="$PLUM" />
</shape>
""".trimStart()

internal val HUSHGRAM_ICONS = listOf(
    HushgramIcon("hushgram", GRADIENT, instagramGlyph = false),
    HushgramIcon("dark", solid("#FF000000"), instagramGlyph = true),
    HushgramIcon("plum", solid(PLUM), instagramGlyph = true),
    HushgramIcon("light", solid("#FFFFFFFF"), instagramGlyph = true, glyphTint = "#FF000000"),
)

/** HushGram's H and ribbon in front of an icon. */
internal const val ICON_MARK = "hushgram_icon_mark"

/** Their one-color shape, for the themed icons of Android 13 and newer. */
internal const val ICON_MARK_MONO = "hushgram_icon_mark_mono"

/**
 * The launch screen's H and ribbon, drawn on a 100 unit square, scaled to 56% and centered in the
 * 108dp layer. Their corners sit 29dp from the middle at most, inside the 66dp circle every launcher
 * shape keeps.
 */
private fun mark(ribbon: String) = """
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <group android:translateX="26.3" android:translateY="26" android:scaleX="0.56" android:scaleY="0.56">
        <path android:fillColor="#FFFFFFFF" android:pathData="M22,15h15a3,3 0 0 1 3,3v64a3,3 0 0 1 -3,3h-15a3,3 0 0 1 -3,-3v-64a3,3 0 0 1 3,-3z" />
        <path android:fillColor="#FFFFFFFF" android:pathData="M63,15h15a3,3 0 0 1 3,3v64a3,3 0 0 1 -3,3h-15a3,3 0 0 1 -3,-3v-64a3,3 0 0 1 3,-3z" />
        <path android:fillColor="$ribbon" android:pathData="M11,38C22,36 30,46 42,45C54,44 66,36 80,40C84,41 87,44 88,48L88,58C85,53 82,52 78,52C66,50 54,57 42,57C30,57 22,49 11,50Z" />
    </group>
</vector>
""".trimStart()

internal val ICON_MARK_XML = mark("#FFFF7A9E")
internal val ICON_MARK_MONO_XML = mark("#FFFFFFFF")

/** An adaptive icon of [background], [foreground] and [monochrome], each a drawable reference. */
internal fun adaptiveXml(background: String, foreground: String, monochrome: String) = """
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="$background" />
    <foreground android:drawable="$foreground" />
    <monochrome android:drawable="$monochrome" />
</adaptive-icon>
""".trimStart()

/** Instagram's own icon as the patch reads it: where its adaptive version is, and its glyph. */
internal class InstagramIcon(
    /** The res folder holding the adaptive version, such as mipmap-anydpi, where HushGram's go too. */
    val folder: String,
    /** The white glyph Instagram's themed icon shows, as a drawable reference. */
    val glyph: String,
)

private fun refuse(why: String): Nothing = throw PatchException("$ICON_PATCH: $why")

private fun Element.children(tag: String): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

private fun Element.all(tag: String): List<Element> =
    getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

/** [name] as the manifest's [owner] package spells it in full. */
private fun fullName(name: String, owner: String) = if (name.startsWith(".")) owner + name else name

/** The application's icon in [manifest], as type/name. */
internal fun applicationIcon(manifest: Document): String {
    val application = manifest.documentElement.children("application").singleOrNull() ?: refuse("the manifest has no single application")
    val icon = OWN_RESOURCE.find(application.getAttribute("android:icon")) ?: refuse("the application's icon isn't one of the app's resources")
    return "${icon.groupValues[1]}/${icon.groupValues[2]}"
}

/**
 * Finds [icon]'s adaptive version in [res], the decoded res folder, and the monochrome layer HushGram's
 * dark and plum icons put in front. Refuses unless there is exactly one adaptive version and its
 * monochrome layer is a reference to one of the app's drawables.
 */
internal fun readInstagramIcon(res: File, icon: String): InstagramIcon {
    val (type, name) = icon.split('/', limit = 2)
    val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    val adaptive = res.listFiles { dir -> dir.isDirectory && (dir.name == type || dir.name.startsWith("$type-")) }
        .orEmpty().sortedBy { it.name }.map { File(it, "$name.xml") }.filter { it.isFile }
        .filter { builder.parse(it).documentElement.tagName == "adaptive-icon" }
    val file = adaptive.singleOrNull() ?: refuse("expected one adaptive version of @$icon, found ${adaptive.size}")
    val glyph = builder.parse(file).documentElement.children("monochrome").singleOrNull()?.getAttribute(DRAWABLE)
        ?: refuse("@$icon has no single monochrome layer")
    if (!OWN_RESOURCE.matches(glyph)) refuse("@$icon's monochrome layer isn't one of the app's drawables")
    return InstagramIcon(file.parentFile.name, glyph)
}

/**
 * Adds an alias per HushGram icon to [manifest], each a copy of Instagram's launcher alias, intent
 * filters and shortcuts included, so it opens the app the same way. Each is off until the extension
 * turns it on, shows its own icon, and carries the two meta-data Instagram's own alternate icons carry,
 * which tell Instagram's component manager to leave it as it is. Refuses unless Instagram's alias is
 * there once and no HushGram alias is.
 */
internal fun addIconAliases(manifest: Document) {
    val root = manifest.documentElement
    val owner = root.getAttribute("package")
    val aliases = root.all("activity-alias")
    val names = aliases.map { fullName(it.getAttribute(NAME), owner) }
    val instagram = aliases.filterIndexed { index, _ -> names[index] == INSTAGRAM_ALIAS }.singleOrNull()
        ?: refuse("expected one $INSTAGRAM_ALIAS alias, found ${names.count { it == INSTAGRAM_ALIAS }}")
    names.firstOrNull { it.startsWith(HUSHGRAM_ALIAS) }?.let { refuse("the manifest already has $it") }
    var after: Element = instagram
    for (icon in HUSHGRAM_ICONS) {
        val alias = instagram.cloneNode(true) as Element
        alias.setAttribute(NAME, icon.alias)
        alias.setAttribute("android:enabled", "false")
        alias.setAttribute("android:icon", "@mipmap/${icon.icon}")
        if (alias.hasAttribute("android:roundIcon")) alias.setAttribute("android:roundIcon", "@mipmap/${icon.icon}")
        for ((key, value) in listOf("default-state" to "false", "cmp_manager.persist_state" to "true").asReversed()) {
            val meta = manifest.createElement("meta-data")
            meta.setAttribute(NAME, key)
            meta.setAttribute("android:value", value)
            alias.insertBefore(meta, alias.firstChild)
        }
        after.parentNode.insertBefore(alias, after.nextSibling)
        after = alias
    }
}

/**
 * The vector Instagram's [glyph] draws, as the decoded file in [res] reads. Refuses unless there is
 * exactly one and it's a vector, which a tinted copy needs.
 */
internal fun readGlyph(res: File, glyph: String): String {
    val (type, name) = OWN_RESOURCE.find(glyph)?.destructured ?: refuse("$glyph isn't one of the app's drawables")
    val files = res.listFiles { dir -> dir.isDirectory && (dir.name == type || dir.name.startsWith("$type-")) }
        .orEmpty().sortedBy { it.name }.map { File(it, "$name.xml") }.filter { it.isFile }
    val file = files.singleOrNull() ?: refuse("expected one $glyph, found ${files.size}")
    if (DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement.tagName != "vector") {
        refuse("$glyph isn't a vector")
    }
    return file.readText()
}

/** [vector] drawn in [color]: its own tint, whose default src_in mode keeps every path's shape and alpha. */
internal fun tinted(vector: String, color: String): String {
    val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(vector.byteInputStream())
    document.documentElement.setAttribute("android:tint", color)
    val out = StringWriter()
    TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(out))
    return out.toString()
}

/**
 * Every drawable and icon the patch adds, by its path in the decoded res folder, for [instagram]'s icon
 * and [glyphVector], the vector its glyph draws.
 */
internal fun iconFiles(instagram: InstagramIcon, glyphVector: String): Map<String, String> {
    val files = linkedMapOf("drawable/$ICON_MARK.xml" to ICON_MARK_XML, "drawable/$ICON_MARK_MONO.xml" to ICON_MARK_MONO_XML)
    for (icon in HUSHGRAM_ICONS) {
        files["drawable/${icon.backgroundName}.xml"] = icon.background
        icon.glyphTint?.let { files["drawable/${icon.glyphName}.xml"] = tinted(glyphVector, it) }
        val front = when {
            !icon.instagramGlyph -> "@drawable/$ICON_MARK"
            icon.glyphTint != null -> "@drawable/${icon.glyphName}"
            else -> instagram.glyph
        }
        val mono = if (icon.instagramGlyph) instagram.glyph else "@drawable/$ICON_MARK_MONO"
        files["${instagram.folder}/${icon.icon}.xml"] = adaptiveXml("@drawable/${icon.backgroundName}", front, mono)
    }
    return files
}

/**
 * Adds the aliases and their icons. Instagram's icon is read for where its adaptive version lives and
 * for its glyph. Refuses before anything changes when a file the patch adds is already there.
 */
internal fun ResourcePatchContext.addAppIcons() {
    document("AndroidManifest.xml").use { manifest ->
        val icon = applicationIcon(manifest)
        val (type, name) = icon.split('/', limit = 2)
        // Patcher 2.0 decodes only what a patch asks for. HushGram's names are asked for so one
        // already in the APK shows up and is refused.
        decode("res/$type*/$name.xml", "res/drawable*/hushgram_icon_*.xml", "res/mipmap*/hushgram_icon_*.xml")
        val res = get("res")
        val instagram = readInstagramIcon(res, icon)
        val (glyphType, glyphName) = instagram.glyph.removePrefix("@").split('/', limit = 2)
        decode("res/$glyphType*/$glyphName.xml")
        val files = iconFiles(instagram, readGlyph(res, instagram.glyph))
        files.keys.firstOrNull { File(res, it).exists() }?.let { refuse("res/$it is already there") }
        addIconAliases(manifest)
        for ((path, xml) in files) File(res, path).apply { parentFile.mkdirs() }.writeText(xml)
    }
}

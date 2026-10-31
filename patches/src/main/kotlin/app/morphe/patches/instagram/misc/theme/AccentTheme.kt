/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

private const val PATCH = "Accent color"

internal const val ACCENT_THEME = "$EXTENSION_PACKAGE/misc/AccentTheme;"
internal const val APPLY_THEME = "$ACCENT_THEME->apply(Landroid/content/Context;)V"

/**
 * The extension's stub the patch writes the attribute list into, space separated. Not a string
 * resource: Instagram answers those from its string packs by number, so an added one reads back as
 * one of Instagram's own strings.
 */
internal const val ATTRIBUTES_STUB = "$ACCENT_THEME->attributes()Ljava/lang/String;"

/** The activity every screen of Instagram's own extends, whose theme setup ends with Prism's overrides. */
internal const val IG_FRAGMENT_ACTIVITY = "Lcom/instagram/base/activity/IgFragmentActivity;"

/** Every theme attribute [accentThemePatch] gave a style of its own, space separated, once it has run. */
internal var accentAttributes: String? = null

private const val CONTEXT = "Landroid/content/Context;"
private const val THEME = "Landroid/content/res/Resources\$Theme;"

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")

/** The part an accent blue plays, as AccentColor.Role has it. */
internal enum class AccentRole { FILL, PRESSED, DIM, LINK_ON_LIGHT, LINK_ON_DARK, PALE }

/** Instagram's accent blues by their red, green and blue, as AccentColor.role reads them. */
internal val ACCENT_BLUES: Map<Int, AccentRole> = mapOf(
    0x0095f6 to AccentRole.FILL, 0x4a5df9 to AccentRole.FILL, 0x5c77ff to AccentRole.FILL,
    0x5465fe to AccentRole.FILL, 0x4150f7 to AccentRole.FILL,
    0x3b52e7 to AccentRole.PRESSED,
    0x232e67 to AccentRole.DIM,
    0x3143e3 to AccentRole.LINK_ON_LIGHT, 0x3849da to AccentRole.LINK_ON_LIGHT, 0x00376b to AccentRole.LINK_ON_LIGHT,
    0x85a1ff to AccentRole.LINK_ON_DARK, 0x8593ff to AccentRole.LINK_ON_DARK, 0x708dff to AccentRole.LINK_ON_DARK,
    0xe0f1ff to AccentRole.PALE, 0xeff1ff to AccentRole.PALE, 0xebf1ff to AccentRole.PALE,
    0xdbdfff to AccentRole.PALE, 0xccddff to AccentRole.PALE,
)

/**
 * A color the theme can turn Instagram's blue into, as AccentHue has it: the three shades it's given
 * and the three worked out from its fill, the same way.
 */
internal class AccentPalette(val name: String, fill: Int, linkOnLight: Int, linkOnDark: Int) {
    val shades: Map<AccentRole, Int> = mapOf(
        AccentRole.FILL to fill,
        AccentRole.PRESSED to scale(fill, 0.85f),
        AccentRole.DIM to scale(fill, 0.45f),
        AccentRole.LINK_ON_LIGHT to linkOnLight,
        AccentRole.LINK_ON_DARK to linkOnDark,
        AccentRole.PALE to towardWhite(fill, 0.878f),
    )

    private companion object {
        fun towardWhite(rgb: Int, share: Float): Int = channels(rgb) { it + Math.round((0xff - it) * share) }

        fun scale(rgb: Int, factor: Float): Int = channels(rgb) { Math.round(it * factor) }

        fun channels(rgb: Int, change: (Int) -> Int): Int =
            (change((rgb shr 16) and 0xff) shl 16) or (change((rgb shr 8) and 0xff) shl 8) or change(rgb and 0xff)
    }
}

/** Every color but Instagram's own blue, as AccentHue lists them. */
internal val ACCENT_PALETTES = listOf(
    AccentPalette("PURPLE", 0x7638fa, 0x5806e0, 0xa07cfd),
    AccentPalette("PINK", 0xd6005a, 0xc1014f, 0xff6fa2),
    AccentPalette("RED", 0xe0010d, 0xc4000b, 0xff6b78),
    AccentPalette("ORANGE", 0xd95700, 0xb54800, 0xff9a40),
    AccentPalette("GREEN", 0x118841, 0x0d7a39, 0x3ee581),
    AccentPalette("TEAL", 0x00838f, 0x00727c, 0x4dd0d9),
)

/**
 * Material You (#187, #264), AccentHue.WALLPAPER: the step of Android 12's first accent family each
 * part takes, as MaterialYou.java reads them (system_accent1_500 is tone 50).
 */
internal val WALLPAPER_STEPS: Map<AccentRole, Int> = mapOf(
    AccentRole.FILL to 500, AccentRole.PRESSED to 600, AccentRole.DIM to 800,
    AccentRole.LINK_ON_LIGHT to 600, AccentRole.LINK_ON_DARK to 200, AccentRole.PALE to 50,
)

/**
 * The same tones of Material's own purple, for the default configuration. The extension offers the
 * wallpaper only on Android 12 and newer, where values-v31 answers, so these never show.
 */
internal val WALLPAPER_FALLBACK: Map<AccentRole, Int> = mapOf(
    AccentRole.FILL to 0x7f67be, AccentRole.PRESSED to 0x6750a4, AccentRole.DIM to 0x381e72,
    AccentRole.LINK_ON_LIGHT to 0x6750a4, AccentRole.LINK_ON_DARK to 0xd0bcff, AccentRole.PALE to 0xf6edff,
)

internal const val WALLPAPER = "WALLPAPER"

internal fun wallpaperColor(shade: String): String = "hushgram_accent_wallpaper_$shade"

internal fun systemColor(role: AccentRole): String = "@android:color/system_accent1_${WALLPAPER_STEPS.getValue(role)}"

internal const val WALLPAPER_PALETTE_STYLE = "HushGramAccentPalette_$WALLPAPER"

internal const val WALLPAPER_BADGE_COLOR = "hushgram_accent_wallpaper_badge"

internal const val WALLPAPER_BADGE_STYLE = "HushGramAccentBadge_$WALLPAPER"

/** One theme attribute set to an accent blue somewhere in Instagram's styles, with the blue's alpha. */
internal data class AccentUse(val attribute: String, val role: AccentRole, val alpha: Int) {
    val shade: String get() = shadeName(role, alpha)
    val style: String get() = "HushGramAccent_${attribute.replace(':', '_')}_$shade"
}

internal fun shadeName(role: AccentRole, alpha: Int): String =
    "${role.name.lowercase(Locale.ROOT)}_%02x".format(alpha)

internal fun shadeAttribute(shade: String): String = "hushgram_accent_$shade"

internal fun paletteColor(palette: AccentPalette, shade: String): String =
    "hushgram_accent_${palette.name.lowercase(Locale.ROOT)}_$shade"

internal fun paletteStyle(palette: AccentPalette): String = "HushGramAccentPalette_${palette.name}"

/**
 * The theme attribute Instagram's red badges read, the dots and counts on its icons (#315). The like
 * heart and the red delete buttons read others, so pointing this one at the picked color leaves them red.
 */
internal const val BADGE_ATTRIBUTE = "igds_color_icon_badge"

internal fun badgeColor(palette: AccentPalette): String = "hushgram_accent_${palette.name.lowercase(Locale.ROOT)}_badge"

internal fun badgeStyle(palette: AccentPalette): String = "HushGramAccentBadge_${palette.name}"

/**
 * The theme attribute Instagram's icons read, through `igdsPrimaryIcon` among others (#8). Its themes
 * point it at the primary text color, so setting it colors the icons and leaves the text alone.
 */
internal const val ICON_ATTRIBUTE = "igds_color_primary_icon"

/**
 * The glyph attributes the icon styles set along with [ICON_ATTRIBUTE] (#8). Only Instagram's forced
 * dark feed overlay ties them to it: its light and dark themes give each a color of its own (grey 9,
 * or the primary text color and white), so the top bar's heart, a screen's back and overflow buttons,
 * the post buttons, the selected profile tab and the saved bookmark kept that color. Each holds icons
 * alone. The unselected tabs' `glyphColorSecondary` stays gray, and the liked heart's red is
 * `igds_color_icon_badge`, so neither is in the list.
 */
internal val GLYPH_ATTRIBUTES = listOf("glyphColorPrimary", "glyphColorSecondaryActive", "feedSaveActiveColor")

/** [ICON_ATTRIBUTE] and each of [GLYPH_ATTRIBUTES] that [attrs] declares, or none without [ICON_ATTRIBUTE]. */
private fun iconAttributes(attrs: Document): List<String> {
    val declared = attrs.children("attr").mapTo(HashSet()) { it.getAttribute("name") }
    return if (ICON_ATTRIBUTE in declared) listOf(ICON_ATTRIBUTE) + GLYPH_ATTRIBUTES.filter { it in declared } else emptyList()
}

/** A style named [name] setting each of [attributes] to the color resource [color]. */
private fun Document.colorStyle(name: String, attributes: List<String>, color: String): Element =
    createElement("style").apply {
        setAttribute("name", name)
        for (attribute in attributes) {
            appendChild(createElement("item").apply {
                setAttribute("name", attribute)
                textContent = "@color/$color"
            })
        }
    }

/** The theme an icon color is for, and the shade it takes there: a link's, made to read on that background. */
internal enum class IconTheme(val role: AccentRole) { LIGHT(AccentRole.LINK_ON_LIGHT), DARK(AccentRole.LINK_ON_DARK) }

internal fun iconColor(palette: String, theme: IconTheme): String =
    "hushgram_accent_${palette.lowercase(Locale.ROOT)}_icon_${theme.name.lowercase(Locale.ROOT)}"

internal fun iconStyle(palette: String, theme: IconTheme): String =
    "HushGramAccentIcons_${palette}_${theme.name.lowercase(Locale.ROOT)}"

private fun Document.children(tag: String): List<Element> {
    val list = documentElement.getElementsByTagName(tag)
    return (0 until list.length).map { list.item(it) as Element }
}

private fun Element.children(tag: String): List<Element> {
    val list = getElementsByTagName(tag)
    return (0 until list.length).map { list.item(it) as Element }
}

/** [text] as a color, for #rgb, #argb, #rrggbb and #aarrggbb, or null for anything else. */
internal fun parseColor(text: String): Int? {
    val hex = text.trim().removePrefix("#").takeIf { text.trim().startsWith("#") } ?: return null
    if (hex.any { Character.digit(it, 16) < 0 }) return null
    val full = when (hex.length) {
        3 -> "ff" + hex.map { "$it$it" }.joinToString("")
        4 -> hex.map { "$it$it" }.joinToString("")
        6 -> "ff$hex"
        8 -> hex
        else -> return null
    }
    return full.toLong(16).toInt()
}

/**
 * Every value each color resource takes in [colors] (one decoded colors.xml per configuration), by
 * name. A color naming another color takes that one's values, a few steps deep.
 */
internal fun colorValues(colors: List<Document>): Map<String, Set<Int>> {
    val raw = HashMap<String, MutableList<String>>()
    for (document in colors) {
        for (color in document.children("color")) raw.getOrPut(color.getAttribute("name")) { ArrayList() } += color.textContent.trim()
    }
    fun resolve(name: String, depth: Int): Set<Int> = raw[name].orEmpty().flatMapTo(HashSet()) { value ->
        when {
            value.startsWith("@color/") && depth < 4 -> resolve(value.removePrefix("@color/"), depth + 1)
            else -> setOfNotNull(parseColor(value))
        }
    }
    return raw.keys.associateWith { resolve(it, 0) }
}

/**
 * Every theme attribute some style in [styles] (one decoded styles.xml per configuration) sets to one
 * of Instagram's accent blues, either named as a color resource or written out, with the part that
 * blue plays and its alpha. A style naming a color through another attribute isn't counted: that
 * attribute is, where it's set.
 */
internal fun accentUses(styles: List<Document>, colors: Map<String, Set<Int>>): Set<AccentUse> {
    val uses = LinkedHashSet<AccentUse>()
    for (document in styles) {
        for (style in document.children("style")) {
            for (item in style.children("item")) {
                val value = item.textContent.trim()
                val argbs = when {
                    value.startsWith("@color/") -> colors[value.removePrefix("@color/")].orEmpty()
                    else -> setOfNotNull(parseColor(value))
                }
                for (argb in argbs) {
                    val role = ACCENT_BLUES[argb and 0xffffff] ?: continue
                    uses += AccentUse(item.getAttribute("name"), role, argb ushr 24)
                }
            }
        }
    }
    return uses
}

/**
 * What the patch adds for [uses], into the decoded attrs.xml, colors.xml and styles.xml of the default
 * configuration, answering every attribute a use has, space separated, for [ATTRIBUTES_STUB]:
 *
 * - an attribute for each shade the uses need (a part and an alpha), `hushgram_accent_fill_ff`;
 * - each color's value of each of those shades, `hushgram_accent_purple_fill_ff`;
 * - for each color, a style setting each shade attribute to that color's resource;
 * - for each use, a style setting its attribute to its shade attribute, `?attr/hushgram_accent_fill_ff`;
 * - when attrs.xml declares [BADGE_ATTRIBUTE], each color's fill as a badge color and a style setting
 *   the attribute to it, `HushGramAccentBadge_PURPLE`, for Badges in the accent color (#315);
 * - when attrs.xml declares [ICON_ATTRIBUTE], each color's light and dark link shades as icon colors
 *   and a style setting the attribute, and each of [GLYPH_ATTRIBUTES] declared, to each,
 *   `HushGramAccentIcons_PURPLE_dark`, for Icons in the accent color (#8).
 *
 * The extension lays a color's style and the styles for whatever its theme's attributes hold onto the
 * theme. The shade attributes stand between them because a part and alpha is the same whatever color
 * is picked, so one style per use serves every color. The colors are resources rather than written
 * out, since some of Instagram's reads take the resource an attribute resolves to and ask for its color.
 * Refuses if any name it adds is already there.
 */
internal fun addAccentResources(uses: Set<AccentUse>, attrs: Document, colors: Document, styles: Document): String {
    val shades = uses.map { it.shade }.distinct().sorted()
    val taken = (attrs.children("attr") + colors.children("color") + styles.children("style"))
        .mapTo(HashSet()) { it.getAttribute("name") }
    val badges = attrs.children("attr").any { it.getAttribute("name") == BADGE_ATTRIBUTE }
    val iconAttributes = iconAttributes(attrs)
    val icons = iconAttributes.isNotEmpty()
    val adding = shades.map(::shadeAttribute) +
        ACCENT_PALETTES.flatMap { palette -> shades.map { paletteColor(palette, it) } + paletteStyle(palette) } +
        uses.map { it.style } +
        (if (badges) ACCENT_PALETTES.flatMap { listOf(badgeColor(it), badgeStyle(it)) } else emptyList()) +
        (if (icons) ACCENT_PALETTES.flatMap { palette ->
            IconTheme.entries.flatMap { listOf(iconColor(palette.name, it), iconStyle(palette.name, it)) }
        } else emptyList())
    adding.firstOrNull { it in taken }?.let { refuse("the resources already have a $it") }

    for (shade in shades) {
        attrs.documentElement.appendChild(attrs.createElement("attr").apply {
            setAttribute("name", shadeAttribute(shade))
            setAttribute("format", "color")
        })
    }
    for (palette in ACCENT_PALETTES) {
        val style = styles.createElement("style").apply { setAttribute("name", paletteStyle(palette)) }
        for (shade in shades) {
            val use = uses.first { it.shade == shade }
            val rgb = palette.shades.getValue(use.role)
            colors.documentElement.appendChild(colors.createElement("color").apply {
                setAttribute("name", paletteColor(palette, shade))
                textContent = "#%02x%06x".format(use.alpha, rgb)
            })
            style.appendChild(styles.createElement("item").apply {
                setAttribute("name", shadeAttribute(shade))
                textContent = "@color/${paletteColor(palette, shade)}"
            })
        }
        styles.documentElement.appendChild(style)
    }
    for (use in uses) {
        styles.documentElement.appendChild(styles.createElement("style").apply {
            setAttribute("name", use.style)
            appendChild(styles.createElement("item").apply {
                setAttribute("name", use.attribute)
                textContent = "?attr/${shadeAttribute(use.shade)}"
            })
        })
    }
    if (badges) {
        for (palette in ACCENT_PALETTES) {
            colors.documentElement.appendChild(colors.createElement("color").apply {
                setAttribute("name", badgeColor(palette))
                textContent = "#ff%06x".format(palette.shades.getValue(AccentRole.FILL))
            })
            styles.documentElement.appendChild(styles.createElement("style").apply {
                setAttribute("name", badgeStyle(palette))
                appendChild(styles.createElement("item").apply {
                    setAttribute("name", BADGE_ATTRIBUTE)
                    textContent = "@color/${badgeColor(palette)}"
                })
            })
        }
    }
    if (icons) {
        for (palette in ACCENT_PALETTES) {
            for (theme in IconTheme.entries) {
                colors.documentElement.appendChild(colors.createElement("color").apply {
                    setAttribute("name", iconColor(palette.name, theme))
                    textContent = "#ff%06x".format(palette.shades.getValue(theme.role))
                })
                styles.documentElement.appendChild(styles.colorStyle(iconStyle(palette.name, theme), iconAttributes, iconColor(palette.name, theme)))
            }
        }
    }
    return uses.map { it.attribute }.distinct().joinToString(" ")
}

/**
 * What the patch adds for Material You, after [addAccentResources] has declared the shade attributes:
 *
 * - for each opaque shade the uses need, a color at [WALLPAPER_FALLBACK] in the default
 *   configuration and at the wallpaper's system color in [colorsV31], `hushgram_accent_wallpaper_fill_ff`;
 * - a style setting each of those shade attributes to its color, [WALLPAPER_PALETTE_STYLE];
 * - when attrs.xml declares [BADGE_ATTRIBUTE], a badge color at the fill and a style pointing the
 *   attribute at it, for Badges in the accent color (#315);
 * - when attrs.xml declares [ICON_ATTRIBUTE], a light and a dark icon color at the link shades and a
 *   style pointing the attribute, and each of [GLYPH_ATTRIBUTES] declared, at each, for Icons in the
 *   accent color (#8).
 *
 * A see-through shade gets none: a plain color can't give a system color another alpha, and a color
 * state list reads back as a file name to Instagram's own reads, so the extension leaves those
 * attributes on Instagram's blue for its color hooks to swap. Refuses if any name it adds is there.
 */
internal fun addWallpaperResources(uses: Set<AccentUse>, attrs: Document, colors: Document, colorsV31: Document, styles: Document) {
    val opaque = uses.filter { it.alpha == 0xff }.distinctBy { it.shade }.sortedBy { it.shade }
    val taken = (colors.children("color") + colorsV31.children("color") + styles.children("style"))
        .mapTo(HashSet()) { it.getAttribute("name") }
    val badges = attrs.children("attr").any { it.getAttribute("name") == BADGE_ATTRIBUTE }
    val iconAttributes = iconAttributes(attrs)
    val icons = iconAttributes.isNotEmpty()
    val adding = opaque.map { wallpaperColor(it.shade) } + WALLPAPER_PALETTE_STYLE +
        (if (badges) listOf(WALLPAPER_BADGE_COLOR, WALLPAPER_BADGE_STYLE) else emptyList()) +
        (if (icons) IconTheme.entries.flatMap { listOf(iconColor(WALLPAPER, it), iconStyle(WALLPAPER, it)) } else emptyList())
    adding.firstOrNull { it in taken }?.let { refuse("the resources already have a $it") }

    fun color(name: String, role: AccentRole) {
        colors.documentElement.appendChild(colors.createElement("color").apply {
            setAttribute("name", name)
            textContent = "#ff%06x".format(WALLPAPER_FALLBACK.getValue(role))
        })
        colorsV31.documentElement.appendChild(colorsV31.createElement("color").apply {
            setAttribute("name", name)
            textContent = systemColor(role)
        })
    }

    val style = styles.createElement("style").apply { setAttribute("name", WALLPAPER_PALETTE_STYLE) }
    for (use in opaque) {
        color(wallpaperColor(use.shade), use.role)
        style.appendChild(styles.createElement("item").apply {
            setAttribute("name", shadeAttribute(use.shade))
            textContent = "@color/${wallpaperColor(use.shade)}"
        })
    }
    styles.documentElement.appendChild(style)
    if (badges) {
        color(WALLPAPER_BADGE_COLOR, AccentRole.FILL)
        styles.documentElement.appendChild(styles.createElement("style").apply {
            setAttribute("name", WALLPAPER_BADGE_STYLE)
            appendChild(styles.createElement("item").apply {
                setAttribute("name", BADGE_ATTRIBUTE)
                textContent = "@color/$WALLPAPER_BADGE_COLOR"
            })
        })
    }
    if (icons) {
        for (theme in IconTheme.entries) {
            color(iconColor(WALLPAPER, theme), theme.role)
            styles.documentElement.appendChild(styles.colorStyle(iconStyle(WALLPAPER, theme), iconAttributes, iconColor(WALLPAPER, theme)))
        }
    }
}

private const val COLORS_V31 = "res/values-v31/colors.xml"

private const val EMPTY_RESOURCES = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>\n"

/**
 * Reads every configuration's colors and styles, then adds the theme resources for each accent
 * attribute found. Refuses unless Instagram's link and primary button attributes are among them, the
 * ones 451's bio links and the Follow buttons on the Activity tab read through their styles (#8).
 * The other configurations are only read: document() writes its file back, and the patcher re-encodes
 * every file written. Answers the attributes, as [addAccentResources] does.
 */
internal fun ResourcePatchContext.addAccentTheme(): String {
    // Patcher 2.0 decodes only the files a patch asks for.
    decode("res/values*/styles.xml", "res/values*/colors.xml", "res/values/attrs.xml")
    val values = get("res").listFiles { file -> file.isDirectory && file.name.startsWith("values") }.orEmpty()
    val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    fun read(name: String) = values.map { File(it, name) }.filter { it.isFile }.map { builder.parse(it) }
    val uses = accentUses(read("styles.xml"), colorValues(read("colors.xml")))
    for (needed in listOf("igds_color_link", "igds_color_primary_button_indigo")) {
        if (uses.none { it.attribute == needed }) refuse("no style sets $needed to one of Instagram's blues")
    }
    get(COLORS_V31, false).takeIf { !it.exists() }?.let { file ->
        file.parentFile.mkdirs()
        file.writeText(EMPTY_RESOURCES)
    }
    return document("res/values/attrs.xml").use { attrs ->
        document("res/values/colors.xml").use { colors ->
            document(COLORS_V31).use { colorsV31 ->
                document("res/values/styles.xml").use { styles ->
                    addAccentResources(uses, attrs, colors, styles).also {
                        addWallpaperResources(uses, attrs, colors, colorsV31, styles)
                    }
                }
            }
        }
    }
}

internal val accentThemePatch = resourcePatch {
    execute {
        accentAttributes = null
        accentAttributes = addAccentTheme()
    }
}

/** The attributes [accentThemePatch] listed, refusing before any change when it hasn't run or listed none it can write. */
internal fun requireAccentAttributes(attributes: String? = accentAttributes): String {
    if (attributes.isNullOrEmpty()) refuse("the theme resources weren't added")
    if (!attributes.all { it.isLetterOrDigit() || it in "_:. " }) refuse("an attribute's name can't be written into the code: $attributes")
    return attributes
}

/**
 * The method laying Instagram's Prism overrides over an activity's theme, the last step of
 * [IG_FRAGMENT_ACTIVITY]'s theme setup. That setup is its one ()V applying one style (the base colors,
 * dark or light), and the (Context)V it calls right after on another class applies the overrides,
 * several styles in a row. The Bloks screens call the same method when Android applies their theme.
 */
internal fun BytecodePatchContext.findThemeOverrides(): MutableMethod {
    val activity = classDefByOrNull(IG_FRAGMENT_ACTIVITY) ?: refuse("no $IG_FRAGMENT_ACTIVITY")
    val setups = activity.methods.filter { method ->
        method.parameterTypes.isEmpty() && method.returnType == "V" && method.code().count { it.calls(THEME, "applyStyle") } == 1
    }
    val setup = setups.singleOrNull()
        ?: refuse("expected one ()V of $IG_FRAGMENT_ACTIVITY applying one style, found ${setups.size}")
    val code = setup.code()
    val applied = code.indexOfFirst { it.calls(THEME, "applyStyle") }
    val called = code.drop(applied + 1).take(3).mapNotNull { instruction ->
        ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
            instruction.opcode == Opcode.INVOKE_VIRTUAL && it.returnType == "V" &&
                it.parameterTypes.map(Any::toString) == listOf(CONTEXT) && it.definingClass != THEME
        }
    }.singleOrNull() ?: refuse("${setup.where()} calls no (Context)V right after applying its style")
    val overrides = classDefByOrNull(called.definingClass)?.methods?.singleOrNull { method ->
        method.name == called.name && method.parameterTypes.map(Any::toString) == listOf(CONTEXT) && method.returnType == "V"
    } ?: refuse("no ${called.definingClass}->${called.name}(Context)")
    if (AccessFlags.STATIC.isSet(overrides.accessFlags)) refuse("${overrides.where()} is static")
    if (overrides.code().count { it.calls(THEME, "applyStyle") } < 2) refuse("${overrides.where()} applies fewer than two styles")
    val returns = overrides.code().indices.filter { overrides.code()[it].opcode == Opcode.RETURN_VOID }
    if (returns.isEmpty()) refuse("${overrides.where()} never returns")
    overrides.requireParameterIntact(PATCH, 0, returns)
    for (needed in listOf(APPLY_THEME, ATTRIBUTES_STUB)) {
        if (extensionMethod(needed) == null) refuse("the extension has no public static $needed")
    }
    if ((extensionMethod(ATTRIBUTES_STUB)?.implementation?.registerCount ?: 0) < 1) refuse("$ATTRIBUTES_STUB has no register to write into")
    return mutableClassDefBy(overrides.definingClass).methods.single {
        it.name == overrides.name && it.parameterTypes.map(Any::toString) == listOf(CONTEXT) && it.returnType == "V"
    }
}

/** What Instagram's theme util logs when its style list isn't loaded yet. Nothing else in the app says it. */
internal const val THEME_UTIL_FAILURE = "Failed to apply theme to Activity because theme provider not initialized"

/**
 * Instagram's theme util: the static (Context)V laying its theme provider's styles onto a context's
 * theme. It styles the activities, and also contexts of Instagram's own making, such as the
 * ContextThemeWrapper the tab bar is inflated in off the main thread, which the overrides never reach
 * (#315). Its class is found by the line it logs when the styles aren't loaded, held or asked of a
 * string pool, and it's that class's one static (Context)V applying a style.
 */
internal fun BytecodePatchContext.findThemeUtil(): MutableMethod {
    val found = classesLoadingString(THEME_UTIL_FAILURE).flatMap { type ->
        type.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
                method.parameterTypes.map(Any::toString) == listOf(CONTEXT) && method.code().any { it.calls(THEME, "applyStyle") }
        }
    }
    val util = found.singleOrNull()
        ?: refuse("expected one static (Context)V applying a style beside \"$THEME_UTIL_FAILURE\", found ${found.size}")
    val returns = util.code().indices.filter { util.code()[it].opcode == Opcode.RETURN_VOID }
    if (returns.isEmpty()) refuse("${util.where()} never returns")
    util.requireParameterIntact(PATCH, 0, returns)
    return mutableClassDefBy(util.definingClass).methods.single {
        it.name == util.name && it.parameterTypes.map(Any::toString) == listOf(CONTEXT) && it.returnType == "V"
    }
}

/**
 * Writes [attributes] into the extension's stub, then runs AccentTheme.apply with the context at each
 * return of the overrides, once Instagram's styles are all on an activity, and of the theme util, for
 * the contexts Instagram styles itself.
 */
internal fun BytecodePatchContext.applyAccentTheme(attributes: String = requireAccentAttributes()) {
    val overrides = findThemeOverrides()
    val util = findThemeUtil()
    val listed = requireAccentAttributes(attributes)
    mutableClassDefBy(ACCENT_THEME).methods.single { it.signature() == ATTRIBUTES_STUB }.returnEarly(listed)
    for (method in listOf(overrides, util)) {
        val context = method.parameterRegisterNumber(0)
        val returns = method.code().indices.filter { method.code()[it].opcode == Opcode.RETURN_VOID }
        for (at in returns.asReversed()) {
            method.addInstructionsAtControlFlowLabel(at, "invoke-static/range { v$context .. v$context }, $APPLY_THEME")
        }
    }
}

private fun BytecodePatchContext.extensionMethod(signature: String): Method? =
    classDefByOrNull(ACCENT_THEME)?.methods?.singleOrNull {
        it.signature() == signature && AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
    }

private fun Method.signature(): String = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Instruction.calls(definingClass: String, name: String): Boolean =
    ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let {
        it.definingClass == definingClass && it.name == name
    } == true

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.where(): String = "$definingClass->$name"

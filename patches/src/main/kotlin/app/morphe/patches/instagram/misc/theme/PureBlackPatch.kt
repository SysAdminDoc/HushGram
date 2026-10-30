/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction31i
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction51l
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File

private const val PATCH = "Pure black dark mode"

/**
 * Instagram's black on its Prism redesign: the dark theme's background and status bar, and the light
 * theme's text. Sheets and buttons in the dark theme use lighter grays of their own ([DARK_SHEETS]).
 */
internal const val PRISM_BLACK = 0xff0c1014L
internal const val PURE_BLACK = 0xff000000L

/**
 * The theme attributes Instagram's styles point at [PRISM_BLACK_COLOR] for a background: the
 * screen, the status bar, media, Reels' tab bar and two banners.
 */
internal val BLACK_BACKGROUNDS = setOf(
    "igds_color_primary_background", "status_bar_background", "igds_color_media_background",
    "igds_color_clips_tab_bar_background", "igds_color_clips_up_next_banner_background", "igds_color_cta_banner_background",
)

internal const val PRISM_BLACK_COLOR = "@color/igds_prism_black"

/**
 * A stock color that's #ff000000 already. Setting igds_prism_black itself would have the patcher
 * re-encode the color table, and that breaks the color state lists Instagram shares between two
 * entries (on 449 three of them named files the patched APK didn't have).
 */
internal const val PURE_BLACK_COLOR = "@color/bds_black"

/** The dark theme's two palettes. The patch fails unless both change. */
internal val DARK_PALETTES = listOf("IgdsPrismGrayOverridesDark", "IgdsPrismSemanticColorsExperimentDark")

/**
 * The Compose palettes Instagram's newer screens draw from, kept names. Prism's first one names
 * [PRISM_BLACK] BLACK; its second, which the Direct inbox and Activity screens read, names it
 * GRAY_1600. The patch fails unless both change.
 */
internal val COMPOSE_PALETTES = listOf(
    "Lcom/instagram/compose/core/theme/BasePrismColors;",
    "Lcom/instagram/compose/core/theme/BasePrismColorsV2;",
)

/**
 * The theme attribute Instagram's bottom sheets take their surface from. The sheet's shapes
 * (igds_bottom_sheet_background and its two Prism twins) fill with it, the sheet's host reads it
 * again for the tint it lays over them, and the screens that paint themselves to match a sheet
 * read it too. On 450 the dark theme sets it to #ff191c1f (#171).
 */
internal const val SHEET_BACKGROUND = "igds_color_elevated_background"

/**
 * The dark theme's styles that set [SHEET_BACKGROUND]: Prism's dark palette, and the two overlays
 * Instagram lays over it when its gray overrides are on, the last of which wins. The light theme's
 * sheets are white already and have no style here. The patch fails unless all three change.
 */
internal val DARK_SHEETS = listOf(
    "IgdsPrismSemanticColorsExperimentDark", "IgdsPrismGrayOverridesDark", "IgdsElevatedBackgroundFixDark",
)

/**
 * The theme attribute the story viewer's root (reel_viewer_root) fills with, which shows in the
 * strip above a story and behind its reply bar, and the color a story's picture shows while it
 * loads. Prism sets it to igds_prism_gray_09, #ff25292e, in both themes (#279).
 */
internal const val STORY_BACKGROUND = "igds_color_stories_loading_background"

/**
 * The dark theme's styles that set [STORY_BACKGROUND]: Prism's dark palette and its gray
 * overrides. The light theme's stories keep their gray. The patch fails unless both change.
 */
internal val DARK_STORIES = listOf("IgdsPrismSemanticColorsExperimentDark", "IgdsPrismGrayOverridesDark")

/**
 * Points each of [BLACK_BACKGROUNDS] that a style in one decoded styles.xml sets to
 * [PRISM_BLACK_COLOR] at [PURE_BLACK_COLOR] instead, and answers the styles it changed.
 */
internal fun blackenStyles(styles: Document): List<String> {
    val list = styles.getElementsByTagName("style")
    val changed = mutableListOf<String>()
    for (style in (0 until list.length).map { list.item(it) as Element }) {
        val items = style.getElementsByTagName("item")
        val black = (0 until items.length).map { items.item(it) as Element }.filter { item ->
            item.getAttribute("name").substringAfter(':') in BLACK_BACKGROUNDS && item.textContent.trim() == PRISM_BLACK_COLOR
        }
        black.forEach { it.textContent = PURE_BLACK_COLOR }
        if (black.isNotEmpty()) changed += style.getAttribute("name")
    }
    return changed
}

/**
 * Points [SHEET_BACKGROUND] at [PURE_BLACK_COLOR] in each of [DARK_SHEETS] that one decoded
 * styles.xml holds, and answers the styles it changed. What sits on a sheet keeps its gray: the
 * pressed row, the separators, the fields and the buttons are other attributes.
 */
internal fun blackenSheets(styles: Document): List<String> = blackenAttribute(styles, SHEET_BACKGROUND, DARK_SHEETS)

/**
 * Points [STORY_BACKGROUND] at [PURE_BLACK_COLOR] in each of [DARK_STORIES] that one decoded
 * styles.xml holds, and answers the styles it changed. The story's own picture and stickers draw
 * over it and keep their colors.
 */
internal fun blackenStories(styles: Document): List<String> = blackenAttribute(styles, STORY_BACKGROUND, DARK_STORIES)

private fun blackenAttribute(styles: Document, attribute: String, names: List<String>): List<String> {
    val list = styles.getElementsByTagName("style")
    val changed = mutableListOf<String>()
    for (style in (0 until list.length).map { list.item(it) as Element }) {
        if (style.getAttribute("name") !in names) continue
        val items = style.getElementsByTagName("item")
        val gray = (0 until items.length).map { items.item(it) as Element }.filter { item ->
            item.getAttribute("name").substringAfter(':') == attribute && item.textContent.trim() != PURE_BLACK_COLOR
        }
        gray.forEach { it.textContent = PURE_BLACK_COLOR }
        if (gray.isNotEmpty()) changed += style.getAttribute("name")
    }
    return changed
}

/**
 * Whether [instruction] loads [PRISM_BLACK]: as an int (a View color) or as a long (a Compose
 * Color's argument). Neither fits a shorter form of either opcode.
 */
internal fun isPrismBlack(instruction: Instruction): Boolean = when (instruction.opcode) {
    Opcode.CONST -> (instruction as WideLiteralInstruction).wideLiteral.toInt() == PRISM_BLACK.toInt()
    Opcode.CONST_WIDE -> (instruction as WideLiteralInstruction).wideLiteral == PRISM_BLACK
    else -> false
}

/**
 * Loads [PURE_BLACK] where Instagram's Compose palettes load [PRISM_BLACK], with the same opcode
 * into the same register, and answers the methods it changed. Other owners of the literal (tab
 * tints, light theme text) stay.
 */
internal fun BytecodePatchContext.blackenLiterals(): List<String> {
    val found = mutableListOf<Pair<String, Method>>()
    COMPOSE_PALETTES.mapNotNull { classDefByOrNull(it) }.forEach { classDef ->
        classDef.methods.forEach { method ->
            if (method.implementation?.instructions?.any(::isPrismBlack) == true) found += classDef.type to method
        }
    }
    val changed = mutableListOf<String>()
    found.groupBy({ it.first }, { it.second }).forEach { (type, methods) ->
        val mutable = mutableClassDefBy(type)
        for (method in methods) {
            val target = mutable.methods.single {
                it.name == method.name && it.returnType == method.returnType &&
                    it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
            }
            target.implementation!!.instructions.toList().forEachIndexed { index, instruction ->
                if (!isPrismBlack(instruction)) return@forEachIndexed
                val register = (instruction as OneRegisterInstruction).registerA
                target.replaceInstruction(
                    index,
                    if (instruction.opcode == Opcode.CONST) BuilderInstruction31i(Opcode.CONST, register, PURE_BLACK.toInt())
                    else BuilderInstruction51l(Opcode.CONST_WIDE, register, PURE_BLACK),
                )
            }
            changed += "$type->${method.name}"
        }
    }
    return changed
}

/**
 * Writes pure black into the decoded styles: the backgrounds [blackenStyles] owns, the sheets
 * [blackenSheets] owns and the story viewer [blackenStories] owns. Only the styles files that name
 * [PRISM_BLACK_COLOR] are opened: document() writes its file back, and the patcher re-encodes every
 * file written. The sheets' and stories' styles sit in the file the palettes are in.
 */
internal fun ResourcePatchContext.blackenThemes() {
    // Patcher 2.0 decodes only the files a patch asks for.
    decode("res/values*/colors.xml", "res/values*/styles.xml")
    val values = get("res").listFiles { file -> file.isDirectory && file.name.startsWith("values") }.orEmpty()
    val colors = File(get("res"), "values/colors.xml")
    // 449 stores it as an RGB color, which decodes without the alpha.
    if (!colors.isFile || !Regex("""<color name="bds_black">#(ff)?000000</color>""", RegexOption.IGNORE_CASE)
            .containsMatchIn(colors.readText())
    ) {
        throw PatchException("$PATCH: $PURE_BLACK_COLOR is no longer #%08x".format(PURE_BLACK))
    }
    val backgrounds = mutableListOf<String>()
    val sheets = mutableListOf<String>()
    val stories = mutableListOf<String>()
    values.filter { File(it, "styles.xml").let { file -> file.isFile && PRISM_BLACK_COLOR in file.readText() } }.forEach { directory ->
        document("res/${directory.name}/styles.xml").use { styles ->
            backgrounds += blackenStyles(styles)
            sheets += blackenSheets(styles)
            stories += blackenStories(styles)
        }
    }
    val missing = DARK_PALETTES - backgrounds.toSet()
    if (missing.isNotEmpty()) {
        throw PatchException("$PATCH: ${missing.joinToString()} no longer set a background to $PRISM_BLACK_COLOR")
    }
    val gray = DARK_SHEETS - sheets.toSet()
    if (gray.isNotEmpty()) throw PatchException("$PATCH: ${gray.joinToString()} no longer set $SHEET_BACKGROUND")
    val grayStories = DARK_STORIES - stories.toSet()
    if (grayStories.isNotEmpty()) throw PatchException("$PATCH: ${grayStories.joinToString()} no longer set $STORY_BACKGROUND")
}

/**
 * A resource patch, for the themes the View screens, the sheets, the story viewer and the status
 * bar read. Only the colors and styles files are decoded.
 */
private val pureBlackStylesPatch = resourcePatch {
    execute {
        blackenThemes()
    }
}

/**
 * Gives Instagram's dark mode a pure black background, its bottom sheets included. A patch-time
 * choice with no switch: the color table can't follow one, so it's listed under Set when you patched.
 */
@Suppress("unused")
val pureBlackPatch = bytecodePatch(
    name = "Pure black dark mode",
    description = "Makes Instagram's dark mode pure black instead of dark gray, on the menus that slide up from " +
        "the bottom too. It looks deeper and can save battery on an OLED screen. Buttons keep their grays so they " +
        "stay easy to see. Works as soon as you patch it in, with no switch.",
    default = false,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch, pureBlackStylesPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("pureBlack")
        val changed = blackenLiterals()
        val missing = COMPOSE_PALETTES.filter { palette -> changed.none { it.startsWith("$palette->") } }
        if (missing.isNotEmpty()) {
            throw PatchException("$PATCH: ${missing.joinToString()} no longer load #%08x".format(PRISM_BLACK))
        }
        // The comments sheet's gray is its host's own background, not a theme color (#108).
        blackenCommentsScreens(findCommentsScreens())
        blackenCommentsSheet(findBottomSheetHooks())
        enableStatus("pureBlack")
    }
}

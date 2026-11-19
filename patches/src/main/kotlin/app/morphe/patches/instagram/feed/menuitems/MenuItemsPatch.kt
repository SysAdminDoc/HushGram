/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.menuitems

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.download.reel.OPTION
import app.morphe.patches.instagram.download.reel.code
import app.morphe.patches.instagram.download.reel.reelRowAdder
import app.morphe.patches.instagram.download.story.HELPER_NAME
import app.morphe.patches.instagram.download.video.findFeedMenu
import app.morphe.patches.instagram.download.video.mutable
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.originalName
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val MENU_ITEMS_STATUS = "menuItems"
internal const val MENU_ITEMS = "$EXTENSION_PACKAGE/feed/MenuItems;"
internal const val FEED_ROWS = "$MENU_ITEMS->feedRows(Ljava/lang/Object;)V"
internal const val REEL_ROW = "$MENU_ITEMS->reelRow(Ljava/lang/Object;)Z"
internal const val STORY_MENU = "$MENU_ITEMS->storyMenu(Ljava/lang/Object;)V"
internal const val STORY_LABELS = "$MENU_ITEMS->storyLabels([Ljava/lang/CharSequence;)[Ljava/lang/CharSequence;"
internal const val EDITS_ROWS = "$MENU_ITEMS->editsRows(Ljava/util/List;)Ljava/util/List;"
internal const val EDITS_TITLE =
    "$MENU_ITEMS->editsTitle(Ljava/lang/CharSequence;Ljava/util/List;Ljava/util/List;)Ljava/lang/CharSequence;"

/**
 * The key of Remix and sequence, the first row of the Made with Edits part of a reel's menu.
 * Instagram keeps it: it's the server's name for the row.
 */
internal const val EDITS_KEY = "remix_and_sequence"

/** Instagram's Report option, which every feed menu row's option is read against. */
internal const val REPORT = "$OPTION->REPORT:$OPTION"

private const val PATCH = "Three-dot menu items"
private const val OBJECT = "Ljava/lang/Object;"
private const val LABEL = "Ljava/lang/CharSequence;"
private const val LABEL_ARRAY = "[Ljava/lang/CharSequence;"
private const val RESOURCES = "Landroid/content/res/Resources;"
private const val LIST = "Ljava/util/List;"
private const val STRING = "Ljava/lang/String;"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun String.width() = if (this == "J" || this == "D") 2 else 1

private fun Method.where() = "$definingClass->$name"

/** One menu's hooks, found, and changed only when [write] is called. */
internal class MenuPart(val menu: String, private val change: () -> Unit) {
    fun write() = change()
}

/**
 * The feed menu's builder, found as Download any video finds it, with a call handing its rows to
 * the extension before each return, and the extension's Report stub, the sample a row's option is
 * read against. Throws naming the patch when the builder moved.
 */
internal fun BytecodePatchContext.findFeedMenuItems(): MenuPart {
    val menu = try {
        findFeedMenu()
    } catch (moved: PatchException) {
        throw PatchException(moved.message.orEmpty().replace("Download any video:", "$PATCH:"))
    }
    val builder = menu.builder
    if (builder.code().none { it.opcode == Opcode.RETURN_OBJECT }) {
        refuse("${builder.where()}, the feed menu's builder, never hands back its rows")
    }
    if (classDefBy(OPTION).fields.none { it.name == "REPORT" && it.type == OPTION && AccessFlags.STATIC.isSet(it.accessFlags) }) {
        refuse("$OPTION has no REPORT option")
    }
    val reportOf = stub("reportOption", OBJECT)
    return MenuPart("feed posts") {
        val method = mutable(builder)
        val code = method.code()
        // The highest index first, so the ones below it stay where they were.
        for (index in code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }.reversed()) {
            val rows = (code[index] as OneRegisterInstruction).registerA
            method.addInstructionsAtControlFlowLabel(index, "invoke-static/range { v$rows .. v$rows }, $FEED_ROWS")
        }
        reportOf.addInstructions(0, "sget-object v0, $REPORT\nreturn-object v0")
    }
}

/**
 * The one method that adds a row to the menu of a reel or a feed video, found as Download any reel
 * finds it, with a check at its start that leaves the row out when the extension says so. Throws
 * naming the patch when there isn't exactly one, or it doesn't take one option.
 */
internal fun BytecodePatchContext.findReelMenuItems(): MenuPart {
    val adder = reelRowAdder(PATCH)
    val parameters = adder.parameterTypes.map(Any::toString)
    val options = parameters.indices.filter { parameters[it] == OPTION }
    val option = options.singleOrNull()
    if (AccessFlags.STATIC.isSet(adder.accessFlags) || adder.returnType != "V" || option == null) {
        refuse("${adder.where()}, the adder of one reel menu row, takes ${parameters.joinToString("")} and answers ${adder.returnType}")
    }
    if (0 in adder.jumpTargets()) refuse("a branch in ${adder.where()} lands on its first instruction, where the check goes")
    // p0 is the helper itself, so the option is one past the words of the parameters before it.
    val register = 1 + parameters.take(option).sumOf { it.width() }
    val method = mutable(adder)
    method.requireLocals(PATCH, 1)
    return MenuPart("reels") {
        method.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p$register
                invoke-static { v0 }, $REEL_ROW
                move-result v0
                if-eqz v0, :keep
                return-void
            """,
            ExternalLabel("keep", method.getInstruction(0)),
        )
    }
}

/**
 * The story menu's helper, found by the name Instagram kept for it, its builders of labels and the
 * method that gives each label its icon, which the extension calls to tell Report and Mute. Each
 * builder tells the extension its menu first and hands its labels through it before each return.
 * Throws naming the patch when the icon method moved or the extension can't call it.
 */
internal fun BytecodePatchContext.findStoryMenuItems(): MenuPart {
    val helpers = mutableListOf<ClassDef>()
    classDefForEach { if (it.originalName() == HELPER_NAME) helpers += it }
    val helper = helpers.singleOrNull() ?: refuse(
        "expected one class named $HELPER_NAME, found " + if (helpers.isEmpty()) "none" else helpers.joinToString { it.type },
    )
    val type = helper.type
    val statics = helper.methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null }
    val builders = statics.filter { it.returnType == LABEL_ARRAY && type in it.parameterTypes.map(Any::toString) }
    if (builders.isEmpty()) refuse("$type builds no story menu from its labels")
    builders.firstOrNull { builder -> builder.code().none { it.opcode == Opcode.RETURN_OBJECT } }?.let {
        refuse("${it.where()} never returns its labels")
    }
    builders.firstOrNull { 0 in it.jumpTargets() }?.let {
        refuse("a branch in ${it.where()} lands on its first instruction, where the hook goes")
    }
    val icons = statics.filter { method ->
        method.returnType == "I" && method.parameterTypes.map(Any::toString) == listOf(type, LABEL) &&
            method.code().any { it.isLabelCheck() }
    }
    val icon = icons.singleOrNull() ?: refuse(
        "expected one method of $type giving a label its icon, found " +
            if (icons.isEmpty()) "none" else icons.joinToString { it.where() },
    )
    if (!AccessFlags.PUBLIC.isSet(helper.accessFlags) || !AccessFlags.PUBLIC.isSet(icon.accessFlags)) {
        refuse("${icon.where()}, the story menu's icon for a label, isn't public for the extension to call")
    }
    val iconOf = stub("storyIcon", "I", OBJECT, LABEL)
    // Each builder's menu, its parameter of the helper's class, read before the body can reuse it.
    val menus = builders.associateWith { builder ->
        val types = builder.parameterTypes.map(Any::toString)
        builder.implementation!!.registerCount - types.sumOf { it.width() } +
            types.take(types.indexOf(type)).sumOf { it.width() }
    }
    return MenuPart("stories") {
        menus.forEach { (builder, menu) ->
            val method = mutable(builder)
            val code = method.code()
            for (index in code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }.reversed()) {
                val labels = (code[index] as OneRegisterInstruction).registerA
                method.addInstructionsAtControlFlowLabel(
                    index,
                    """
                        invoke-static/range { v$labels .. v$labels }, $STORY_LABELS
                        move-result-object v$labels
                    """,
                )
            }
            method.addInstructions(0, "invoke-static/range { v$menu .. v$menu }, $STORY_MENU")
        }
        iconOf.addInstructions(
            0,
            """
                check-cast p0, $type
                invoke-static { p0, p1 }, $type->${icon.name}($type$LABEL)I
                move-result p0
                return p0
            """,
        )
    }
}

/**
 * The Made with Edits part of a reel's menu: the [adder] its builder hands the rows and the part's
 * title to, the parameter registers of the [rows] and [title] there, and the [key] field of a row.
 */
private class EditsRows(val adder: MethodReference, val rows: Int, val title: Int, val key: FieldReference)

/** The registers an invoke passes, in order. */
private fun Instruction.arguments(): List<Int> = when (this) {
    is RegisterRangeInstruction -> List(registerCount) { startRegister + it }
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

/** The register [code] holds an object in after the instruction at [at], when a move-result-object follows it. */
private fun resultAfter(code: List<Instruction>, at: Int): Int? =
    code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT }?.let { (it as OneRegisterInstruction).registerA }

/**
 * The Made with Edits part [method] builds, or null when it builds none: [EDITS_KEY] (loaded, or
 * asked of Redex's string pool) put into a String field of a row, an array of those rows made into
 * a List, and that List handed to one adder that also takes the part's title.
 */
private fun BytecodePatchContext.editsRowsIn(method: Method): EditsRows? {
    val code = method.code()
    val at = code.indices.firstOrNull { stringLoadedAt(code, it) == EDITS_KEY } ?: return null
    val key = when (code[at].opcode) {
        Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO -> (code[at] as OneRegisterInstruction).registerA
        else -> resultAfter(code, at)
    } ?: return null
    val store = (at + 1 until code.size).firstOrNull {
        code[it].opcode == Opcode.IPUT_OBJECT && (code[it] as TwoRegisterInstruction).registerA == key &&
            ((code[it] as ReferenceInstruction).reference as FieldReference).type == STRING
    } ?: return null
    val field = (code[store] as ReferenceInstruction).reference as FieldReference
    val lists = code.indices.mapNotNull { index ->
        val array = code[index]
        if (array.opcode != Opcode.FILLED_NEW_ARRAY && array.opcode != Opcode.FILLED_NEW_ARRAY_RANGE) return@mapNotNull null
        if (((array as ReferenceInstruction).reference as TypeReference).type != "[${field.definingClass}") return@mapNotNull null
        val made = resultAfter(code, index) ?: return@mapNotNull null
        val wrap = code.getOrNull(index + 2) ?: return@mapNotNull null
        val call = (wrap as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
        val wraps = (wrap.opcode == Opcode.INVOKE_STATIC || wrap.opcode == Opcode.INVOKE_STATIC_RANGE) &&
            call.returnType == LIST && wrap.arguments() == listOf(made)
        if (wraps) resultAfter(code, index + 2) else null
    }.toSet()
    if (lists.isEmpty()) return null
    return code.mapNotNull { call ->
        if (call.opcode != Opcode.INVOKE_VIRTUAL && call.opcode != Opcode.INVOKE_VIRTUAL_RANGE) return@mapNotNull null
        val adder = (call as ReferenceInstruction).reference as MethodReference
        val types = adder.parameterTypes.map(Any::toString)
        if (adder.returnType != "V" || types.count { it == LABEL } != 1) return@mapNotNull null
        // p0 is the adder's own object, so each parameter is one past the words of the ones before it.
        val registers = types.indices.map { 1 + types.take(it).sumOf { type -> type.width() } }
        val arguments = call.arguments()
        val rows = types.indices.singleOrNull { types[it] == LIST && arguments.getOrNull(registers[it]) in lists }
            ?: return@mapNotNull null
        EditsRows(adder, registers[rows], registers[types.indexOf(LABEL)], field)
    }.distinctBy { it.adder.toString() }.singleOrNull()
}

/**
 * The adder of the Made with Edits part of a reel's menu (#707), found from the builder that puts
 * [EDITS_KEY] into a row, with a call at its start handing the rows through the extension, which
 * leaves out the unticked ones, and the title too, which goes when no row is left. The extension's
 * key stub reads a row's key off the row's own field. Throws naming the patch when there isn't
 * exactly one adder, or the extension can't read a row's key.
 */
internal fun BytecodePatchContext.findEditsMenuItems(): MenuPart {
    val loaders = classesLoadingString(EDITS_KEY).flatMap { it.methods }.filter { loadsString(it, EDITS_KEY) }
    if (loaders.isEmpty()) refuse("nothing loads $EDITS_KEY, the key of a reel's Remix and sequence row")
    val found = loaders.mapNotNull { editsRowsIn(it) }.distinctBy { it.adder.toString() }
    val edits = found.singleOrNull() ?: refuse(
        "expected one adder of a reel's Made with Edits rows, found " +
            if (found.isEmpty()) "none" else found.joinToString { it.adder.toString() },
    )
    val row = classDefBy(edits.key.definingClass)
    val keys = row.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == STRING }
    if (!AccessFlags.PUBLIC.isSet(row.accessFlags) || keys.map { it.name } != listOf(edits.key.name) ||
        !AccessFlags.PUBLIC.isSet(keys.single().accessFlags)
    ) {
        refuse("${row.type}, a row of a reel's Made with Edits part, doesn't keep its key in one public field for the extension to read")
    }
    val adder = classDefBy(edits.adder.definingClass).methods.singleOrNull {
        it.name == edits.adder.name && it.returnType == "V" && it.implementation != null &&
            it.parameterTypes.map(Any::toString) == edits.adder.parameterTypes.map(Any::toString)
    } ?: refuse("${edits.adder}, the adder of a reel's Made with Edits rows, has no body to hook")
    if (0 in adder.jumpTargets()) refuse("a branch in ${adder.where()} lands on its first instruction, where the hook goes")
    val method = mutable(adder)
    method.requireLocals(PATCH, 3)
    val keyOf = stub("editsRowKey", STRING, OBJECT)
    return MenuPart("reels' Edits rows") {
        method.addInstructions(
            0,
            """
                move-object/from16 v0, p${edits.rows}
                invoke-static { v0 }, $EDITS_ROWS
                move-result-object v1
                move-object/16 p${edits.rows}, v1
                move-object/from16 v2, p${edits.title}
                invoke-static { v2, v0, v1 }, $EDITS_TITLE
                move-result-object v2
                move-object/16 p${edits.title}, v2
            """,
        )
        keyOf.addInstructions(
            0,
            """
                check-cast p0, ${row.type}
                iget-object p0, p0, ${edits.key}
                return-object p0
            """,
        )
    }
}

/** A static (Resources, Object, int) check answering a boolean, the way the icon method matches a label to a string. */
private fun Instruction.isLabelCheck(): Boolean {
    if (opcode != Opcode.INVOKE_STATIC && opcode != Opcode.INVOKE_STATIC_RANGE) return false
    val call = (this as ReferenceInstruction).reference as? MethodReference ?: return false
    return call.returnType == "Z" && call.parameterTypes.map(Any::toString) == listOf(RESOURCES, OBJECT, "I")
}

/** The extension's static stub [name], which the patch writes the body of. */
private fun BytecodePatchContext.stub(name: String, returns: String, vararg parameters: String) =
    mutableClassDefBy(MENU_ITEMS).methods.singleOrNull {
        it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == returns &&
            it.parameterTypes.map(Any::toString) == parameters.toList()
    } ?: refuse("$MENU_ITEMS has no static $returns $name(${parameters.joinToString()})")

/**
 * Every menu part found, each one that moved left out with a warning, so the other menus still get
 * their items left out. Throws when none was found, with each one's reason. Nothing is changed here.
 */
internal fun BytecodePatchContext.findMenuItems(): List<MenuPart> {
    val finders = listOf<Pair<String, BytecodePatchContext.() -> MenuPart>>(
        "feed posts" to { findFeedMenuItems() },
        "reels" to { findReelMenuItems() },
        "stories" to { findStoryMenuItems() },
        "reels' Edits rows" to { findEditsMenuItems() },
    )
    val parts = mutableListOf<MenuPart>()
    val refusals = mutableListOf<Pair<String, String>>()
    for ((menu, find) in finders) {
        try {
            parts += find()
        } catch (refusal: PatchException) {
            refusals += menu to refusal.message.orEmpty()
        }
    }
    if (parts.isEmpty()) throw PatchException("$PATCH: found none of the menus. ${refusals.joinToString(" ") { it.second }}")
    for ((menu, reason) in refusals) {
        patchLog.warning("$reason. $PATCH goes in leaving the menu on $menu as Instagram builds it.")
    }
    return parts
}

/**
 * Three-dot menu items (#697, #707): leaves the items the user unticks, Report, Mute, Unfollow,
 * Interested, Not interested, Why you're seeing this post and the playback rows, out of the menus
 * behind the three dots on feed posts, reels, feed videos and stories (Report and Mute there), and
 * the Made with Edits rows (Remix and sequence, Use as reference, Use template, Get Edits) out of a
 * reel's menu, with the part's title once none is left. HushGram's own Loop this video row reads
 * the same choice through Stop looping. In the default selection with nothing left out, so every
 * menu shows as Instagram builds it until the user picks.
 */
@Suppress("unused")
val menuItemsPatch = bytecodePatch(
    name = "Three-dot menu items",
    description = "Lets you leave Report, Mute and other items out of the menus behind the three dots on posts, reels " +
        "and stories. Every item shows until you untick it in HushGram settings > Feed.",
) {
    category("Fixes")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(MENU_ITEMS_STATUS)
        findMenuItems().forEach { it.write() }
        enableStatus(MENU_ITEMS_STATUS)
    }
}

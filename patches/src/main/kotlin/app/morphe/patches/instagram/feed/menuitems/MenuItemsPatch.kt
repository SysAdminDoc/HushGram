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
import app.morphe.patches.instagram.download.reel.ROW_MARKER
import app.morphe.patches.instagram.download.reel.code
import app.morphe.patches.instagram.download.story.HELPER_NAME
import app.morphe.patches.instagram.download.video.findFeedMenu
import app.morphe.patches.instagram.download.video.mutable
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.originalName
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.typesMarked
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val MENU_ITEMS_STATUS = "menuItems"
internal const val MENU_ITEMS = "$EXTENSION_PACKAGE/feed/MenuItems;"
internal const val FEED_ROWS = "$MENU_ITEMS->feedRows(Ljava/lang/Object;)V"
internal const val REEL_ROW = "$MENU_ITEMS->reelRow(Ljava/lang/Object;)Z"
internal const val STORY_MENU = "$MENU_ITEMS->storyMenu(Ljava/lang/Object;)V"
internal const val STORY_LABELS = "$MENU_ITEMS->storyLabels([Ljava/lang/CharSequence;)[Ljava/lang/CharSequence;"

/** Instagram's Report option, which every feed menu row's option is read against. */
internal const val REPORT = "$OPTION->REPORT:$OPTION"

private const val PATCH = "Three-dot menu items"
private const val OBJECT = "Ljava/lang/Object;"
private const val LABEL = "Ljava/lang/CharSequence;"
private const val LABEL_ARRAY = "[Ljava/lang/CharSequence;"
private const val RESOURCES = "Landroid/content/res/Resources;"

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
 * The one method that adds a row to the menu of a reel or a feed video, found by its marker, with
 * a check at its start that leaves the row out when the extension says so. Throws naming the patch
 * when there isn't exactly one, or it doesn't take one option.
 */
internal fun BytecodePatchContext.findReelMenuItems(): MenuPart {
    val adders = typesMarked(ROW_MARKER).sorted().mapNotNull { classDefByOrNull(it) }
        .flatMap { classDef -> classDef.methods.filter { ROW_MARKER in it.markers() } }
    val adder = adders.singleOrNull() ?: refuse(
        "expected one method holding the $ROW_MARKER marker, found " +
            if (adders.isEmpty()) "none" else adders.joinToString { it.where() },
    )
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
 * behind the three dots on feed posts, reels, feed videos and stories (Report and Mute there). In
 * the default selection with nothing left out, so every menu shows as Instagram builds it until
 * the user picks.
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

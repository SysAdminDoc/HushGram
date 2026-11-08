/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.savemenu

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.reel.OPTION
import app.morphe.patches.instagram.download.reel.code
import app.morphe.patches.instagram.download.video.FeedMenu
import app.morphe.patches.instagram.download.video.downloadRow
import app.morphe.patches.instagram.download.video.findFeedMenu
import app.morphe.patches.instagram.download.video.mutable
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesTouching
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val FEED_SAVE_STATUS = "feedSave"
internal const val SAVE_ROW = "$EXTENSION_PACKAGE/feed/FeedSaveRow;"
internal const val OFFER_SAVE = "$SAVE_ROW->offer(Ljava/lang/Object;Ljava/util/ArrayList;)V"
internal const val ALLOW_SAVE = "$SAVE_ROW->allow(Ljava/util/List;)Ljava/util/List;"
internal const val SETTLE_SAVE = "$SAVE_ROW->settle(Ljava/lang/Object;)V"

/** Instagram's own Save and Unsave options, which its feed menu handles a tap on itself. */
internal const val SAVE_OPTION = "$OPTION->SAVE:$OPTION"
internal const val UNSAVE_OPTION = "$OPTION->UNSAVE:$OPTION"

private const val PATCH = "Save in the post menu"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
private const val OBJECT = "Ljava/lang/Object;"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * How Instagram asks whether a post is saved: [lookup], a static (Media) method answering the post's
 * saved state, and [test], a static method of that state answering yes when it's saved.
 */
internal class SavedCheck(val lookup: MethodReference, val test: MethodReference)

/**
 * Finds [SavedCheck] where Instagram's older post menu picks Unsave over Save: the lookup and the test
 * run back to back and a no from the test jumps past the Unsave it loads. Every such pick in the app
 * has to agree, and both methods have to be public so the extension can call them.
 */
internal fun BytecodePatchContext.findSavedCheck(): SavedCheck {
    val found = LinkedHashMap<String, SavedCheck>()
    for (classDef in classesTouching(OPTION, "UNSAVE")) {
        for (method in classDef.methods) {
            val code = method.code()
            for (at in 5 until code.size) {
                if (code[at].opcode != Opcode.SGET_OBJECT || code[at].referenceText() != UNSAVE_OPTION) continue
                val start = at - 5
                val lookup = code[start].staticCall() ?: continue
                val test = code[start + 2].staticCall() ?: continue
                if (lookup.parameterTypes.map(Any::toString) != listOf(MEDIA) || !lookup.returnType.startsWith("L")) continue
                if (test.parameterTypes.map(Any::toString) != listOf(lookup.returnType) || test.returnType != "Z") continue
                if (code[start + 1].opcode != Opcode.MOVE_RESULT_OBJECT || code[start + 3].opcode != Opcode.MOVE_RESULT ||
                    code[start + 4].opcode != Opcode.IF_EQZ
                ) continue
                val state = (code[start + 1] as OneRegisterInstruction).registerA
                val answer = (code[start + 3] as OneRegisterInstruction).registerA
                if (code[start + 2].firstArgument() != state || (code[start + 4] as OneRegisterInstruction).registerA != answer) continue
                found["$lookup $test"] = SavedCheck(lookup, test)
            }
        }
    }
    val check = found.values.singleOrNull() ?: refuse(
        if (found.isEmpty()) "found no place Instagram picks Unsave for a saved post"
        else "the places Instagram picks Unsave disagree on how a saved post is told: ${found.keys}",
    )
    for (call in listOf(check.lookup, check.test)) {
        val owner = classDefByOrNull(call.definingClass) ?: refuse("${call.definingClass} isn't in the app")
        val target = owner.methods.firstOrNull {
            it.name == call.name && it.returnType == call.returnType && it.parameterTypes.map(Any::toString) == call.parameterTypes.map(Any::toString)
        } ?: refuse("$call isn't in the app")
        if (!AccessFlags.PUBLIC.isSet(owner.accessFlags) || !AccessFlags.PUBLIC.isSet(target.accessFlags) ||
            !AccessFlags.STATIC.isSet(target.accessFlags)
        ) refuse("$call isn't a public static method the extension can call")
    }
    return check
}

/**
 * Save in the post menu's hooks, found: the feed menu Download any video adds its rows to (found by
 * the same finders, so it shows with Download left out), its short menu's list of kept options,
 * the saved check and the row's stubs in the extension's FeedSaveRow. Nothing is changed until
 * [write]. The builder adds Instagram's own Save or Unsave to some posts after anyone else's rows,
 * so a settle call before each of its returns takes the added row out again where that happened.
 */
internal class FeedSaveRowHooks(
    private val context: BytecodePatchContext,
    private val menu: FeedMenu,
    private val kept: Method,
    private val check: SavedCheck,
    private val row: MutableClass,
    private val postOf: MutableMethod,
    private val adder: MutableMethod,
    private val saveOf: MutableMethod,
    private val unsaveOf: MutableMethod,
    private val savedOf: MutableMethod,
) {
    fun write() = with(context) {
        val others = menu.others
        // The short list's last return first, so the ones before it stay where they were.
        val short = mutable(kept)
        val code = short.code()
        for (index in code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }.reversed()) {
            val list = (code[index] as OneRegisterInstruction).registerA
            short.addInstructionsAtControlFlowLabel(
                index,
                """
                    invoke-static { v$list }, $ALLOW_SAVE
                    move-result-object v$list
                """,
            )
        }
        val builder = mutable(menu.builder)
        val built = builder.code()
        val inserts = built.indices.filter { built[it].opcode == Opcode.RETURN_OBJECT }.map { index ->
            val rows = (built[index] as OneRegisterInstruction).registerA
            index to "invoke-static/range { v$rows .. v$rows }, $SETTLE_SAVE"
        } + (others.at to "invoke-static { v${others.state}, v${others.rows} }, $OFFER_SAVE")
        // The highest index first, so the ones below it stay where they were.
        for ((index, call) in inserts.sortedByDescending { it.first }) {
            builder.addInstructionsAtControlFlowLabel(index, call)
        }
        postOf.addInstructions(
            0,
            """
                check-cast p0, ${others.stateType}
                iget-object p0, p0, ${others.media}
                return-object p0
            """,
        )
        saveOf.addInstructions(0, "sget-object v0, $SAVE_OPTION\nreturn-object v0")
        unsaveOf.addInstructions(0, "sget-object v0, $UNSAVE_OPTION\nreturn-object v0")
        savedOf.addInstructions(
            0,
            """
                check-cast p0, $MEDIA
                invoke-static { p0 }, ${check.lookup}
                move-result-object p0
                invoke-static { p0 }, ${check.test}
                move-result p0
                return p0
            """,
        )
        row.methods.remove(adder)
        row.methods.add(downloadRow(adder, others, all = true))
    }
}

/** Finds [FeedSaveRowHooks], or stops the patch saying what moved. Nothing is changed here. */
internal fun BytecodePatchContext.findFeedSaveRow(): FeedSaveRowHooks {
    val menu = try {
        findFeedMenu()
    } catch (moved: PatchException) {
        throw PatchException(moved.message.orEmpty().replace("Download any video:", "$PATCH:"))
    }
    val shortList = menu.shortLists.singleOrNull() ?: refuse(
        "expected one list of the options the short feed menu keeps, found " +
            if (menu.shortLists.isEmpty()) "none" else menu.shortLists.joinToString { "${it.definingClass}->${it.name}" },
    )
    if (menu.builder.code().none { it.opcode == Opcode.RETURN_OBJECT }) {
        refuse("${menu.builder.definingClass}->${menu.builder.name}, the feed menu's builder, never hands back its rows")
    }
    val returns = shortList.code().filter { it.opcode == Opcode.RETURN_OBJECT }
    if (returns.isEmpty()) refuse("${shortList.definingClass}->${shortList.name} never returns its list")
    returns.map { (it as OneRegisterInstruction).registerA }.firstOrNull { it > 15 }?.let {
        refuse("${shortList.definingClass}->${shortList.name} returns its list in v$it, out of an invoke's reach")
    }
    for (option in listOf("SAVE", "UNSAVE")) {
        if (classDefBy(OPTION).fields.none { it.name == option && it.type == OPTION && AccessFlags.STATIC.isSet(it.accessFlags) }) {
            refuse("$OPTION has no $option option")
        }
    }
    val check = findSavedCheck()
    val row = mutableClassDefBy(SAVE_ROW)
    fun stub(name: String, returns: String, vararg parameters: String) = row.methods.singleOrNull {
        it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == returns &&
            it.parameterTypes.map(Any::toString) == parameters.toList()
    } ?: refuse("$SAVE_ROW has no static $returns $name(${parameters.joinToString()})")
    return FeedSaveRowHooks(
        this, menu, shortList, check, row,
        stub("menuMedia", OBJECT, OBJECT),
        stub("addRow", "V", OBJECT, ARRAY_LIST, OBJECT, CHAR_SEQUENCE),
        stub("saveOption", OBJECT),
        stub("unsaveOption", OBJECT),
        stub("saved", "I", OBJECT),
    )
}

private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

private fun Instruction.staticCall(): MethodReference? =
    takeIf { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
        ?.let { (it as? ReferenceInstruction)?.reference as? MethodReference }

private fun Instruction.firstArgument(): Int? = when (this) {
    is RegisterRangeInstruction -> startRegister
    is FiveRegisterInstruction -> registerC
    else -> null
}

/**
 * Save, or Remove from saved on a post you saved, in the menu behind a feed post's three dots (#569):
 * Instagram's own options, which its menu already handles, added where anyone else's rows start and
 * kept by the short menu. The same patch lets the extension take the Save button off feed posts.
 * In the default selection with both switches off.
 */
@Suppress("unused")
val saveInPostMenuPatch = bytecodePatch(
    name = "Save in the post menu",
    description = "Adds Save to the menu behind the three dots on posts in your feed, and a switch that takes the " +
        "Save button off them. Starts off. Turn them on in HushGram settings > Feed.",
) {
    category("Fixes")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(FEED_SAVE_STATUS)
        findFeedSaveRow().write()
        enableStatus(FEED_SAVE_STATUS)
    }
}

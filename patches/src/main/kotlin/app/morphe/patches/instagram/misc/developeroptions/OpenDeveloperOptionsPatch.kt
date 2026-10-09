/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.developeroptions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.analytics.loadsString
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Open developer options"
internal const val OPEN_DEVELOPER_OPTIONS = "$EXTENSION_PACKAGE/misc/DeveloperOptions;->open()I"

/** The toast key the developer options opener shows when it can't open them, held by its one static. */
internal const val OPTIONS_ERROR = "debug_options_error"

/**
 * The SettingsStatus methods that say the override reader went in, for Export and Validate, and the
 * writer, for Import, Restore and Reset. A build that lacks either still has the long press.
 */
internal const val OVERRIDE_EXCHANGE_STATUS = "overrideExchange"
internal const val OVERRIDE_IMPORT_STATUS = "overrideImport"

/** What the server can set a long press of Home to do; only the press's handler holds both. */
internal val LONG_PRESS_STRINGS = listOf("click", "activity")

private const val CONTEXT = "Landroid/content/Context;"
private const val FRAGMENT_ACTIVITY = "Landroidx/fragment/app/FragmentActivity;"
private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val MAIN_ACTIVITY = "Lcom/instagram/mainactivity/InstagramMainActivity;"

/**
 * Opens Instagram's developer options on a long press of Home. Off in the default selection: the
 * options change how Instagram behaves for this phone, so they're for people who asked for them.
 */
@Suppress("unused")
val openDeveloperOptionsPatch = bytecodePatch(
    name = "Open developer options",
    description = "A long press on the Home tab opens Instagram's own developer options, where you can change its " +
        "hidden settings. A wrong change can break parts of Instagram until you reset it. On once you patch it " +
        "in. Turn it off in HushGram settings > Developer.",
    default = false,
) {
    category("Settings")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("developerOptions")
        requireStatusMethod(OVERRIDE_EXCHANGE_STATUS)
        requireStatusMethod(OVERRIDE_IMPORT_STATUS)
        requireStatusMethod(FLAG_NAMES_STATUS)
        openDeveloperOptions(findOverrideEditor(), findWhitehatScreen())
    }
}

/**
 * Puts in developer options for the [editor] and the Whitehat [screen] already found: the long
 * press, the MetaConfig and Whitehat entries, Export, Validate and Import as far as their reader
 * and writer are found, and Import flag names when MetaConfig's list is found. Every stub body is
 * assembled, every MetaConfig row traced and the opener found before anything changes, so nothing
 * after the long press hook can refuse.
 */
internal fun BytecodePatchContext.openDeveloperOptions(editor: OverrideEditor, screen: String) {
    val exchange = overrideExchangeOrWarn(editor)
    val names = flagNamesOrWarn()
    val stubs = listOfNotNull(prepareOverrideEditor(editor), prepareWhitehatScreen(editor, screen), exchange?.reader, exchange?.writer)
    openOnLongPress(findOptionsOpener())
    stubs.forEach { putStubs(it) }
    if (names != null) applyFlagNameList(names)
    enableStatus("developerOptions")
    if (exchange != null) enableStatus(OVERRIDE_EXCHANGE_STATUS)
    if (exchange?.writer != null) enableStatus(OVERRIDE_IMPORT_STATUS)
    if (names != null) enableStatus(FLAG_NAMES_STATUS)
}

/** The override reader's stubs, and the writer's when it was found too, assembled and not yet put in. */
internal class OverrideExchangeStubs(val reader: PreparedStubs, val writer: PreparedStubs?)

/**
 * The override reader and writer, or as much of them as this build has, with a warning in the patch
 * log for what's left out. Only Export, Validate and Import use them, so a reader that moved leaves
 * all three out, and a writer that moved leaves Import out. The writer starts from the reader's
 * model, so it's only looked for once the reader is found. Nothing here changes the app.
 */
internal fun BytecodePatchContext.overrideExchangeOrWarn(editor: OverrideEditor): OverrideExchangeStubs? {
    val (reader, readerStubs) = try {
        findOverrideReader(editor).let { it to prepareOverrideReader(it, editor) }
    } catch (moved: PatchException) {
        patchLog.warning("${moved.message}. The long press and the MetaConfig and Whitehat entries go in without " +
            "Export, Validate and Import.")
        return null
    }
    val writer = try {
        findOverrideWriter(reader.model).stubs
    } catch (moved: PatchException) {
        patchLog.warning("${moved.message}. Export and Validate go in without Import.")
        null
    }
    return OverrideExchangeStubs(readerStubs, writer)
}

/** The developer options opener: its instance, a static field of its own class, and its open method. */
internal class OptionsOpener(val instance: String, val open: String)

/**
 * Finds the class whose one static taking a context, an activity, a session and a Callable loads
 * [OPTIONS_ERROR], and in it the instance it keeps of itself and the instance method taking the
 * first three, which Instagram's settings link and its debug button call to open the options. The
 * static holds the key on 385611438 and asks a string pool for it on 385611395 and 385611400 (#77),
 * so it's read either way.
 */
internal fun BytecodePatchContext.findOptionsOpener(): OptionsOpener {
    val found = mutableListOf<String>()
    classesLoadingString(OPTIONS_ERROR).forEach { classDef ->
        val holds = classDef.methods.any { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.parameterTypes.map(Any::toString) == listOf(CONTEXT, FRAGMENT_ACTIVITY, USER_SESSION, "Ljava/util/concurrent/Callable;") &&
                loadsString(method, OPTIONS_ERROR)
        }
        if (holds) found += classDef.type
    }
    val type = found.singleOrNull() ?: refuse("expected one developer options opener holding $OPTIONS_ERROR, found ${found.size}")
    val opener = classDefBy(type)
    val instance = opener.fields.filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.type == type }.singleOrNull()
        ?: refuse("$type keeps no single instance of itself")
    val open = opener.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.map(Any::toString) == listOf(CONTEXT, FRAGMENT_ACTIVITY, USER_SESSION)
    }.singleOrNull() ?: refuse("$type has no single method opening the options for a context, an activity and a session")
    return OptionsOpener("$type->${instance.name}:$type", "$type->${open.name}($CONTEXT$FRAGMENT_ACTIVITY$USER_SESSION)V")
}

/**
 * Asks [OPEN_DEVELOPER_OPTIONS] first thing in the Home tab's long press, the one onLongClick
 * holding [LONG_PRESS_STRINGS], and on a yes hands the handler's main activity and session to
 * [opener] and ends the press as handled. Otherwise the press does what it did.
 */
internal fun BytecodePatchContext.openOnLongPress(opener: OptionsOpener) {
    val found = mutableListOf<String>()
    classesHolding(*LONG_PRESS_STRINGS.toTypedArray()).forEach { classDef ->
        if (classDef.methods.any { it.isLongPress() && it.strings().containsAll(LONG_PRESS_STRINGS) }) found += classDef.type
    }
    val type = found.singleOrNull() ?: refuse("expected one long press handler holding $LONG_PRESS_STRINGS, found ${found.size}")
    val handler = mutableClassDefBy(type)
    fun field(fieldType: String) = handler.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == fieldType }
        .singleOrNull()?.let { "$type->${it.name}:$fieldType" }
        ?: refuse("$type keeps no single $fieldType")
    val activity = field(MAIN_ACTIVITY)
    val session = field(USER_SESSION)
    val method = handler.methods.single { it.isLongPress() }
    // `this` sits right under the view; the call needs four locals below it.
    val self = method.implementation!!.registerCount - 2
    if (self < 4) refuse("$type->onLongClick has ${self} locals, the call needs four")
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $OPEN_DEVELOPER_OPTIONS
            move-result v0
            if-eqz v0, :press
            move-object/from16 v3, v$self
            iget-object v1, v3, $activity
            iget-object v2, v3, $session
            sget-object v3, ${opener.instance}
            invoke-virtual { v3, v1, v1, v2 }, ${opener.open}
            const/4 v0, 0x1
            return v0
        """,
        ExternalLabel("press", method.getInstruction(0)),
    )
}

private fun Method.isLongPress() = name == "onLongClick" && returnType == "Z" &&
    parameterTypes.map(Any::toString) == listOf("Landroid/view/View;")

private fun Method.strings(): Set<String> = implementation?.instructions?.mapNotNull { instruction ->
    if (instruction.opcode != Opcode.CONST_STRING && instruction.opcode != Opcode.CONST_STRING_JUMBO) null
    else ((instruction as ReferenceInstruction).reference as StringReference).string
}?.toSet().orEmpty()

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

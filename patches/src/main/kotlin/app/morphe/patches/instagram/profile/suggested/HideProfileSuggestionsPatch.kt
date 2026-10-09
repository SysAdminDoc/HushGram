/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.suggested

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.readsAfter
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide suggested people on profiles"
internal const val PROFILE_SUGGESTIONS = "$EXTENSION_PACKAGE/profile/ProfileSuggestions;"
internal const val INLINE_ROW = "$PROFILE_SUGGESTIONS->inlineRow(I)I"
internal const val CHAINING_BUTTON = "$PROFILE_SUGGESTIONS->chainingButton(Landroid/view/View;)V"
internal const val KEEP_STANDALONE_ROW = "$PROFILE_SUGGESTIONS->keepStandaloneRow()I"

/**
 * What the profile's action row (Follow, Message and the buttons beside them) throws when it has
 * suggested accounts to show and nothing to bind them with. Only that binder loads it.
 */
internal const val NO_SUGGESTED_BINDER = "No suggested ViewBinder to bind suggested users"

/** The person-plus button that opens suggested accounts. Instagram keeps its name. */
internal const val FOLLOW_CHAINING_BUTTON = "Lcom/instagram/follow/chaining/FollowChainingButton;"

/** The trace names of the profile header's binder group, which lays out the header's rows. */
internal const val HEADER_BIND = "ProfileHeaderBinderGroup.bindView"
internal const val HEADER_CREATE = "ProfileHeaderBinderGroup.createView"

/** The binder group's override that lists the header's rows, a name Instagram keeps. */
internal const val BUILD_ROWS = "buildRowViewTypes"

/** The header row type of suggested accounts in a row of their own, named in its enum's setup. */
internal const val STANDALONE_CHAINING = "ITEM_TYPE_STANDALONE_USER_CHAINING"

/** View.GONE, which the action row gives its suggestions' container when there are none to show. */
private const val GONE = 8

private const val INTEGER = "Ljava/lang/Integer;"

/**
 * Takes the accounts Instagram suggests off profiles. In the default selection with its switch off:
 * suggestions are one of Instagram's features, so leaving them out is the user's pick. Asked for in
 * #15 and #20.
 */
@Suppress("unused")
val hideProfileSuggestionsPatch = bytecodePatch(
    name = "Hide suggested people on profiles",
    description = "Takes Suggested for you and the Discover people button off profiles, yours and other people's. " +
        "Bios, counts, posts and follower lists stay. Off until you turn it on. Switch: HushGram settings > Profiles.",
) {
    category("Feed")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("profileSuggestions")
        val sites = findProfileSuggestions()
        hideProfileSuggestions(sites)
        enableStatus("profileSuggestions")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** The profile's action row, found by the message only it throws. */
internal object ProfileActionsFingerprint : Fingerprint(
    strings = listOf(NO_SUGGESTED_BINDER),
    custom = { method, _ -> method.holdsString(NO_SUGGESTED_BINDER) },
)

/** The profile header's binder, found by its trace name. */
internal object ProfileHeaderBindFingerprint : Fingerprint(
    name = "bindView",
    strings = listOf(HEADER_BIND),
    custom = { method, _ -> method.holdsString(HEADER_BIND) },
)

/** The setup of the header's row types, the one enum setup naming the standalone row of suggestions. */
internal object HeaderRowTypesFingerprint : Fingerprint(
    name = "<clinit>",
    strings = listOf(STANDALONE_CHAINING),
    custom = { method, classDef -> classDef.superclass == "Ljava/lang/Enum;" && method.holdsString(STANDALONE_CHAINING) },
)

/** A method of the app, by its class, name and parameters. */
internal data class MethodKey(val type: String, val name: String, val parameters: List<String>)

private fun Method.key() = MethodKey(definingClass, name, parameterTypes.map(CharSequence::toString))

/**
 * Where each hook goes: in the action row, the read of its show flag and the set-up of the
 * person-plus button, and in the header's list of rows, the read of the standalone row's type.
 */
internal class ProfileSuggestionSites(
    val actions: MethodKey,
    /** The read of whether to show the inline row of suggestions, and the register it fills. */
    val showFlag: Int,
    val showRegister: Int,
    /** The call that sets the person-plus button up, and the register holding the button. */
    val buttonSetup: Int,
    val buttonRegister: Int,
    val rows: MethodKey,
    /** The read of the standalone row's type, its register, and the field it reads. */
    val standalone: Int,
    val standaloneRegister: Int,
    val standaloneField: String,
)

/**
 * Finds the three places, failing before anything changes when any of them isn't there exactly
 * once, since that's an update this patch hasn't seen:
 * - In the action row, the one boolean field read of a type the row checks for with instance-of,
 *   tested straight away by an if-eqz whose branch sets View.GONE (its suggestions' container).
 * - In the same method, the one call to [FOLLOW_CHAINING_BUTTON] taking a state, an Integer and a
 *   boolean, which sets the button up. Nothing may jump to the instruction after either of them.
 * - In the header binder's class, the one [BUILD_ROWS] reading the field its row type enum's setup
 *   stores [STANDALONE_CHAINING] in, read once and handed straight to the call that adds a row by
 *   its int type. Nothing after that call may read the register the type was read into.
 */
internal fun BytecodePatchContext.findProfileSuggestions(): ProfileSuggestionSites {
    val actions = uniqueMethod(PATCH, "profile action row holding \"$NO_SUGGESTED_BINDER\"", ProfileActionsFingerprint)
    val code = actions.instructions()
    val where = "${actions.definingClass}->${actions.name}"
    val targets = actions.jumpTargets()
    val flow = ControlFlow.of(actions)

    val modelTypes = code.filter { it.opcode == Opcode.INSTANCE_OF }.map { it.typeReference() }.toSet()
    val flags = code.indices.filter { at ->
        val read = code[at]
        val test = code.getOrNull(at + 1)
        if (read.opcode != Opcode.IGET_BOOLEAN || read.fieldReference()?.definingClass !in modelTypes) return@filter false
        if (test?.opcode != Opcode.IF_EQZ || (test as OneRegisterInstruction).registerA != (read as OneRegisterInstruction).registerA) {
            return@filter false
        }
        val branch = code[flow.normal[at + 1].first()]
        branch is NarrowLiteralInstruction && branch.opcode in LITERALS && branch.narrowLiteral == GONE
    }
    val showFlag = flags.singleOrNull()
        ?: refuse("expected one flag in $where that hides its suggested accounts when false, found ${flags.size}")
    if (showFlag + 1 in targets) refuse("something in $where jumps to the test of its suggestions' flag")
    val showRegister = (code[showFlag] as OneRegisterInstruction).registerA

    val buttonClass = classDefByOrNull(FOLLOW_CHAINING_BUTTON) ?: refuse("$FOLLOW_CHAINING_BUTTON isn't in this build")
    val framework = generateSequence(buttonClass.superclass) { classDefByOrNull(it)?.superclass }
        .firstOrNull { classDefByOrNull(it) == null }
    if (framework == null || !(framework.startsWith("Landroid/view/") || framework.startsWith("Landroid/widget/"))) {
        refuse("$FOLLOW_CHAINING_BUTTON isn't an Android view")
    }
    val setups = code.indices.filter { at ->
        val called = code[at].methodReference() ?: return@filter false
        val parameters = called.parameterTypes.map(CharSequence::toString)
        code[at].opcode in INVOKES && called.definingClass == FOLLOW_CHAINING_BUTTON && called.returnType == "V" &&
            parameters.size == 3 && parameters[1] == INTEGER && parameters[2] == "Z"
    }
    val buttonSetup = setups.singleOrNull()
        ?: refuse("expected one call in $where setting up $FOLLOW_CHAINING_BUTTON, found ${setups.size}")
    if (buttonSetup + 1 !in code.indices || buttonSetup + 1 in targets) {
        refuse("something in $where jumps to the instruction after $FOLLOW_CHAINING_BUTTON is set up")
    }
    val buttonRegister = code[buttonSetup].argumentRegisters().first()

    val bind = uniqueMethod(PATCH, "profile header binder holding \"$HEADER_BIND\"", ProfileHeaderBindFingerprint)
    val header = classDefBy(bind.definingClass)
    if (header.methods.none { it.holdsString(HEADER_CREATE) }) refuse("${header.type} has no method holding \"$HEADER_CREATE\"")
    val setup = uniqueMethod(PATCH, "row type setup naming $STANDALONE_CHAINING", HeaderRowTypesFingerprint)
    val standaloneField = standaloneField(setup)

    val readers = header.methods.filter { it.name == BUILD_ROWS }.flatMap { method ->
        method.instructions().indices.filter { method.instructions()[it].readsStatic(standaloneField) }.map { method to it }
    }
    val (rows, standalone) = readers.singleOrNull()
        ?: refuse("expected one read of $standaloneField in ${header.type}->$BUILD_ROWS, found ${readers.size}")
    val rowCode = rows.instructions()
    val register = (rowCode[standalone] as OneRegisterInstruction).registerA
    val typeRead = rowCode.getOrNull(standalone + 1)
    val typeField = typeRead?.fieldReference()
    if (typeRead?.opcode != Opcode.IGET || typeField?.definingClass != setup.definingClass || typeField.type != "I" ||
        (typeRead as TwoRegisterInstruction).registerB != register
    ) {
        refuse("${header.type}->$BUILD_ROWS doesn't read the row type's int right after reading $standaloneField")
    }
    val typeRegister = typeRead.registerA
    val add = rowCode.getOrNull(standalone + 2)
    val added = add?.methodReference()
    if (add == null || add.opcode !in INVOKES || added?.returnType != "V" ||
        added.parameterTypes.map(CharSequence::toString) != listOf("I") || add.argumentRegisters().lastOrNull() != typeRegister
    ) {
        refuse("${header.type}->$BUILD_ROWS doesn't add a row by the standalone row's type right after reading it")
    }
    if (standalone + 3 !in rowCode.indices) refuse("${header.type}->$BUILD_ROWS ends at the standalone row")
    if (rows.readsAfter(standalone + 2, register).isNotEmpty()) {
        refuse("${header.type}->$BUILD_ROWS reads v$register after adding the standalone row")
    }

    return ProfileSuggestionSites(
        actions.key(), showFlag, showRegister, buttonSetup, buttonRegister,
        rows.key(), standalone, register, standaloneField,
    )
}

/** The static field [setup] stores its row type in: the first of the enum's own type written after the name. */
private fun standaloneField(setup: Method): String {
    val code = setup.instructions()
    val named = code.indices.filter { code[it].stringLoaded() == STANDALONE_CHAINING }
    val at = named.singleOrNull() ?: refuse("${setup.definingClass}'s setup names $STANDALONE_CHAINING ${named.size} times")
    return code.drop(at).firstNotNullOfOrNull { instruction ->
        instruction.fieldReference()?.takeIf {
            instruction.opcode == Opcode.SPUT_OBJECT && it.definingClass == setup.definingClass && it.type == setup.definingClass
        }
    }?.toString() ?: refuse("${setup.definingClass}'s setup doesn't store $STANDALONE_CHAINING")
}

/**
 * Puts the three hooks in:
 * - the flag goes through [INLINE_ROW] right after it's read, so a 0 sends the action row down its
 *   own path for no suggestions;
 * - the person-plus button goes to [CHAINING_BUTTON] right after it's set up;
 * - the read of the standalone row's type is replaced by a call to [KEEP_STANDALONE_ROW], which
 *   keeps the read's label, so whatever jumps there asks too; on a 0 the code goes past the add,
 *   and otherwise reads the type as before. The rows that join the add further down never pass
 *   the question.
 */
internal fun BytecodePatchContext.hideProfileSuggestions(sites: ProfileSuggestionSites) {
    val actions = mutable(sites.actions)
    // The later index first, so the earlier one stays where it was found.
    listOf(
        sites.showFlag to "${invoke(sites.showRegister, INLINE_ROW)}\nmove-result v${sites.showRegister}",
        sites.buttonSetup to invoke(sites.buttonRegister, CHAINING_BUTTON),
    ).sortedByDescending { it.first }.forEach { (at, smali) -> actions.addInstructions(at + 1, smali) }

    val rows = mutable(sites.rows)
    val register = sites.standaloneRegister
    rows.replaceInstruction(sites.standalone, "invoke-static { }, $KEEP_STANDALONE_ROW")
    val past = rows.getInstruction(sites.standalone + 3)
    rows.addInstructionsWithLabels(
        sites.standalone + 1,
        """
            move-result v$register
            if-eqz v$register, :past
            sget-object v$register, ${sites.standaloneField}
        """,
        ExternalLabel("past", past),
    )
}

private fun invoke(register: Int, hook: String) =
    if (register > 15) "invoke-static/range { v$register .. v$register }, $hook" else "invoke-static { v$register }, $hook"

private fun BytecodePatchContext.mutable(key: MethodKey): MutableMethod =
    mutableClassDefBy(key.type).methods.single { it.name == key.name && it.parameterTypes.map(CharSequence::toString) == key.parameters }

private val LITERALS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST)

private val INVOKES = setOf(
    Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE,
    Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE, Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE,
)

private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.holdsString(value: String) = instructions().any { it.stringLoaded() == value }

private fun Instruction.stringLoaded(): String? =
    if (opcode != Opcode.CONST_STRING && opcode != Opcode.CONST_STRING_JUMBO) null
    else ((this as ReferenceInstruction).reference as StringReference).string

private fun Instruction.readsStatic(field: String) = opcode == Opcode.SGET_OBJECT && fieldReference()?.toString() == field

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.typeReference(): String? = ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type

/** The registers an invoke hands over, in order. */
private fun Instruction.argumentRegisters(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}

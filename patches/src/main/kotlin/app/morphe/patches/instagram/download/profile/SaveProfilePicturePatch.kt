/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.profile

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.instagram.download.profilePictureBridges
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.originalName
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.literalReads
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

internal const val PROFILE_PICTURE_PATCH = "Save profile picture"
internal const val PROFILE_PICTURE = "$EXTENSION_PACKAGE/download/ProfilePicture;"
internal const val OFFER_PICTURE = "$PROFILE_PICTURE->offer(Ljava/lang/Object;Ljava/lang/Object;Landroid/content/Context;)V"
internal const val ADD_ROW_STUB = "addRow"

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val CONTEXT = "Landroid/content/Context;"
private const val CLICK = "Landroid/view/View\$OnClickListener;"

/** The parameters of the stub [ADD_ROW_STUB], and of the sheet's adder of a plain row after them. */
internal val ADD_ROW_PARAMETERS = listOf(OBJECT, CONTEXT, CLICK, STRING)
internal val ROW_ADDER_PARAMETERS = listOf(CONTEXT, CLICK, STRING, "I", "Z")

/**
 * The names of the two helpers that build the menu on someone's profile, one a bottom sheet and the
 * other its newer form. Each helper's class keeps its name, and the method that shows the menu
 * hands the same name to the sheet it builds for that helper.
 */
internal val PROFILE_MENUS = listOf("UserOptionsBottomSheetOverflowHelper", "UserOptionsOverflowHelper")

/**
 * Save profile picture, View profile picture, Copy username and Copy bio in the menu on someone's
 * profile, through the one hook before the menu shows. Included in the default selection with every
 * switch off.
 */
@Suppress("unused")
val saveProfilePicturePatch = bytecodePatch(
    name = "Save profile picture",
    description = "Adds Save profile picture, View profile picture, Copy username and Copy bio rows to the menu on " +
        "someone's profile. Save keeps their picture at the largest size Instagram has, View opens it full screen " +
        "with pinch zoom, and the copy rows copy the text exactly. Every switch is off until you turn it on. " +
        "Switches: HushGram settings > Downloads.",
    default = true,
) {
    category("Downloads")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("profilePicture")
        val menus = findProfileMenus()
        val bridges = profilePictureBridges(PROFILE_PICTURE_PATCH)
        applyProfileMenus(menus)
        bridges()
        enableStatus("profilePicture")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$PROFILE_PICTURE_PATCH: $why")

/**
 * One way the menu shows: right before [index], the new instance of the class that shows the
 * built sheet, [sheet] holds the sheet and [owner] the helper, whose fields [user] and [context]
 * hold the profile's account and the context the sheet is shown with. [free] are two locals
 * nothing reads after [index].
 */
internal class ProfileMenuSite(
    val name: String,
    val index: Int,
    val sheet: Int,
    val owner: Int,
    val user: FieldReference,
    val context: FieldReference,
    val free: List<Int>,
)

/** The method that shows the menu, its two ways, the sheet's adder of a plain row and the stub that calls it. */
internal class ProfileMenus(
    val method: MutableMethod,
    val sites: List<ProfileMenuSite>,
    val sheetType: String,
    val adder: Method,
)

/**
 * Finds the method that shows the menu on someone's profile and checks each way it shows it
 * before anything changes. For each of [PROFILE_MENUS] the method loads the name and builds a sheet
 * with it, the same sheet class both times. That sheet goes to a new instance of one class that
 * shows it, with a context the method reads right then off the helper whose class keeps the same
 * name. The helper holds the account in its one field of Instagram's account type. The sheet has
 * one adder of a plain row (context, click listener, label, int, boolean), which the method calls.
 */
internal fun BytecodePatchContext.findProfileMenus(): ProfileMenus {
    val hosts = classesHolding(*PROFILE_MENUS.toTypedArray())
    val candidates = hosts.flatMap { host ->
        host.methods.filter { method -> PROFILE_MENUS.all { it in method.strings() } }.map { host.type to it }
    }
    val (hostType, found) = candidates.singleOrNull()
        ?: refuse("expected one method loading ${PROFILE_MENUS.joinToString(" and ")}, found ${candidates.size}")
    val method = mutableClassDefBy(hostType).methods.single { it.name == found.name && it.parameters() == found.parameters() && it.returnType == found.returnType }
    val code = method.code()
    val targets = method.jumpTargets()

    var sheetType: String? = null
    var showType: String? = null
    val sites = PROFILE_MENUS.map { name ->
        val loads = code.indices.filter { (code[it].reference() as? StringReference)?.string == name }
        val load = loads.singleOrNull() ?: refuse("expected one load of \"$name\", found ${loads.size}")
        val made = code.getOrNull(load + 1)
        val built = code.getOrNull(load + 2)
        val sheet = (made as? OneRegisterInstruction)?.registerA
        val type = (made?.reference() as? TypeReference)?.type
        val init = built?.call()
        val takes = init?.parameters().orEmpty()
        val handed = built?.arguments().orEmpty()
        if (made?.opcode != Opcode.NEW_INSTANCE || sheet == null || type == null || built?.opcode != Opcode.INVOKE_DIRECT ||
            init?.name != "<init>" || init.definingClass != type || takes.size != 2 || !takes[0].startsWith("L") || takes[1] != STRING ||
            handed.size != 3 || handed[0] != sheet || handed[2] != (code[load] as OneRegisterInstruction).registerA
        ) refuse("\"$name\" isn't handed to a new sheet right after it's loaded")
        if (sheetType != null && sheetType != type) refuse("the two menus build different sheets, $sheetType and $type")
        sheetType = type

        // Where the sheet goes to be shown: the one constructor of another class taking just the sheet.
        val shows = method.literalReads(load + 1).filter { at ->
            val call = code[at].call()
            code[at].opcode == Opcode.INVOKE_DIRECT && call?.name == "<init>" && call.parameters() == listOf(type) &&
                code[at].arguments().getOrNull(1) == sheet
        }
        val shown = shows.singleOrNull() ?: refuse("expected the \"$name\" sheet shown once, found ${shows.size}")
        val shower = (code[shown] as FiveRegisterInstruction).registerC
        val index = shown - 1
        val maker = code.getOrNull(index)
        val read = code.getOrNull(shown + 1)
        val show = code.getOrNull(shown + 2)
        val showClass = code[shown].call()!!.definingClass
        if (maker?.opcode != Opcode.NEW_INSTANCE || (maker as OneRegisterInstruction).registerA != shower ||
            (maker.reference() as? TypeReference)?.type != showClass
        ) refuse("the \"$name\" sheet's shower isn't made right before it's handed the sheet")
        if (showType != null && showType != showClass) refuse("the two menus are shown by different classes, $showType and $showClass")
        showType = showClass
        val context = read?.field()
        val showCall = show?.call()
        if (read?.opcode != Opcode.IGET_OBJECT || context == null || context.type != CONTEXT ||
            show?.opcode != Opcode.INVOKE_VIRTUAL || showCall?.definingClass != showClass || showCall.parameters() != listOf(CONTEXT) ||
            showCall.returnType != "V" || show.arguments() != listOf(shower, (read as TwoRegisterInstruction).registerA)
        ) refuse("the \"$name\" sheet isn't shown with a context read off its helper right then")
        val owner = read.registerB
        if (owner == shower || owner == sheet) refuse("the \"$name\" helper's register is reused before the sheet shows")
        if (sheet > 15 || owner > 15) refuse("the \"$name\" sheet or helper is in a register over v15")
        if (index in targets) refuse("something jumps to the \"$name\" sheet's shower, so the hook would be skipped")

        val helper = classDefByOrNull(context.definingClass) ?: refuse("the \"$name\" helper ${context.definingClass} isn't in the app")
        if (helper.originalName() != name) refuse("${helper.type} isn't the helper named \"$name\"")
        val users = helper.fields.filter { it.type == USER && !AccessFlags.STATIC.isSet(it.accessFlags) }
        val user = users.singleOrNull() ?: refuse("expected one account field on the \"$name\" helper, found ${users.size}")
        val free = method.freeLocalsAt(PROFILE_PICTURE_PATCH, index, 2)
        ProfileMenuSite(name, index, sheet, owner, user, context, free)
    }

    val sheetClass = classDefByOrNull(sheetType!!) ?: refuse("the sheet $sheetType isn't in the app")
    val adders = sheetClass.methods.filter {
        it.parameters() == ROW_ADDER_PARAMETERS && it.returnType == "V" && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
            !AccessFlags.STATIC.isSet(it.accessFlags)
    }
    val adder = adders.singleOrNull() ?: refuse("expected one adder of a plain row on $sheetType, found ${adders.size}")
    if (code.none { it.call()?.let { call -> call.definingClass == sheetType && call.name == adder.name && call.parameters() == ROW_ADDER_PARAMETERS } == true }) {
        refuse("the menu doesn't add its rows with ${adder.name}")
    }
    stub()
    return ProfileMenus(method, sites, sheetType!!, adder)
}

/** Only called once [findProfileMenus] found everything. */
internal fun BytecodePatchContext.applyProfileMenus(menus: ProfileMenus) {
    val adder = "${menus.sheetType}->${menus.adder.name}(${ROW_ADDER_PARAMETERS.joinToString("")})V"
    // Six locals for the adder's arguments in a row, then the stub's four parameters. No icon (-1), and
    // false for the plain text color: true draws the row in the red Instagram gives Report.
    replace(stub(), 10, """
        check-cast p0, ${menus.sheetType}
        move-object v0, p0
        move-object v1, p1
        move-object v2, p2
        move-object v3, p3
        const/4 v4, -0x1
        const/4 v5, 0x0
        invoke-virtual/range { v0 .. v5 }, $adder
        const/4 v0, 0x1
        return v0
    """)
    // From the end, so the first site's index still holds when the second's code goes in.
    menus.sites.sortedByDescending { it.index }.forEach { site ->
        val (user, context) = site.free
        menus.method.addInstructions(
            site.index,
            """
                iget-object v$user, v${site.owner}, ${site.user}
                iget-object v$context, v${site.owner}, ${site.context}
                invoke-static { v${site.sheet}, v$user, v$context }, $OFFER_PICTURE
            """,
        )
    }
}

private fun BytecodePatchContext.stub(): MutableMethod {
    val extension = mutableClassDefByOrNull(PROFILE_PICTURE) ?: refuse("the extension has no $PROFILE_PICTURE")
    if (extension.methods.none { "${it.definingClass}->${it.name}(${it.parameters().joinToString("")})${it.returnType}" == OFFER_PICTURE &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
    ) refuse("the extension has no public static $OFFER_PICTURE")
    return extension.methods.singleOrNull {
        it.name == ADD_ROW_STUB && it.parameters() == ADD_ROW_PARAMETERS && it.returnType == "Z" &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("$PROFILE_PICTURE has no static Z $ADD_ROW_STUB(${ADD_ROW_PARAMETERS.joinToString("")})")
}

private fun BytecodePatchContext.replace(method: MutableMethod, registers: Int, body: String) {
    val replacement = ImmutableMethod(method.definingClass, method.name, method.parameters, method.returnType,
        method.accessFlags, method.annotations, method.hiddenApiRestrictions,
        ImmutableMethodImplementation(registers, emptyList(), null, null)).toMutable().apply {
        addInstructionsWithLabels(0, body.trimIndent())
    }
    val owner = mutableClassDefBy(method.definingClass)
    owner.methods.remove(method)
    owner.methods.add(replacement)
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Method.parameters(): List<String> = parameterTypes.map { it.toString() }
private fun Method.strings(): Set<String> = code().mapNotNull { (it.reference() as? StringReference)?.string }.toSet()
private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
private fun Instruction.call() = reference() as? MethodReference
private fun Instruction.field() = reference() as? FieldReference
private fun MethodReference.parameters(): List<String> = parameterTypes.map { it.toString() }
private fun Instruction.arguments(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction ->
        (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}

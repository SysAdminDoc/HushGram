/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.share

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
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

private const val PATCH = "Hide group buttons on the share sheet"
internal const val HIDE_GROUP_BUTTON = "$EXTENSION_PACKAGE/share/ShareSheet;->hideGroupButton()Z"
internal const val HIDE_GROUP_ACTION = "$EXTENSION_PACKAGE/share/ShareSheet;->hideGroupAction()Z"
internal const val HIDE_GROUP_SEND = "$EXTENSION_PACKAGE/share/ShareSheet;->hideGroupSend()Z"
internal const val FILTER_ACTIONS = "$EXTENSION_PACKAGE/share/ShareSheetActions;->filter(Ljava/util/List;)Ljava/util/List;"

/**
 * Names the share sheet gives its round buttons that only the code naming its row holds: the row's
 * builder loads them all, and so does the class that picks the names. Kept strings.
 */
internal val ACTION_NAMES = listOf("add_to_your_story", "whatsapp_status", "screenshot_preview")
internal const val ACTION_PICKER_NAME = "copy_link"
private const val GEN_AI_PARAMS = "Lcom/instagram/direct/model/genai/GenAIParamsMetadata;"

/** The share sheet, and the fields it keeps its New group button and its search box in. All kept names. */
internal const val SHARE_SHEET = "Linstagram/features/direct/fragment/sharesheet/DirectShareSheetFragment;"
internal const val GROUP_BUTTON = "createGroupButton"
internal const val STICKY_SEARCH_BOX = "stickySearchBox"

/** The search box, and its kept setters for the action it can show at its end. */
internal const val SEARCH_BOX = "Lcom/instagram/igds/components/search/IgdsInlineSearchBox;"
internal const val ACTION_VISIBLE = "setVisibilityOfCustomActionButton"
internal const val ACTION_ENABLED = "setCustomActionEnabled"

/** The bar under the sheet once you pick people, and the button in it that sends to them as a group. Kept names. */
internal const val COMPOSER = "Lcom/instagram/direct/fragment/sharesheet/view/DirectShareSheetFragmentMessageComposerViewBinder;"
internal const val GROUP_SEND_BUTTON = "Lcom/instagram/direct/fragment/sharesheet/groupsendbutton/shared/GroupSendButton;"
internal const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

private const val VIEW = "Landroid/view/View;"
private const val VIEW_STUB = "Landroid/view/ViewStub;"
private const val LAYOUT_PARAMS = "Landroid/view/ViewGroup\$LayoutParams;"

/**
 * Takes the group buttons off the share sheet. In the default selection with its switch off:
 * they're buttons some people use, and asked to go by others, so leaving them out is the user's pick.
 */
@Suppress("unused")
val hideShareSheetGroupPatch = bytecodePatch(
    name = "Hide group buttons on the share sheet",
    description = "Takes the New group button and the button that sends to several people as a group off the " +
        "share sheet, and lets you pick which of the round buttons under the people it shows. You can still start " +
        "a group from your messages. Both start off. Find them in HushGram settings > Sharing.",
) {
    category("Interaction")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("shareSheet")
        val sheet = findShareSheet()
        val send = findGroupSend()
        val actions = findActionListOrWarn()
        guardGroupButton(sheet.build)
        guardGroupAction(sheet.reveals)
        guardGroupSend(send)
        actions?.let { filterActionList(it) }
        enableStatus("shareSheet")
    }
}

/** A method by its class, name and parameter types. */
internal class SheetMethod(val type: String, val name: String, val parameters: List<String>)

/**
 * A place the share sheet shows the search box's own New group action: the method, the index of
 * its [ACTION_VISIBLE] call on the box read from [STICKY_SEARCH_BOX], the register holding the box
 * there, and a local the guard after it may borrow.
 */
internal class RevealSite(val method: SheetMethod, val at: Int, val box: Int, val free: Int)

/** Where the share sheet builds its New group button from a stub, and where it reveals the button. */
internal class ShareSheetSites(val build: SheetMethod, val reveals: List<RevealSite>)

/**
 * Finds the share sheet's New group methods by the [GROUP_BUTTON] field. The build is the one
 * method writing it: it inflates a stub it takes and keeps what comes out. The reveals are where a
 * method reading it shows, when there's no button, the search box's action with [ACTION_VISIBLE]
 * instead: on 450 one method of its own, which 451's 385713073 copies into onActivityResult and
 * onViewCreated. Checks the search box field and both setters the guard calls are there. Fails when
 * any of that isn't so, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findShareSheet(): ShareSheetSites {
    val sheet = classDefByOrNull(SHARE_SHEET) ?: refuse("$SHARE_SHEET isn't in this Instagram")
    if (sheet.fields.none { it.name == GROUP_BUTTON && it.type == VIEW }) refuse("$SHARE_SHEET keeps no $GROUP_BUTTON view")
    if (sheet.fields.none { it.name == STICKY_SEARCH_BOX && it.type == SEARCH_BOX && !AccessFlags.STATIC.isSet(it.accessFlags) }) {
        refuse("$SHARE_SHEET keeps no $STICKY_SEARCH_BOX")
    }
    val box = classDefByOrNull(SEARCH_BOX) ?: refuse("$SEARCH_BOX isn't in this Instagram")
    for (setter in listOf(ACTION_VISIBLE, ACTION_ENABLED)) {
        box.methods.singleOrNull {
            it.name == setter && it.parameterTypes.map(CharSequence::toString) == listOf("Z") && it.returnType == "V" &&
                AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
        } ?: refuse("$SEARCH_BOX has no public $setter(Z)V")
    }

    val builds = sheet.methods.filter { it.touchesGroupButton(Opcode.IPUT_OBJECT) }
    val build = builds.singleOrNull() ?: refuse("$SHARE_SHEET keeps its $GROUP_BUTTON in ${builds.size} methods, expected one")
    val buildAt = "$SHARE_SHEET->${build.name}"
    if (build.isStatic() || build.returnType != "V" || VIEW_STUB !in build.parameters() ||
        build.implementation!!.instructions.none { it.calls(VIEW_STUB, "inflate") }
    ) {
        refuse("$buildAt isn't a build of the button from a stub")
    }
    if (build.locals() < 3) refuse("$buildAt has fewer than three registers of its own")
    // this sits just past the locals, and the guard reads a field of it, which reaches v15 at most.
    if (build.locals() > 15) refuse("$buildAt keeps this past v15, out of reach of a field read")

    val reveals = sheet.methods.filter { it.testsGroupButton() }.flatMap { method -> revealsIn(method) }
    if (reveals.isEmpty()) refuse("expected a method of $SHARE_SHEET revealing its $GROUP_BUTTON or the search box's action, found none")

    return ShareSheetSites(build.site(SHARE_SHEET), reveals)
}

/**
 * The calls in [method] showing the search box's action: each [ACTION_VISIBLE] on a register last
 * written by a read of [STICKY_SEARCH_BOX], in a register an invoke can name, with a local free
 * after the call for the guard's answer.
 */
private fun revealsIn(method: Method): List<RevealSite> {
    val code = method.implementation!!.instructions.toList()
    return code.indices.filter { code[it].calls(SEARCH_BOX, ACTION_VISIBLE) }.mapNotNull { at ->
        val box = (code[at] as FiveRegisterInstruction).registerC
        val read = (at - 1 downTo 0).firstOrNull { code[it].writes(box) } ?: return@mapNotNull null
        val field = (code[read].takeIf { it.opcode == Opcode.IGET_OBJECT } as? ReferenceInstruction)?.reference as? FieldReference
        if (field?.definingClass != SHARE_SHEET || field.name != STICKY_SEARCH_BOX) return@mapNotNull null
        val where = "$SHARE_SHEET->${method.name}"
        if (box > 15) refuse("$where keeps the search box past v15 at instruction $at, out of reach of the guard's calls")
        val free = method.freeLocalsAt(PATCH, at + 1, 1, except = listOf(box)).single()
        RevealSite(method.site(SHARE_SHEET), at, box, free)
    }
}

/** Where the bar under the sheet shows its send-as-group button, the session's parameter register there, and where it hides the button. */
internal class GroupSendSites(val show: SheetMethod, val session: Int, val hide: SheetMethod)

/**
 * Finds [COMPOSER]'s two send-as-group methods. The show is the one method calling a method of
 * [GROUP_SEND_BUTTON], which sets the button up for the people picked and makes it visible; it
 * takes the session. The hide is the one other method taking just the session and reading a
 * [GROUP_SEND_BUTTON] field, which Instagram calls when the pick no longer gets the button.
 */
internal fun BytecodePatchContext.findGroupSend(): GroupSendSites {
    val composer = classDefByOrNull(COMPOSER) ?: refuse("$COMPOSER isn't in this Instagram")
    val shows = composer.methods.filter { method ->
        method.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == GROUP_SEND_BUTTON
        } == true
    }
    val show = shows.singleOrNull() ?: refuse("expected one method of $COMPOSER setting up its $GROUP_SEND_BUTTON, found ${shows.size}")
    val showAt = "$COMPOSER->${show.name}"
    if (show.isStatic() || show.returnType != "V" || show.parameters().count { it == USER_SESSION } != 1) {
        refuse("$showAt isn't an instance method taking the session once")
    }
    if (show.locals() < 2) refuse("$showAt has fewer than two registers of its own")
    // A parameter's p-register: this is p0, and each parameter before it takes one, or two for a long or a double.
    val session = 1 + show.parameters().takeWhile { it != USER_SESSION }.sumOf { if (it == "J" || it == "D") 2 else 1 }

    val hides = composer.methods.filter { method ->
        method.name != show.name && !method.isStatic() && method.returnType == "V" && method.parameters() == listOf(USER_SESSION) &&
            method.implementation?.instructions?.any {
                it.opcode == Opcode.IGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).type == GROUP_SEND_BUTTON
            } == true
    }
    val hide = hides.singleOrNull() ?: refuse("expected one method of $COMPOSER hiding its $GROUP_SEND_BUTTON, found ${hides.size}")
    return GroupSendSites(show.site(COMPOSER), session, hide.site(COMPOSER))
}

/**
 * Where the row of round buttons asks for its button names: the builder, and the index and register
 * of the move-result that takes the list of names.
 */
internal class ActionListSite(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val moveResult: Int,
    val register: Int,
)

/**
 * Finds where the share sheet's row of round buttons (Add to story, Share to, WhatsApp, Copy link,
 * Download and the rest) gets its list of button names. One method builds the row: it loads the
 * [ACTION_NAMES] and asks a picker for the names, a call returning a list that takes the sheet's
 * Gen AI metadata, a sheet type, a string and three booleans, and it makes one button for each name it
 * knows. The picker's own class loads [ACTION_PICKER_NAME]. The two other places that ask the picker
 * only look up where a tapped button stood, for Instagram's logging, and load none of the names.
 * Fails unless exactly one call in the builder fits, with its result taken by a move-result-object.
 */
internal fun BytecodePatchContext.findActionList(): ActionListSite {
    val sites = mutableListOf<ActionListSite>()
    classesHolding(*ACTION_NAMES.toTypedArray()).forEach { classDef ->
        classDef.methods.forEach { method ->
            val code = method.implementation?.instructions?.toList() ?: return@forEach
            val asks = code.indices.filter { at -> code[at].isPickerCall() }
            if (asks.isEmpty() || !ACTION_NAMES.all { loadsString(method, it) }) return@forEach
            for (at in asks) {
                val take = code.getOrNull(at + 1)
                if (take?.opcode != Opcode.MOVE_RESULT_OBJECT) continue
                val picker = ((code[at] as ReferenceInstruction).reference as MethodReference).definingClass
                val pickerClass = classDefByOrNull(picker) ?: continue
                if (pickerClass.methods.none { loadsString(it, ACTION_PICKER_NAME) }) continue
                sites += ActionListSite(
                    classDef.type, method.name, method.parameters(), method.returnType, at + 1,
                    (take as OneRegisterInstruction).registerA,
                )
            }
        }
    }
    val site = sites.singleOrNull() ?: refuse("expected one call asking for the share sheet's button names, found ${sites.size}")
    val where = "${site.type}->${site.name}"
    if (site.register > 255) refuse("$where keeps the names past v255, out of reach of a move-result")
    val method = classDefByOrNull(site.type)!!.methods.single { it.name == site.name && it.parameters() == site.parameters }
    // The filter goes in right after the move-result, so no branch may enter between the two.
    if (site.moveResult + 1 in method.jumpTargets()) refuse("a branch enters $where right after it takes the button names")
    return site
}

/** Whether this is the call that asks for the share sheet's button names. */
private fun Instruction.isPickerCall(): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false
    val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    val types = call.parameterTypes.map(CharSequence::toString)
    return call.returnType == "Ljava/util/List;" && types.size == 6 && types[0] == GEN_AI_PARAMS &&
        types[2] == "Ljava/lang/String;" && types.drop(3).all { it == "Z" }
}

/** The row's list of button names, or null with a warning, so the group buttons still go in. */
internal fun BytecodePatchContext.findActionListOrWarn(): ActionListSite? = try {
    findActionList()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in without the share sheet button picker.")
    null
}

/**
 * Hands the list of button names to [FILTER_ACTIONS] the moment it comes back, and goes on with
 * what it answers: the same list, or a copy without the buttons the user hid. The builder makes
 * one button for each name left, so a hidden one takes no room.
 */
internal fun BytecodePatchContext.filterActionList(site: ActionListSite) {
    mutableMethod(SheetMethod(site.type, site.name, site.parameters)).addInstructions(
        site.moveResult + 1,
        """
            invoke-static/range { v${site.register} .. v${site.register} }, $FILTER_ACTIONS
            move-result-object v${site.register}
        """,
    )
}

/**
 * Asks [HIDE_GROUP_BUTTON] first thing in the build, and on a yes returns before the stub is
 * inflated, so it takes no room. Beside a button of its own the search box is a small square, so it
 * gets the full width first, as Instagram gives it where New group sits inside the box instead.
 */
internal fun BytecodePatchContext.guardGroupButton(build: SheetMethod) {
    mutableMethod(build).addInstructions(
        0,
        """
            invoke-static { }, $HIDE_GROUP_BUTTON
            move-result v0
            if-eqz v0, :build
            iget-object v0, p0, $SHARE_SHEET->$STICKY_SEARCH_BOX:$SEARCH_BOX
            if-eqz v0, :hidden
            invoke-virtual { v0 }, $VIEW->getLayoutParams()$LAYOUT_PARAMS
            move-result-object v1
            if-eqz v1, :hidden
            const/4 v2, -0x1
            iput v2, v1, $LAYOUT_PARAMS->width:I
            invoke-virtual { v0, v1 }, $VIEW->setLayoutParams($LAYOUT_PARAMS)V
            :hidden
            return-void
            :build
            nop
        """,
    )
}

/**
 * Asks [HIDE_GROUP_ACTION] right after each reveal shows the search box's action, and on a yes
 * hides and turns off that action again. Where the sheet put New group in the search box, it gave
 * the box that action before the reveal runs, and the box shows its action again whenever it
 * refreshes while the action is on, so hiding it alone isn't enough. A jump to the instruction
 * after the call didn't come through it and skips the guard, as it should. Later sites of one
 * method go first, so writing one doesn't move another. The guard sits mid-method, so its own
 * label goes through addInstructionsWithLabels: plain addInstructions leaves it pointing into the
 * compiled snippet, and the dex then branches to the snippet's offset from the method's start.
 */
internal fun BytecodePatchContext.guardGroupAction(reveals: List<RevealSite>) {
    reveals.sortedByDescending { it.at }.forEach { site ->
        val answer = site.free
        mutableMethod(site.method).addInstructionsWithLabels(
            site.at + 1,
            """
                invoke-static { }, $HIDE_GROUP_ACTION
                move-result v$answer
                if-eqz v$answer, :shown
                const/4 v$answer, 0x0
                invoke-virtual { v${site.box}, v$answer }, $SEARCH_BOX->$ACTION_VISIBLE(Z)V
                invoke-virtual { v${site.box}, v$answer }, $SEARCH_BOX->$ACTION_ENABLED(Z)V
                :shown
                nop
            """,
        )
    }
}

/**
 * Asks [HIDE_GROUP_SEND] first thing in the show, and on a yes calls the hide with the same session
 * and returns, so the button is put away rather than left as it was. The registers are moved low
 * first, since the show's parameters sit past v15 where a plain invoke can't reach them.
 */
internal fun BytecodePatchContext.guardGroupSend(send: GroupSendSites) {
    mutableMethod(send.show).addInstructions(
        0,
        """
            invoke-static { }, $HIDE_GROUP_SEND
            move-result v0
            if-eqz v0, :show
            move-object/from16 v0, p0
            move-object/from16 v1, p${send.session}
            invoke-virtual { v0, v1 }, $COMPOSER->${send.hide.name}($USER_SESSION)V
            return-void
            :show
            nop
        """,
    )
}

private fun BytecodePatchContext.mutableMethod(site: SheetMethod) = mutableClassDefBy(site.type).methods.single {
    it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
}

private fun Method.site(type: String) = SheetMethod(type, name, parameters())

private fun Method.parameters() = parameterTypes.map(CharSequence::toString)

private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)

/** The registers a method has besides its parameters and `this`. */
private fun Method.locals(): Int {
    val parameters = parameterTypes.sumOf { if (it == "J" || it == "D") 2 else 1 } + if (isStatic()) 0 else 1
    return (implementation?.registerCount ?: 0) - parameters
}

private fun Method.touchesGroupButton(opcode: Opcode) = implementation?.instructions?.any {
    it.opcode == opcode && ((it as ReferenceInstruction).reference as FieldReference).let { field ->
        field.definingClass == SHARE_SHEET && field.name == GROUP_BUTTON && field.type == VIEW
    }
} == true

/** Whether the method reads [GROUP_BUTTON] and tests it for null right away, as a reveal does. */
private fun Method.testsGroupButton(): Boolean {
    val code = implementation?.instructions?.toList() ?: return false
    return code.indices.any { at ->
        code[at].opcode == Opcode.IGET_OBJECT && ((code[at] as ReferenceInstruction).reference as FieldReference).let { field ->
            field.definingClass == SHARE_SHEET && field.name == GROUP_BUTTON && field.type == VIEW
        } && code.getOrNull(at + 1)?.let {
            (it.opcode == Opcode.IF_EQZ || it.opcode == Opcode.IF_NEZ) &&
                (it as OneRegisterInstruction).registerA == (code[at] as TwoRegisterInstruction).registerA
        } == true
    }
}

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val written = (this as? OneRegisterInstruction)?.registerA ?: return false
    return written == register || (opcode.setsWideRegister() && written + 1 == register)
}

private fun Instruction.calls(type: String, name: String) =
    ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let { it.definingClass == type && it.name == name } == true

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

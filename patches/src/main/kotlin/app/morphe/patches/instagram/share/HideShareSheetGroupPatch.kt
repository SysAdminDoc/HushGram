/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.share

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
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
internal const val HIDE_SEARCH_BOX = "$EXTENSION_PACKAGE/share/ShareSheet;->searchBox(Landroid/view/View;)V"
internal const val QUICK_SEND_PEOPLE = "$EXTENSION_PACKAGE/share/QuickSend;->people(I)I"
internal const val QUICK_SEND_INFLATE =
    "$EXTENSION_PACKAGE/share/QuickSend;->inflate(Landroid/view/LayoutInflater;ILandroid/view/ViewGroup;)Landroid/view/View;"
internal const val QUICK_SEND_SLOTS = "$EXTENSION_PACKAGE/share/QuickSend;->addSlots(Landroid/widget/PopupWindow;I)I"
internal const val QUICK_SEND_AVATAR = "$EXTENSION_PACKAGE/share/QuickSend;->avatarId(I)I"
internal const val QUICK_SEND_LABEL = "$EXTENSION_PACKAGE/share/QuickSend;->labelId(I)I"

/** The message the face for a rank throws with past the ranks it knows. Only that constructor loads it. */
internal const val QUICK_SEND_RANKS = "rank higher than MAX_AVATAR_COUNT"
private const val POPUP_WINDOW = "Landroid/widget/PopupWindow;"
private const val LAYOUT_INFLATER = "Landroid/view/LayoutInflater;"

/**
 * The name the share sheet's list gives its Add a note entry, which the row a long press on Share
 * opens leaves out before taking its first [QUICK_SEND_STOCK] people. Only that method loads it.
 */
internal const val QUICK_SEND_NOTE = "add_content_note"
internal const val QUICK_SEND_STOCK = 4

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
        "share sheet, and lets you pick which of the round buttons under the people it shows and hide its search " +
        "bar. It can also fill the row of people a long press on Share opens. You can still start a group from your " +
        "messages. All start off. Find them in HushGram settings > Sharing.",
) {
    category("Interaction")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("shareSheet")
        val sheet = findShareSheet()
        val send = findGroupSend()
        val actions = findActionListOrWarn()
        val search = findSearchBoxOrWarn()
        val quickSend = findQuickSendOrWarn()
        guardGroupButton(sheet.build)
        guardGroupAction(sheet.reveals)
        guardGroupSend(send)
        actions?.let { filterActionList(it) }
        search?.let { hookSearchBox(it) }
        quickSend?.let { askQuickSendPeople(it) }
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
    // 455's x86_64 builds ask a pool for whatsapp_status in the builder, so a class holding all three names misses it.
    val loading = ACTION_NAMES.map { name -> classesLoadingString(name) }
    val alsoLoading = loading.drop(1).map { classes -> classes.mapTo(HashSet()) { it.type } }
    loading.first().filter { classDef -> alsoLoading.all { classDef.type in it } }.forEach { classDef ->
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

/**
 * Whether this is the call that asks for the share sheet's button names. Up to 453 it takes the Gen
 * AI metadata, the sheet type, a string and three booleans; 454 dropped the metadata and the string
 * for a holder of its own, so there it's the holder, the sheet type and the booleans.
 */
private fun Instruction.isPickerCall(): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false
    val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    val types = call.parameterTypes.map(CharSequence::toString)
    val withMetadata = types.size == 6 && types[0] == GEN_AI_PARAMS && types[2] == "Ljava/lang/String;"
    val withHolder = types.size == 5 && types[0].startsWith("L") && types[1].startsWith("L")
    return call.returnType == "Ljava/util/List;" && (withMetadata || withHolder) && types.takeLast(3).all { it == "Z" }
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

/** Where the share sheet keeps its search box: the method, the index of the write and the register holding the box. */
internal class SearchBoxSite(val method: SheetMethod, val at: Int, val box: Int)

/**
 * Finds where the share sheet keeps the search box it just found by its id: the one write of
 * [STICKY_SEARCH_BOX] in the sheet of a value cast to [SEARCH_BOX] by the instruction before, which
 * onViewCreated makes. The sheet shows the box again a few lines later, which the extension undoes
 * each time the box is laid out. Fails unless there's
 * exactly one, with no branch entering right after it, where the hook goes.
 */
internal fun BytecodePatchContext.findSearchBox(): SearchBoxSite {
    val sheet = classDefByOrNull(SHARE_SHEET) ?: refuse("$SHARE_SHEET isn't in this Instagram")
    val found = sheet.methods.flatMap { method ->
        val code = method.implementation?.instructions?.toList() ?: emptyList()
        code.indices.filter { at -> code[at].keepsSearchBox() && code.getOrNull(at - 1)?.castsToSearchBox((code[at] as TwoRegisterInstruction).registerA) == true }
            .map { at -> method to at }
    }
    val (method, at) = found.singleOrNull() ?: refuse("$SHARE_SHEET keeps a found $STICKY_SEARCH_BOX in ${found.size} places, expected one")
    if (at + 1 in method.jumpTargets()) refuse("a branch enters $SHARE_SHEET->${method.name} right after it keeps the search box")
    return SearchBoxSite(method.site(SHARE_SHEET), at, (method.implementation!!.instructions.elementAt(at) as TwoRegisterInstruction).registerA)
}

/** Where the sheet keeps its search box, or null with a warning, so the group buttons still go in. */
internal fun BytecodePatchContext.findSearchBoxOrWarn(): SearchBoxSite? = try {
    findSearchBox()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in without the switch hiding the search bar.")
    null
}

/**
 * Hands the search box to [HIDE_SEARCH_BOX] right after the sheet keeps it, which hides it while
 * the user wants it gone. A range call, so the box's register may sit anywhere.
 */
internal fun BytecodePatchContext.hookSearchBox(site: SearchBoxSite) {
    mutableMethod(site.method).addInstructions(
        site.at + 1,
        "invoke-static/range { v${site.box} .. v${site.box} }, $HIDE_SEARCH_BOX",
    )
}

/**
 * Where the row a long press on Share opens settles on four people: the method filling the row,
 * the index of its `const/4 vN, 4` right before the take of the first four, and vN; and the row's
 * width clamp, the `(I)V` method it hands the row's size to, which keeps the size at four or less
 * with a `const/4 vM, 4` and a `const/4 p1, 4`, and vM there; and the clamp class's constructor,
 * the index of the `iput` keeping the most faces the row draws, and the register holding its four.
 * [slots] says where the faces past Instagram's go.
 */
internal class QuickSendSites(
    val fill: SheetMethod,
    val four: Int,
    val count: Int,
    val clamp: SheetMethod,
    val limit: Int,
    val size: Int,
    val build: SheetMethod,
    val cap: Int,
    val faces: Int,
    val slots: QuickSendSlots,
)

/**
 * Where the row gets room for more faces than its layout has. Instagram's layout holds four slots, an
 * avatar and its name each, and the class making a face for each rank finds its slot by a switch over
 * the ranks it knows, five, throwing past them. In the popup's constructor: [inflate], the index of
 * its one `LayoutInflater.inflate(int, ViewGroup)`, which [QUICK_SEND_INFLATE] takes over so the
 * layout is known; [end], its one `return-void`, where [QUICK_SEND_SLOTS] adds the slots and the
 * most faces is kept again as the count it made room for; and [kept], that field. In [slot], the
 * constructor of a face by rank: [ranks], the index of its `const-string` of [QUICK_SEND_RANKS]
 * that starts the throw; [tail], the index where every rank's branch meets to find the slot's name
 * with [find] on the content view in [content] and the id in [id]; the registers holding the rank
 * and the popup; [avatar], the field the slot's avatar is kept in; and [look], the call each known
 * rank finds its avatar with. The two lookups differ where Redex folded the name's cast to a text
 * view into [find] (armeabi-v7a), so the avatar, an image, never goes through it.
 */
internal class QuickSendSlots(
    val inflate: Int,
    val end: Int,
    val kept: String,
    val slot: SheetMethod,
    val ranks: Int,
    val tail: Int,
    val find: String,
    val content: Int,
    val id: Int,
    val rank: Int,
    val popup: Int,
    val avatar: String,
    val look: String,
)

/**
 * Finds the row a long press on Share opens. The one method loading [QUICK_SEND_NOTE] leaves the
 * note out of the share sheet's list and takes its first four with a static
 * `(Iterable, int)List` call right after `const/4 vN, 4`; a few lines on it hands the row's
 * `List.size()` to the clamp. The clamp opens `const/4 vM, 4`, `if-le p1, vM` past
 * `const/4 p1, 4`. Instagram's other way of filling the row (a ranked list it loads itself) goes
 * through the same clamp, and both draw no more faces than an int field of the clamp's class that
 * its constructor sets to four, which another of its methods reads. Fails unless each is there once.
 */
internal fun BytecodePatchContext.findQuickSend(): QuickSendSites {
    val takes = classesLoadingString(QUICK_SEND_NOTE).flatMap { classDef ->
        classDef.methods.filter { loadsString(it, QUICK_SEND_NOTE) }.flatMap { method ->
            val code = method.implementation?.instructions?.toList() ?: emptyList()
            code.indices.filter { at -> code[at].isTake() && code.getOrNull(at - 1)?.isFourInto((code[at] as FiveRegisterInstruction).registerD) == true }
                .map { at -> Triple(classDef.type, method, at - 1) }
        }
    }
    val (type, fill, four) = takes.singleOrNull() ?: refuse("expected one take of four people by the method loading $QUICK_SEND_NOTE, found ${takes.size}")
    val fillAt = "$type->${fill.name}"
    if (four + 1 in fill.jumpTargets()) refuse("a branch enters $fillAt between its four and its take")
    val code = fill.implementation!!.instructions.toList()
    val count = (code[four] as OneRegisterInstruction).registerA

    val clamps = (four + 2 until code.size).mapNotNull { at ->
        val call = (code[at] as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
        val sized = code.getOrNull(at - 1)?.opcode == Opcode.MOVE_RESULT &&
            ((code.getOrNull(at - 2) as? ReferenceInstruction)?.reference as? MethodReference)?.let { it.name == "size" && it.returnType == "I" } == true
        if (!sized || code[at].opcode != Opcode.INVOKE_VIRTUAL || call.returnType != "V" || call.parameterTypes.map(CharSequence::toString) != listOf("I")) {
            return@mapNotNull null
        }
        call
    }
    val call = clamps.firstOrNull() ?: refuse("$fillAt hands the row's size to no (I)V method after its take")
    val clampClass = classDefByOrNull(call.definingClass) ?: refuse("${call.definingClass} isn't in this Instagram")
    val clamp = clampClass.methods.singleOrNull { it.name == call.name && it.parameters() == listOf("I") && it.returnType == "V" && !it.isStatic() }
        ?: refuse("${call.definingClass} defines no ${call.name}(I)V")
    val clampAt = "${call.definingClass}->${call.name}"
    val body = clamp.implementation!!.instructions.toList()
    val size = clamp.implementation!!.registerCount - 1
    val limit = (body.getOrNull(0) as? OneRegisterInstruction)?.registerA ?: refuse("$clampAt is empty")
    val keeps = body.size > 3 && body[0].isFourInto(limit) && body[1].opcode == Opcode.IF_LE &&
        (body[1] as TwoRegisterInstruction).let { it.registerA == size && it.registerB == limit } && body[2].isFourInto(size)
    if (!keeps) refuse("$clampAt doesn't open by keeping the size at four")
    val targets = clamp.jumpTargets()
    if (3 !in targets) refuse("$clampAt doesn't jump past its four when the size fits")
    if (1 in targets || 2 in targets) refuse("a branch enters $clampAt inside its clamp")
    // The answer goes back into the clamp's own registers with move and a plain invoke, which reach v15.
    if (size > 15 || limit > 15) refuse("$clampAt keeps the size past v15")

    val caps = clampClass.methods.filter { it.name == "<init>" }.flatMap { build ->
        val made = build.implementation?.instructions?.toList() ?: emptyList()
        val targets = build.jumpTargets()
        made.indices.mapNotNull { at -> made.fourKeptAt(at, call.definingClass, targets)?.let { (faces, field) -> Triple(build, at, faces) to field } }
    }.filter { (_, field) ->
        clampClass.methods.any { other -> other.name != "<init>" && other.readsIntField(call.definingClass, field) }
    }.map { it.first }
    val (build, cap, faces) = caps.singleOrNull() ?: refuse("expected one constructor of ${call.definingClass} keeping four faces, found ${caps.size}")
    if (cap in build.jumpTargets()) refuse("a branch enters ${call.definingClass}'s constructor at its four faces")
    val kept = ((build.implementation!!.instructions.elementAt(cap) as ReferenceInstruction).reference as FieldReference).toString()
    val slots = findQuickSendSlots(call.definingClass, build, kept)
    return QuickSendSites(fill.site(type), four, count, clamp.site(call.definingClass), limit, size, build.site(call.definingClass), cap, faces, slots)
}

/**
 * Finds where the row gets room for more faces ([QuickSendSlots]): in the popup's constructor
 * [build] its one inflate and its one `return-void`, and the one constructor loading
 * [QUICK_SEND_RANKS], which takes the popup and a rank, and in it the throw, the one call finding
 * the slot's name every rank's branch meets at, the popup's register and the avatar's field. Fails
 * unless each is there once and in reach of the plain invokes and field writes the patch adds.
 */
private fun BytecodePatchContext.findQuickSendSlots(popup: String, build: Method, kept: String): QuickSendSlots {
    val made = build.implementation!!.instructions.toList()
    val inflates = made.indices.filter { at ->
        val call = (made[at] as? ReferenceInstruction)?.reference as? MethodReference
        made[at].opcode == Opcode.INVOKE_VIRTUAL && call != null && call.definingClass == LAYOUT_INFLATER && call.name == "inflate" &&
            call.parameterTypes.map(CharSequence::toString) == listOf("I", "Landroid/view/ViewGroup;") && call.returnType == VIEW
    }
    val inflate = inflates.singleOrNull() ?: refuse("$popup's constructor inflates ${inflates.size} layouts, expected one")
    val ends = made.indices.filter { made[it].opcode == Opcode.RETURN_VOID }
    val end = ends.singleOrNull() ?: refuse("$popup's constructor returns in ${ends.size} places, expected one")
    // The slots go in with the popup in p0 and the count in v0, both plain invoke registers.
    if (build.locals() > 15) refuse("$popup's constructor keeps this past v15")

    val makers = classesLoadingString(QUICK_SEND_RANKS).flatMap { classDef ->
        classDef.methods.filter { it.name == "<init>" && loadsString(it, QUICK_SEND_RANKS) }.map { classDef.type to it }
    }
    val (type, slot) = makers.singleOrNull() ?: refuse("expected one constructor loading \"$QUICK_SEND_RANKS\", found ${makers.size}")
    val at = "$type's constructor"
    if (slot.parameters() != listOf(popup, "I")) refuse("$at doesn't take the popup and a rank")
    val code = slot.implementation!!.instructions.toList()
    val ranks = code.indices.singleOrNull { (code[it] as? ReferenceInstruction)?.reference?.toString() == QUICK_SEND_RANKS }
        ?: refuse("$at loads \"$QUICK_SEND_RANKS\" more than once")
    if (code.drop(ranks + 1).take(3).none { it.opcode == Opcode.THROW }) refuse("$at doesn't throw past the ranks it knows")
    val targets = slot.jumpTargets()
    if (ranks in targets) refuse("a branch enters $at at its throw")
    val compared = code.take(ranks).filter { it.opcode == Opcode.IF_EQ }.map { (it as TwoRegisterInstruction).registerA }.toSet()
    val rank = compared.singleOrNull() ?: refuse("$at tests ${compared.size} registers against the ranks, expected one")

    val meets = code.indices.filter { it in targets && code[it].findsView() }
    val tail = meets.singleOrNull() ?: refuse("expected one call finding a view where $at's ranks meet, found ${meets.size}")
    val find = code[tail] as FiveRegisterInstruction
    val shown = code.filter { it.opcode == Opcode.INVOKE_VIRTUAL && it.calls(POPUP_WINDOW, "getContentView") }
        .map { (it as FiveRegisterInstruction).registerC }.toSet()
    val window = shown.singleOrNull() ?: refuse("$at reads the popup's content view from ${shown.size} registers, expected one")
    val avatars = code.filter { it.opcode == Opcode.IPUT_OBJECT }.map { (it as ReferenceInstruction).reference as FieldReference }
        .filter { it.definingClass == type && it.type == VIEW }.map(FieldReference::toString).toSet()
    val avatar = avatars.singleOrNull() ?: refuse("$at keeps ${avatars.size} views of its own, expected the avatar alone")
    // Each known rank keeps the view its lookup answered: the call two before each write of the avatar.
    val looks = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == avatar && code[it].opcode == Opcode.IPUT_OBJECT }
        .map { put -> code.getOrNull(put - 2)?.takeIf { it.findsView() && code[put - 1].opcode == Opcode.MOVE_RESULT_OBJECT } }
    if (looks.isEmpty() || looks.any { it == null }) refuse("$at keeps an avatar it didn't find with a view lookup")
    val look = looks.map { (it as ReferenceInstruction).reference }
        .filter { (it as MethodReference).returnType == VIEW }.map(Any::toString).toSet().singleOrNull()
        ?: refuse("$at finds its avatars with other than one lookup answering a view")
    val registers = listOf(find.registerC, find.registerD, rank, window)
    if (registers.toSet().size != 4) refuse("$at shares a register between the rank, the popup and the slot's lookup")
    if (registers.any { it > 15 } || slot.locals() > 15) refuse("$at keeps a register the patch writes past v15")
    return QuickSendSlots(
        inflate, end, kept, slot.site(type), ranks, tail, (find as ReferenceInstruction).reference.toString(),
        find.registerC, find.registerD, rank, window, avatar, look,
    )
}

/**
 * Whether this is a static `(View, int)` call with two registers answering a view, Instagram's view
 * lookup that never answers null. Where Redex folded a cast into it, it answers the view's own class.
 */
private fun Instruction.findsView(): Boolean {
    if (opcode != Opcode.INVOKE_STATIC) return false
    val call = (this as ReferenceInstruction).reference as? MethodReference ?: return false
    return call.returnType.startsWith("L") && call.parameterTypes.map(CharSequence::toString) == listOf(VIEW, "I") &&
        (this as FiveRegisterInstruction).registerCount == 2
}

/**
 * The register this `iput` of an int field of [type] stores and the field's name, when the last
 * write to the register before it is `const/4` four with no branch landing in between; null otherwise.
 */
private fun List<Instruction>.fourKeptAt(at: Int, type: String, targets: Set<Int>): Pair<Int, String>? {
    val put = this[at]
    if (put.opcode != Opcode.IPUT) return null
    val field = (put as ReferenceInstruction).reference as? FieldReference ?: return null
    if (field.definingClass != type || field.type != "I") return null
    val register = (put as TwoRegisterInstruction).registerA
    for (back in at - 1 downTo 0) {
        if (back + 1 in targets) return null
        val earlier = this[back]
        if (!earlier.opcode.setsRegister() || earlier !is OneRegisterInstruction) continue
        val written = earlier.registerA
        if (written == register || (earlier.opcode.setsWideRegister() && written + 1 == register)) {
            return if (earlier.isFourInto(register)) register to field.name else null
        }
    }
    return null
}

/** Whether this method reads the int field [name] of [type]. */
private fun Method.readsIntField(type: String, name: String) = implementation?.instructions?.any { instruction ->
    if (instruction.opcode != Opcode.IGET) return@any false
    val field = (instruction as ReferenceInstruction).reference as? FieldReference ?: return@any false
    field.definingClass == type && field.name == name && field.type == "I"
} == true

/** The row a long press on Share opens, or null with a warning, so the rest still goes in. */
internal fun BytecodePatchContext.findQuickSendOrWarn(): QuickSendSites? = try {
    findQuickSend()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in without the switch filling the row a long press on Share opens.")
    null
}

/**
 * Hands all three fours to [QUICK_SEND_PEOPLE] and goes on with its answer: right after the take's
 * `const/4`, where the loop over the list ends, so that label stays on the stock four; in the
 * clamp right after its own four, where the `const/4 p1, 4` that cut the size becomes a move of
 * the answer; and in the constructor right before the most faces is kept. With the switch off the
 * answer is four, so the row stays as Instagram makes it.
 */
internal fun BytecodePatchContext.askQuickSendPeople(sites: QuickSendSites) {
    mutableMethod(sites.fill).addInstructions(
        sites.four + 1,
        """
            invoke-static/range { v${sites.count} .. v${sites.count} }, $QUICK_SEND_PEOPLE
            move-result v${sites.count}
        """,
    )
    val clamp = mutableMethod(sites.clamp)
    clamp.addInstructions(
        1,
        """
            invoke-static { v${sites.limit} }, $QUICK_SEND_PEOPLE
            move-result v${sites.limit}
        """,
    )
    clamp.replaceInstruction(4, "move v${sites.size}, v${sites.limit}")
    val slots = sites.slots
    val build = mutableMethod(sites.build)
    // Last first, so the earlier sites keep their indices: the slots before the return, which a
    // branch onto it runs too; the inflate, in place; then the most faces.
    build.addInstructionsAtControlFlowLabel(
        slots.end,
        """
            iget v0, p0, ${slots.kept}
            invoke-static { p0, v0 }, $QUICK_SEND_SLOTS
            move-result v0
            iput v0, p0, ${slots.kept}
        """,
    )
    val inflate = build.getInstruction<FiveRegisterInstruction>(slots.inflate)
    build.replaceInstruction(
        slots.inflate,
        "invoke-static { v${inflate.registerC}, v${inflate.registerD}, v${inflate.registerE} }, $QUICK_SEND_INFLATE",
    )
    build.addInstructions(
        sites.cap,
        """
            invoke-static/range { v${sites.faces} .. v${sites.faces} }, $QUICK_SEND_PEOPLE
            move-result v${sites.faces}
        """,
    )
    askQuickSendRanks(slots)
}

/**
 * Gives the face for a rank past the ones Instagram knows the slot [QUICK_SEND_SLOTS] made for it:
 * right before the throw, [QUICK_SEND_AVATAR] answers the avatar's id for the rank, or zero for a
 * rank with no slot, which goes on to the throw. With an id, the avatar is found and kept as a
 * known rank's is, the name's id comes from [QUICK_SEND_LABEL], and it goes on where the known
 * ranks meet to find the name.
 */
private fun BytecodePatchContext.askQuickSendRanks(slots: QuickSendSlots) {
    val slot = mutableMethod(slots.slot)
    val content = "v${slots.content}"
    val id = "v${slots.id}"
    val rank = "v${slots.rank}"
    val popup = "v${slots.popup}"
    slot.addInstructionsWithLabels(
        slots.ranks,
        """
            invoke-static { $rank }, $QUICK_SEND_AVATAR
            move-result $id
            if-eqz $id, :ranks
            invoke-virtual { $popup }, $POPUP_WINDOW->getContentView()$VIEW
            move-result-object $content
            invoke-static { $content, $id }, ${slots.look}
            move-result-object $content
            iput-object $content, p0, ${slots.avatar}
            invoke-virtual { $popup }, $POPUP_WINDOW->getContentView()$VIEW
            move-result-object $content
            invoke-static { $rank }, $QUICK_SEND_LABEL
            move-result $id
            goto :meet
            :ranks
            nop
        """,
        ExternalLabel("meet", slot.getInstruction(slots.tail)),
    )
}

/** Whether this is a static `(Iterable, int)List` call, the take of a list's first few. */
private fun Instruction.isTake(): Boolean {
    if (opcode != Opcode.INVOKE_STATIC) return false
    val call = (this as ReferenceInstruction).reference as? MethodReference ?: return false
    return call.returnType == "Ljava/util/List;" && call.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Iterable;", "I") &&
        (this as FiveRegisterInstruction).registerCount == 2
}

/** Whether this is `const/4 v[register], 4`. */
private fun Instruction.isFourInto(register: Int) = opcode == Opcode.CONST_4 &&
    (this as OneRegisterInstruction).registerA == register && (this as NarrowLiteralInstruction).narrowLiteral == QUICK_SEND_STOCK

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

private fun Instruction.keepsSearchBox() = opcode == Opcode.IPUT_OBJECT &&
    ((this as ReferenceInstruction).reference as FieldReference).let { field ->
        field.definingClass == SHARE_SHEET && field.name == STICKY_SEARCH_BOX && field.type == SEARCH_BOX
    }

private fun Instruction.castsToSearchBox(register: Int) = opcode == Opcode.CHECK_CAST &&
    (this as OneRegisterInstruction).registerA == register && (this as ReferenceInstruction).reference.toString() == SEARCH_BOX

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val written = (this as? OneRegisterInstruction)?.registerA ?: return false
    return written == register || (opcode.setsWideRegister() && written + 1 == register)
}

private fun Instruction.calls(type: String, name: String) =
    ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let { it.definingClass == type && it.name == name } == true

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

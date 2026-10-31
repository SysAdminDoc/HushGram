/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.threads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCreating
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
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
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

private const val PATCH = "Hide the Threads button"

/** A profile's top bar, which keeps its name in Instagram's builds. */
internal const val PROFILE_ACTION_BAR = "Lcom/instagram/profile/actionbar/ProfileActionBar;"

/** The two groups the bar's buttons go in, one each side of the name. */
internal const val BAR_GROUP = "Lcom/instagram/common/ui/base/IgLinearLayout;"

internal const val THREADS_BUTTON = "$EXTENSION_PACKAGE/profile/ThreadsButton;"
internal const val BUTTONS = "$THREADS_BUTTON->buttons(Ljava/util/List;)Ljava/util/List;"
internal const val ICON_STUB = "icon"
internal const val HIDE_BANNER = "$THREADS_BUTTON->hideBanner()Z"
internal const val HIDE_CREATE = "$THREADS_BUTTON->hideCreateInBar()Z"

/** The profile screen, which keeps its name in Instagram's builds. Its own-profile flag guards the Create button. */
internal const val USER_DETAIL = "Lcom/instagram/profile/fragment/UserDetailFragment;"

/**
 * Instagram 453's id/profile_header_create_button (0x7f0b30b9 on 452), the view id of the plus on your own profile,
 * the same in all four 453 builds (aapt2).
 */
internal const val CREATE_BUTTON_ID = 0x7f0b3077

/** The events only the Threads chip under a profile's bio logs, loaded by its constructor. */
internal const val BANNER_TAP = "tap_threads_banner"
internal const val BANNER_IMPRESSION = "impression_threads_banner"
internal const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val ADD = "Ljava/util/List;->add(Ljava/lang/Object;)Z"

internal const val SHARE_BUTTON = "$EXTENSION_PACKAGE/profile/ShareProfileButton;"
internal const val SHARE_HOOK = "$SHARE_BUTTON->button(Landroid/view/View;Ljava/lang/Object;)V"
internal const val SHARE_STUB = "isShare"
internal const val SUBSCRIBE_STUB = "isSubscribe"
internal const val OVERFLOW_STUB = "isOverflow"

/** The strings only Share profile's tap loads: what it logs sharing, and where it was tapped. */
internal const val SHARE_URL = "share_profile_url"
internal const val SHARE_TRAY = "button_tray"

/** What a tap on Subscribe and on the arrow at the row's end logs, each with [PROFILE_HEADER] as where it was tapped (#248). */
internal const val SUBSCRIBE_TAP = "subscribe_tap"
internal const val OVERFLOW_TAP = "tap_dropdown_menu"
internal const val PROFILE_HEADER = "user_profile_header"

/** The pool tags of the action row's text buttons and of Share profile as an icon, loaded by their builders. */
internal const val TEXT_TAG = "generic"
internal const val SHARE_ICON_TAG = "share_profile_icon"
private const val VIEW = "Landroid/view/View;"

private const val LIST = "Ljava/util/List;"
private const val OBJECT = "Ljava/lang/Object;"
private const val NEXT = "Ljava/util/Iterator;->next()Ljava/lang/Object;"
private const val ITERATOR = "Ljava/util/List;->iterator()Ljava/util/Iterator;"

/**
 * Takes the Threads button off the top bar of profiles. Included in the default selection with its
 * switch initially off, so leaving the button out remains the user's pick.
 *
 * Only the list the bar builds its buttons from changes. The menu, the bell and the other buttons
 * are built as before, and the Threads profile is still a tap away in the menu. Instagram 451 shows
 * Threads as a chip under the bio instead on most profiles, so the method adding that chip asks the
 * extension first too, and the other chips stay.
 *
 * Two more switches ride along, each off to start. The same list holds the Create button on your
 * own profile, which the extension leaves out by its icon (#254). Instagram 451 builds that plus in
 * the profile screen's action bar instead, so the action bar asks the extension before adding it.
 * And the methods building the row of action buttons under the bio, as text, as icons and as the
 * arrow at its end, hand each button and its model to the extension, which hides Share profile's
 * (#253), Subscribe's and the arrow's (#248), and lets the others take their space.
 */
@Suppress("unused")
val hideThreadsButtonPatch = bytecodePatch(
    name = "Hide the Threads button",
    description = "Takes the Threads button off the top of profiles, with switches for the Create button and Share " +
        "profile on your own, and Subscribe and the arrow after the buttons on creators' profiles. The menu and " +
        "the other buttons stay. Each starts off. Turn them on in HushGram settings > Profiles.",
    default = true,
) {
    category("Profiles")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("threadsButton")
        val button = findThreadsButton()
        val banner = findThreadsBanner()
        val create = findCreateInBar()
        val share = findShareButton()
        hideThreadsButton(button)
        hideThreadsBanner(banner)
        hideCreateInBar(create)
        hideShareButton(share)
        enableStatus("threadsButton")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Where the hook goes: in the bar's method [name], right at [hook], after the list of buttons is
 * read into [register]. [button] is the type the bar's builder takes each item as, and [icon] its
 * int field holding the icon's resource id.
 */
internal class ThreadsButtonSite(
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val hook: Int,
    val register: Int,
    val button: String,
    val icon: String,
)

/**
 * Finds the list the bar builds its buttons from, failing before anything changes when any of it
 * isn't there exactly once, since that's an update this patch hasn't seen. In [PROFILE_ACTION_BAR],
 * the one static call taking a list and the bar's two [BAR_GROUP]s builds the buttons. Its list is
 * read once, from a getter with no arguments. Between that read and the call, branches are fine as
 * long as nothing jumps in from outside and nothing writes over the list's register, so every build
 * gets the list the hook answered. The builder goes through
 * the list it's handed once, casting each item to the button type, which is public with one int
 * field: the icon. Other lists it walks don't count.
 */
internal fun BytecodePatchContext.findThreadsButton(): ThreadsButtonSite {
    val bar = classDefByOrNull(PROFILE_ACTION_BAR) ?: refuse("this Instagram build has no $PROFILE_ACTION_BAR")
    val calls = bar.methods.flatMap { method ->
        val code = method.code()
        code.indices.filter { code[it].buildsBar() }.map { method to it }
    }
    val (binder, building) = calls.singleOrNull()
        ?: refuse("expected one call building the buttons in $PROFILE_ACTION_BAR, found ${calls.size}")
    val where = "${bar.type}->${binder.name}"
    val code = binder.code()
    val builder = code[building].call()!!
    val takes = builder.parameters()
    // A long or a double takes two registers, so the list's register is counted past them.
    val register = code[building].arguments()[takes.take(takes.indexOf(LIST)).sumOf { if (it == "J" || it == "D") 2 else 1 }]

    val reads = code.indices.filter { at ->
        at > 0 && code[at].opcode == Opcode.MOVE_RESULT_OBJECT && (code[at] as OneRegisterInstruction).registerA == register &&
            code[at - 1].call()?.let { it.parameterTypes.isEmpty() && it.returnType == LIST } == true
    }
    val read = reads.singleOrNull() ?: refuse("expected one list of buttons read into v$register in $where, found ${reads.size}")
    if (read > building) refuse("$where reads its list of buttons after building them")
    val flow = try {
        ControlFlow.of(binder)
    } catch (failure: IllegalArgumentException) {
        refuse("$where can't be followed: ${failure.message}")
    }
    val from = List(code.size) { mutableListOf<Int>() }
    for (at in code.indices) (flow.normal[at] + flow.exceptional[at]).forEach { from[it] += at }
    if ((read + 1..building).any { at -> from[at].any { it !in read until building } }) {
        refuse("something jumps in between $where's list of buttons and the call building them")
    }
    if ((read + 1 until building).any { code[it].writes(register) }) {
        refuse("$where writes over v$register between its list of buttons and the call building them")
    }

    val built = classDefByOrNull(builder.definingClass) ?: refuse("the buttons' builder ${builder.definingClass} isn't in the app")
    val build = built.methods.singleOrNull { it.name == builder.name && it.parameters() == takes && it.returnType == builder.returnType }
        ?: refuse("${builder.definingClass} has no ${builder.name}")
    val steps = build.code()
    // Only the walk of the list it was handed: 450's builder also walks a list of Booleans later on.
    val width = { type: String -> if (type == "J" || type == "D") 2 else 1 }
    val registers = build.implementation?.registerCount ?: refuse("${builder.definingClass}->${builder.name} has no code")
    val handed = registers - takes.sumOf(width) + takes.take(takes.indexOf(LIST)).sumOf(width)
    val walks = steps.indices.filter { at ->
        steps[at].call()?.toString() == ITERATOR && steps[at].arguments() == listOf(handed) &&
            steps.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
    }.map { (steps[it + 1] as OneRegisterInstruction).registerA }.toSet()
    val casts = steps.indices.filter { at ->
        steps[at].call()?.toString() == NEXT && steps[at].arguments().singleOrNull() in walks &&
            steps.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT &&
            steps.getOrNull(at + 2)?.opcode == Opcode.CHECK_CAST &&
            (steps[at + 2] as OneRegisterInstruction).registerA == (steps[at + 1] as OneRegisterInstruction).registerA
    }
    val cast = casts.singleOrNull()
        ?: refuse("expected ${builder.definingClass}->${builder.name} to go through its buttons once, found ${casts.size}")
    val button = (steps[cast + 2].reference() as TypeReference).type
    val buttonClass = classDefByOrNull(button) ?: refuse("the button type $button isn't in the app")
    if (!AccessFlags.PUBLIC.isSet(buttonClass.accessFlags)) refuse("$button isn't public, so the extension can't read its icon")
    val icons = buttonClass.fields.filter { it.type == "I" && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val icon = icons.singleOrNull() ?: refuse("expected one int field on $button, its icon, found ${icons.size}")
    if (!AccessFlags.PUBLIC.isSet(icon.accessFlags)) refuse("$button's icon ${icon.name} isn't public, so the extension can't read it")

    val extension = classDefByOrNull(THREADS_BUTTON) ?: refuse("the extension has no $THREADS_BUTTON")
    if (extension.methods.none { it.signature() == BUTTONS && it.publicStatic() }) refuse("the extension has no public static $BUTTONS")
    if (extension.methods.none { it.isIconStub() }) refuse("$THREADS_BUTTON has no public static I $ICON_STUB($OBJECT)")
    return ThreadsButtonSite(binder.name, binder.parameters(), binder.returnType, read + 1, register, button, "$button->${icon.name}:I")
}

/**
 * Writes the stub that reads a button's icon, then hands the list of buttons to [BUTTONS] right
 * after it's read, keeping the answer in the same register. Only called once [findThreadsButton]
 * found everything.
 */
internal fun BytecodePatchContext.hideThreadsButton(site: ThreadsButtonSite) {
    replace(mutableClassDefBy(THREADS_BUTTON).methods.single { it.isIconStub() }, 1, """
        check-cast p0, ${site.button}
        iget p0, p0, ${site.icon}
        return p0
    """)
    val binder = mutableClassDefBy(PROFILE_ACTION_BAR).methods.single {
        it.name == site.name && it.parameters() == site.parameters && it.returnType == site.returnType
    }
    binder.addInstructions(
        site.hook,
        """
            invoke-static/range { v${site.register} .. v${site.register} }, $BUTTONS
            move-result-object v${site.register}
        """,
    )
}

/** Where the Threads chip is added: the static [name] of [type], taking [parameters]. */
internal class ThreadsBannerSite(val type: String, val name: String, val parameters: List<String>, val banner: String)

/**
 * Finds the one place a profile's header adds its Threads chip, failing before anything changes
 * when any of it isn't there exactly once. Instagram 451 moved the Threads link from the top bar to
 * a chip in the header's row of banners, under the bio. The chip is the class whose constructor
 * loads both [BANNER_TAP] and [BANNER_IMPRESSION], and it's added to the row's list by one static
 * method taking the session first and the list last, which creates the chip and adds it. Both the
 * View and the Compose headers build from that list.
 */
internal fun BytecodePatchContext.findThreadsBanner(): ThreadsBannerSite {
    val banners = classesLoadingString(BANNER_TAP).filter { candidate ->
        candidate.methods.any { it.name == "<init>" && loadsString(it, BANNER_TAP) && loadsString(it, BANNER_IMPRESSION) }
    }
    val banner = banners.singleOrNull()?.type
        ?: refuse("expected one Threads chip loading \"$BANNER_TAP\" and \"$BANNER_IMPRESSION\", found ${banners.size}")
    val adders = classesCreating(banner).flatMap { owner -> owner.methods.map { owner to it } }.filter { (_, method) ->
        val takes = method.parameters()
        val code = method.code()
        val created = code.indexOfFirst { it.opcode == Opcode.NEW_INSTANCE && (it.reference() as? TypeReference)?.type == banner }
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && takes.size == 4 &&
            takes.first() == USER_SESSION && takes.last() == LIST &&
            created >= 0 && code.drop(created).any { it.call()?.toString() == ADD }
    }
    val (owner, adder) = adders.singleOrNull()
        ?: refuse("expected one method adding the Threads chip $banner to a profile's banners, found ${adders.size}")
    val registers = adder.implementation?.registerCount ?: refuse("${owner.type}->${adder.name} has no code")
    if (registers - adder.parameters().size < 1) refuse("${owner.type}->${adder.name} has no local for the hook")
    return ThreadsBannerSite(owner.type, adder.name, adder.parameters(), banner)
}

/**
 * Asks [HIDE_BANNER] first thing in the method adding the Threads chip, and returns before the chip
 * is made when it says so. The other chips close up, and a profile whose only chip was Threads gets
 * Instagram's own header with no banners. Only called once [findThreadsBanner] found everything.
 */
internal fun BytecodePatchContext.hideThreadsBanner(site: ThreadsBannerSite) {
    val adder = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameters() == site.parameters && it.returnType == "V"
    }
    adder.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HIDE_BANNER
            move-result v0
            if-eqz v0, :add
            return-void
        """,
        ExternalLabel("add", adder.getInstruction(0)),
    )
}

/**
 * Where 451's own profile adds its Create button: in [type]'s [name], the stretch right after the
 * guard at [guard] up to [skip], where the guard jumps when the profile isn't yours. [register]
 * holds the guard's flag.
 */
internal class CreateInBarSite(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val guard: Int,
    val skip: Int,
    val register: Int,
)

/**
 * Finds where Instagram 451's profile screen adds the Create button to its action bar, failing
 * before anything changes when any of it isn't there exactly once. The button's builder gets the
 * view id [CREATE_BUTTON_ID] loaded and stored straight into one of its int fields. The nearest
 * branch before that is the guard: an if-eqz on a flag just read off [USER_DETAIL], true on your
 * own profile, jumping past the store when it's false. Nothing else may jump in between the guard
 * and the id, so every way to the button goes past the hook.
 */
internal fun BytecodePatchContext.findCreateInBar(): CreateInBarSite {
    val stores = classesLoading(CREATE_BUTTON_ID.toLong()).flatMap { owner ->
        owner.methods.flatMap { method ->
            val code = method.code()
            code.indices.filter { code[it].storesId(code.getOrNull(it + 1)) }.map { Triple(owner, method, it) }
        }
    }
    val (owner, adder, load) = stores.singleOrNull()
        ?: refuse("expected one Create button stored with its id 0x${CREATE_BUTTON_ID.toString(16)}, found ${stores.size}")
    val where = "${owner.type}->${adder.name}"
    val code = adder.code()
    val guard = (load - 1 downTo 1).firstOrNull { code[it].opcode in BRANCHES || !code[it].opcode.canContinue() }
        ?: refuse("$where adds the Create button with no guard before it")
    val flag = code[guard - 1]
    if (code[guard].opcode != Opcode.IF_EQZ || flag.opcode != Opcode.IGET_BOOLEAN ||
        (flag as OneRegisterInstruction).registerA != (code[guard] as OneRegisterInstruction).registerA ||
        (flag.reference() as FieldReference).definingClass != USER_DETAIL
    ) {
        refuse("$where guards the Create button with something other than a flag of $USER_DETAIL")
    }
    // An iget-boolean only writes v0 to v15, so the flag's register is in reach of const/4.
    val register = (flag as OneRegisterInstruction).registerA
    val flow = try {
        ControlFlow.of(adder)
    } catch (failure: IllegalArgumentException) {
        refuse("$where can't be followed: ${failure.message}")
    }
    val skip = flow.normal[guard].singleOrNull { it != guard + 1 } ?: refuse("$where's guard doesn't jump anywhere")
    if (skip <= load + 1) refuse("$where's guard doesn't jump past the Create button")
    val from = List(code.size) { mutableListOf<Int>() }
    for (at in code.indices) (flow.normal[at] + flow.exceptional[at]).forEach { from[it] += at }
    if ((guard + 1..load).any { at -> from[at].any { it !in guard until load } }) {
        refuse("something jumps in between $where's guard and the Create button")
    }

    val extension = classDefByOrNull(THREADS_BUTTON) ?: refuse("the extension has no $THREADS_BUTTON")
    if (extension.methods.none { it.signature() == HIDE_CREATE && it.publicStatic() }) refuse("the extension has no public static $HIDE_CREATE")
    return CreateInBarSite(owner.type, adder.name, adder.parameters(), adder.returnType, guard, skip, register)
}

/**
 * Asks [HIDE_CREATE] right after the guard lets your own profile through, and jumps where the guard
 * jumps for someone else's when it says so. Otherwise the flag goes back to true and the button is
 * built as before. Only called once [findCreateInBar] found everything.
 */
internal fun BytecodePatchContext.hideCreateInBar(site: CreateInBarSite) {
    val adder = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameters() == site.parameters && it.returnType == site.returnType
    }
    adder.addInstructionsWithLabels(
        site.guard + 1,
        """
            invoke-static { }, $HIDE_CREATE
            move-result v${site.register}
            if-nez v${site.register}, :skip
            const/4 v${site.register}, 0x1
        """,
        ExternalLabel("skip", adder.getInstruction(site.skip)),
    )
}

/**
 * Where the row's buttons are hidden: [model], [subscribe] and [overflow] are the classes of Share
 * profile's, Subscribe's and the arrow's models, and [builders] the methods building the action
 * row's buttons that hand one back.
 */
internal class ShareButtonSite(val model: String, val subscribe: String, val overflow: String, val builders: List<ShareBuilder>)

/** A static [name] of [type] taking [parameters] that hands back the button in [view], built from the model in [from], at each of [returns]. */
internal class ShareBuilder(val type: String, val name: String, val parameters: List<String>, val returns: List<Int>, val view: Int, val from: Int)

/**
 * Finds Share profile's buttons, failing before anything changes when any of it isn't there exactly
 * once. Its model is the class whose onClick loads both [SHARE_URL] and [SHARE_TRAY], and it's one
 * kind of action button, the one interface it has. Each button of the row under the bio is built by
 * a static method taking a model of that kind first and handing back a View. The class building
 * Share profile as an icon, the one method loading [SHARE_ICON_TAG], builds the text buttons too, in
 * the one loading [TEXT_TAG]. Subscribe's and the arrow's models are the one public class of that
 * kind each whose onClick loads [SUBSCRIBE_TAP] or [OVERFLOW_TAP] with [PROFILE_HEADER]. The text
 * builder builds Subscribe, and the arrow has a builder of its own in the same class, the one taking
 * the arrow's model first (#248). Each keeps its model in one register up to its returns (some
 * builds copy it out first and reuse the parameter's), and hands back its button from one register.
 */
internal fun BytecodePatchContext.findShareButton(): ShareButtonSite {
    val models = classesLoadingString(SHARE_URL).filter { candidate ->
        candidate.methods.any {
            it.name == "onClick" && it.parameters() == listOf(VIEW) && it.returnType == "V" &&
                loadsString(it, SHARE_URL) && loadsString(it, SHARE_TRAY)
        }
    }
    val model = models.singleOrNull()
        ?: refuse("expected one Share profile button loading \"$SHARE_URL\" and \"$SHARE_TRAY\" when tapped, found ${models.size}")
    val kind = model.interfaces.singleOrNull()
        ?: refuse("expected Share profile's ${model.type} to be one kind of action button, found ${model.interfaces.size} interfaces")
    if (!AccessFlags.PUBLIC.isSet(model.accessFlags)) refuse("${model.type} isn't public, so the extension can't check for it")

    val builds = { method: Method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == VIEW && method.parameters().firstOrNull() == kind
    }
    val icons = classesLoadingString(SHARE_ICON_TAG).flatMap { owner ->
        owner.methods.filter { builds(it) && loadsString(it, SHARE_ICON_TAG) }.map { owner to it }
    }
    val (owner, icon) = icons.singleOrNull()
        ?: refuse("expected one method building Share profile as an icon from a $kind, found ${icons.size}")
    val texts = owner.methods.filter { builds(it) && loadsString(it, TEXT_TAG) }
    val text = texts.singleOrNull()
        ?: refuse("expected one method in ${owner.type} building the action row's text buttons from a $kind, found ${texts.size}")
    val subscribe = rowModel(kind, SUBSCRIBE_TAP, "Subscribe")
    val overflow = rowModel(kind, OVERFLOW_TAP, "arrow")
    val menus = owner.methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == VIEW && it.parameters().firstOrNull() == overflow
    }
    val menu = menus.singleOrNull()
        ?: refuse("expected one method in ${owner.type} building the arrow from its $overflow, found ${menus.size}")

    val extension = classDefByOrNull(SHARE_BUTTON) ?: refuse("the extension has no $SHARE_BUTTON")
    if (extension.methods.none { it.signature() == SHARE_HOOK && it.publicStatic() }) refuse("the extension has no public static $SHARE_HOOK")
    for (stub in listOf(SHARE_STUB, SUBSCRIBE_STUB, OVERFLOW_STUB)) {
        if (extension.methods.none { it.isRowStub(stub) }) refuse("$SHARE_BUTTON has no public static Z $stub($OBJECT)")
    }
    return ShareButtonSite(model.type, subscribe, overflow, listOf(text, icon, menu).map { shareBuilder(owner.type, it) })
}

/** The one public action button of [kind] whose onClick loads [tap] and [PROFILE_HEADER]: the model of [what]'s button. */
private fun BytecodePatchContext.rowModel(kind: String, tap: String, what: String): String {
    val models = classesLoadingString(tap).filter { candidate ->
        kind in candidate.interfaces && candidate.methods.any {
            it.name == "onClick" && it.parameters() == listOf(VIEW) && it.returnType == "V" &&
                loadsString(it, tap) && loadsString(it, PROFILE_HEADER)
        }
    }
    val model = models.singleOrNull()
        ?: refuse("expected one $what button of $kind loading \"$tap\" and \"$PROFILE_HEADER\" when tapped, found ${models.size}")
    if (!AccessFlags.PUBLIC.isSet(model.accessFlags)) refuse("${model.type} isn't public, so the extension can't check for it")
    return model.type
}

private fun shareBuilder(owner: String, builder: Method): ShareBuilder {
    val where = "$owner->${builder.name}"
    val code = builder.code()
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
    if (returns.isEmpty()) refuse("expected a return in $where, found none")
    val views = returns.map { (code[it] as OneRegisterInstruction).registerA }.toSet()
    val view = views.singleOrNull() ?: refuse("$where hands back its button from ${views.size} registers, expected one")
    val registers = builder.implementation!!.registerCount
    val parameter = registers - builder.parameters().sumOf { if (it == "J" || it == "D") 2 else 1 }
    val from = if (code.none { it.writes(parameter) }) parameter else modelCopy(where, builder, code, parameter, returns)
    if (view == from) refuse("$where hands back its model")
    if (view > 15 || from > 15) refuse("$where keeps its button or model past v15, out of a plain call's reach")
    return ShareBuilder(owner, builder.name, builder.parameters(), returns, view, from)
}

/**
 * Where [builder] keeps its model once it reuses the model's [parameter] register: the one register
 * it copies the model into on the way to every return, before anything writes [parameter], and never
 * writes again (453's 385914503 arrow builder moves it out at its start and builds the button in its
 * place).
 */
private fun modelCopy(where: String, builder: Method, code: List<Instruction>, parameter: Int, returns: List<Int>): Int {
    val copies = code.indices.filter {
        code[it].opcode in MOVE_OBJECTS && (code[it] as TwoRegisterInstruction).registerB == parameter
    }
    val copy = copies.singleOrNull() ?: refuse("$where writes over its model in v$parameter")
    val register = (code[copy] as OneRegisterInstruction).registerA
    val flow = try {
        ControlFlow.of(builder)
    } catch (failure: IllegalArgumentException) {
        refuse("$where can't be followed: ${failure.message}")
    }
    // Every instruction reachable from [starts] without going through the copy.
    fun reach(starts: List<Int>): Set<Int> {
        val seen = mutableSetOf<Int>()
        val pending = ArrayDeque(starts)
        while (pending.isNotEmpty()) {
            val at = pending.removeFirst()
            if (at != copy && seen.add(at)) pending += flow.normal[at] + flow.exceptional[at]
        }
        return seen
    }
    val before = reach(listOf(0))
    if (returns.any { it in before }) refuse("$where can return before copying its model out of v$parameter")
    if (before.any { code[it].writes(parameter) }) refuse("$where writes over its model in v$parameter before copying it")
    val after = reach(flow.normal[copy] + flow.exceptional[copy])
    if (after.any { copy in flow.normal[it] || copy in flow.exceptional[it] }) refuse("$where copies its model out of v$parameter more than once")
    if (code.indices.any { it != copy && code[it].writes(register) }) refuse("$where writes over its model's copy in v$register")
    return register
}

private val MOVE_OBJECTS = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

/**
 * Writes the stubs that tell Share profile's, Subscribe's and the arrow's models, then hands each
 * button and its model to [SHARE_HOOK] right before its builder gives it back, on each return's own
 * label so every branch there runs it. Only called once [findShareButton] found everything.
 */
internal fun BytecodePatchContext.hideShareButton(site: ShareButtonSite) {
    for ((stub, model) in listOf(SHARE_STUB to site.model, SUBSCRIBE_STUB to site.subscribe, OVERFLOW_STUB to site.overflow)) {
        replace(mutableClassDefBy(SHARE_BUTTON).methods.single { it.isRowStub(stub) }, 1, """
            instance-of p0, p0, $model
            return p0
        """)
    }
    for (builder in site.builders) {
        val method = mutableClassDefBy(builder.type).methods.single {
            it.name == builder.name && it.parameters() == builder.parameters && it.returnType == VIEW
        }
        // From the last return back, so each hook leaves the returns before it where they were.
        for (returnAt in builder.returns.sortedDescending()) {
            val at = method.implementation!!.instructions[returnAt]
            if (at.opcode != Opcode.RETURN_OBJECT || (at as OneRegisterInstruction).registerA != builder.view) {
                refuse("${builder.type}->${builder.name} changed before its button could be hooked")
            }
            method.addInstructionsAtControlFlowLabel(
                returnAt,
                "invoke-static { v${builder.view}, v${builder.from} }, $SHARE_HOOK",
            )
        }
    }
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

/** Whether this is a static call taking one list and two of the bar's groups: the call building its buttons. */
private fun Instruction.buildsBar(): Boolean {
    if (opcode != Opcode.INVOKE_STATIC && opcode != Opcode.INVOKE_STATIC_RANGE) return false
    val takes = call()?.parameters() ?: return false
    return takes.count { it == LIST } == 1 && takes.count { it == BAR_GROUP } == 2
}

private val BRANCHES = setOf(
    Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE,
    Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ,
    Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32, Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH,
)

/** Whether this loads [CREATE_BUTTON_ID] and [next] stores it straight into an int field. */
private fun Instruction.storesId(next: Instruction?): Boolean {
    if ((this as? NarrowLiteralInstruction)?.narrowLiteral != CREATE_BUTTON_ID) return false
    return next?.opcode == Opcode.IPUT && (next as TwoRegisterInstruction).registerA == (this as OneRegisterInstruction).registerA
}

/** Whether this writes [register], or a wide pair covering it. */
private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val written = (this as? OneRegisterInstruction)?.registerA ?: return false
    return written == register || (opcode.setsWideRegister() && written + 1 == register)
}

private fun Method.isIconStub() = name == ICON_STUB && parameters() == listOf(OBJECT) && returnType == "I" && publicStatic()

private fun Method.isRowStub(stub: String) = name == stub && parameters() == listOf(OBJECT) && returnType == "Z" && publicStatic()

private fun Method.publicStatic() = AccessFlags.PUBLIC.isSet(accessFlags) && AccessFlags.STATIC.isSet(accessFlags)

private fun Method.signature() = "$definingClass->$name(${parameters().joinToString("")})$returnType"

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.parameters(): List<String> = parameterTypes.map { it.toString() }

private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

private fun Instruction.call() = reference() as? MethodReference

private fun MethodReference.parameters(): List<String> = parameterTypes.map { it.toString() }

/** The registers an invoke hands over, in order. */
private fun Instruction.arguments(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}

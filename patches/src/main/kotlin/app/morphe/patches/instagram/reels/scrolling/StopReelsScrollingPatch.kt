/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.scrolling

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.direct.reels.viewerChatPatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesAccessing
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.liveAcrossInjection
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.reels.partnership.fillStub
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Stop Reels scrolling"
internal const val REEL_SCROLLING = "$EXTENSION_PACKAGE/reels/ReelScrolling;"
internal const val PAGER_HOOK = "$REEL_SCROLLING->pager(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)I"

/** The extension's bridges reading a viewer's config off the object its pager goes into and off its auto scroller (#804). */
internal const val PAGER_CONFIG = "pagerConfig"
internal const val SCROLLER_CONFIG = "scrollerConfig"
internal const val USER_INPUT_HOOK = "$REEL_SCROLLING->userInput(Ljava/lang/Object;I)I"
internal const val PULL_HOOK = "$REEL_SCROLLING->pull(Ljava/lang/Object;)I"

/** AndroidX's pager, which Instagram keeps by name along with its public setter. */
internal const val VIEW_PAGER = "Landroidx/viewpager2/widget/ViewPager2;"
internal const val SET_USER_INPUT = "setUserInputEnabled"

/** What a viewer was opened with, and where from. Both keep their names, and the source its constants'. */
internal const val VIEWER_CONFIG = "Lcom/instagram/clips/intf/ClipsViewerConfig;"
internal const val VIEWER_SOURCE = "Lcom/instagram/clips/intf/ClipsViewerSource;"

/** The pull-down layout around the Reels viewer, named in Instagram's layouts so its name stays. */
internal const val PULL_LAYOUT = "Linstagram/features/clips/viewer/ui/ClipsSwipeRefreshLayout;"

/** The layout's two touch handlers, Android's names. */
internal val PULL_TOUCHES = listOf("onInterceptTouchEvent", "onTouchEvent")

/**
 * The tag the Reels pager's lookup of a page's view loads, a plain string from 456 on, where the
 * pager's other trace names went (`LX/091c;->EDF` on 386214574).
 */
internal const val PAGER_VIEW_LOOKUP = "ClipsViewPagerImpl_getViewAtIndex"

/** Android's view, which the pager's setup is handed and the viewer's teardown isn't. */
private const val ANDROID_VIEW = "Landroid/view/View;"

private const val MOTION_EVENT = "Landroid/view/MotionEvent;"

/**
 * Keeps a finger from moving the Reels viewer on to the next reel, and a pull down from loading
 * fresh ones. Included in the default selection with its switch initially off, so stopping them
 * is the user's pick. The reel you opened plays as before, and everything on it still works.
 */
@Suppress("unused")
val stopReelsScrollingPatch = bytecodePatch(
    name = "Stop Reels scrolling",
    description = "Stops a swipe in Reels from moving to the next reel, so you stay on the one you opened, with " +
        "switches that leave reels from a profile, Search and Explore or a song page free to scroll. Play only " +
        "the reel you opened does the same, auto scroll included, for reels you open from a post on Home, a " +
        "suggested reels row or a message, and leaves the Reels tab alone. Another " +
        "switch stops swiping after as many reels as you pick, for as long as you pick, and Take a " +
        "scroll break covers Home, Reels and Explore for 5 minutes after the time you pick. Starts off. Turn " +
        "it on in HushGram settings > Reels.",
    default = true,
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch, viewerChatPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("reelScrolling")
        val site = findReelsScrolling()
        val pages = findPageStores()
        val autoScroll = findAutoScrollCheck()
        stopReelsScrolling(site)
        reportPages(pages)
        holdAutoScroll(autoScroll)
        enableStatus("reelScrolling")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** The Reels pager's class and its [VIEW_PAGER] field, as `Lclass;->name:type`. */
internal class ReelsPager(val owner: String, val field: String)

/**
 * The Reels pager: the class of the one method loading [PAGER_VIEW_LOOKUP]. It holds one
 * [VIEW_PAGER] field, and its switches that stop and free a finger's hold on the pager go through
 * one static method of the class taking just the class, which reads that field and calls
 * [SET_USER_INPUT] (`LX/091c;->A01(LX/091c;)V` on 386214574).
 */
internal fun BytecodePatchContext.reelsPager(refuse: (String) -> Nothing): ReelsPager {
    val lookups = classesLoadingString(PAGER_VIEW_LOOKUP).sortedBy { it.type }.flatMap { it.methods }
        .filter { loadsString(it, PAGER_VIEW_LOOKUP) }
    val lookup = lookups.singleOrNull() ?: refuse("expected one method loading \"$PAGER_VIEW_LOOKUP\", found ${lookups.size}")
    val owner = lookup.definingClass
    val pagerClass = classDefBy(owner)
    val fields = pagerClass.fields.filter { it.type == VIEW_PAGER && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val field = fields.singleOrNull()?.let { "$owner->${it.name}:$VIEW_PAGER" }
        ?: refuse("expected $owner to hold one $VIEW_PAGER field, found ${fields.size}")
    val switches = pagerClass.methods.filter { method ->
        val code = method.instructions()
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.map(CharSequence::toString) == listOf(owner) &&
            code.any { it.opcode == Opcode.IGET_OBJECT && it.fieldReference()?.toString() == field } &&
            code.any { it.methodReference()?.let { called -> called.definingClass == VIEW_PAGER && called.name == SET_USER_INPUT } == true }
    }
    if (switches.size != 1) {
        refuse("expected one static method of $owner taking it that reads $field and calls $VIEW_PAGER->$SET_USER_INPUT, found ${switches.size}")
    }
    return ReelsPager(owner, field)
}

/**
 * The method that sets the Reels pager up: of the methods storing [pager]'s field, the one handed
 * an [ANDROID_VIEW], since the viewer's teardown clearing the field takes none. A static of the
 * Reels viewer on 456 (`LX/06ZV;->A0R` on 386214574), the viewer's onViewCreated on 451 and the
 * pager's own setupView on 452.
 */
internal fun BytecodePatchContext.reelsPagerSetup(pager: ReelsPager, refuse: (String) -> Nothing): Method {
    val name = pager.field.substringAfter("->").substringBefore(":")
    val setups = classesAccessing(pager.owner, name, Opcode.IPUT_OBJECT).flatMap { it.methods }.filter { method ->
        ANDROID_VIEW in method.parameterTypes.map(CharSequence::toString) &&
            method.instructions().any { it.opcode == Opcode.IPUT_OBJECT && it.fieldReference()?.toString() == pager.field }
    }
    return setups.singleOrNull() ?: refuse("expected one method handed a $ANDROID_VIEW to store ${pager.field}, found ${setups.size}")
}

/**
 * Where the three hooks go: after the viewer's store of its pager, with the pager's register, the
 * register of the object it's stored in, that object's viewer config field, the config's source
 * field and a free local there; in front of the pager's setter, with the register of its `this`; and
 * at the start of each of the pull-down layout's touch handlers, with a free local in each. The
 * extension reads the viewer's config off the object the pager went into when that object's class
 * and its config field are public ([bridged]), so a chat's reel opened as its Blend's is told (#804).
 */
internal class ReelsScrollingSite(
    val viewer: String,
    val name: String,
    val parameters: List<String>,
    val store: Int,
    val pager: Int,
    val holder: Int,
    val config: String,
    val source: String,
    val answer: Int,
    val setterSelf: Int,
    val touches: Map<String, Int>,
    val field: String,
    val bridged: Boolean,
)

/**
 * Finds all three places before anything changes, failing when one isn't there exactly once, since
 * that's an update this patch hasn't seen.
 *
 * The Reels pager is the [VIEW_PAGER] field of [reelsPager], and its setup ([reelsPagerSetup])
 * stores it once. AndroidX's [SET_USER_INPUT] stores its parameter in one
 * field. The pull-down layout declares both [PULL_TOUCHES] itself. The object the pager is stored in
 * holds one [VIEWER_CONFIG], and that holds one [VIEWER_SOURCE].
 */
internal fun BytecodePatchContext.findReelsScrolling(): ReelsScrollingSite {
    val found = reelsPager(::refuse)
    val field = found.field
    val viewer = reelsPagerSetup(found, ::refuse)
    val code = viewer.instructions()
    val where = "${viewer.definingClass}->${viewer.name}"
    val stores = code.indices.filter { code[it].opcode == Opcode.IPUT_OBJECT && code[it].fieldReference()?.toString() == field }
    val store = stores.singleOrNull() ?: refuse("expected $where to store $field once, found ${stores.size}")
    if (store + 1 !in code.indices) refuse("$where ends at its store of $field")
    // An iput names v15 at most, so the pager's register fits the hook's invokes as it is.
    val pager = (code[store] as TwoRegisterInstruction).registerA
    val holder = (code[store] as TwoRegisterInstruction).registerB
    val owner = code[store].fieldReference()!!.definingClass
    val configs = classDefByOrNull(owner)?.fields?.filter { it.type == VIEWER_CONFIG && !AccessFlags.STATIC.isSet(it.accessFlags) }.orEmpty()
    val config = configs.singleOrNull() ?: refuse("$owner holds ${configs.size} $VIEWER_CONFIG fields, not one")
    val configClass = classDefByOrNull(VIEWER_CONFIG) ?: refuse("$VIEWER_CONFIG isn't in this build")
    val sources = configClass.fields.filter { it.type == VIEWER_SOURCE && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val source = sources.singleOrNull() ?: refuse("$VIEWER_CONFIG holds ${sources.size} $VIEWER_SOURCE fields, not one")
    // Not the pager's own register or the object it went into, which the hook reads after writing the answer.
    val live = viewer.liveAcrossInjection(store + 1)
    val free = (0 until minOf(viewer.localRegisterCount(), 16)).filter { it !in live && it != pager && it != holder }
    val answer = free.firstOrNull()
        ?: refuse("$where has no local register up to v15 that nothing reads after storing its pager, and the hook needs 1")
    val bridged = classDefByOrNull(owner)?.let { AccessFlags.PUBLIC.isSet(it.accessFlags) } == true && AccessFlags.PUBLIC.isSet(config.accessFlags)
    if (!bridged) {
        patchLog.warning("$PATCH: $owner or its ${config.name}, the viewer's config, isn't public. A chat's reel opened as its Blend's goes by its source.")
    }

    val pagerClass = classDefByOrNull(VIEW_PAGER) ?: refuse("$VIEW_PAGER isn't in this build")
    val setterSelf = findSetter(pagerClass)

    val layout = classDefByOrNull(PULL_LAYOUT) ?: refuse("$PULL_LAYOUT isn't in this build")
    val touches = PULL_TOUCHES.associateWith { name ->
        val handlers = layout.methods.filter {
            it.name == name && it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(MOTION_EVENT)
        }
        val handler = handlers.singleOrNull() ?: refuse("expected one $name($MOTION_EVENT)Z in $PULL_LAYOUT, found ${handlers.size}")
        if (AccessFlags.STATIC.isSet(handler.accessFlags) || handler.implementation == null) {
            refuse("$PULL_LAYOUT->$name isn't an instance method with code")
        }
        handler.freeLocalsAt(PATCH, 0, 1).single()
    }
    return ReelsScrollingSite(
        viewer.definingClass, viewer.name, viewer.parameterTypes.map(CharSequence::toString), store, pager, holder,
        "$owner->${config.name}:$VIEWER_CONFIG", "$VIEWER_CONFIG->${source.name}:$VIEWER_SOURCE", answer, setterSelf, touches, field,
        bridged,
    )
}

/**
 * The register holding `this` in the pager's [SET_USER_INPUT], whose parameter comes next. The
 * setter has to store its parameter, untouched, in one boolean field of the pager, and nothing may
 * jump back to its start.
 */
private fun findSetter(pagerClass: ClassDef): Int {
    val setters = pagerClass.methods.filter {
        it.name == SET_USER_INPUT && it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf("Z")
    }
    val setter = setters.singleOrNull() ?: refuse("expected one $SET_USER_INPUT(Z)V in $VIEW_PAGER, found ${setters.size}")
    val where = "$VIEW_PAGER->$SET_USER_INPUT"
    if (AccessFlags.STATIC.isSet(setter.accessFlags) || setter.implementation == null) refuse("$where isn't an instance method with code")
    val self = setter.localRegisterCount()
    val enabled = setter.parameterRegisterNumber(0)
    val code = setter.instructions()
    val stores = code.indices.filter { index ->
        val instruction = code[index]
        val stored = instruction.fieldReference()
        instruction.opcode == Opcode.IPUT_BOOLEAN && stored?.definingClass == VIEW_PAGER && stored.type == "Z" &&
            (instruction as TwoRegisterInstruction).registerA == enabled && instruction.registerB == self
    }
    val store = stores.singleOrNull() ?: refuse("expected $where to store its parameter in one field, found ${stores.size}")
    if (0 in setter.jumpTargets()) refuse("something in $where jumps back to its start")
    setter.requireParameterIntact(PATCH, 0, listOf(store))
    return self
}

/**
 * Writes the three hooks, everything having been found first.
 *
 * After the viewer stores its pager, [PAGER_HOOK] gets the pager, where the viewer was opened
 * from and the object the pager went into, whose viewer config the [PAGER_CONFIG] bridge reads, and
 * on a 0 the pager's input is turned off. In front of everything in [SET_USER_INPUT],
 * [USER_INPUT_HOOK] gets the pager and the value as an int and hands back the value the setter stores.
 * At the start of each touch handler of the pull-down layout, [PULL_HOOK] gets the layout and answers
 * 0 to have it return false and let the touch go by, and 1 to run it as before.
 */
internal fun BytecodePatchContext.stopReelsScrolling(site: ReelsScrollingSite) {
    val viewer = mutableClassDefBy(site.viewer).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    val answer = site.answer
    val pager = site.pager
    // Found again, since another patch may have put code ahead of the store in this method since.
    val store = viewer.instructions().indexOfFirst { it.opcode == Opcode.IPUT_OBJECT && it.fieldReference()?.toString() == site.field }
    if (store < 0) refuse("${site.viewer}->${site.name} no longer stores ${site.field}")
    if (site.bridged) {
        val owner = site.config.substringBefore("->")
        fillStub(
            REEL_SCROLLING, PAGER_CONFIG, "Ljava/lang/Object;", 1,
            """
                check-cast p0, $owner
                iget-object p0, p0, ${site.config}
                return-object p0
            """,
        )
    }
    viewer.addInstructionsWithLabels(
        store + 1,
        """
            iget-object v$answer, v${site.holder}, ${site.config}
            if-eqz v$answer, :source
            iget-object v$answer, v$answer, ${site.source}
            :source
            invoke-static { v$pager, v$answer, v${site.holder} }, $PAGER_HOOK
            move-result v$answer
            if-nez v$answer, :keep
            const/4 v$answer, 0x0
            invoke-virtual { v$pager, v$answer }, $VIEW_PAGER->$SET_USER_INPUT(Z)V
        """,
        ExternalLabel("keep", viewer.getInstruction(store + 1)),
    )

    val setter = mutableClassDefBy(VIEW_PAGER).methods.single {
        it.name == SET_USER_INPUT && it.parameterTypes.map(CharSequence::toString) == listOf("Z")
    }
    // The setter's store names both registers, so they're v15 or below.
    val self = site.setterSelf
    val enabled = self + 1
    setter.addInstructions(
        0,
        """
            invoke-static { v$self, v$enabled }, $USER_INPUT_HOOK
            move-result v$enabled
        """,
    )

    val layout = mutableClassDefBy(PULL_LAYOUT)
    for ((name, free) in site.touches) {
        val handler = layout.methods.single {
            it.name == name && it.parameterTypes.map(CharSequence::toString) == listOf(MOTION_EVENT)
        }
        handler.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p0 .. p0 }, $PULL_HOOK
                move-result v$free
                if-nez v$free, :stock
                const/4 v$free, 0x0
                return v$free
            """,
            ExternalLabel("stock", handler.getInstruction(0)),
        )
    }
}

private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

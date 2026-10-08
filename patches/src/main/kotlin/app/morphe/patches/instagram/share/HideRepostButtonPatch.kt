/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.share

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.feed.FeedStateWrite
import app.morphe.patches.instagram.feed.feedRowState
import app.morphe.patches.instagram.feed.flagWrites
import app.morphe.patches.instagram.feed.holdsString
import app.morphe.patches.instagram.feed.isToString
import app.morphe.patches.instagram.feed.prepareFlagWrites
import app.morphe.patches.instagram.feed.printedFlag
import app.morphe.patches.instagram.feed.sameAs
import app.morphe.patches.instagram.misc.analytics.loadsString
import app.morphe.patches.instagram.misc.analytics.stringLoadedAt
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import app.morphe.util.getFreeRegisterProvider
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

private const val PATCH = "Hide the Repost button"
internal const val HIDE_REPOSTS = "$EXTENSION_PACKAGE/share/RepostButton;->hide()Z"
internal const val REPOSTS_ELIGIBLE = "$EXTENSION_PACKAGE/share/RepostButton;->eligible(Ljava/lang/Boolean;)Ljava/lang/Boolean;"
internal const val REPOSTS_FEED_UFI = "$EXTENSION_PACKAGE/share/RepostButton;->feedUfi(Landroid/view/View;Landroid/view/View;)V"
internal const val REPOSTS_FEED_COMPONENT = "$EXTENSION_PACKAGE/share/RepostButton;->feedComponent()Z"
internal const val REPOSTS_FEED_RESTORE = "$EXTENSION_PACKAGE/share/RepostButton;->restoreFeedUfi(Landroid/view/View;Landroid/view/View;)V"
internal const val REPOSTS_FEED_STATE = "$EXTENSION_PACKAGE/share/RepostButton;->feedState(I)Z"

/**
 * The labels Feed's action-row state prints its Repost flags under in its toString: whether the
 * button shows, whether its count does, and whether it animates.
 */
internal const val REPOST_ENABLED_LABEL = ", isRepostButtonEnabled="
internal const val REPOST_COUNT_LABEL = ", shouldShowRepostCount="
internal const val REPOST_ANIMATE_LABEL = ", shouldAnimateRepostButton="

/** The post model, a kept name. */
internal const val MEDIA = "Lcom/instagram/feed/media/Media;"

/**
 * The field that says whether a post or reel can be reposted. Its name predates reposts; 449's
 * clips buttons call what they read from it isEligibleForRepostsProduction. Instagram's data trees
 * key a field by its name's hash.
 */
internal const val REPOSTS_FIELD = "enable_media_notes_production"
internal val REPOSTS_HASH = REPOSTS_FIELD.hashCode()

private const val BOOLEAN = "Ljava/lang/Boolean;"
private const val BOUNCY_UFI_BUTTON = "Lcom/instagram/ui/widget/bouncyufibutton/IgBouncyUfiButtonImageView;"
private const val UFI_COUNT = "Lcom/instagram/common/ui/base/IgTextView;"

/** Feed's inflated repost icon and count in Instagram 450, the same ids in every build. */
internal const val REPOSTS_UFI_ICON_ID = 0x7f0b3614
internal const val REPOSTS_UFI_COUNT_ID = 0x7f0b3613

/**
 * The role the component renderer gives the Repost button. Its label is a string resource, and
 * Instagram numbers those per build (450's x86 build 385611439 has 438's 0x7f136e0d at 0x7f136e0f),
 * so the role is what's matched.
 */
internal const val BUTTON_ROLE = "android.widget.Button"

/** How far before a tree read its hash may be loaded, for a branch or two in between. */
private const val HASH_REACH = 4

/**
 * Takes the Repost button off posts and reels. Off in the default selection: reposting is one of
 * Instagram's features, so leaving it out is the user's pick.
 */
@Suppress("unused")
val hideRepostButtonPatch = bytecodePatch(
    name = "Hide the Repost button",
    description = "Takes the Repost button and its count off posts and reels, so nothing gets reposted by mistake. " +
        "Share still sends a post or reel to someone.",
    default = false,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("repostButton")
        // Everything is found and checked before the first change.
        val feedState = findFeedRepostState()
        val sites = findRepostSites()
        val feedUfi = findFeedUfiSite()
        val component = findFeedRepostComponent()
        requireSeparateMethods(repostHookMethods(feedState, sites, feedUfi, component))
        val hideState = prepareFeedState(feedState)
        val hideUfi = prepareFeedUfi(feedUfi)
        val hideComponent = prepareFeedComponent(component)
        hideState()
        guardRepostGetter(sites.getter)
        sites.reads.groupBy { Triple(it.type, it.name, it.parameters) }.values.forEach(::filterRepostReads)
        hideUfi()
        hideComponent()
        enableStatus("repostButton")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun methodKey(type: String, name: String, parameters: List<String>) = "$type->$name(${parameters.joinToString("")})"

/** The methods each of this patch's hooks changes, by hook. */
internal fun repostHookMethods(
    state: FeedRepostState,
    sites: RepostSites,
    feedUfi: FeedUfiSite,
    component: FeedRepostComponent,
): Map<String, Set<String>> = mapOf(
    "the action-row state hook" to state.writes.map { methodKey(state.type, "<init>", it.parameters) }.toSet(),
    "the getter guard" to setOf(methodKey(MEDIA, sites.getter, emptyList())),
    "the tree read filter" to sites.reads.map { methodKey(it.type, it.name, it.parameters) }.toSet(),
    "the Feed UFI hook" to setOf(methodKey(feedUfi.type, feedUfi.name, feedUfi.parameters)),
    "the Feed component hook" to setOf(
        methodKey(component.method.definingClass, component.method.name, component.method.parameterTypes.map(CharSequence::toString)),
    ),
)

/**
 * Fails before any change when two hooks would change the same method. Each one places its lines
 * by instruction positions found in the untouched method, so another hook's lines would move them.
 */
internal fun requireSeparateMethods(hooks: Map<String, Set<String>>) {
    val owners = mutableMapOf<String, String>()
    for ((hook, methods) in hooks) for (method in methods) {
        owners.put(method, hook)?.let { other -> refuse("$other and $hook would both change $method") }
    }
}

/** A read of the field from a post's data tree: where its move-result-object is and the register it fills. */
internal class RepostRead(val type: String, val name: String, val parameters: List<String>, val at: Int, val register: Int)

/** The post model's getter of the field, and every tree read of it outside the model. */
internal class RepostSites(val getter: String, val reads: List<RepostRead>)

/** Feed's already-inflated UFI repost views and the point before the Share button is bound. */
internal class FeedUfiSite(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val insert: Int,
    val holder: Int,
    val icon: FieldReference,
    val count: FieldReference,
)

/**
 * Finds [MEDIA]'s one getter of [REPOSTS_FIELD]: an instance method taking nothing, answering a
 * Boolean, that loads the field's name and its hash and has a register to spare. Then every read
 * of the field from a data tree elsewhere: an interface call taking an int and answering a Boolean,
 * handed [REPOSTS_HASH] loaded just before it, its answer moved straight out. The model's own other
 * reads copy the field between its forms and are left alone. Fails when the getter isn't there,
 * there's more than one, or no button reads the field, since that's an update this patch hasn't
 * seen.
 */
internal fun BytecodePatchContext.findRepostSites(): RepostSites {
    val media = classDefByOrNull(MEDIA) ?: refuse("$MEDIA is missing")
    val getters = media.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.isEmpty() && method.returnType == BOOLEAN &&
            method.holdsString(REPOSTS_FIELD) && method.holdsHash()
    }
    val getter = getters.singleOrNull() ?: refuse("expected one getter of $REPOSTS_FIELD in $MEDIA, found ${getters.size}")
    val implementation = getter.implementation!!
    if (implementation.registerCount - 1 < 1) refuse("$MEDIA->${getter.name} has no register of its own for the guard")

    val reads = mutableListOf<RepostRead>()
    val hashed = classesLoading(REPOSTS_HASH.toLong()).mapTo(HashSet()) { it.type }
    classDefForEach { classDef ->
        if (classDef.type !in hashed || classDef.type.startsWith(EXTENSION_ROOT) || classDef.type == MEDIA) return@classDefForEach
        classDef.methods.forEach { method -> reads += method.repostReads(classDef.type) }
    }
    if (reads.isEmpty()) refuse("nothing reads $REPOSTS_FIELD from a post's data tree")
    return RepostSites(getter.name, reads)
}

/**
 * Finds the Feed UFI binder that inflates `reposts_ufi_icon` and `reposts_ufi_count`, then inserts
 * before the next bouncy UFI button field. On 449 that next button is Share, so every native repost
 * icon/count update has already run and Share remains untouched.
 */
internal fun BytecodePatchContext.findFeedUfiSite(): FeedUfiSite {
    val sites = mutableListOf<FeedUfiSite>()
    val loading = classesLoading(REPOSTS_UFI_ICON_ID.toLong()).mapTo(HashSet()) { it.type }
    classDefForEach { classDef ->
        if (classDef.type !in loading || classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.methods.forEach { method ->
            val code = method.implementation?.instructions?.toList() ?: return@forEach
            val iconId = code.indexOfLiteral(REPOSTS_UFI_ICON_ID)
            val countId = code.indexOfLiteral(REPOSTS_UFI_COUNT_ID)
            if (iconId < 0 || countId < 0) return@forEach
            val icon = code.fieldStoreAfter(iconId, BOUNCY_UFI_BUTTON) ?: return@forEach
            val count = code.fieldStoreAfter(countId, UFI_COUNT) ?: return@forEach
            if (icon.holder != count.holder) {
                refuse("${classDef.type}->${method.name} stores Feed UFI icon and count on different holders")
            }
            val insert = code.indexOfFirstBouncyReadAfter(maxOf(icon.at, count.at), icon.field)
            if (insert < 0) refuse("${classDef.type}->${method.name} has Feed UFI views but no following Share button read")
            sites += FeedUfiSite(
                classDef.type,
                method.name,
                method.parameterTypes.map(CharSequence::toString),
                insert,
                icon.holder,
                icon.field,
                count.field,
            )
        }
    }
    return sites.singleOrNull()
        ?: refuse("expected one Feed UFI repost binder, found ${sites.size}")
}

/**
 * The component-backed Feed row's Repost renderer, separate from the view binder, and where in it
 * the hook goes. On 449 the renderer is a method of its own and the hook goes first. 450's Redex
 * merges it with other Feed components into one method that picks its part by the component's
 * class, so the hook goes at the start of the one part that reaches the repost icon and the
 * [BUTTON_ROLE] it gives the button. The role is read either way, loaded itself or asked of a
 * string pool, since which strings Redex pools differs from build to build.
 */
internal class FeedRepostComponent(val method: Method, val at: Int)

internal fun BytecodePatchContext.findFeedRepostComponent(): FeedRepostComponent {
    val renders = mutableListOf<Method>()
    val loading = classesLoading(REPOSTS_UFI_ICON_ID.toLong()).mapTo(HashSet()) { it.type }
    classDefForEach { classDef ->
        if (classDef.type !in loading || classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.methods.forEach { method ->
            val code = method.implementation?.instructions?.toList() ?: return@forEach
            if (!AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.size == 1 &&
                method.parameterTypes.single().startsWith("L") && method.returnType.startsWith("L") &&
                code.indexOfLiteral(REPOSTS_UFI_ICON_ID) >= 0 && loadsString(method, BUTTON_ROLE)) renders += method
        }
    }
    val render = renders.singleOrNull() ?: refuse("expected one Feed Repost component renderer, found ${renders.size}")
    val parts = mergedParts(render)
    if (parts.isEmpty()) return FeedRepostComponent(render, 0)
    val code = render.implementation!!.instructions.toList()
    val flow = ControlFlow.of(render)
    val icons = code.indices.filter { (code[it] as? NarrowLiteralInstruction)?.narrowLiteral == REPOSTS_UFI_ICON_ID && code[it].opcode == Opcode.CONST }
    val roles = code.indices.filter { stringLoadedAt(code, it) == BUTTON_ROLE }
    val checks = parts.map { it - 2 }.toSet()
    val reposts = parts.filter { start ->
        val seen = mutableSetOf<Int>()
        val pending = java.util.ArrayDeque<Int>().apply { add(start) }
        while (pending.isNotEmpty()) {
            val at = pending.removeFirst()
            if (at in checks || !seen.add(at)) continue
            pending.addAll(flow.normal[at]); pending.addAll(flow.exceptional[at])
        }
        icons.any { it in seen } && roles.any { it in seen }
    }
    val at = reposts.singleOrNull()
        ?: refuse("expected one part of ${render.definingClass}->${render.name} drawing Repost, found ${reposts.size}")
    if (flow.normal.indices.any { from -> from != at - 1 && at in flow.normal[from] }) {
        refuse("${render.definingClass}->${render.name}'s Repost part is reached other than from its class check")
    }
    return FeedRepostComponent(render, at)
}

/**
 * Where each part of a Redex-merged method starts: right after an instance-of check of the
 * method's own receiver (or a copy made before the first check) and the if-eqz that skips the part.
 */
private fun mergedParts(method: Method): List<Int> {
    val implementation = method.implementation!!
    val code = implementation.instructions.toList()
    val receivers = mutableSetOf(method.localRegisterCount())
    for (instruction in code) {
        if (instruction.opcode == Opcode.INSTANCE_OF) break
        if (instruction.opcode in setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16) &&
            (instruction as TwoRegisterInstruction).registerB in receivers) receivers += instruction.registerA
    }
    return code.indices.filter { at ->
        val check = code[at]
        check.opcode == Opcode.INSTANCE_OF && (check as TwoRegisterInstruction).registerB in receivers &&
            code.getOrNull(at + 1)?.let { skip -> skip.opcode == Opcode.IF_EQZ &&
                (skip as OneRegisterInstruction).registerA == check.registerA } == true
    }.map { it + 2 }
}

/** Native component rendering accepts null for an empty component; neither icon nor count mounts. */
internal fun BytecodePatchContext.hideFeedComponent(found: FeedRepostComponent) = prepareFeedComponent(found)()

/** Checks the renderer for [hideFeedComponent] and answers the change, made only when called. */
internal fun BytecodePatchContext.prepareFeedComponent(found: FeedRepostComponent): () -> Unit {
    val method = mutableClassDefBy(found.method.definingClass).methods.single {
        it.name == found.method.name && it.returnType == found.method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == found.method.parameterTypes.map(CharSequence::toString)
    }
    val register = if (found.at == 0) {
        method.requireLocals(PATCH, 1)
        0
    } else {
        // Only a local that no later instruction reads before writing it, low enough for const/4.
        val live = RegisterLiveness.of(method).liveInto(found.at)
        (0 until minOf(method.localRegisterCount(), 16)).firstOrNull { it !in live }
            ?: refuse("${method.definingClass}->${method.name} has no spare register at its Repost part")
    }
    // Off goes on to the part's own first instruction. An internal label would stay where the block
    // was assembled, which is only the part's start when the part opens the method.
    return {
        method.addInstructionsWithLabels(
            found.at,
            """
                invoke-static { }, $REPOSTS_FEED_COMPONENT
                move-result v$register
                if-eqz v$register, :draw
                const/4 v$register, 0x0
                return-object v$register
            """,
            ExternalLabel("draw", method.getInstruction(found.at)),
        )
    }
}

/** Feed's action-row state, the Repost flags it keeps, and every constructor write of them. */
internal class FeedRepostState(val type: String, val flags: List<FieldReference>, val writes: List<FeedStateWrite>)

/**
 * Feed draws each post's action row from an immutable state, and its toString prints
 * [REPOST_ENABLED_LABEL] followed by whether the row shows the Repost button. That flag is the
 * boolean field of the state's own class that toString loads from itself and appends right after
 * the label. It's final, so only the state's constructors set it, and [hideFeedState] passes every
 * one of those writes through [REPOSTS_FEED_STATE]. The row is drawn again from the same state, so
 * a button hidden only after a draw can come back on the next one (#69).
 *
 * The flags printed under [REPOST_COUNT_LABEL] and [REPOST_ANIMATE_LABEL] go the same way when each
 * is a final field of the state's own that its constructor sets once. On 450 the count is one, and
 * the animation is printed from a constant, so it's left out.
 *
 * Fails before any change when no class prints the label or more than one does, when what follows
 * the label isn't such a flag, when a method other than a constructor sets it, or when a write has
 * no register for the answer.
 */
internal fun BytecodePatchContext.findFeedRepostState(): FeedRepostState {
    val state = feedRowState(PATCH, REPOST_ENABLED_LABEL)
    val toString = state.methods.single { it.isToString() }
    val enabled = toString.printedFlag(PATCH, state.type, REPOST_ENABLED_LABEL)
    // Read as it is now, so a write Hide comments has already hooked is found where it sits.
    val stored = mutableClassDefBy(state.type)
    val writes = stored.flagWrites(PATCH, enabled).toMutableList()
    if (writes.isEmpty()) refuse("${state.type}'s constructor never sets what it prints as \"$REPOST_ENABLED_LABEL\"")
    val flags = mutableListOf(enabled)
    for (label in listOf(REPOST_COUNT_LABEL, REPOST_ANIMATE_LABEL)) {
        if (!toString.holdsString(label)) continue
        // A flag printed some other way, or set anywhere but once in a constructor, is left alone.
        val flag = try { toString.printedFlag(PATCH, state.type, label) } catch (_: PatchException) { continue }
        val set = try { stored.flagWrites(PATCH, flag) } catch (_: PatchException) { continue }
        if (set.size == 1 && flags.none { it.sameAs(flag) }) {
            flags += flag
            writes += set
        }
    }
    return FeedRepostState(state.type, flags, writes)
}

/**
 * Right before each constructor write of a Repost flag, passes the value through
 * [REPOSTS_FEED_STATE], so a state built while the switch is on keeps the button and its count off
 * however often Feed draws the row from it. A label on the write moves onto the call, so a branch
 * to the write runs the call too.
 */
internal fun BytecodePatchContext.hideFeedState(found: FeedRepostState) = prepareFeedState(found)()

/** Checks every write for [hideFeedState] and answers the change, made only when called. */
internal fun BytecodePatchContext.prepareFeedState(found: FeedRepostState) =
    prepareFlagWrites(PATCH, found.type, "Repost", found.writes, REPOSTS_FEED_STATE)

private fun Method.holdsHash() = implementation?.instructions?.any {
    it is NarrowLiteralInstruction && it.opcode == Opcode.CONST && it.narrowLiteral == REPOSTS_HASH
} == true

private fun List<Instruction>.indexOfLiteral(value: Int) = indexOfFirst {
    it is NarrowLiteralInstruction && it.opcode == Opcode.CONST && it.narrowLiteral == value
}

private class FieldStore(val at: Int, val holder: Int, val field: FieldReference)

private fun List<Instruction>.fieldStoreAfter(start: Int, fieldType: String): FieldStore? {
    for (at in start + 1 until minOf(size, start + 12)) {
        val instruction = this[at]
        if (instruction.opcode != Opcode.IPUT_OBJECT || instruction !is TwoRegisterInstruction) continue
        val field = ((instruction as? ReferenceInstruction)?.reference as? FieldReference) ?: continue
        if (field.type == fieldType) return FieldStore(at, instruction.registerB, field)
    }
    return null
}

private fun List<Instruction>.indexOfFirstBouncyReadAfter(start: Int, skipped: FieldReference): Int {
    for (at in start + 1 until size) {
        val instruction = this[at]
        if (instruction.opcode != Opcode.IGET_OBJECT) continue
        val field = ((instruction as? ReferenceInstruction)?.reference as? FieldReference) ?: continue
        if (field.type == BOUNCY_UFI_BUTTON && field.toString() != skipped.toString()) return at
    }
    return -1
}

private fun Method.repostReads(type: String): List<RepostRead> {
    val code = implementation?.instructions?.toList() ?: return emptyList()
    val reads = mutableListOf<RepostRead>()
    for ((at, instruction) in code.withIndex()) {
        if (instruction.opcode != Opcode.INVOKE_INTERFACE && instruction.opcode != Opcode.INVOKE_INTERFACE_RANGE) continue
        val called = (instruction as ReferenceInstruction).reference as MethodReference
        if (called.returnType != BOOLEAN || called.parameterTypes.map(CharSequence::toString) != listOf("I")) continue
        // The tree, then the int it's asked for.
        val key = when (instruction) {
            is FiveRegisterInstruction -> instruction.registerD
            is RegisterRangeInstruction -> instruction.startRegister + 1
            else -> continue
        }
        if (!code.hashLoadedInto(key, at)) continue
        val result = code.getOrNull(at + 1)
        if (result?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("$type->$name reads $REPOSTS_FIELD and drops the answer")
        reads += RepostRead(type, name, parameterTypes.map(CharSequence::toString), at + 1, (result as OneRegisterInstruction).registerA)
    }
    return reads
}

/** Whether [REPOSTS_HASH] is loaded into [register] within [HASH_REACH] instructions before [at], and nothing else writes it between. */
private fun List<Instruction>.hashLoadedInto(register: Int, at: Int): Boolean {
    for (back in (at - 1) downTo maxOf(0, at - HASH_REACH)) {
        val instruction = this[back]
        val writes = instruction is OneRegisterInstruction && instruction !is FiveRegisterInstruction &&
            instruction.registerA == register && instruction.opcode.setsRegister()
        if (!writes) continue
        return instruction is NarrowLiteralInstruction && instruction.opcode == Opcode.CONST && instruction.narrowLiteral == REPOSTS_HASH
    }
    return false
}

/**
 * First thing in the getter, asks [HIDE_REPOSTS], and on a yes answers FALSE: the post can't be
 * reposted. Otherwise the getter reads the field as it did.
 */
internal fun BytecodePatchContext.guardRepostGetter(getter: String) {
    val method = mutableClassDefBy(MEDIA).methods.single {
        it.name == getter && it.parameterTypes.isEmpty() && it.returnType == BOOLEAN
    }
    method.addInstructions(
        0,
        """
            invoke-static { }, $HIDE_REPOSTS
            move-result v0
            if-eqz v0, :read
            sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
            return-object v0
            :read
            nop
        """,
    )
}

/**
 * Right after each of one method's tree reads moves its answer out, passes it through
 * [REPOSTS_ELIGIBLE]. A range call, so the answer's register may be any.
 */
internal fun BytecodePatchContext.filterRepostReads(reads: List<RepostRead>) {
    val first = reads.first()
    val method = mutableClassDefBy(first.type).methods.single {
        it.name == first.name && it.parameterTypes.map(CharSequence::toString) == first.parameters
    }
    for (read in reads.sortedByDescending { it.at }) {
        method.addInstructions(
            read.at + 1,
            """
                invoke-static/range { v${read.register} .. v${read.register} }, $REPOSTS_ELIGIBLE
                move-result-object v${read.register}
            """,
        )
    }
}

/** Hide the Feed UFI repost views after Instagram has rebound them for this row. */
internal fun BytecodePatchContext.hideFeedUfi(site: FeedUfiSite) = prepareFeedUfi(site)()

/** Checks the binder for [hideFeedUfi] and answers the change, made only when called. */
internal fun BytecodePatchContext.prepareFeedUfi(site: FeedUfiSite): () -> Unit {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    val registers = method.getFreeRegisterProvider(site.insert, 2, site.holder)
    val icon = registers.getFreeRegister()
    val count = registers.getFreeRegister()
    // Native rebinding resets listeners/text, but not every icon's visibility. Remove only
    // our previous hide before it reads the holder, so native state always wins afterwards.
    val holderParameter = method.parameterTypes.indices.singleOrNull { method.parameterTypes[it] == site.icon.definingClass }
        ?: refuse("${site.type}->${site.name} has no unique Feed UFI holder parameter")
    method.requireLocals(PATCH, 3)
    val holder = method.parameterRegisterNumber(holderParameter)
    return {
        method.addInstructionsAtControlFlowLabel(
            site.insert,
            """
                iget-object v$icon, v${site.holder}, ${site.icon}
                iget-object v$count, v${site.holder}, ${site.count}
                invoke-static { v$icon, v$count }, $REPOSTS_FEED_UFI
            """,
        )
        method.addInstructions(
            0,
            """
                move-object/from16 v0, v$holder
                iget-object v1, v0, ${site.icon}
                iget-object v2, v0, ${site.count}
                invoke-static { v1, v2 }, $REPOSTS_FEED_RESTORE
            """,
        )
    }
}

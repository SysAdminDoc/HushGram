/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.reel

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.typesMarked
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

private const val NI_PATCH = "Download any reel"

internal const val NOT_INTERESTED_TYPE = "$EXTENSION_PACKAGE/download/ReelNotInterestedButton;"
internal const val NOT_INTERESTED_ON = "$NOT_INTERESTED_TYPE->on()Z"
internal const val NOT_INTERESTED_LITHO =
    "$NOT_INTERESTED_TYPE->litho(Ljava/lang/Object;Ljava/lang/Object;JJ)Ljava/lang/Object;"
internal const val NOT_INTERESTED_LITHO_ABOVE_MORE =
    "$NOT_INTERESTED_TYPE->lithoAboveMore(Ljava/lang/Object;Ljava/lang/Object;JJ)Ljava/lang/Object;"
internal const val NOT_INTERESTED_REMEMBER = "$NOT_INTERESTED_TYPE->remember(Ljava/lang/Object;Ljava/lang/Object;)Z"

/** Instagram's own Not interested on the object that binds the Reels viewer's items, and that object's constructor. */
internal const val NOT_INTERESTED_MARKER = "ClipsMediaItemViewBinderDelegate_handleNotInterested"
internal const val BINDER_INIT_MARKER = "ClipsMediaItemViewBinderDelegate_init"

/** The Reels viewer's config, a type of Instagram's own library that keeps its name. */
internal const val VIEWER_CONFIG = "Lcom/instagram/clips/intf/ClipsViewerConfig;"

private const val NI_OBJECT = "Ljava/lang/Object;"

/** How many instructions the Download icon's block is, which the icon's block goes in front of. */
internal const val DOWNLOAD_BLOCK = 14

/**
 * What the Not interested icon beside a reel (#191) needs, all found by structure.
 *
 * Instagram's own Not interested is [run], a method of [delegate], the object that binds the
 * viewer's items. It takes the reel's item ([itemType]) and the reel's state ([stateType]). The
 * column's state interface [stateInterface] answers both through [stateGetter] and the viewer's
 * config through [configGetter]. [constructor] is the delegate's constructor, which takes that same
 * config as its parameter [configRegister] (a p register number) and ends at [returnAt].
 */
internal class NotInterestedSite(
    val delegate: String,
    val run: MethodReference,
    val itemType: String,
    val stateType: String,
    val stateInterface: String,
    val configGetter: MethodReference,
    val stateGetter: MethodReference,
    val constructor: MethodReference,
    val configRegister: Int,
    val returnAt: Int,
)

private fun refuseNotInterested(detail: String): Nothing = throw PatchException("$NI_PATCH, the Not interested button beside a reel: $detail")

internal fun Method.parameterNames() = parameterTypes.map(CharSequence::toString)

private fun methodReference(method: Method) =
    ImmutableMethodReference(method.definingClass, method.name, method.parameterNames(), method.returnType)

/**
 * Adds the Not interested button beside a reel (#191) next to the Download one when both columns
 * are as this patch knows them, then the Block button in front of it, which borrows its delegate.
 * Each is a step of its own: a build where one doesn't fit gets a warning and keeps Download any
 * reel and the Download button without it, and without the Block button when Not interested is the
 * one that doesn't fit.
 */
internal fun BytecodePatchContext.addNotInterestedButtonWhereItFits(site: ReelButtonSite) {
    val found = try {
        findNotInterestedSite(site).also { addNotInterestedButton(site, it) }
    } catch (unknown: PatchException) {
        patchLog.warning("${unknown.message}. $NI_PATCH goes in without the Not interested or Block buttons beside a reel.")
        return
    }
    try {
        addBlockButton(site, found, findBlockSite(found))
    } catch (unknown: PatchException) {
        patchLog.warning("${unknown.message}. $NI_PATCH goes in without the Block button beside a reel.")
    }
}

/**
 * Finds what [addNotInterestedButton] needs: the method holding the Not interested marker and the
 * constructor holding the delegate's marker, each exactly once, the column state's getters of the
 * reel's state and of the viewer's config, and the one place the constructor ends. Fails naming what
 * it didn't find exactly once, before anything changes.
 */
internal fun BytecodePatchContext.findNotInterestedSite(site: ReelButtonSite): NotInterestedSite {
    val names = listOf(NOT_INTERESTED_MARKER, BINDER_INIT_MARKER)
    val found = names.associateWith { mutableListOf<Method>() }
    typesMarked(*names.toTypedArray()).forEach { type ->
        classDefByOrNull(type)?.methods?.forEach { method -> method.markers().forEach { found[it]?.add(method) } }
    }
    fun one(name: String): Method = found.getValue(name)
        .distinctBy { "${it.definingClass}->${it.name}${it.parameterNames()}" }.singleOrNull()
        ?: refuseNotInterested("expected one method holding the $name marker, found ${found.getValue(name).size}")
    val run = one(NOT_INTERESTED_MARKER)
    val init = one(BINDER_INIT_MARKER)
    val delegate = run.definingClass
    val delegateClass = classDefByOrNull(delegate) ?: refuseNotInterested("the delegate $delegate is missing")
    val arguments = run.parameterNames()
    if (!AccessFlags.PUBLIC.isSet(delegateClass.accessFlags) || !AccessFlags.PUBLIC.isSet(run.accessFlags) ||
        AccessFlags.STATIC.isSet(run.accessFlags) || run.returnType != "V" || arguments.size != 2 ||
        arguments.any { !it.startsWith("L") }
    ) {
        refuseNotInterested("$delegate->${run.name} isn't a public method of a reel's item and state")
    }
    if (arguments[0] != site.itemType) {
        refuseNotInterested("$delegate->${run.name} takes ${arguments[0]}, not the column's reel item ${site.itemType}")
    }
    if (init.definingClass != delegate || init.name != "<init>") {
        refuseNotInterested("${init.definingClass}->${init.name} holding the delegate's marker isn't the constructor of $delegate")
    }

    val state = classDefByOrNull(site.state.type) ?: refuseNotInterested("the column's state ${site.state.type} is missing")
    fun getter(returns: String): Method = state.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType == returns
    }.singleOrNull() ?: refuseNotInterested("expected one getter of $returns in the column's state ${state.type}")
    val stateGetter = getter(arguments[1])
    val configGetter = getter(VIEWER_CONFIG)
    if (!AccessFlags.PUBLIC.isSet(state.accessFlags) || !AccessFlags.PUBLIC.isSet(stateGetter.accessFlags) ||
        !AccessFlags.PUBLIC.isSet(configGetter.accessFlags)
    ) {
        refuseNotInterested("the column's state ${state.type} or its getters aren't public")
    }

    val parameters = init.parameterNames()
    val configAt = parameters.indices.filter { parameters[it] == VIEWER_CONFIG }.singleOrNull()
        ?: refuseNotInterested("expected one $VIEWER_CONFIG parameter in the constructor of $delegate")
    val register = 1 + parameters.take(configAt).sumOf { if (it == "J" || it == "D") 2 else 1 }

    val body = mutableClassDefBy(delegate).methods.singleOrNull { it.name == "<init>" && it.parameterNames() == parameters }
        ?: refuseNotInterested("expected one constructor of $delegate taking ${parameters.joinToString()}")
    val code = body.implementation?.instructions?.toList() ?: refuseNotInterested("the constructor of $delegate has no body")
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
    val returnAt = returns.singleOrNull()
        ?: refuseNotInterested("expected one return in the constructor of $delegate, found ${returns.size}")
    if (returnAt != code.lastIndex || returnAt in body.jumpTargets()) {
        refuseNotInterested("the constructor of $delegate doesn't end in a return nothing jumps to")
    }
    if (body.localRegisterCount() < 2) refuseNotInterested("the constructor of $delegate has fewer than two local registers")
    if (classDefByOrNull(NOT_INTERESTED_TYPE) == null) refuseNotInterested("$NOT_INTERESTED_TYPE is missing")

    return NotInterestedSite(
        delegate, methodReference(run), arguments[0], arguments[1], state.type, methodReference(configGetter),
        methodReference(stateGetter), methodReference(init), register, returnAt,
    )
}

/**
 * Right in front of the Download icon's block at each of [site]'s places, asks the extension whether
 * the Not interested icon is on, and when it is hands it the column, the column's color filter and
 * the size and margin Save was handed, and adds the icon it answers to the column. The Download
 * block is left as it was, so it still runs after this one and skips on its own. Then the delegate's
 * constructor reports the delegate with the viewer's config, and the bridges the extension reads the
 * column's config and state and runs Instagram's own Not interested through are written.
 */
internal fun BytecodePatchContext.addNotInterestedButton(site: ReelButtonSite, found: NotInterestedSite) {
    val render = mutableClassDefBy(site.column).methods.single {
        it.name == site.render.name && it.parameterNames() == site.render.parameterTypes.map(CharSequence::toString) &&
            it.returnType == site.render.returnType
    }
    // Everything that can refuse is checked before anything changes.
    val bridges = mutableClassDefBy(NOT_INTERESTED_TYPE)
    fun stub(name: String, returns: String, vararg parameters: String) = bridges.methods.singleOrNull {
        it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == returns &&
            it.parameterNames() == parameters.toList()
    } ?: throw PatchException("$NI_PATCH: $NOT_INTERESTED_TYPE has no static $returns $name(${parameters.joinToString()})")
    val configOf = stub("notInterestedConfig", NI_OBJECT, NI_OBJECT)
    val stateOf = stub("notInterestedState", NI_OBJECT, NI_OBJECT)
    val runOf = stub("notInterestedRun", NI_OBJECT, NI_OBJECT, NI_OBJECT, NI_OBJECT)
    val ascending = site.sites.sortedBy { it.at }
    val starts = ascending.mapIndexed { rank, place -> place to place.at + DOWNLOAD_BLOCK * rank }
    for ((_, start) in starts) {
        val first = render.implementation?.instructions?.elementAtOrNull(start)
        if ((first as? ReferenceInstruction)?.reference.toString() != REEL_BUTTON_ON) {
            throw PatchException("$NI_PATCH: the Download button's block isn't where the Not interested button goes in $start")
        }
    }
    val constructor = mutableClassDefBy(found.delegate).methods.single {
        it.name == "<init>" && it.parameterNames() == found.constructor.parameterTypes.map(CharSequence::toString)
    }

    starts.sortedByDescending { it.second }.forEach { (place, start) ->
        val litho = if (place.aboveMore) NOT_INTERESTED_LITHO_ABOVE_MORE else NOT_INTERESTED_LITHO
        render.addInstructionsWithLabels(
            start,
            columnIconBlock(site, place, NOT_INTERESTED_ON, litho),
            ExternalLabel("skip", render.getInstruction(start)),
        )
    }

    // The delegate is made with the viewer's config, which the column's state hands back. Both locals
    // are free where the constructor ends, as nothing reads one after its return.
    constructor.addInstructions(
        found.returnAt,
        """
            move-object/from16 v0, p${found.configRegister}
            move-object/from16 v1, p0
            invoke-static { v0, v1 }, $NOT_INTERESTED_REMEMBER
        """,
    )

    rewriteStub(
        bridges, configOf, 0,
        """
            check-cast p0, ${site.column}
            iget-object p0, p0, ${site.state}
            invoke-interface { p0 }, ${found.configGetter}
            move-result-object p0
            return-object p0
        """,
    )
    rewriteStub(
        bridges, stateOf, 0,
        """
            check-cast p0, ${site.column}
            iget-object p0, p0, ${site.state}
            invoke-interface { p0 }, ${found.stateGetter}
            move-result-object p0
            return-object p0
        """,
    )
    rewriteStub(
        bridges, runOf, 0,
        """
            check-cast p0, ${found.delegate}
            check-cast p1, ${found.itemType}
            check-cast p2, ${found.stateType}
            invoke-virtual { p0, p1, p2 }, ${found.run}
            const/4 p0, 0x0
            return-object p0
        """,
    )
}

/**
 * The fourteen instructions that add an extension's icon to the column at [place]: ask [on], and
 * when it answers yes hand [litho] the column, the column's color filter and the size and margin Save
 * was handed, and add what it answers with the column's own add. Each jumps to `:skip`, the
 * instruction the block goes in front of.
 */
internal fun columnIconBlock(site: ReelButtonSite, place: ReelSaveSite, on: String, litho: String): String {
    val w = place.window
    val s = place.first
    val tint = if (site.tintPrivate) "invoke-direct/range" else "invoke-virtual/range"
    return """
        invoke-static {}, $on
        move-result v$w
        if-eqz v$w, :skip
        $tint { v$s .. v${s + 1} }, ${site.tint}
        move-result-object v${w + 1}
        move-object/from16 v$w, v$s
        move-wide/from16 v${w + 2}, v${s + 2}
        move-wide/from16 v${w + 4}, v${s + 4}
        invoke-static/range { v$w .. v${w + 5} }, $litho
        move-result-object v${w + 1}
        if-eqz v${w + 1}, :skip
        move-object/from16 v$w, v${place.list}
        check-cast v${w + 1}, ${site.addType}
        invoke-virtual/range { v$w .. v${w + 1} }, ${site.add}
    """
}

/** Swaps [stub] of [bridges] for the same method with [locals] locals and [body]. */
internal fun rewriteStub(bridges: MutableClass, stub: MutableMethod, locals: Int, body: String) {
    val parameters = stub.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
    bridges.methods.remove(stub)
    bridges.methods.add(
        ImmutableMethod(
            stub.definingClass, stub.name, stub.parameters, stub.returnType, stub.accessFlags, stub.annotations,
            stub.hiddenApiRestrictions, ImmutableMethodImplementation(locals + parameters, emptyList(), null, null),
        ).toMutable().apply { addInstructions(0, body) },
    )
}

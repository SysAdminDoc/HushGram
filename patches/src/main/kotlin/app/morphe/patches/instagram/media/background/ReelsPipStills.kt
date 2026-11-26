/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.background

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.download.reel.rewriteStub
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/** The extension class letting photo and carousel reels into the reels window (#873). */
internal const val REELS_PIP_STILLS = "$EXTENSION_PACKAGE/media/ReelsPipStills;"

/** Asked in the window's check right after it asked whether the reel is a video. */
internal const val PIP_STILLS_WINDOW = "$REELS_PIP_STILLS->window(ILjava/lang/Object;)I"

/** The extension's stub whose body the patch writes: a reel model's media_type. */
internal const val PIP_STILLS_TYPE = "mediaType"

/** The key Instagram's models read `media_type` by: the Java hash of the field's name. */
internal val MEDIA_TYPE_KEY = "media_type".hashCode()

private const val STILLS_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val STILLS_FUNCTION1 = "Lkotlin/jvm/functions/Function1;"
private const val STILLS_BOOLEAN_VALUE = "Ljava/lang/Boolean;->booleanValue()Z"
private const val STILLS_INTEGER = "Ljava/lang/Integer;"

/**
 * The window's check: the settings builder's (UserSession, reel) boolean method, which Instagram's
 * window navigation asks too. [answer] is the index of the move-result taking its first question's
 * answer, whether the reel is a video, which the if-ne right after compares with [yes], a register
 * holding 1. [model] holds the reel's model there. The other fields are what Instagram's video
 * question reads the reel's media_type through: the model's child at [childKey] by [child], wrapped
 * by [wrap], its tree [tree], that tree's child at [mediaKey] by [grandchild], and the type by [type].
 */
internal class ReelsPipStillSites(
    val gate: Method,
    val answer: Int,
    val yes: Int,
    val model: Int,
    val modelType: String,
    val childKey: Int,
    val child: MethodReference,
    val wrap: MethodReference,
    val tree: FieldReference,
    val mediaKey: Int,
    val grandchild: MethodReference,
    val type: MethodReference,
)

/**
 * Finds the window's check from [builder], the settings builder [findReelsPipButtons] found: the one
 * virtual call there to a method of its own class taking a UserSession and the reel and answering a
 * boolean (its static checks take a UserSession and a boolean).
 * In the check, the first Boolean read is the answer to its video question, a predicate over the
 * reel's model, and the if-ne after its move-result is where the hook goes. The predicate's lambda
 * case reads the model's child, wraps it and hands it to Instagram's static video question, which
 * reads the media_type of its tree's child. Every name and key comes from there, so the extension's
 * read is Instagram's own. Fails naming [patch] when any of it isn't that shape.
 */
internal fun BytecodePatchContext.findReelsPipStills(patch: String, builder: Method): ReelsPipStillSites {
    fun refuse(why: String): Nothing = throw PatchException("$patch: $why")
    val owner = builder.definingClass
    val checks = builder.stillsCode().filter { it.opcode == Opcode.INVOKE_VIRTUAL }.mapNotNull { it.stillsCall() }.filter { call ->
        call.definingClass == owner && call.returnType == "Z" && call.parameterTypes.map(Any::toString).let {
            it.size == 2 && it[0] == STILLS_SESSION && it[1].startsWith("L") && it[1] != STILLS_SESSION
        }
    }.distinct()
    val check = checks.singleOrNull() ?: refuse("expected one window check called by the settings builder, found ${checks.size}")
    val gate = classDefBy(owner).methods.singleOrNull {
        it.name == check.name && it.returnType == "Z" && it.parameterTypes.map(Any::toString) == check.parameterTypes.map(Any::toString)
    } ?: refuse("the window check ${check.name} isn't on $owner")
    val code = gate.stillsCode()

    val read = code.indexOfFirst { it.opcode == Opcode.INVOKE_VIRTUAL && it.stillsCall()?.toString() == STILLS_BOOLEAN_VALUE }
    if (read < 3) refuse("the window check reads no Boolean after a predicate")
    val predicate = code[read - 3] as? FiveRegisterInstruction
    val predicateCall = predicate?.stillsCall()
    val predicateParameters = predicateCall?.parameterTypes?.map(Any::toString) ?: emptyList()
    if (predicate?.opcode != Opcode.INVOKE_STATIC || predicateCall?.returnType != "Ljava/lang/Object;" ||
        predicateParameters.size != 2 || predicateParameters[1] != STILLS_FUNCTION1 ||
        code[read - 2].opcode != Opcode.MOVE_RESULT_OBJECT || code[read - 1].opcode != Opcode.CHECK_CAST
    ) refuse("the window check's first Boolean isn't a predicate's answer")
    val modelType = predicateParameters[0]
    val model = predicate!!.registerC
    val lambda = predicate.registerD
    val result = code.getOrNull(read + 1) as? OneRegisterInstruction
    val compare = code.getOrNull(read + 2) as? TwoRegisterInstruction
    if (result == null || compare == null || result.opcode != Opcode.MOVE_RESULT || compare.opcode != Opcode.IF_NE ||
        compare.registerA != result.registerA
    ) refuse("the window check doesn't compare its video answer right away")
    val answerRegister = result.registerA
    val yes = compare.registerB
    if (code.subList(read - 2, read + 2).any { it.stillsWrites(model) }) refuse("the window check reuses the model's register")
    if (gate.stillsConstantBefore(read + 2, yes) != 1) refuse("the window check's video answer isn't compared with 1")
    if (maxOf(answerRegister, model, yes) > 15) refuse("the window check's registers are past v15")
    if (read + 2 in gate.jumpTargets()) refuse("the window check can jump onto its video comparison")

    // The predicate is a lambda class's case, picked by the int its constructor takes.
    val made = code.subList(0, read - 3).indexOfLast { it.stillsWrites(lambda) }
    val lambdaType = ((code.getOrNull(made) as? ReferenceInstruction)?.reference as? TypeReference)?.type
    if (code.getOrNull(made)?.opcode != Opcode.NEW_INSTANCE || lambdaType == null) refuse("the video predicate isn't made in the check")
    val init = (made + 1 until read - 3).firstOrNull {
        code[it].opcode == Opcode.INVOKE_DIRECT && code[it].stillsCall()?.definingClass == lambdaType &&
            (code[it] as FiveRegisterInstruction).registerC == lambda
    } ?: refuse("the video predicate isn't constructed")
    val case = gate.stillsConstantBefore(init, (code[init] as FiveRegisterInstruction).registerD)
        ?: refuse("the video predicate has no constant case")
    val invoke = classDefBy(lambdaType).methods.singleOrNull {
        it.name == "invoke" && it.returnType == "Ljava/lang/Object;" && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;")
    } ?: refuse("$lambdaType has no invoke(Object)")
    val arm = invoke.stillsCase(case) ?: refuse("$lambdaType has no case $case")
    val armCode = invoke.stillsCode()
    val end = (arm until armCode.size).firstOrNull { armCode[it].opcode == Opcode.RETURN_OBJECT } ?: refuse("case $case doesn't return")
    val childAt = (arm until end).firstOrNull {
        armCode[it].opcode == Opcode.INVOKE_INTERFACE && armCode[it].stillsCall()?.let { call ->
            call.returnType == modelType && call.parameterTypes.map(Any::toString) == listOf("I")
        } == true
    } ?: refuse("case $case reads no child of the model")
    val child = armCode[childAt].stillsCall()!!
    val childKey = invoke.stillsConstantBefore(childAt, (armCode[childAt] as FiveRegisterInstruction).registerD)
        ?: refuse("case $case reads its child by no constant")
    val wrapAt = (childAt + 1 until end).firstOrNull {
        armCode[it].opcode == Opcode.INVOKE_STATIC && armCode[it].stillsCall()?.parameterTypes?.map(Any::toString) == listOf(modelType)
    } ?: refuse("case $case doesn't wrap the child")
    val wrap = armCode[wrapAt].stillsCall()!!
    val questionAt = (wrapAt + 1 until end).firstOrNull {
        armCode[it].opcode == Opcode.INVOKE_STATIC && armCode[it].stillsCall()?.let { call ->
            call.returnType == "Z" && call.parameterTypes.map(Any::toString) == listOf(wrap.returnType)
        } == true
    } ?: refuse("case $case asks no static video question")
    val question = armCode[questionAt].stillsCall()!!

    // The video question reads its tree's child, then that child's media_type.
    val asked = classDefBy(question.definingClass).methods.singleOrNull {
        it.name == question.name && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == question.parameterTypes.map(Any::toString) && it.returnType == "Z"
    } ?: refuse("the video question ${question.name} isn't on ${question.definingClass}")
    val askedCode = asked.stillsCode()
    val own = asked.implementation!!.registerCount - 1
    val treeAt = askedCode.indexOfFirst { it.opcode == Opcode.IGET_OBJECT && (it as TwoRegisterInstruction).registerB == own }
    val tree = (askedCode.getOrNull(treeAt) as? ReferenceInstruction)?.reference as? FieldReference
        ?: refuse("the video question doesn't read its wrapper's tree")
    val grandAt = (treeAt + 1 until askedCode.size).firstOrNull {
        askedCode[it].opcode == Opcode.INVOKE_INTERFACE && askedCode[it].stillsCall()?.let { call ->
            call.returnType == tree.type && call.parameterTypes.map(Any::toString) == listOf("I")
        } == true
    } ?: refuse("the video question reads no child of its tree")
    val grandchild = askedCode[grandAt].stillsCall()!!
    val mediaKey = asked.stillsConstantBefore(grandAt, (askedCode[grandAt] as FiveRegisterInstruction).registerD)
        ?: refuse("the video question reads its child by no constant")
    val typeAt = (grandAt + 1 until askedCode.size).firstOrNull {
        askedCode[it].opcode == Opcode.INVOKE_INTERFACE && askedCode[it].stillsCall()?.let { call ->
            call.returnType == STILLS_INTEGER && call.parameterTypes.map(Any::toString) == listOf("I")
        } == true && asked.stillsConstantBefore(it, (askedCode[it] as FiveRegisterInstruction).registerD) == MEDIA_TYPE_KEY
    } ?: refuse("the video question doesn't read media_type")
    val type = askedCode[typeAt].stillsCall()!!

    return ReelsPipStillSites(gate, read + 1, yes, model, modelType, childKey, child, wrap, tree, mediaKey, grandchild, type)
}

/**
 * Writes the extension's media_type read the way the video question reads it, and has the window's
 * check hand its video answer and the reel's model to [PIP_STILLS_WINDOW], whose answer goes on to
 * the check's own comparison, so a still the extension lets in still meets the check's ad and size
 * questions.
 */
internal fun BytecodePatchContext.addReelsPipStills(patch: String, sites: ReelsPipStillSites) {
    val stills = mutableClassDefBy(REELS_PIP_STILLS)
    val stub = stills.methods.singleOrNull {
        it.name == PIP_STILLS_TYPE && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == STILLS_INTEGER &&
            it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;")
    } ?: throw PatchException("$patch: $REELS_PIP_STILLS has no static $PIP_STILLS_TYPE(Object)")
    rewriteStub(
        stills, stub, 1,
        """
            check-cast p0, ${sites.child.definingClass}
            const v0, ${sites.childKey}
            invoke-interface { p0, v0 }, ${sites.child}
            move-result-object p0
            if-eqz p0, :none
            invoke-static { p0 }, ${sites.wrap}
            move-result-object p0
            if-eqz p0, :none
            iget-object p0, p0, ${sites.tree}
            if-eqz p0, :none
            const v0, ${sites.mediaKey}
            invoke-interface { p0, v0 }, ${sites.grandchild}
            move-result-object p0
            if-eqz p0, :none
            const v0, $MEDIA_TYPE_KEY
            invoke-interface { p0, v0 }, ${sites.type}
            move-result-object p0
            return-object p0
            :none
            const/4 p0, 0x0
            return-object p0
        """,
    )

    val gate = mutable(sites.gate)
    val answer = gate.getInstruction<OneRegisterInstruction>(sites.answer).registerA
    gate.addInstructions(
        sites.answer + 1,
        """
            invoke-static { v$answer, v${sites.model} }, $PIP_STILLS_WINDOW
            move-result v$answer
        """,
    )
}

/** Handed Instagram's bind and its arguments, to bind again for a carousel's video page. */
internal const val PIP_STILLS_BINDING = "$REELS_PIP_STILLS->binding(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"

/** Asked at the window's bind which reel to bind the player to, null for a picture. */
internal const val PIP_STILLS_BOUND = "$REELS_PIP_STILLS->bound(Landroid/view/View;Ljava/lang/Object;)Ljava/lang/Object;"

/** Asked right after which carousel page's video the bind plays. */
internal const val PIP_STILLS_PAGE = "$REELS_PIP_STILLS->page(Ljava/lang/Object;)Ljava/lang/Integer;"

/** Asked first by the receiver of the window's Previous and Next. */
internal const val PIP_STILLS_SKIP = "$REELS_PIP_STILLS->skip(Landroid/content/Intent;)I"

/** The extension's stub whose body the patch writes: the Media of the window's reel. */
internal const val PIP_STILLS_MEDIA = "reelMedia"

/** The one string the window's bind loads that names it. */
internal const val PIP_BIND_TAG = "clips_pip"

/** The Java hashes of the window's Next and Previous actions, which their receiver switches on. */
internal val PIP_NEXT_HASH = "instagram.features.clips.pip.ACTION_NEXT".hashCode()
internal val PIP_PREVIOUS_HASH = "instagram.features.clips.pip.ACTION_PREVIOUS".hashCode()

private const val SIMPLE_VIDEO_LAYOUT = "Lcom/instagram/ui/simplevideolayout/SimpleVideoLayout;"
private const val FEED_MEDIA = "Lcom/instagram/feed/media/Media;"
private const val STILLS_OBJECT = "Ljava/lang/Object;"

/**
 * The window's bind: the case of a Litho bind lambda's `invoke(Object, Object)` that binds
 * Instagram's player to the window's video layout. At [site], the bind's second ask whether it has
 * a reel, an if-eqz on [reel] that jumps past the player when it has none, [layout] holds the video
 * layout, and [index] the null page the player's video source call at [source] takes. [reelType] is
 * the reel's class, whose one Media field is [media].
 */
internal class ReelsPipBindSites(
    val bind: Method,
    val site: Int,
    val source: Int,
    val layout: Int,
    val reel: Int,
    val index: Int,
    val reelType: String,
    val media: FieldReference,
)

/**
 * Finds the window's bind: the one `invoke(Object, Object)Object` loading [PIP_BIND_TAG], in the
 * case that loads it. The video source call is the last `(Integer)` virtual call before the tag, on
 * the reel, with a register that took a const 0 for its page. The site is the last if-eqz on the
 * reel before that call, and the layout the last SimpleVideoLayout check-cast before the reel's read.
 * Nothing may write those three registers between the site and the call, nor `this` and the two
 * arguments before the site. Fails naming [patch] when any of it isn't that shape.
 */
internal fun BytecodePatchContext.findReelsPipBind(patch: String): ReelsPipBindSites {
    fun refuse(why: String): Nothing = throw PatchException("$patch: $why")
    val binds = classesLoadingString(PIP_BIND_TAG).flatMap { it.methods }.filter { method ->
        method.name == "invoke" && method.returnType == STILLS_OBJECT &&
            method.parameterTypes.map(Any::toString) == listOf(STILLS_OBJECT, STILLS_OBJECT) && loadsString(method, PIP_BIND_TAG)
    }
    val bind = binds.singleOrNull() ?: refuse("expected one window bind loading $PIP_BIND_TAG, found ${binds.size}")
    val code = bind.stillsCode()
    val tag = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == PIP_BIND_TAG }
    if (tag < 0) refuse("the window bind asks a pool for $PIP_BIND_TAG")
    val source = (tag - 1 downTo 0).firstOrNull {
        code[it].opcode == Opcode.INVOKE_VIRTUAL && code[it].stillsCall()?.let { call ->
            call.parameterTypes.map(Any::toString) == listOf(STILLS_INTEGER) && call.returnType.startsWith("L")
        } == true
    } ?: refuse("the window bind reads no video source before $PIP_BIND_TAG")
    val call = code[source] as FiveRegisterInstruction
    val reelType = code[source].stillsCall()!!.definingClass
    val reel = call.registerC
    val index = call.registerD
    val site = (source - 1 downTo 0).firstOrNull {
        code[it].opcode == Opcode.IF_EQZ && (code[it] as OneRegisterInstruction).registerA == reel
    } ?: refuse("the window bind doesn't ask whether it has a reel")
    val read = (site - 1 downTo 0).firstOrNull { code[it].stillsWrites(reel) } ?: refuse("the window bind never reads its reel")
    val readField = (code[read] as? ReferenceInstruction)?.reference as? FieldReference
    if (code[read].opcode != Opcode.IGET_OBJECT || readField?.type != reelType) refuse("the window bind's reel isn't a field read")
    val cast = (read - 1 downTo 0).firstOrNull {
        code[it].opcode == Opcode.CHECK_CAST && ((code[it] as ReferenceInstruction).reference as TypeReference).type == SIMPLE_VIDEO_LAYOUT
    } ?: refuse("the window bind has no video layout before its reel")
    val layout = (code[cast] as OneRegisterInstruction).registerA
    if ((cast + 1 until site).any { code[it].stillsWrites(layout) }) refuse("the window bind reuses its layout's register")
    if ((site until source).any { code[it].stillsWrites(reel) || code[it].stillsWrites(index) }) {
        refuse("the window bind reuses its reel's or page's register before the video source")
    }
    if (bind.stillsConstantBefore(source, index) != 0) refuse("the window bind's page isn't a null it loads before asking for the reel")
    if (maxOf(layout, reel, index) > 15) refuse("the window bind's registers are past v15")
    if (site in bind.jumpTargets()) refuse("the window bind can jump onto its reel question")
    bind.requireThisIntact(patch, listOf(site))
    bind.requireParameterIntact(patch, 0, listOf(site))
    bind.requireParameterIntact(patch, 1, listOf(site))
    val media = classDefBy(reelType).fields.filter { it.type == FEED_MEDIA && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val field = media.singleOrNull() ?: refuse("$reelType has ${media.size} Media fields, expected one")
    return ReelsPipBindSites(bind, site, source, layout, reel, index, reelType, field)
}

/**
 * Writes the extension's read of a reel's Media from the reel's one Media field, and has the bind
 * hand [PIP_STILLS_BINDING] its own arguments, [PIP_STILLS_BOUND] the layout and the reel, whose
 * answer the bind goes on with, null being Instagram's own answer for no reel, and
 * [PIP_STILLS_PAGE] that reel, whose answer is the page the video source call reads.
 */
internal fun BytecodePatchContext.addReelsPipBind(patch: String, sites: ReelsPipBindSites) {
    val stills = mutableClassDefBy(REELS_PIP_STILLS)
    val stub = stills.methods.singleOrNull {
        it.name == PIP_STILLS_MEDIA && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == STILLS_OBJECT &&
            it.parameterTypes.map(Any::toString) == listOf(STILLS_OBJECT)
    } ?: throw PatchException("$patch: $REELS_PIP_STILLS has no static $PIP_STILLS_MEDIA(Object)")
    rewriteStub(
        stills, stub, 0,
        """
            check-cast p0, ${sites.reelType}
            iget-object p0, p0, ${sites.media}
            return-object p0
        """,
    )

    mutable(sites.bind).addInstructions(
        sites.site,
        """
            invoke-static/range { p0 .. p2 }, $PIP_STILLS_BINDING
            invoke-static { v${sites.layout}, v${sites.reel} }, $PIP_STILLS_BOUND
            move-result-object v${sites.reel}
            check-cast v${sites.reel}, ${sites.reelType}
            invoke-static { v${sites.reel} }, $PIP_STILLS_PAGE
            move-result-object v${sites.index}
        """,
    )
}

/**
 * The receiver of the window's Previous and Next: the one `(Context, Intent, X)V` method switching
 * on [PIP_NEXT_HASH] and [PIP_PREVIOUS_HASH], the hashes of their actions. Fails naming [patch]
 * when there isn't exactly one.
 */
internal fun BytecodePatchContext.findReelsPipTurns(patch: String): Method {
    val receivers = classesLoading(PIP_NEXT_HASH.toLong()).flatMap { it.methods }.filter { method ->
        val parameters = method.parameterTypes.map(Any::toString)
        val literals = method.stillsCode().mapNotNull { (it as? NarrowLiteralInstruction)?.narrowLiteral }
        method.returnType == "V" && parameters.size == 3 && parameters[0] == "Landroid/content/Context;" &&
            parameters[1] == "Landroid/content/Intent;" && PIP_NEXT_HASH in literals && PIP_PREVIOUS_HASH in literals &&
            method.stillsCode().any { it.stillsCall()?.toString() == "Landroid/content/Intent;->getAction()Ljava/lang/String;" }
    }
    return receivers.singleOrNull()
        ?: throw PatchException("$patch: expected one receiver of the reels window's Previous and Next, found ${receivers.size}")
}

/**
 * Has the receiver ask [PIP_STILLS_SKIP] first, with the button's intent, and return right away when
 * the extension turned a carousel's page, before Instagram goes to another reel.
 */
internal fun BytecodePatchContext.addReelsPipTurns(patch: String, receiver: Method) {
    val method = mutable(receiver)
    method.requireLocals(patch, 1)
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static { p2 }, $PIP_STILLS_SKIP
            move-result v0
            if-eqz v0, :instagram
            return-void
        """,
        ExternalLabel("instagram", method.getInstruction(0)),
    )
}

private fun Method.stillsCode(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

private fun Instruction.stillsCall(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

/** Whether the instruction writes [register], as its first register, a wide pair's second included. */
private fun Instruction.stillsWrites(register: Int): Boolean = opcode.setsRegister() &&
    (this as? OneRegisterInstruction)?.let { it.registerA == register || opcode.setsWideRegister() && it.registerA + 1 == register } == true

/** The constant [register] last took before [at], counting only a const write. */
private fun Method.stillsConstantBefore(at: Int, register: Int): Int? =
    stillsCode().take(at).lastOrNull { it.stillsWrites(register) }
        ?.takeIf { it.opcode in setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16) }
        ?.let { it as? NarrowLiteralInstruction }?.narrowLiteral

/** The index of the instruction the method's first switch jumps to for [key], or null. */
private fun Method.stillsCase(key: Int): Int? {
    val code = stillsCode()
    val addresses = ArrayList<Int>(code.size)
    var address = 0
    for (instruction in code) {
        addresses += address
        address += instruction.codeUnits
    }
    val at = code.indexOfFirst { it.opcode == Opcode.PACKED_SWITCH || it.opcode == Opcode.SPARSE_SWITCH }
    if (at < 0) return null
    val payload = addresses.indexOf(addresses[at] + (code[at] as OffsetInstruction).codeOffset)
        .takeIf { it >= 0 }?.let { code[it] as? SwitchPayload } ?: return null
    val target = payload.switchElements.singleOrNull { it.key == key }?.offset ?: return null
    return addresses.indexOf(addresses[at] + target).takeIf { it >= 0 }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.mediawalk

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.direct.reels.MEDIA
import app.morphe.patches.instagram.direct.reels.THREAD_KEY
import app.morphe.patches.instagram.feed.carousel.REBOUND_VIEW_PAGER
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCallingInto
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
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

private const val PATCH = "Swipe through a chat's photos and posts"
internal const val MEDIA_WALK = "$EXTENSION_PACKAGE/direct/ChatMediaWalk;"
internal const val WALK_OPEN = "$MEDIA_WALK->open(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/List;I)Ljava/util/List;"
internal const val WALK_START = "$MEDIA_WALK->startIndex()I"
internal const val WALK_PAGER = "$MEDIA_WALK->pager(Ljava/lang/Object;)V"
internal const val WALK_HOLD = "$MEDIA_WALK->holdDismiss()I"

/** The chat's media viewer. Redex keeps its name, and it holds the session, the activity and the page builder. */
internal const val MEDIA_VIEWER = "Lcom/instagram/direct/fragment/permanentmedia/DirectAggregatedMediaViewerController;"

/** Loaded by the drag delegate of the viewer, which also decides whether a swipe up or down starts a dismiss. */
internal const val DRAG_NAN = "DirectMediaViewerDragControllerDelegate.onDrag scale is NaN"

/** Loaded by the reader of the attachment a shared post holds, among a few others that take something else. */
internal const val ATTACHMENT_CAST = "null cannot be cast to non-null type com.instagram.direct.model.GenericFBAttachment"

/** Loaded by the store that reads a chat's messages: in the reader itself or in a helper of it, depending on the build. */
internal const val THREAD_ENTRY = "ThreadEntry not found"

/** What Instagram's converter of a shared post's attachment to media asks for its media. */
internal const val MEDIA_CACHE = "Lcom/instagram/feed/media/MediaCache;"

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val ACTIVITY = "Landroidx/fragment/app/FragmentActivity;"
private const val PREVIEW_IMAGE = "Lcom/instagram/model/mediasize/ExtendedImageUrl;"
private const val LIST = "Ljava/util/List;"
private const val INTEGER = "Ljava/lang/Integer;"
private const val LONG = "Ljava/lang/Long;"
private const val STRING = "Ljava/lang/String;"
private const val MEDIA_ID = "$MEDIA->getId()$STRING"

/** How many arguments the page builder's full call takes, and how many of them are Instagram's flags at its end. */
private const val PAGE_ARGUMENTS = 20
private const val FIRST_FIXED_FLAG = 15

/** One of the extension's stubs: what its body becomes, and the registers that needs, its parameters among them. */
internal class WalkStub(val name: String, val registers: Int, val body: String)

/** Everything the patch writes into a build, read in full before it changes anything. */
internal class MediaWalkSite(
    val open: Method,
    val key: String,
    val mode: String,
    val list: String,
    val index: String,
    val albumMode: String,
    val pagerWrite: Int,
    val pagerRegister: Int,
    val hold: Method,
    val stubs: List<WalkStub>,
)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.types(): List<String> = parameterTypes.map(Any::toString)

private fun Method.isStatic(): Boolean = AccessFlags.STATIC.isSet(accessFlags)

private fun Instruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.called(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun FieldReference.text(): String = "$definingClass->$name:$type"

private fun MethodReference.text(): String = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/**
 * Where [register] was written last in the listed code before [before], past any moves and from a
 * move-result to its invoke, or -1 when nothing did and it holds what the method was given.
 */
private fun originAt(code: List<Instruction>, before: Int, register: Int): Int {
    var wanted = register
    for (at in before - 1 downTo 0) {
        val step = code[at]
        if (!step.opcode.setsRegister()) continue
        val written = (step as? OneRegisterInstruction)?.registerA ?: continue
        if (written != wanted && !(step.opcode.setsWideRegister() && written + 1 == wanted)) continue
        when (step.opcode) {
            Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16, Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16 ->
                wanted = (step as TwoRegisterInstruction).registerB
            Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_OBJECT, Opcode.MOVE_RESULT_WIDE -> return at - 1
            else -> return at
        }
    }
    return -1
}

/** The single register a call of the form with up to five registers reads first. */
private fun firstArgument(step: Instruction): Int =
    (step as? FiveRegisterInstruction)?.registerC ?: refuse("$step has no argument register")

/**
 * The viewer's opener: the one method handed a chat's key and the list of pages, which it stores the
 * key from. Its first Integer picks how it pages (0 an album it was given, 1 a chat's media it asks
 * the repository for, which would replace the list).
 */
private fun openerOf(viewer: ClassDef): Method = viewer.methods.singleOrNull { method ->
    !method.isStatic() && method.returnType == "V" && THREAD_KEY in method.types() && LIST in method.types() &&
        method.code().any { it.opcode == Opcode.IPUT_OBJECT && it.field()?.definingClass == MEDIA_VIEWER && it.field()?.type == THREAD_KEY }
} ?: refuse("$MEDIA_VIEWER has no one method taking a $THREAD_KEY and a $LIST and storing the key")

private fun Method.onlyParameter(type: String): Int =
    types().indices.singleOrNull { types()[it] == type } ?: refuse("$name takes no one $type: ${types()}")

/**
 * The viewer's opener writes the album mode to its mode field before anything else, then the mode
 * it was handed, so the first write says which value a walk has to hand it.
 */
private fun albumModeIn(open: Method, mode: Int): String {
    val code = open.code()
    val write = code.indexOfFirst { it.opcode == Opcode.IPUT_OBJECT && it.field()?.definingClass == MEDIA_VIEWER && it.field()?.type == INTEGER }
    if (write < 1) refuse("${open.name} writes no Integer field of the viewer")
    val value = code[write - 1]
    if (value.opcode != Opcode.SGET_OBJECT || value.field()?.type != INTEGER ||
        (value as OneRegisterInstruction).registerA != (code[write] as TwoRegisterInstruction).registerA
    ) refuse("${open.name} writes its mode field from something other than a constant")
    val register = open.parameterRegisterNumber(mode)
    val readsMode = code.take(write).any { step ->
        step.opcode == Opcode.MOVE_OBJECT_FROM16 && (step as TwoRegisterInstruction).registerB == register
    }
    if (!readsMode) refuse("${open.name} doesn't read its first Integer ahead of its mode field")
    return value.field()!!.text()
}

/** What the page builder's full call is given, read from the method Instagram taps go through. */
private class PageCall(
    val builder: String,
    val message: String,
    val item: String,
    val read: Method,
    val call: MethodReference,
    val kind: FieldReference,
    val wrap: MethodReference,
    val type: MethodReference,
    val time: MethodReference,
    val sender: FieldReference,
    val firstText: MethodReference,
    val secondText: MethodReference,
    val expiring: MethodReference,
    val flags: List<Int>,
)

/**
 * The page builder is the type the viewer holds with a public method taking a message and a Long and
 * calling its own builder of a page, with media as its fifth argument. A shared post gets a page by
 * that same call with the post's media, so what each argument is comes from this method's code.
 */
private fun BytecodePatchContext.pageCall(viewer: ClassDef): PageCall {
    val candidates = viewer.fields.map { it.type }.filter { it.startsWith("L") }.distinct()
        .mapNotNull { classDefByOrNull(it) }
        .flatMap { held ->
            held.methods.filter { method ->
                !method.isStatic() && AccessFlags.PUBLIC.isSet(method.accessFlags) && method.types().size == 2 &&
                    method.types()[1] == LONG && method.code().any { step -> isPageCall(step, held.type) }
            }.map { held to it }
        }
    val (holder, read) = candidates.singleOrNull()
        ?: refuse("$MEDIA_VIEWER holds no one type with a method building a page: ${candidates.map { it.second.text() }}")
    val code = read.code()
    val at = code.indexOfFirst { isPageCall(it, holder.type) }
    val call = code[at].called()!!
    val start = (code[at] as RegisterRangeInstruction).startRegister
    val message = read.types()[0]
    val types = call.parameterTypes.map(Any::toString)
    fun what(position: Int) = code.getOrNull(originAt(code, at, start + position))
    fun fieldAt(position: Int, wanted: String): FieldReference =
        what(position)?.field()?.takeIf { it.type == wanted } ?: refuse("${read.name}: argument $position isn't a field of type $wanted")
    fun callOn(position: Int, wanted: String): MethodReference =
        what(position)?.called()?.takeIf { it.definingClass == message && it.parameterTypes.isEmpty() && it.returnType == wanted }
            ?: refuse("${read.name}: argument $position isn't the message's own ()$wanted")
    fun literal(position: Int): Int =
        (what(position) as? NarrowLiteralInstruction)?.narrowLiteral ?: refuse("${read.name}: argument $position isn't a constant")

    for (position in listOf(1, 2, 3, 12, 13)) {
        if (literal(position) != 0) refuse("${read.name}: argument $position isn't null")
    }
    val typeAt = originAt(code, at, start + 6)
    val typeCall = code.getOrNull(typeAt)?.called()?.takeIf { it.parameterTypes.size == 1 && it.returnType == types[6] }
        ?: refuse("${read.name}: argument 6 isn't made from one value")
    val wrap = code.getOrNull(originAt(code, typeAt, firstArgument(code[typeAt])))?.called()
        ?.takeIf { it.parameterTypes.map(Any::toString) == listOf(MEDIA) }
        ?: refuse("${read.name}: argument 6 isn't read from the media")
    val boxAt = originAt(code, at, start + 7)
    code.getOrNull(boxAt)?.called()?.takeIf { it.definingClass == LONG && it.name == "valueOf" }
        ?: refuse("${read.name}: argument 7 isn't a boxed time")
    val time = code.getOrNull(originAt(code, boxAt, firstArgument(code[boxAt])))?.called()
        ?.takeIf { it.definingClass == message && it.parameterTypes.isEmpty() && it.returnType == "J" }
        ?: refuse("${read.name}: argument 7 isn't the message's own time")
    return PageCall(
        builder = holder.type,
        message = message,
        item = read.returnType,
        read = read,
        call = call,
        kind = fieldAt(5, types[5]),
        wrap = wrap,
        type = typeCall,
        time = time,
        sender = fieldAt(9, STRING),
        firstText = callOn(10, STRING),
        secondText = callOn(11, STRING),
        expiring = callOn(14, "Z"),
        flags = (FIRST_FIXED_FLAG until PAGE_ARGUMENTS).map { literal(it) },
    )
}

private fun isPageCall(step: Instruction, builder: String): Boolean {
    if (step.opcode != Opcode.INVOKE_STATIC_RANGE || (step as RegisterRangeInstruction).registerCount != PAGE_ARGUMENTS) return false
    val call = step.called() ?: return false
    return call.definingClass == builder && call.parameterTypes.size == PAGE_ARGUMENTS && call.parameterTypes[4].toString() == MEDIA
}

/** The attachment a current shared post holds, and Instagram's converter of one to media. */
private class SharedPost(val attachment: MethodReference, val convert: MethodReference, val previewId: FieldReference, val preview: FieldReference)

private fun BytecodePatchContext.sharedPost(message: String): SharedPost {
    val getters = classesLoadingString(ATTACHMENT_CAST).flatMap { it.methods }.filter { method ->
        method.isStatic() && method.types() == listOf(message) && method.returnType.startsWith("L") && method.returnType != STRING &&
            loadsString(method, ATTACHMENT_CAST)
    }
    val getter = getters.singleOrNull() ?: refuse("no one method reads a message's attachment: ${getters.map { it.text() }}")
    val attachment = getter.returnType
    val converters = classesCallingInto(MEDIA_CACHE).flatMap { it.methods }.filter { method ->
        method.isStatic() && method.returnType == MEDIA && method.types() == listOf(USER_SESSION, attachment)
    }
    val converter = converters.singleOrNull() ?: refuse("no one method makes media of an attachment: ${converters.map { it.text() }}")
    val code = converter.code()
    val previewId = code.firstOrNull { it.opcode == Opcode.IGET_WIDE && it.field()?.definingClass == attachment }?.field()
        ?: refuse("${converter.text()} reads no id from the attachment")
    val preview = code.firstOrNull { it.opcode == Opcode.IGET_OBJECT && it.field()?.definingClass == attachment && it.field()?.type == PREVIEW_IMAGE }?.field()
        ?: refuse("${converter.text()} reads no image from the attachment")
    return SharedPost(getter, converter, previewId, preview)
}

/** The store's reader of a chat's messages and how the opener gets the store for a session. */
private class Store(val type: String, val read: Method, val get: MethodReference)

private fun BytecodePatchContext.store(open: Method): Store {
    // Two public methods have this shape. One trims what the other returns, so the reader is the one that
    // doesn't call a sibling of its own shape. The string can sit in a helper of the reader, depending on the build.
    val readers = classesLoadingString(THREAD_ENTRY).filter { type -> type.methods.any { loadsString(it, THREAD_ENTRY) } }
        .flatMap { type ->
            val shaped = type.methods.filter { method ->
                !method.isStatic() && AccessFlags.PUBLIC.isSet(method.accessFlags) && method.returnType == LIST &&
                    method.types() == listOf(THREAD_KEY, "Z")
            }
            shaped.filter { method ->
                method.code().none { line ->
                    val called = (line as? ReferenceInstruction)?.reference as? MethodReference
                    called != null && shaped.any { it.text() == called.text() }
                }
            }
        }
    val read = readers.singleOrNull() ?: refuse("no one store method reads a chat's messages: ${readers.map { it.text() }}")
    val code = open.code()
    val gets = code.indices.filter { at ->
        code[at].opcode == Opcode.CHECK_CAST && ((code[at] as ReferenceInstruction).reference as? TypeReference)?.type == read.definingClass && at >= 2 &&
            code[at - 1].opcode == Opcode.MOVE_RESULT_OBJECT && code[at - 2].opcode == Opcode.INVOKE_STATIC &&
            code[at - 2].called()?.parameterTypes?.map(Any::toString) == listOf(USER_SESSION)
    }.map { code[it - 2].called()!! }.distinctBy { it.text() }
    val get = gets.singleOrNull() ?: refuse("${open.name} gets no one store from the session: ${gets.map { it.text() }}")
    return Store(read.definingClass, read, get)
}

/** Refuses a member a class outside Instagram's package can't reach, which would only fail on the phone. */
private fun BytecodePatchContext.requireClassReachable(type: String) {
    val owner = classDefByOrNull(type) ?: refuse("no class $type")
    if (!AccessFlags.PUBLIC.isSet(owner.accessFlags)) refuse("$type isn't public")
}

private fun BytecodePatchContext.requireReachable(field: FieldReference) {
    requireClassReachable(field.definingClass)
    val found = classDefBy(field.definingClass).fields.singleOrNull { it.name == field.name && it.type == field.type }
        ?: refuse("${field.text()} isn't on its class")
    if (!AccessFlags.PUBLIC.isSet(found.accessFlags)) refuse("${field.text()} isn't public")
}

private fun BytecodePatchContext.requireReachable(method: MethodReference) {
    requireClassReachable(method.definingClass)
    val found = classDefBy(method.definingClass).methods.singleOrNull {
        it.name == method.name && it.returnType == method.returnType && it.types() == method.parameterTypes.map(Any::toString)
    } ?: refuse("${method.text()} isn't on its class")
    if (!AccessFlags.PUBLIC.isSet(found.accessFlags)) refuse("${method.text()} isn't public")
}

internal fun BytecodePatchContext.findMediaWalk(): MediaWalkSite {
    val viewer = classDefByOrNull(MEDIA_VIEWER) ?: refuse("no $MEDIA_VIEWER")
    val pager = classDefByOrNull(REBOUND_VIEW_PAGER) ?: refuse("no $REBOUND_VIEW_PAGER")

    val open = openerOf(viewer)
    val modeAt = open.types().indexOf(INTEGER)
    if (modeAt < 0) refuse("${open.name} takes no Integer")
    val keyAt = open.onlyParameter(THREAD_KEY)
    val listAt = open.onlyParameter(LIST)
    val indexAt = open.onlyParameter("I")
    if (open.localRegisterCount() < 4) refuse("${open.name} has too few locals")
    if (0 in open.jumpTargets()) refuse("${open.name} branches to its own first instruction")
    val albumMode = albumModeIn(open, modeAt)

    val code = open.code()
    val pagerWrites = code.indices.filter { code[it].opcode == Opcode.IPUT_BOOLEAN && code[it].field()?.definingClass == REBOUND_VIEW_PAGER }
    val pagerWrite = pagerWrites.singleOrNull() ?: refuse("${open.name} sets no one flag on the pager: $pagerWrites")
    if (pagerWrite + 1 in open.jumpTargets()) refuse("${open.name} branches to right after it readies the pager")
    val pagerRegister = (code[pagerWrite] as TwoRegisterInstruction).registerB

    val page = pageCall(viewer)
    val shared = sharedPost(page.message)
    val store = store(open)

    val messageClass = classDefBy(page.message)
    val legacy = messageClass.methods.firstOrNull { method ->
        !method.isStatic() && method.returnType == MEDIA && method.parameterTypes.isEmpty() &&
            AccessFlags.PUBLIC.isSet(method.accessFlags) && method.code().any { it.opcode == Opcode.INSTANCE_OF }
    } ?: refuse("${page.message} has no reader of the media a message holds")

    val direction = pager.methods.singleOrNull { method ->
        method.name == "setScrollDirection" && method.returnType == "V" && method.parameterTypes.size == 1 && AccessFlags.PUBLIC.isSet(method.accessFlags)
    } ?: refuse("$REBOUND_VIEW_PAGER has no setScrollDirection")
    val directionType = direction.types().single()
    val directionOf = classDefByOrNull(directionType)?.methods?.singleOrNull { method ->
        method.isStatic() && method.name == "valueOf" && method.types() == listOf(STRING) && method.returnType == directionType
    } ?: refuse("$directionType has no valueOf(String)")
    requireClassReachable(directionType)
    if (!AccessFlags.PUBLIC.isSet(directionOf.accessFlags)) refuse("${directionOf.text()} isn't public")

    val drags = classesLoadingString(DRAG_NAN).flatMap { it.methods }.filter { loadsString(it, DRAG_NAN) }
    val drag = drags.singleOrNull() ?: refuse("no one method loads \"$DRAG_NAN\": ${drags.map { it.text() }}")
    val dragTypes = listOf(drag.types().firstOrNull() ?: refuse("${drag.text()} takes nothing"), "F", "F", "F")
    val hold = classDefBy(drag.definingClass).methods.singleOrNull { method ->
        !method.isStatic() && method.returnType == "Z" && method.types() == dragTypes
    } ?: refuse("${drag.definingClass} has no one method deciding on a drag")
    if (hold.localRegisterCount() < 1) refuse("${hold.text()} has no local")
    if (0 in hold.jumpTargets()) refuse("${hold.text()} branches to its own first instruction")

    val session = viewer.fields.singleOrNull { it.type == USER_SESSION && !AccessFlags.STATIC.isSet(it.accessFlags) }
        ?: refuse("$MEDIA_VIEWER holds no one $USER_SESSION")
    val activity = viewer.fields.singleOrNull { it.type == ACTIVITY && !AccessFlags.STATIC.isSet(it.accessFlags) }
        ?: refuse("$MEDIA_VIEWER holds no one $ACTIVITY")
    val builderOf = classDefBy(page.builder).methods.singleOrNull { method ->
        method.isStatic() && method.returnType == page.builder && method.types() == listOf("Landroid/content/Context;", USER_SESSION)
    } ?: refuse("${page.builder} has no one maker taking a context and a session")
    val itemMedia = classDefBy(page.item).fields.singleOrNull { it.type == MEDIA && !AccessFlags.STATIC.isSet(it.accessFlags) }
        ?: refuse("${page.item} holds no one media")
    val attachmentType = shared.attachment.returnType

    for (type in listOf(MEDIA_VIEWER, REBOUND_VIEW_PAGER, MEDIA, USER_SESSION, page.builder, page.message, page.item, attachmentType, store.type, directionType)) {
        requireClassReachable(type)
    }
    for (field in listOf(session, activity, page.kind, page.sender, shared.previewId, shared.preview, itemMedia)) requireReachable(field)
    for (method in listOf(
        builderOf, page.read, page.call, page.wrap, page.type, page.time, page.firstText, page.secondText, page.expiring,
        shared.attachment, shared.convert, store.read, store.get, legacy, direction,
    )) requireReachable(method)

    val declared = classDefBy(MEDIA_WALK).methods.map { it.name }.toSet()
    val stubs = stubs(page, shared, store, legacy, direction, directionOf, session, activity, builderOf, itemMedia)
    for (stub in stubs) if (stub.name !in declared) refuse("$MEDIA_WALK has no ${stub.name}")

    return MediaWalkSite(
        open = open,
        key = open.parameterRegister(keyAt),
        mode = open.parameterRegister(modeAt),
        list = open.parameterRegister(listAt),
        index = open.parameterRegister(indexAt),
        albumMode = albumMode,
        pagerWrite = pagerWrite,
        pagerRegister = pagerRegister,
        hold = hold,
        stubs = stubs,
    )
}

private fun stubs(
    page: PageCall,
    shared: SharedPost,
    store: Store,
    legacy: Method,
    direction: Method,
    directionOf: Method,
    session: FieldReference,
    activity: FieldReference,
    builderOf: Method,
    itemMedia: FieldReference,
): List<WalkStub> {
    val message = page.message
    val builder = page.builder
    val attachment = shared.attachment.returnType
    return listOf(
        WalkStub("session", 2, """
            check-cast p0, $MEDIA_VIEWER
            iget-object v0, p0, ${session.text()}
            return-object v0
        """),
        WalkStub("factory", 3, """
            check-cast p0, $MEDIA_VIEWER
            iget-object v0, p0, ${activity.text()}
            iget-object v1, p0, ${session.text()}
            invoke-static { v0, v1 }, ${builderOf.text()}
            move-result-object v0
            return-object v0
        """),
        WalkStub("messages", 4, """
            check-cast p0, $USER_SESSION
            invoke-static { p0 }, ${store.get.text()}
            move-result-object v0
            check-cast v0, ${store.type}
            check-cast p1, $THREAD_KEY
            const/4 v1, 0x0
            invoke-virtual { v0, p1, v1 }, ${store.read.text()}
            move-result-object v0
            return-object v0
        """),
        WalkStub("kind", 2, """
            check-cast p0, $message
            iget-object v0, p0, ${page.kind.text()}
            if-nez v0, :named
            const/4 v0, 0x0
            return-object v0
            :named
            invoke-virtual { v0 }, Ljava/lang/Enum;->name()$STRING
            move-result-object v0
            return-object v0
        """),
        WalkStub("time", 3, """
            check-cast p0, $message
            invoke-virtual { p0 }, ${page.time.text()}
            move-result-wide v0
            return-wide v0
        """),
        WalkStub("expiring", 2, """
            check-cast p0, $message
            invoke-virtual { p0 }, ${page.expiring.text()}
            move-result v0
            return v0
        """),
        WalkStub("plainItem", 3, """
            check-cast p0, $builder
            check-cast p1, $message
            const/4 v0, 0x0
            invoke-virtual { p0, p1, v0 }, ${page.read.text()}
            move-result-object v0
            return-object v0
        """),
        WalkStub("legacyMedia", 2, """
            check-cast p0, $message
            invoke-virtual { p0 }, ${legacy.text()}
            move-result-object v0
            return-object v0
        """),
        WalkStub("attachment", 2, """
            check-cast p0, $message
            invoke-static { p0 }, ${shared.attachment.text()}
            move-result-object v0
            return-object v0
        """),
        WalkStub("previewId", 3, """
            check-cast p0, $attachment
            iget-wide v0, p0, ${shared.previewId.text()}
            return-wide v0
        """),
        WalkStub("preview", 2, """
            check-cast p0, $attachment
            iget-object v0, p0, ${shared.preview.text()}
            return-object v0
        """),
        WalkStub("previewMedia", 3, """
            check-cast p0, $USER_SESSION
            check-cast p1, $attachment
            invoke-static { p0, p1 }, ${shared.convert.text()}
            move-result-object v0
            return-object v0
        """),
        // The same call the plain page makes, past its message's own media: no expiry, since a walk leaves those out.
        WalkStub("sharedItem", 25, """
            move-object/from16 v0, p1
            check-cast v0, $message
            move-object/from16 v2, p0
            check-cast v2, $builder
            const/4 v3, 0x0
            move-object v4, v3
            move-object v5, v3
            move-object/from16 v6, p2
            check-cast v6, $MEDIA
            iget-object v7, v0, ${page.kind.text()}
            invoke-static { v6 }, ${page.wrap.text()}
            move-result-object v8
            invoke-static { v8 }, ${page.type.text()}
            move-result-object v8
            invoke-virtual { v0 }, ${page.time.text()}
            move-result-wide v10
            invoke-static { v10, v11 }, Ljava/lang/Long;->valueOf(J)$LONG
            move-result-object v9
            move-object v10, v3
            iget-object v11, v0, ${page.sender.text()}
            invoke-virtual { v0 }, ${page.firstText.text()}
            move-result-object v12
            invoke-virtual { v0 }, ${page.secondText.text()}
            move-result-object v13
            move-object v14, v3
            move-object v15, v3
            const/16 v16, 0x0
            const/16 v17, ${page.flags[0]}
            const/16 v18, ${page.flags[1]}
            const/16 v19, ${page.flags[2]}
            const/16 v20, ${page.flags[3]}
            const/16 v21, ${page.flags[4]}
            invoke-static/range { v2 .. v21 }, ${page.call.text()}
            move-result-object v0
            return-object v0
        """),
        WalkStub("itemId", 2, """
            check-cast p0, ${page.item}
            iget-object v0, p0, ${itemMedia.text()}
            if-nez v0, :has
            const/4 v0, 0x0
            return-object v0
            :has
            invoke-virtual { v0 }, $MEDIA_ID
            move-result-object v0
            return-object v0
        """),
        WalkStub("scroll", 3, """
            check-cast p0, $REBOUND_VIEW_PAGER
            invoke-static { p1 }, ${directionOf.text()}
            move-result-object v0
            invoke-virtual { p0, v0 }, ${direction.text()}
            return-void
        """),
    )
}

/** [stub] with an empty body of [registers] registers, its parameters among them. */
private fun replaced(stub: Method, registers: Int): MutableMethod = ImmutableMethod(
    stub.definingClass, stub.name, stub.parameters, stub.returnType, stub.accessFlags, stub.annotations,
    stub.hiddenApiRestrictions, ImmutableMethodImplementation(registers, emptyList(), null, null),
).toMutable()

internal fun BytecodePatchContext.applyMediaWalk(site: MediaWalkSite) {
    val walk = mutableClassDefBy(MEDIA_WALK)
    for (stub in site.stubs) {
        val old = walk.methods.single { it.name == stub.name }
        walk.methods.remove(old)
        walk.methods.add(replaced(old, stub.registers).apply { addInstructions(0, stub.body) })
    }

    val hold = mutableClassDefBy(site.hold.definingClass).methods.single {
        it.name == site.hold.name && it.parameterTypes.map(Any::toString) == site.hold.types()
    }
    hold.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $WALK_HOLD
            move-result v0
            if-eqz v0, :instagram
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("instagram", hold.getInstruction(0)),
    )

    val open = mutableClassDefBy(site.open.definingClass).methods.single {
        it.name == site.open.name && it.parameterTypes.map(Any::toString) == site.open.types()
    }
    open.addInstructions(site.pagerWrite + 1, "invoke-static/range { v${site.pagerRegister} .. v${site.pagerRegister} }, $WALK_PAGER")
    open.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            move-object/from16 v1, ${site.key}
            move-object/from16 v2, ${site.list}
            move/from16 v3, ${site.index}
            invoke-static { v0, v1, v2, v3 }, $WALK_OPEN
            move-result-object v0
            if-eq v0, v2, :instagram
            move-object/from16 ${site.list}, v0
            invoke-static { }, $WALK_START
            move-result v0
            move/from16 ${site.index}, v0
            sget-object v0, ${site.albumMode}
            move-object/from16 ${site.mode}, v0
        """,
        ExternalLabel("instagram", open.getInstruction(0)),
    )
}

@Suppress("unused")
val chatMediaWalkPatch = bytecodePatch(
    name = "Swipe through a chat's photos and posts",
    description = "Open a photo or video from a chat and swipe up through the chat's other photos, videos and shared " +
        "posts, in chat order. Starts off. Turn it on in HushGram settings > Messages.",
) {
    category("Interaction")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("chatMediaWalk")
        applyMediaWalk(findMediaWalk())
        enableStatus("chatMediaWalk")
    }
}

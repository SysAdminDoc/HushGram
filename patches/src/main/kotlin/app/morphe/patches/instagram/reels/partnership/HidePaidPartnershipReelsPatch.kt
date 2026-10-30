/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.partnership

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.pandoGetter
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesAccessing
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.extension.typesMarked
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.reels.suggested.ClipsItemConverterFingerprint
import app.morphe.patches.instagram.reels.suggested.TO_CLIPS_ITEM
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

private const val PATCH = "Hide paid partnership Reels"
internal const val PAID_PARTNERSHIP_REELS = "$EXTENSION_PACKAGE/reels/PaidPartnershipReels;"
internal const val PARTNERSHIP_PAGE_FILTER =
    "$PAID_PARTNERSHIP_REELS->page(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;"

/** The Media field behind the Paid partnership label, which Instagram's Reels item reads to show it. */
internal const val PAID_PARTNERSHIP_FIELD = "is_paid_partnership"

/** The Media field holding where a promotion of the post stands, as Instagram's boost flows read it. */
internal const val BOOSTED_STATUS_FIELD = "boosted_status"

/**
 * The marker of the Reels viewer's network callback taking a page its fetcher asked for, the one
 * place every viewer's pages pass on their way in, whichever source made them.
 */
internal const val REELS_PAGE_ARRIVED = "ClipsItemsNetworkCallback_onSuccess"

/** The marker of the Reels viewer's fetcher asking for a page, where it makes that callback. */
internal const val REELS_FETCH = "AbstractPaginatingClipsItemsFetcher_maybeFetchInternal"

/** The analytics name every Instagram screen answers, the Reels viewer's built from where it was opened. */
private const val GET_MODULE_NAME = "getModuleName"

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val LIST = "Ljava/util/List;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"

/**
 * Leaves reels with a Paid partnership label, and ones Instagram marks as boosted, out of the
 * Reels tab. Reels the user went to see stay. In the default selection with its switch off:
 * what's sponsored is still one of Instagram's own reels, so leaving it out is the user's pick.
 * Asked for in #12.
 */
@Suppress("unused")
val hidePaidPartnershipReelsPatch = bytecodePatch(
    name = "Hide paid partnership Reels",
    description = "Leaves out reels with a Paid partnership label, and reels Instagram marks as boosted. Ads have " +
        "their own switch. Starts off. Turn it on in HushGram settings > Reels.",
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("paidPartnershipReels")
        val sites = findPaidPartnershipReels()
        hidePaidPartnershipReels(sites)
        enableStatus("paidPartnershipReels")
    }
}

private fun refuse(detail: String): Nothing = refuseFor(PATCH, detail)

private fun refuseFor(patch: String, detail: String): Nothing = throw PatchException("$patch: $detail")

/**
 * Where each page of the Reels viewer comes in: the viewer's network callback ([callback]) and its
 * [success] method, which takes the page source ([pageInterface]); the instruction [join] where the
 * page and its fallback meet in register [list]; the local [scratch] a hook borrows there; the
 * callback's [moduleField], the viewer's module name; and the Reels item type with its Media field,
 * which a filter's stub reads. Hide AI-labeled reels filters the same pages.
 */
internal open class ReelsPageSites(
    val callback: String,
    val success: String,
    val pageInterface: String,
    val join: Int,
    val list: Int,
    val scratch: Int,
    val moduleField: String,
    val itemType: String,
    val mediaField: String,
)

/** What this patch changes: the [ReelsPageSites], and the Media getters for [PAID_PARTNERSHIP_FIELD] and [BOOSTED_STATUS_FIELD]. */
internal class PaidPartnershipSites(
    page: ReelsPageSites,
    val partnershipGetter: Method,
    val boostedGetter: Method,
) : ReelsPageSites(
    page.callback, page.success, page.pageInterface, page.join, page.list, page.scratch, page.moduleField,
    page.itemType, page.mediaField,
)

/** Finds where the Reels viewer takes each page of items, with this patch's stubs. See [findReelsPage]. */
internal fun BytecodePatchContext.findPaidPartnershipReels(): PaidPartnershipSites {
    val page = findReelsPage(PATCH)
    val partnership = publicGetter(PAID_PARTNERSHIP_FIELD, "Ljava/lang/Boolean;")
    val boosted = publicGetter(BOOSTED_STATUS_FIELD, STRING)
    classDefByOrNull(PAID_PARTNERSHIP_REELS)?.let { extension ->
        if (extension.methods.none { it.isStaticStub("page", listOf(LIST, STRING), LIST) }) {
            refuse("the extension has no static page(List, String)List")
        }
        listOf("media" to OBJECT, "paidPartnership" to "Ljava/lang/Boolean;", "boostedStatus" to STRING)
            .filter { (stub, returns) -> extension.methods.none { it.isStaticStub(stub, listOf(OBJECT), returns) } }
            .forEach { (stub, returns) -> refuse("the extension has no static $stub(Object)$returns to fill") }
    } ?: refuse("the extension has no $PAID_PARTNERSHIP_REELS")
    return PaidPartnershipSites(page, partnership, boosted)
}

/**
 * Finds where the Reels viewer takes each page of items, and which viewer asked for it, for the
 * patch named [patch].
 *
 * Every source of Reels answers a page through one interface's method: the instance methods taking
 * the UserSession and answering a List that call the Reels item converter, by [TO_CLIPS_ITEM], or
 * a static helper that does. The response a source hands back doesn't say which screen asked for
 * it: one response class serves the Reels tab, a hashtag, an audio page, Saved and shared reels
 * alike. The viewer's network callback ([REELS_PAGE_ARRIVED]) does. It asks the source for its page,
 * and for a fallback list when the page is empty, and holds the module name its fetcher
 * ([REELS_FETCH]) gave it from the viewer's getModuleName, "clips_viewer_" and where the viewer was
 * opened. So the hook goes where the page and its fallback meet, and hands the extension the list
 * with that name.
 *
 * Fails before anything changes when any of it isn't there or isn't shaped that way, since that's
 * an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findReelsPage(patch: String): ReelsPageSites {
    fun refuse(detail: String): Nothing = refuseFor(patch, detail)
    val converter = uniqueMethod(patch, "Reels item converter holding \"$TO_CLIPS_ITEM\"", ClipsItemConverterFingerprint)
    val itemType = converter.returnType
    val item = classDefByOrNull(itemType) ?: refuse("the Reels item converter answers $itemType, which isn't a class of the app")
    if (!AccessFlags.PUBLIC.isSet(item.accessFlags)) refuse("$itemType isn't public, so the extension can't reach it")
    val mediaFields = item.fields.filter { it.type == MEDIA && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val mediaField = mediaFields.singleOrNull()
        ?: refuse("expected $itemType to keep its post in one Media field, found ${mediaFields.map { it.name }}")
    if (!AccessFlags.PUBLIC.isSet(mediaField.accessFlags)) refuse("$itemType's post field ${mediaField.name} isn't public")

    val pages = linkedSetOf<Method>()
    callersOf(converter).forEach { caller ->
        when {
            caller.isPage() -> pages += caller
            AccessFlags.STATIC.isSet(caller.accessFlags) && caller.returnType == "V" ->
                callersOf(caller).filter { it.isPage() }.forEach { pages += it }
        }
    }
    if (pages.isEmpty()) refuse("nothing answering a page of Reels items from a UserSession calls the Reels item converter")
    val names = pages.map { it.name }.distinct()
    val pageName = names.singleOrNull() ?: refuse("expected every page of Reels items to share one name, found $names")
    val declared = pages.map { declaringInterfaces(it) }.distinct()
    val pageInterface = declared.singleOrNull()?.singleOrNull()
        ?: refuse("expected every page of Reels items to be one interface's $pageName, found $declared")

    val success = markedMethods(REELS_PAGE_ARRIVED).singleOrNull()
        ?: refuse("expected one method marked $REELS_PAGE_ARRIVED, found ${markedMethods(REELS_PAGE_ARRIVED).map { it.describe() }}")
    if (AccessFlags.STATIC.isSet(success.accessFlags) || success.returnType != "V" ||
        success.parameterTypes.map(Any::toString) != listOf(pageInterface)
    ) {
        refuse("expected ${success.describe()} to take a $pageInterface and answer nothing")
    }
    val (join, list) = pageJoin(patch, success, pageInterface, pageName)
    success.requireThisIntact("$patch: the Reels page's callback", listOf(join))
    val scratch = success.freeLocalsAt("$patch: the Reels page's callback", join, 1, except = listOf(list)).single()
    val moduleField = moduleField(patch, success.definingClass)

    return ReelsPageSites(
        success.definingClass, success.name, pageInterface, join, list, scratch, moduleField, itemType, mediaField.name,
    )
}

/**
 * Where the page and its fallback meet in [success], and the register holding the list there. The
 * callback asks [pageInterface] for its [pageName] page, and when that's empty for the source's one
 * other (UserSession)List, into the same register; both are the only two such calls. The
 * instruction after the fallback's result has to be reached from nowhere but the fallback and a
 * branch between the two calls, past which nothing writes the list, so that register holds a List
 * from one of the two on every path to it.
 */
private fun pageJoin(patch: String, success: Method, pageInterface: String, pageName: String): Pair<Int, Int> {
    fun refuse(detail: String): Nothing = refuseFor(patch, detail)
    val where = success.describe()
    val flow = ControlFlow.of(success)
    val code = flow.instructions
    val calls = code.indices.filter { at ->
        val called = code[at].methodReference()
        code[at].opcode == Opcode.INVOKE_INTERFACE && called != null && called.definingClass == pageInterface &&
            called.returnType == LIST && called.parameterTypes.map(Any::toString) == listOf(USER_SESSION)
    }
    if (calls.size != 2 || code[calls[0]].methodReference()!!.name != pageName) {
        refuse("expected $where to ask for the $pageName page and then one other list, found ${calls.map { code[it].methodReference()!!.name }}")
    }
    val (page, fallback) = calls
    val results = listOf(page, fallback).map { call ->
        (code.getOrNull(call + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction)?.registerA
            ?: refuse("$where doesn't keep the list it asks for at instruction $call")
    }
    val list = results.distinct().singleOrNull()
        ?: refuse("$where keeps the page in v${results[0]} and its fallback in v${results[1]}")
    if (list > 15) refuse("$where keeps the page in v$list, past what the hook's call can name")
    val join = fallback + 2
    if (join >= code.size) refuse("$where ends at the fallback")

    val into = Array(code.size) { mutableListOf<Int>() }
    flow.normal.forEachIndexed { from, next -> next.forEach { into[it] += from } }
    val handlers = flow.exceptional.flatMap { it }.toSet()
    val branches = into[join].filter { it != fallback + 1 }
    if (fallback + 1 !in into[join] || branches.isEmpty() || branches.any { it <= page + 1 || it >= fallback }) {
        refuse("expected $where to reach instruction $join from its fallback and a check between the two, found ${into[join]}")
    }
    for (at in page + 2..join) {
        if (at in handlers || into[at].any { it < page + 1 || (at <= fallback + 1 && it >= at) }) {
            refuse("$where reaches instruction $at from outside the page's check")
        }
    }
    (page + 2..fallback).firstOrNull { at -> code[at].writes(list) }
        ?.let { refuse("$where writes over the page in v$list at instruction $it") }
    return join to list
}

/**
 * The [callback]'s String field holding the viewer's module name. The fetcher ([REELS_FETCH]) makes
 * the callback with what the viewer's getModuleName answered, read straight before; the callback's
 * one constructor keeps that parameter in one field, and nothing else writes it.
 */
private fun BytecodePatchContext.moduleField(patch: String, callback: String): String {
    fun refuse(detail: String): Nothing = refuseFor(patch, detail)
    val fetch = markedMethods(REELS_FETCH).singleOrNull()
        ?: refuse("expected one method marked $REELS_FETCH, found ${markedMethods(REELS_FETCH).map { it.describe() }}")
    val code = fetch.implementation?.instructions?.toList().orEmpty()
    val targets = fetch.jumpTargets()
    val made = code.indices.filter { at ->
        val called = code[at].methodReference()
        called != null && called.definingClass == callback && called.name == "<init>" &&
            (code[at].opcode == Opcode.INVOKE_DIRECT || code[at].opcode == Opcode.INVOKE_DIRECT_RANGE)
    }
    if (made.isEmpty()) refuse("${fetch.describe()} doesn't make $callback")
    val slots = made.map { at ->
        val constructor = code[at].methodReference()!!
        val registers = code[at].registers()
        val parameters = constructor.parameterTypes.map(Any::toString)
        val named = parameters.indices.filter { index ->
            parameters[index] == STRING &&
                answeredBy(code, targets, at, registers[1 + parameters.take(index).sumOf { it.width() }]) == GET_MODULE_NAME
        }
        val slot = named.singleOrNull()
            ?: refuse("expected ${fetch.describe()} to make $callback with one module name at instruction $at, found $named")
        parameters to slot
    }.distinct()
    val (parameters, slot) = slots.singleOrNull()
        ?: refuse("${fetch.describe()} makes $callback in more than one way: $slots")

    val callbackClass = classDefBy(callback)
    val constructor = callbackClass.methods.singleOrNull { it.name == "<init>" && it.parameterTypes.map(Any::toString) == parameters }
        ?: refuse("$callback has no constructor taking $parameters")
    val held = constructor.parameterRegisterNumber(slot)
    val body = constructor.implementation?.instructions?.toList().orEmpty()
    val kept = body.indices.filter { at ->
        val field = body[at].fieldReference()
        body[at].opcode == Opcode.IPUT_OBJECT && (body[at] as TwoRegisterInstruction).registerA == held &&
            field != null && field.definingClass == callback && field.type == STRING
    }
    val at = kept.singleOrNull()
        ?: refuse("expected $callback's constructor to keep its module name in one field, found ${kept.size}")
    constructor.requireParameterIntact("$patch: the Reels page's callback", slot, listOf(at))
    val field = body[at].fieldReference()!!.name
    val writers = callbackClass.methods.filter { method ->
        method.implementation?.instructions?.any { instruction ->
            val written = instruction.fieldReference()
            instruction.opcode == Opcode.IPUT_OBJECT && written != null && written.definingClass == callback && written.name == field
        } == true
    }
    val outside = classesAccessing(callback, field, Opcode.IPUT_OBJECT).map { it.type }.filter { it != callback }
    if (writers.size != 1 || outside.isNotEmpty()) {
        refuse("$callback->$field is written by ${writers.map { it.describe() } + outside}, not only its constructor")
    }
    return field
}

/**
 * The name of the method whose answer [register] holds at instruction [at], read by a
 * move-result-object of a call taking nothing and answering a String, along straight code with no
 * jump into it; null for anything else.
 */
private fun answeredBy(code: List<Instruction>, targets: Set<Int>, at: Int, register: Int): String? {
    for (index in at - 1 downTo 1) {
        if (code[index].writes(register)) {
            if (code[index].opcode != Opcode.MOVE_RESULT_OBJECT) return null
            val called = code[index - 1].methodReference() ?: return null
            return called.name.takeIf { called.parameterTypes.isEmpty() && called.returnType == STRING }
        }
        if (index in targets) return null
    }
    return null
}

/**
 * Fills the extension's three stubs, the item's post and the post's two fields, then hands the
 * list the callback holds where the page and its fallback meet to [PARTNERSHIP_PAGE_FILTER], with
 * the callback's module name, at that instruction's own label so the branch from the page's check
 * passes through it too. The filter answers the list it was handed unless it leaves something out,
 * and a List either way, so nothing is cast.
 */
internal fun BytecodePatchContext.hidePaidPartnershipReels(sites: PaidPartnershipSites) {
    fillReelsItemMedia(PAID_PARTNERSHIP_REELS, sites)
    fillStub(PAID_PARTNERSHIP_REELS, "paidPartnership", "Ljava/lang/Boolean;", 1, getterBody(sites.partnershipGetter))
    fillStub(PAID_PARTNERSHIP_REELS, "boostedStatus", STRING, 1, getterBody(sites.boostedGetter))
    filterReelsPage(sites, PARTNERSHIP_PAGE_FILTER)
}

/** Replaces the body of [extension]'s static [name](Object) answering [returns] with [body], in [registers] registers. */
internal fun BytecodePatchContext.fillStub(extension: String, name: String, returns: String, registers: Int, body: String) {
    val methods = mutableClassDefBy(extension).methods
    val stub = methods.single { it.isStaticStub(name, listOf(OBJECT), returns) }
    methods.remove(stub)
    methods.add(replaced(stub, registers, body))
}

/** Fills [extension]'s media(Object)Object stub, which reads a Reels item's post. */
internal fun BytecodePatchContext.fillReelsItemMedia(extension: String, sites: ReelsPageSites) {
    // Answers an Object: null for anything that isn't a Reels item, else the item's post, which can be null.
    fillStub(
        extension, "media", OBJECT, 2,
        """
            instance-of v0, p0, ${sites.itemType}
            if-nez v0, :item
            const/4 v0, 0x0
            return-object v0
            :item
            check-cast p0, ${sites.itemType}
            iget-object v0, p0, ${sites.itemType}->${sites.mediaField}:$MEDIA
            return-object v0
        """,
    )
}

/**
 * Hands the list the callback holds where the page and its fallback meet to [filter], a static
 * (List, String)List, with the callback's module name, at that instruction's own label so the
 * branch from the page's check passes through it too. Another patch's filter put there before
 * runs after this one.
 */
internal fun BytecodePatchContext.filterReelsPage(sites: ReelsPageSites, filter: String) {
    val success = mutableClassDefBy(sites.callback).methods.single {
        it.name == sites.success && it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf(sites.pageInterface)
    }
    val self = success.localRegisterCount()
    success.addInstructionsAtControlFlowLabel(
        sites.join,
        """
            move-object/from16 v${sites.scratch}, v$self
            iget-object v${sites.scratch}, v${sites.scratch}, ${sites.callback}->${sites.moduleField}:$STRING
            invoke-static { v${sites.list}, v${sites.scratch} }, $filter
            move-result-object v${sites.list}
        """,
    )
}

/** A stub's body that hands its argument, a Media, to [getter] and answers what it reads. */
private fun getterBody(getter: Method) = """
    check-cast p0, $MEDIA
    invoke-virtual { p0 }, $MEDIA->${getter.name}()${getter.returnType}
    move-result-object p0
    return-object p0
"""

/** Media's getter for [field], which the extension calls, so it and Media have to be public. */
private fun BytecodePatchContext.publicGetter(field: String, returns: String): Method {
    val getter = pandoGetter(PATCH, MEDIA, field, returns)
    if (!AccessFlags.PUBLIC.isSet(classDefBy(MEDIA).accessFlags) || !AccessFlags.PUBLIC.isSet(getter.accessFlags)) {
        refuse("$MEDIA->${getter.name} isn't public, so the extension can't reach it")
    }
    return getter
}

/** The app's methods carrying the marker [name], in a stable order. */
private fun BytecodePatchContext.markedMethods(name: String): List<Method> =
    typesMarked(name).sorted().flatMap { type -> classDefBy(type).methods.filter { name in it.markers() } }

/** The methods of the app that call [target] by its class, name and parameters. */
private fun BytecodePatchContext.callersOf(target: Method): List<Method> {
    val parameters = target.parameterTypes.map(Any::toString)
    return classesCalling(target.definingClass, target.name).flatMap { classDef ->
        classDef.methods.filter { method ->
            method.implementation?.instructions?.any { instruction ->
                val called = instruction.methodReference()
                called != null && called.definingClass == target.definingClass && called.name == target.name &&
                    called.parameterTypes.map(Any::toString) == parameters && called.returnType == target.returnType
            } == true
        }
    }
}

/** An instance method with a body, taking the UserSession and answering a List. */
private fun Method.isPage() = implementation != null && !AccessFlags.STATIC.isSet(accessFlags) &&
    !AccessFlags.ABSTRACT.isSet(accessFlags) && returnType == LIST &&
    parameterTypes.map(Any::toString) == listOf(USER_SESSION)

internal fun Method.isStaticStub(name: String, parameters: List<String>, returns: String) =
    this.name == name && AccessFlags.STATIC.isSet(accessFlags) && returnType == returns &&
        parameterTypes.map(Any::toString) == parameters

private fun Method.describe() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Instruction.methodReference() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.fieldReference() = (this as? ReferenceInstruction)?.reference as? FieldReference

/** Whether this instruction writes [register], a wide write's second half included. */
private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return destination == register || (opcode.setsWideRegister() && destination + 1 == register)
}

/** The registers an invoke passes, the receiver first. */
private fun Instruction.registers(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

/** How many registers a parameter of this type takes. */
private fun String.width() = if (this == "J" || this == "D") 2 else 1

/** The app's interfaces, over [method]'s class and its superclasses, that declare [method]. */
private fun BytecodePatchContext.declaringInterfaces(method: Method): Set<String> {
    val found = sortedSetOf<String>()
    val parameters = method.parameterTypes.map(Any::toString)
    val seen = mutableSetOf<String>()
    fun visit(type: String) {
        if (!seen.add(type)) return
        val declaring = classDefByOrNull(type) ?: return
        if (declaring.methods.any { it.name == method.name && it.returnType == method.returnType && it.parameterTypes.map(Any::toString) == parameters }) {
            found += type
        }
        declaring.interfaces.forEach(::visit)
    }
    var holder = classDefByOrNull(method.definingClass)
    while (holder != null) {
        holder.interfaces.forEach(::visit)
        holder = holder.superclass?.let { classDefByOrNull(it) }
    }
    return found
}

/** [stub] with its body replaced by [body], in [registers] registers. */
private fun replaced(stub: Method, registers: Int, body: String): MutableMethod =
    ImmutableMethod(
        stub.definingClass, stub.name, stub.parameters, stub.returnType, stub.accessFlags, null,
        stub.hiddenApiRestrictions, ImmutableMethodImplementation(registers, emptyList(), null, null),
    ).toMutable().apply { addInstructionsWithLabels(0, body) }

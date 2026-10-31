/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCallingInto
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.patchLog
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
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
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

/** The extension class that asks Home for its next page after a short one (#52). */
internal const val HOME_NEXT_PAGE = "$EXTENSION_PACKAGE/feed/HomeNextPage;"

/** First thing in Home's list builder, with the adapter. */
internal const val HOME_BUILD_STARTS = "$HOME_NEXT_PAGE->buildStarts(Ljava/lang/Object;)V"

/**
 * Held by the method Instagram calls as the scroll brings Home's loading row into view, which asks
 * Home's load more policy for the next page through a (Map)V method of the policy's own.
 */
internal const val VISIBLE_SPINNER = "triggered_by_visible_spinner"

/**
 * Held by the adapter's methods that read Home's list for its most visible post, through a host
 * the adapter keeps in a field: the way the extension reaches Home's list too (#28).
 */
internal const val MOST_VISIBLE_GONE = "getMostVisibleMediaIndex called after fragment is destroyed"

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val MAP = "Ljava/util/Map;"
private const val VIEW_GROUP = "Landroid/view/ViewGroup;"

/**
 * The way from Home's adapter to the view its list draws into: the adapter's [host] field, the
 * host's call for the list ([list], made with [listInvoke]), and the list's call for its view
 * ([view], made with [viewInvoke]).
 */
internal class HomeListPath(
    val host: FieldReference,
    val list: MethodReference,
    val listInvoke: String,
    val view: MethodReference,
    val viewInvoke: String,
)

/**
 * What asking Home for its next page needs, found before anything changes: Home's list builder,
 * the adapter's field holding its load more policy (a lazy value, read through [lazyValue]), the
 * policy's call for the next page ([ask], the one Instagram makes when the loading row comes into
 * view), its "a page is loading" and "there's a next page" questions, the feed's paging source
 * field, the way to Home's list view where a build shows one ([list]), and the extension's stubs
 * that reach them.
 */
internal class HomeNextPageTargets(
    val adapter: String,
    val builder: String,
    val builderParameters: List<String>,
    val adapterRegister: String,
    val policyField: FieldReference,
    val lazyValue: MethodReference,
    val policy: String,
    val ask: MethodReference,
    val loading: MethodReference,
    val more: MethodReference,
    val source: FieldReference,
    val list: HomeListPath?,
    private val stubs: Map<String, MutableMethod>,
) {
    /**
     * Fills the stubs, then hands Home's list builder to [HOME_BUILD_STARTS] first thing. No return
     * merges two types, and each stub uses its own parameters only. listOf stays as built, answering
     * null, without a [list].
     */
    fun write(context: BytecodePatchContext) {
        list?.let { path ->
            // Answers an Object: the list's view, or the null list itself.
            stubs.getValue("listOf").addInstructionsWithLabels(
                0,
                """
                    check-cast p0, $adapter
                    iget-object p0, p0, ${path.host}
                    ${path.listInvoke} { p0 }, ${path.list}
                    move-result-object p0
                    if-nez p0, :list
                    return-object p0
                    :list
                    ${path.viewInvoke} { p0 }, ${path.view}
                    move-result-object p0
                    return-object p0
                """,
            )
        }
        // Answers an Object: the policy, from the adapter's lazy field.
        stubs.getValue("policyOf").addInstructions(
            0,
            """
                check-cast p0, $adapter
                iget-object p0, p0, $policyField
                invoke-interface { p0 }, $lazyValue
                move-result-object p0
                return-object p0
            """,
        )
        stubs.getValue("sourceOf").addInstructions(
            0,
            """
                check-cast p0, ${source.definingClass}
                iget-object p0, p0, $source
                return-object p0
            """,
        )
        for ((stub, question) in listOf("loadingNow" to loading, "moreLeft" to more)) {
            stubs.getValue(stub).addInstructions(
                0,
                """
                    check-cast p0, $policy
                    invoke-virtual { p0 }, $question
                    move-result p0
                    return p0
                """,
            )
        }
        stubs.getValue("askNextPage").addInstructions(
            0,
            """
                check-cast p0, $policy
                check-cast p1, $MAP
                invoke-virtual { p0, p1 }, $ask
                return-void
            """,
        )
        context.mutableClassDefBy(adapter).methods
            .single { it.name == builder && it.parameterTypes.map(CharSequence::toString) == builderParameters }
            .addInstructions(0, "invoke-static/range { $adapterRegister .. $adapterRegister }, $HOME_BUILD_STARTS")
    }
}

/**
 * Finds [HomeNextPageTargets], refusing anything it can't pin down to one:
 *
 * - Home's list builder, the one method holding [BUILD_MODELS] and [SHIMMER_KEY], and the feed it
 *   reads its "no next page" flag from (the last boolean field it reads before [SHIMMER_KEY]), the
 *   object FeedSuggestions.homeFeedRead hands over.
 * - The load more policy, [findLoadMoreRow]'s class: its "there's a next page" question is the one
 *   isLoading() and the show check share, as [endFollowingAtItsCard] finds it.
 * - Its call for the next page: the (Map)V method of the policy that the method holding
 *   [VISIBLE_SPINNER] calls. Its "a page is loading" question is the one that call and isLoading()
 *   both ask, other than the next page question.
 * - The adapter's field holding the policy: a lazy field of the adapter whose value Instagram casts
 *   to the policy, or an interface of it, right after reading it.
 * - The feed's paging source: the String field of the feed the show check compares with Following's.
 *
 * The way to Home's list view ([findHomeList]) is the one part it does without: a build that
 * doesn't show it warns and keeps the rest.
 *
 * Instagram 450's builder was an instance method of the adapter. 451's (385713073's LX/05cW->A05) is
 * static and takes the adapter as one of its parameters, the one of the adapter's type, which is what
 * gets handed over then. Everything the extension reaches has to be public.
 */
internal fun BytecodePatchContext.findHomeNextPage(): HomeNextPageTargets {
    val builders = feedBuilders()
    val (adapterClass, builder, loadingRow) = builders.singleOrNull()
        ?: refuse("expected one method holding $BUILD_MODELS and $SHIMMER_KEY, found ${builders.size}")
    val adapter = adapterClass.type
    val adapterRegister = if (!AccessFlags.STATIC.isSet(builder.accessFlags)) "p0" else {
        val taken = builder.parameterTypes.indices.filter { builder.parameterTypes[it].toString() == adapter }
        builder.parameterRegister(
            taken.singleOrNull() ?: refuse("$adapter->${builder.name} is static and takes ${taken.size} adapters, not one to hand over"),
        )
    }
    val feed = builderFeed(loadingRow)

    val (policyClass, shows) = findLoadMoreRow()
    val policy = policyClass.type
    val isLoading = policyClass.methods.singleOrNull { it.name == "isLoading" && it.returnType == "Z" && it.parameterTypes.isEmpty() }
        ?: refuse("$policy has no isLoading()")
    val more = (isLoading.ownQuestions(policy) intersect shows.ownQuestions(policy)).singleOrNull()
        ?: refuse("$policy->isLoading and ${shows.name} don't share one question")

    val ask = visibleSpinnerAsk(policy)
    val asking = policyClass.methods.singleOrNull {
        it.name == ask.name && it.parameterTypes.map(CharSequence::toString) == listOf(MAP) && it.returnType == "V"
    } ?: refuse("$policy declares no $ask")
    val loading = ((asking.ownQuestions(policy) intersect isLoading.ownQuestions(policy)) - more).singleOrNull()
        ?: refuse("$ask and $policy->isLoading don't share one question besides $more")

    val (field, lazyValue) = adapterPolicyField(adapter, setOf(policy) + policyClass.interfaces)
    val source = feedSource(shows, feed)

    for (type in listOf(adapter, policy, field.type, feed)) {
        val flags = classDefByOrNull(type)?.accessFlags
        if (flags == null || !AccessFlags.PUBLIC.isSet(flags)) refuse("$type isn't public, so the extension can't reach it")
    }
    for ((owner, reference) in listOf(adapterClass to field, classDefBy(feed) to source)) {
        val declared = owner.fields.singleOrNull { it.name == reference.name && it.type == reference.type }
        if (declared == null || !AccessFlags.PUBLIC.isSet(declared.accessFlags) || AccessFlags.STATIC.isSet(declared.accessFlags)) {
            refuse("$reference isn't a public instance field of ${owner.type}")
        }
    }
    val questions = listOf(loading, more).map { ImmutableMethodReference(policy, it, emptyList(), "Z") }
    for (called in listOf(ask) + questions) policyClass.requirePublic(called)

    // The stubs last, so a build missing any of them refuses before anything is written.
    val stubs = mapOf(
        "policyOf" to stub("policyOf", listOf(OBJECT), OBJECT),
        "sourceOf" to stub("sourceOf", listOf(OBJECT), STRING),
        "loadingNow" to stub("loadingNow", listOf(OBJECT), "I"),
        "moreLeft" to stub("moreLeft", listOf(OBJECT), "I"),
        "askNextPage" to stub("askNextPage", listOf(OBJECT, OBJECT), "V"),
        "listOf" to stub("listOf", listOf(OBJECT), OBJECT),
    )
    return HomeNextPageTargets(
        adapter = adapter,
        builder = builder.name,
        builderParameters = builder.parameterTypes.map(CharSequence::toString),
        adapterRegister = adapterRegister,
        policyField = field,
        lazyValue = lazyValue,
        policy = policy,
        ask = ask,
        loading = questions[0],
        more = questions[1],
        source = source,
        list = findHomeList(adapterClass),
        stubs = stubs,
    )
}

/**
 * The way from Home's adapter to its list view, or null with a warning where a build doesn't show
 * one, and chains of next pages stop at their cap as before. The adapter's methods holding
 * [MOST_VISIBLE_GONE] read a field of the adapter, the host, and ask it right away for the list
 * with a call of the host's own that takes nothing. The list's class declares one instance method
 * taking nothing and answering a ViewGroup, the view the list draws into. One such path between
 * them, every class and member on it public, or there's none.
 */
internal fun BytecodePatchContext.findHomeList(adapterClass: ClassDef): HomeListPath? {
    fun without(detail: String): HomeListPath? {
        patchLog.warning("Home next page: $detail. Home stops asking for short pages at its cap, even at the end of its list.")
        return null
    }
    val readers = adapterClass.methods.filter { it.holdsString(MOST_VISIBLE_GONE) }
    if (readers.isEmpty()) return without("${adapterClass.type} holds no \"$MOST_VISIBLE_GONE\"")
    val reads = readers.flatMap { method ->
        val code = method.implementation!!.instructions.toList()
        code.indices.mapNotNull { at -> hostListRead(code, at, adapterClass.type) }
    }.distinctBy { (field, call, _) -> "$field $call" }
    val paths = reads.mapNotNull { (field, call, opcode) ->
        val listClass = classDefByOrNull(call.returnType) ?: return@mapNotNull null
        val view = listClass.methods.singleOrNull {
            it.parameterTypes.isEmpty() && it.returnType == VIEW_GROUP && !AccessFlags.STATIC.isSet(it.accessFlags)
        } ?: return@mapNotNull null
        val listInvoke = if (opcode == Opcode.INVOKE_INTERFACE) "invoke-interface" else "invoke-virtual"
        val viewInvoke = if (AccessFlags.INTERFACE.isSet(listClass.accessFlags)) "invoke-interface" else "invoke-virtual"
        HomeListPath(field, call, listInvoke, ImmutableMethodReference(listClass.type, view.name, emptyList(), VIEW_GROUP), viewInvoke)
    }
    val path = paths.singleOrNull()
        ?: return without("expected one way from ${adapterClass.type} to Home's list view, found ${paths.map { it.host.name }}")

    val declared = adapterClass.fields.singleOrNull { it.name == path.host.name && it.type == path.host.type }
    if (declared == null || !AccessFlags.PUBLIC.isSet(declared.accessFlags) || AccessFlags.STATIC.isSet(declared.accessFlags)) {
        return without("${path.host} isn't a public instance field of ${adapterClass.type}")
    }
    for (called in listOf(path.list, path.view)) {
        val owner = classDefByOrNull(called.definingClass)
        if (owner == null || !AccessFlags.PUBLIC.isSet(owner.accessFlags) || !owner.declaresPublic(called)) {
            return without("$called isn't a public instance method of a public class")
        }
    }
    return path
}

/**
 * The field read at [at], the call made of its value next, and that call's opcode, when the field
 * is [adapter]'s and the call is the field type's own, takes nothing and answers an object.
 */
private fun hostListRead(code: List<Instruction>, at: Int, adapter: String): Triple<FieldReference, MethodReference, Opcode>? {
    if (code[at].opcode != Opcode.IGET_OBJECT) return null
    val field = (code[at] as ReferenceInstruction).reference as FieldReference
    if (field.definingClass != adapter) return null
    val call = code.getOrNull(at + 1) ?: return null
    if (call.opcode != Opcode.INVOKE_INTERFACE && call.opcode != Opcode.INVOKE_VIRTUAL) return null
    val called = (call as ReferenceInstruction).reference as MethodReference
    if (called.definingClass != field.type || called.parameterTypes.isNotEmpty() || !called.returnType.startsWith("L")) return null
    if ((call as FiveRegisterInstruction).registerC != (code[at] as TwoRegisterInstruction).registerA) return null
    return Triple(field, called, call.opcode)
}

/**
 * [findHomeNextPage], or a warning and Instagram's own paging where a build doesn't have what it
 * needs. Found before anything else in the patch changes.
 */
internal fun BytecodePatchContext.homeNextPageOrWarn(): HomeNextPageTargets? = try {
    findHomeNextPage()
} catch (refused: PatchException) {
    patchLog.warning("${refused.message}. Home waits for the scroll after a page the switches left short.")
    null
}

/** The type of the feed [builder] reads its flag from: the last boolean field it reads before [SHIMMER_KEY]. */
private fun builderFeed(builder: Method): String {
    val code = builder.implementation!!.instructions.toList()
    val shimmer = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string?.isShimmerKey() == true }
    val read = (shimmer - 1 downTo 0).firstOrNull { code[it].opcode == Opcode.IGET_BOOLEAN }
        ?: refuse("${builder.definingClass}->${builder.name} reads no flag before its loading row")
    return ((code[read] as ReferenceInstruction).reference as FieldReference).definingClass
}

/**
 * The (Map)V method of [policy] called by the methods loading [VISIBLE_SPINNER], held or asked of a
 * string pool. One such method between them, or the build is refused.
 */
private fun BytecodePatchContext.visibleSpinnerAsk(policy: String): MethodReference {
    val calls = classesLoadingString(VISIBLE_SPINNER).flatMap { classDef ->
        classDef.methods.filter { loadsString(it, VISIBLE_SPINNER) }.flatMap { method ->
            method.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
                ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
                    instruction.opcode == Opcode.INVOKE_VIRTUAL && it.definingClass == policy &&
                        it.parameterTypes.map(CharSequence::toString) == listOf(MAP) && it.returnType == "V"
                }
            }
        }
    }.distinctBy { it.toString() }
    return calls.singleOrNull()
        ?: refuse("expected one (Map)V call on $policy where $VISIBLE_SPINNER is loaded, found ${calls.map { it.name }}")
}

/**
 * The lazy field of [adapter] holding the load more policy, and the lazy's getValue(): the field
 * read, getValue() asked of it, and the value cast to one of [policyTypes] right away. Searched in
 * the classes calling into the policy or its interfaces, where Instagram asks Home's policy for its
 * next page. One field between them, or the build is refused.
 */
private fun BytecodePatchContext.adapterPolicyField(adapter: String, policyTypes: Set<String>): Pair<FieldReference, MethodReference> {
    val found = policyTypes.flatMap { classesCallingInto(it) }.distinctBy { it.type }.flatMap { classDef ->
        classDef.methods.flatMap { method ->
            val code = method.implementation?.instructions?.toList().orEmpty()
            code.indices.mapNotNull { at -> lazyPolicyRead(code, at, adapter, policyTypes) }
        }
    }.distinctBy { (field, getter) -> "$field $getter" }
    return found.singleOrNull()
        ?: refuse("expected one field of $adapter whose value is cast to its load more policy, found ${found.map { it.first.name }}")
}

/**
 * The field read at [at] and the getValue() asked of it next, when the field is [adapter]'s and the
 * value is cast to one of [policyTypes] right after.
 */
private fun lazyPolicyRead(code: List<Instruction>, at: Int, adapter: String, policyTypes: Set<String>): Pair<FieldReference, MethodReference>? {
    if (code[at].opcode != Opcode.IGET_OBJECT) return null
    val field = (code[at] as ReferenceInstruction).reference as FieldReference
    if (field.definingClass != adapter) return null
    val get = code.getOrNull(at + 1) ?: return null
    val getter = (get as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    if (get.opcode != Opcode.INVOKE_INTERFACE || getter.name != "getValue" || getter.parameterTypes.isNotEmpty() ||
        getter.returnType != OBJECT || getter.definingClass != field.type ||
        (get as FiveRegisterInstruction).registerC != (code[at] as TwoRegisterInstruction).registerA
    ) {
        return null
    }
    val result = code.getOrNull(at + 2) ?: return null
    val cast = code.getOrNull(at + 3) ?: return null
    if (result.opcode != Opcode.MOVE_RESULT_OBJECT || cast.opcode != Opcode.CHECK_CAST) return null
    if ((cast as OneRegisterInstruction).registerA != (result as OneRegisterInstruction).registerA) return null
    if (((cast as ReferenceInstruction).reference as TypeReference).type !in policyTypes) return null
    return field to getter
}

/**
 * The paging source field of [feed]: the String field [shows] reads into the register it compares
 * with [FOLLOWING_FEED], read last before the comparison loads that name.
 */
private fun feedSource(shows: Method, feed: String): FieldReference {
    val code = shows.implementation!!.instructions.toList()
    val where = "${shows.definingClass}->${shows.name}"
    val named = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == FOLLOWING_FEED }
    if (named < 0) refuse("$where doesn't load $FOLLOWING_FEED")
    val name = (code[named] as OneRegisterInstruction).registerA
    val equals = (named + 1 until code.size).firstOrNull {
        ((code[it] as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == "Ljava/lang/String;->equals(Ljava/lang/Object;)Z"
    } ?: refuse("$where doesn't compare $FOLLOWING_FEED")
    val compared = (code[equals] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) }
    val register = compared.singleOrNull { it != name } ?: refuse("$where compares $FOLLOWING_FEED with itself")
    val read = (named - 1 downTo 0).firstOrNull { (code[it] as? OneRegisterInstruction)?.registerA == register }
        ?.takeIf { code[it].opcode == Opcode.IGET_OBJECT }
        ?: refuse("$where doesn't read the source it compares from a field")
    val field = (code[read] as ReferenceInstruction).reference as FieldReference
    if (field.type != STRING || field.definingClass != feed) refuse("$where compares $field, not a String field of $feed")
    return field
}

/** Refuses unless this class declares [called] as a public instance method, so the extension can call it. */
private fun ClassDef.requirePublic(called: MethodReference) {
    if (!declaresPublic(called)) refuse("$called isn't a public instance method of $type")
}

/** Whether this class declares [called] as a public instance method. */
private fun ClassDef.declaresPublic(called: MethodReference): Boolean {
    val declared = methods.singleOrNull {
        it.name == called.name && it.returnType == called.returnType &&
            it.parameterTypes.map(CharSequence::toString) == called.parameterTypes.map(CharSequence::toString)
    }
    return declared != null && AccessFlags.PUBLIC.isSet(declared.accessFlags) && !AccessFlags.STATIC.isSet(declared.accessFlags)
}

/** The extension's static stub [name] on [HOME_NEXT_PAGE], taking [parameters] and answering [returns]. */
private fun BytecodePatchContext.stub(name: String, parameters: List<String>, returns: String): MutableMethod =
    mutableClassDefBy(HOME_NEXT_PAGE).methods.singleOrNull {
        it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == returns &&
            it.parameterTypes.map(CharSequence::toString) == parameters
    } ?: refuse("$HOME_NEXT_PAGE has no static $name(${parameters.joinToString("")})$returns")

private fun Method.holdsString(string: String): Boolean = implementation?.instructions?.any {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
} == true

private fun refuse(detail: String): Nothing = throw PatchException("Home next page: $detail")

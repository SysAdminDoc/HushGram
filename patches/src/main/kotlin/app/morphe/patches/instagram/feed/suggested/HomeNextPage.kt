/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.analytics.loadsString
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCallingInto
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.classesLoadingString
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

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val MAP = "Ljava/util/Map;"

/**
 * What asking Home for its next page needs, found before anything changes: Home's list builder,
 * the adapter's field holding its load more policy (a lazy value, read through [lazyValue]), the
 * policy's call for the next page ([ask], the one Instagram makes when the loading row comes into
 * view), its "a page is loading" and "there's a next page" questions, the feed's paging source
 * field, and the extension's stubs that reach them.
 */
internal class HomeNextPageTargets(
    val adapter: String,
    val builder: String,
    val builderParameters: List<String>,
    val policyField: FieldReference,
    val lazyValue: MethodReference,
    val policy: String,
    val ask: MethodReference,
    val loading: MethodReference,
    val more: MethodReference,
    val source: FieldReference,
    private val stubs: Map<String, MutableMethod>,
) {
    /**
     * Fills the stubs, then hands Home's list builder to [HOME_BUILD_STARTS] first thing. Every
     * stub has one way out, so no return merges two types, and each uses its own parameters only.
     */
    fun write(context: BytecodePatchContext) {
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
            .addInstructions(0, "invoke-static/range { p0 .. p0 }, $HOME_BUILD_STARTS")
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
 * Instagram 450's 385611438 has them as LX/01ls->A1A, LX/01ls->A0m, LX/01lQ->A00(Map), A02() and
 * EBU(), and LX/01mK->A01. Everything the extension reaches has to be public.
 */
internal fun BytecodePatchContext.findHomeNextPage(): HomeNextPageTargets {
    val builders = classesHolding(BUILD_MODELS, SHIMMER_KEY).flatMap { classDef ->
        classDef.methods.filter { it.holdsString(BUILD_MODELS) && it.holdsString(SHIMMER_KEY) }.map { classDef to it }
    }
    val (adapterClass, builder) = builders.singleOrNull()
        ?: refuse("expected one method holding $BUILD_MODELS and $SHIMMER_KEY, found ${builders.size}")
    val adapter = adapterClass.type
    if (AccessFlags.STATIC.isSet(builder.accessFlags)) refuse("$adapter->${builder.name} is static, so it has no adapter to hand over")
    val feed = builderFeed(builder)

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
    )
    return HomeNextPageTargets(
        adapter = adapter,
        builder = builder.name,
        builderParameters = builder.parameterTypes.map(CharSequence::toString),
        policyField = field,
        lazyValue = lazyValue,
        policy = policy,
        ask = ask,
        loading = questions[0],
        more = questions[1],
        source = source,
        stubs = stubs,
    )
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
    val shimmer = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == SHIMMER_KEY }
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
    val declared = methods.singleOrNull {
        it.name == called.name && it.returnType == called.returnType &&
            it.parameterTypes.map(CharSequence::toString) == called.parameterTypes.map(CharSequence::toString)
    }
    if (declared == null || !AccessFlags.PUBLIC.isSet(declared.accessFlags) || AccessFlags.STATIC.isSet(declared.accessFlags)) {
        refuse("$called isn't a public instance method of $type")
    }
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

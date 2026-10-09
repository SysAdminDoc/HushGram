/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.feed.filterParsedFeedItems
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.flags.FlagRead
import app.morphe.patches.instagram.misc.flags.answerFlagReads
import app.morphe.patches.instagram.misc.flags.findFlagReads
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Hide Meta AI"
private const val META_AI = "$EXTENSION_PACKAGE/metaai/MetaAi;"
internal const val SEARCH_FLAG = "$META_AI->searchFlag(I)Z"
internal const val META_AI_FILTER = "$META_AI->filter(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val FOLLOW_UP_BAR = "$META_AI->followUpBar(Landroid/view/View;)Landroid/view/View;"
internal const val HOME_BUTTON = "$META_AI->homeButton(Ljava/lang/String;)Ljava/lang/String;"

/** Home's top bar setup holds both on 449, and its Meta AI button is the second one's case. */
internal val HOME_BAR_SETUP = listOf("MainFeedActionBarDelegate:configureActionBar", "meta_ai")
private const val ITERATOR_NEXT = "Ljava/util/Iterator;->next()Ljava/lang/Object;"
private const val STRING_LENGTH = "Ljava/lang/String;->length()I"
private const val STRING_EQUALS = "Ljava/lang/String;->equals(Ljava/lang/Object;)Z"

/** Two strings only the search results page's bottom bar setup holds on 449. */
internal val FOLLOW_UP_SETUP = listOf("keyboardHeightChangeDetector", "bottomSearchSuggestionPillsHelper")
private const val FIND_VIEW = "Landroid/view/View;->findViewById(I)Landroid/view/View;"
private const val INFLATE = "Landroid/view/ViewStub;->inflate()Landroid/view/View;"
private const val VIEW_STUB = "Landroid/view/ViewStub;"

/** How far before the stub's lookup the check that it's there may come: 449 puts one call between them. */
private const val CHECK_WITHIN = 6

/** How far before 450's null test the view it tests may be set: 450 puts ten instructions between them. */
private const val VIEW_SET_WITHIN = 16

/**
 * The server flags the search switch answers off on Instagram 450. Three decide whether a search bar
 * offers Meta AI: the Search tab's, which also covers its results and Meta AI's answers there, the
 * messages inbox's "ask Meta AI" bar, and the Meta AI ring at the end of the inbox's bar. The fourth
 * puts a Meta AI chats ("hatch") button in Home's top bar when the server's list has no messages
 * button, and the same item in the bar's settings list.
 */
internal val SEARCH_FLAGS = listOf(0x81067f00111f66L, 0x81041600061138L, 0x810414001b112eL, 0x8116a000017225L)

/** Meta AI's feed item kinds: Vibes videos, Meta AI chats and Imagine pictures of you. */
internal val META_AI_UNITS = listOf("VIBES_IN_FEED_UNIT", "HATCH_IMMERSIVE_IN_FEED_UNIT", "MEMU_IN_FEED_UNIT")

@Suppress("unused")
val hideMetaAiPatch = bytecodePatch(
    name = "Hide Meta AI",
    description = "Takes Meta AI out of the search bars, in the Search tab and at the top of your messages, so they " +
        "search the plain way, drops the Ask a follow-up bar under search results and Meta AI's buttons in Home's " +
        "top bar and the message composer, hides its optional row in your inbox, and removes Meta AI's posts from " +
        "your home feed. It can also take About this reel, or only its Ask Meta AI box, out of a reel's More menu, " +
        "and Meta AI's target out of the share sheet. Search and posts have switches that start on. About this reel, " +
        "Ask Meta AI and the share sheet each have one that's off until you turn it on. The search switch applies " +
        "after Instagram restarts. Switches: HushGram settings > Meta AI.",
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        // Every read is found and checked, and SettingsStatus is confirmed to carry the switch's
        // method, before any hook changes an instruction.
        requireStatusMethod("metaAi")
        val reads = findSearchFlagReads()
        val followUp = findFollowUpBarCheck()
        val homeButtons = findHomeButtonNames()
        val composer = findComposerButtonVisibility()
        val inbox = findOptionalInboxRow()
        val about = findAboutSummaryCalls()
        val askBox = findAskMetaAiBox()
        val shareTarget = findShareTargetCheck()
        filterParsedFeedItems(PATCH, META_AI_FILTER, META_AI_UNITS)
        answerSearchFlagReads(reads)
        dropFollowUpBar(followUp)
        dropHomeButton(homeButtons)
        holdComposerButtons(composer)
        holdOptionalInboxRow(inbox)
        dropAboutSummary(about)
        holdAskMetaAiBox(askBox)
        holdShareTarget(shareTarget)
        enableStatus("metaAi")
    }
}

/**
 * Every place the app loads one of [SEARCH_FLAGS] and reads it as a boolean (see [findFlagReads]).
 * Fails when a flag is never read, or is loaded for any other use.
 */
internal fun BytecodePatchContext.findSearchFlagReads(): List<FlagRead> = findFlagReads(PATCH, SEARCH_FLAGS)

/** Passes each read's answer through MetaAi.searchFlag, right after its move-result. */
internal fun BytecodePatchContext.answerSearchFlagReads(reads: List<FlagRead>) = answerFlagReads(reads, SEARCH_FLAG)

/** A value one of the hooks passes through: the method, and the index and register of the instruction that last sets it. */
internal class HookSite(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val moveResult: Int,
    val register: Int,
)

/**
 * The check, in the search results page's bottom bar setup (the one method holding
 * [FOLLOW_UP_SETUP]), that leaves the page without its "Ask a follow-up…" bar. The bar is a stub a
 * static (View, int) lookup hands to inflate(), and a null at the check leaves the page the way it is
 * when the layout has no stub; every later use of the bar checks for that.
 *
 * On 449 the check is a findViewById of the same id, tested for null, before the lookup. The pills
 * inside the bar come from a second stub, found inside the bar through a findViewById and a cast,
 * which is never inflated once the bar isn't.
 *
 * On 450 the lookup is the findViewById, and a null there takes the path for a bar inflated earlier,
 * which asserts it's there. So the check is the null test of the view the lookup searches, just
 * before it, and the site is the instruction that sets that view, with nothing jumping in between.
 *
 * The lookup is a static (View, int) call answering a ViewStub. The x86_64 build 385611440 has it
 * inlined, a findViewById whose answer is cast to ViewStub, which then counts as the lookup. 449
 * finds the pills' stub inside the bar that way too, so an inlined lookup is only taken when the
 * method makes no static (View, int) ViewStub call at all, whether or not that call's stub is
 * inflated where the finder looks, and only when the view it searches was last set by an
 * iget-object, as 385611440's page view is. The pills' stub is searched in the inflated bar.
 */
internal fun BytecodePatchContext.findFollowUpBarCheck(): HookSite {
    val setups = mutableListOf<Pair<ClassDef, Method>>()
    classesHolding(*FOLLOW_UP_SETUP.toTypedArray()).forEach { classDef ->
        classDef.methods.forEach { method ->
            if (method.strings().containsAll(FOLLOW_UP_SETUP)) setups += classDef to method
        }
    }
    val (classDef, method) = setups.singleOrNull()
        ?: refuse("${setups.size} methods hold ${FOLLOW_UP_SETUP.joinToString(" and ")}, not one")
    val where = "${classDef.type}->${method.name}"
    val code = method.implementation!!.instructions.toList()
    // Whether the stub held at [ready] (a static lookup's move-result, an inlined one's cast) goes to inflate() soon after.
    fun inflatedAfter(ready: Int): Boolean {
        val stub = (code[ready] as OneRegisterInstruction).registerA
        return (ready + 1..minOf(code.lastIndex, ready + CHECK_WITHIN)).any {
            code[it].methodReference()?.toString() == INFLATE && code[it].arguments() == listOf(stub)
        }
    }
    fun staticLookup(index: Int): Boolean {
        val call = code[index].methodReference() ?: return false
        return code[index].opcode == Opcode.INVOKE_STATIC && call.returnType == VIEW_STUB &&
            call.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;", "I")
    }
    // Whether the view the call at [index] searches was last set by an iget-object before it.
    fun searchesAField(index: Int): Boolean {
        val view = code[index].arguments().first()
        val set = (index - 1 downTo 0).firstOrNull { code[it].writesObject(view) } ?: return false
        return code[set].opcode == Opcode.IGET_OBJECT
    }
    val statics = code.indices.filter { index ->
        staticLookup(index) && code.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT && inflatedAfter(index + 1)
    }
    val inlined = code.indices.filter { index ->
        val result = code.getOrNull(index + 1)
        val cast = code.getOrNull(index + 2)
        code[index].methodReference()?.toString() == FIND_VIEW && result?.opcode == Opcode.MOVE_RESULT_OBJECT &&
            cast?.opcode == Opcode.CHECK_CAST && (cast as ReferenceInstruction).reference.toString() == VIEW_STUB &&
            (cast as OneRegisterInstruction).registerA == (result as OneRegisterInstruction).registerA && inflatedAfter(index + 2) &&
            searchesAField(index)
    }
    val lookups = if (code.indices.any(::staticLookup)) statics else inlined
    val lookup = lookups.singleOrNull() ?: refuse("$where looks up a stub to inflate ${lookups.size} times, not once")
    val site = (lookup - 1 downTo maxOf(0, lookup - CHECK_WITHIN))
        .firstOrNull { code[it].methodReference()?.toString() == FIND_VIEW }
        ?.let { find -> stubCheck(where, code, find, lookup) }
        ?: pageViewCheck(where, method, code, lookup)
    return HookSite(
        classDef.type, method.name, method.parameterTypes.map(CharSequence::toString), method.returnType,
        site.first, site.second,
    )
}

/** 449's findViewById of the stub: its move-result and register, once it checks the stub the lookup takes and tests it for null. */
private fun stubCheck(where: String, code: List<Instruction>, find: Int, lookup: Int): Pair<Int, Int> {
    if (code[find].arguments() != code[lookup].arguments()) refuse("$where checks one view and inflates another")
    val result = code[find + 1]
    if (result.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("$where drops the check's answer")
    val register = (result as OneRegisterInstruction).registerA
    val test = code[find + 2]
    if (test.opcode != Opcode.IF_EQZ || (test as OneRegisterInstruction).registerA != register) {
        refuse("$where doesn't test the stub for null")
    }
    return find + 1 to register
}

/**
 * 450's null test of the view the lookup searches: the instruction that last sets that view and its
 * register, once the test comes within [CHECK_WITHIN] before the lookup, the view isn't set again
 * between them, and nothing jumps in between the view being set and the lookup.
 */
private fun pageViewCheck(where: String, method: Method, code: List<Instruction>, lookup: Int): Pair<Int, Int> {
    val view = code[lookup].arguments().first()
    val test = (lookup - 1 downTo maxOf(0, lookup - CHECK_WITHIN)).firstOrNull {
        code[it].opcode == Opcode.IF_EQZ && (code[it] as OneRegisterInstruction).registerA == view
    } ?: refuse("$where inflates its stub without checking it's there")
    if ((test + 1 until lookup).any { code[it].writesObject(view) }) refuse("$where checks one view and searches another")
    val set = (test - 1 downTo maxOf(0, test - VIEW_SET_WITHIN)).firstOrNull { code[it].writesObject(view) }
        ?: refuse("$where tests a view it doesn't set nearby")
    if (code[set].opcode != Opcode.IGET_OBJECT && code[set].opcode != Opcode.MOVE_RESULT_OBJECT) {
        refuse("$where sets the view it searches with ${code[set].opcode}")
    }
    val flow = ControlFlow.of(method)
    val entered = code.indices.flatMap { from ->
        flow.normal[from].filter { it != from + 1 } + flow.exceptional[from]
    }.toSet()
    if ((set + 1..lookup).any { it in entered }) refuse("$where jumps in between setting the view and searching it")
    return set to view
}

private fun Instruction.writesObject(register: Int) =
    opcode.setsRegister() && (this as? OneRegisterInstruction)?.registerA == register

/** Passes the stub check's answer through MetaAi.followUpBar, right after its move-result. */
internal fun BytecodePatchContext.dropFollowUpBar(check: HookSite) = passThrough(check, FOLLOW_UP_BAR)

/**
 * Where Home's top bar setup (the one method holding [HOME_BAR_SETUP]) takes each button name from
 * the server's list: an Iterator.next() cast to String, tested for null and then for an empty name,
 * the name the "meta_ai" case compares. A null there is skipped like an empty name, so the bar is
 * built without that button. Another loop in the method, over the names the bar ended up with, casts
 * them without the null test.
 */
internal fun BytecodePatchContext.findHomeButtonNames(): HookSite {
    val setups = mutableListOf<Pair<ClassDef, Method>>()
    classesHolding(*HOME_BAR_SETUP.toTypedArray()).forEach { classDef ->
        classDef.methods.forEach { method ->
            if (method.strings().containsAll(HOME_BAR_SETUP)) setups += classDef to method
        }
    }
    val (classDef, method) = setups.singleOrNull()
        ?: refuse("${setups.size} methods hold ${HOME_BAR_SETUP.joinToString(" and ")}, not one")
    val where = "${classDef.type}->${method.name}"
    val code = method.implementation!!.instructions.toList()
    val casts = code.indices.filter { index ->
        if (code[index].opcode != Opcode.CHECK_CAST || index < 2) return@filter false
        val register = (code[index] as OneRegisterInstruction).registerA
        val test = code.getOrNull(index + 1)
        code[index - 2].methodReference()?.toString() == ITERATOR_NEXT &&
            code[index - 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            (code[index - 1] as OneRegisterInstruction).registerA == register &&
            ((code[index] as ReferenceInstruction).reference.toString() == "Ljava/lang/String;") &&
            test?.opcode == Opcode.IF_EQZ && (test as OneRegisterInstruction).registerA == register &&
            code.getOrNull(index + 2)?.methodReference()?.toString() == STRING_LENGTH &&
            code[index + 2].arguments() == listOf(register)
    }
    val cast = casts.singleOrNull() ?: refuse("$where takes ${casts.size} button names from a list, not one")
    val register = (code[cast] as OneRegisterInstruction).registerA
    val comparedToMetaAi = code.indices.any { index ->
        val name = ((code[index] as? ReferenceInstruction)?.reference as? StringReference)?.string
        name == "meta_ai" && code.getOrNull(index + 1)?.methodReference()?.toString() == STRING_EQUALS &&
            code[index + 1].arguments() == listOf(register, (code[index] as OneRegisterInstruction).registerA)
    }
    if (!comparedToMetaAi) refuse("$where never compares the name it takes with \"meta_ai\"")
    return HookSite(
        classDef.type, method.name, method.parameterTypes.map(CharSequence::toString), method.returnType,
        cast, register,
    )
}

/** Passes each of Home's button names through MetaAi.homeButton, right after its cast. */
internal fun BytecodePatchContext.dropHomeButton(names: HookSite) = passThrough(names, HOME_BUTTON)

/** Puts [hook] on [site]'s register right after the instruction that sets it, and the answer back in it. */
private fun BytecodePatchContext.passThrough(site: HookSite, hook: String) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.returnType == site.returnType &&
            it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    method.addInstructions(
        site.moveResult + 1,
        """
            invoke-static/range { v${site.register} .. v${site.register} }, $hook
            move-result-object v${site.register}
        """,
    )
}

private fun Method.strings(): Set<String> = implementation?.instructions
    ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }?.toSet() ?: emptySet()

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

/** The registers an invoke passes, in order. */
private fun Instruction.arguments(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

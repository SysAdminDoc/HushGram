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
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.originalName
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.instagram.misc.flags.FlagRead
import app.morphe.patches.instagram.misc.flags.answerFlagReads
import app.morphe.patches.instagram.misc.flags.findFlagReads
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.Reference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide Meta AI"
private const val META_AI = "$EXTENSION_PACKAGE/metaai/MetaAi;"
internal const val SEARCH_FLAG = "$META_AI->searchFlag(I)Z"
internal const val META_AI_FILTER = "$META_AI->filter(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val FOLLOW_UP_BAR = "$META_AI->followUpBar(Landroid/view/View;)Landroid/view/View;"
internal const val FOLLOW_UP_STUB = "$META_AI->followUpStub(Landroid/view/View;)Landroid/view/View;"
internal const val HOME_BUTTON = "$META_AI->homeButton(Ljava/lang/String;)Ljava/lang/String;"

/** Home's top bar setup holds the trace; 453 names Meta AI's button "hatch", older builds "meta_ai". */
internal val HOME_BAR_SETUP = listOf("MainFeedActionBarDelegate:configureActionBar")
internal val HOME_BUTTON_NAMES = listOf("meta_ai", "hatch")
private const val ITERATOR_NEXT = "Ljava/util/Iterator;->next()Ljava/lang/Object;"
private const val STRING_LENGTH = "Ljava/lang/String;->length()I"
private const val STRING_EQUALS = "Ljava/lang/String;->equals(Ljava/lang/Object;)Z"
private const val STRING_HASH = "Ljava/lang/String;->hashCode()I"

/** Two strings only the search results page's bottom bar setup holds on 449. */
internal val FOLLOW_UP_SETUP = listOf("keyboardHeightChangeDetector", "bottomSearchSuggestionPillsHelper")
private const val FIND_VIEW = "Landroid/view/View;->findViewById(I)Landroid/view/View;"
private const val INFLATE = "Landroid/view/ViewStub;->inflate()Landroid/view/View;"
private const val VIEW_STUB = "Landroid/view/ViewStub;"

/** How far before the stub's lookup the check that it's there may come: 449 puts one call between them. */
private const val CHECK_WITHIN = 6

/** How far before 450's null test the view it tests may be set: 450 puts ten instructions between them. */
private const val VIEW_SET_WITHIN = 16

/** How far after 450's null test of the bar's stub the helper inflating it may come: 450 puts one call between them. */
private const val INFLATE_WITHIN = 4

private const val VIEW = "Landroid/view/View;"

/**
 * The server flags the search switch answers off on Instagram 450. Three decide whether a search bar
 * offers Meta AI: the Search tab's, which also covers its results and Meta AI's answers there, the
 * messages inbox's "ask Meta AI" bar, and the Meta AI ring at the end of the inbox's bar. The fourth
 * puts a Meta AI chats ("hatch") button in Home's top bar when the server's list has no messages
 * button, and the same item in the bar's settings list.
 */
internal val SEARCH_FLAGS = listOf(0x81066200111e66L, 0x81040000061068L, 0x8103fe001b105eL, 0x81166400017394L)

/** The Search tab's flag, which the search results page reads too (#152). */
internal val RESULTS_FLAG = SEARCH_FLAGS[0]

/** The search results page's two fragments, by the names Instagram's build keeps for them. */
internal val RESULTS_PAGES = listOf("BaseSerpGridFragment", "CompositeSerpTabbedFragment")

/** What the results page's row builder keys Meta AI's summary rows with. It names neither fragment. */
internal const val SUMMARY_ROWS = "hcm_sources_"

/** Meta AI's feed item kinds: Vibes videos, Meta AI chats and Imagine pictures of you. */
internal val META_AI_UNITS = listOf("VIBES_IN_FEED_UNIT", "HATCH_IMMERSIVE_IN_FEED_UNIT", "MEMU_IN_FEED_UNIT")

@Suppress("unused")
val hideMetaAiPatch = bytecodePatch(
    name = "Hide Meta AI",
    description = "Removes Meta AI from Instagram's search bars, messages, home top bar, reels menus, share sheet " +
        "and feed. Each part has its own switch. The search one and the Meta AI posts one start on, the rest " +
        "start off. Change them in HushGram settings > Meta AI.",
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        // Every read is found and checked, and SettingsStatus is confirmed to carry the switch's
        // method, before any hook changes an instruction.
        requireStatusMethod("metaAi")
        val reads = searchFlagSites(findSearchFlagReads())
        val followUp = findFollowUpBarCheck()
        val followUpStub = findFollowUpBarStub()
        val summaryRows = findSummaryRows()
        val homeButtons = findHomeButtonNames()
        val composer = findComposerButtonVisibility()
        val inbox = findOptionalInboxRow()
        val about = findAboutSummaryCalls()
        val askBox = findAskMetaAiBox()
        val shareTarget = findShareTargetCheck()
        val museBanner = findMuseBannerBuilder()
        val museCard = findQuickPromotionBuilder()
        val museButton = findMuseButtonTest()
        val blend = findBlendVisibilityCheck()
        filterParsedFeedItems(PATCH, META_AI_FILTER, META_AI_UNITS)
        answerSearchFlagReads(reads)
        // 453's 64-bit builds check the bar's own stub, so the check is the stub and gets one hook.
        followUp?.takeUnless { it.sameSiteAs(followUpStub) }?.let(::dropFollowUpBar)
        // After the pills' check, which comes later in the same method, so this site hasn't moved.
        dropFollowUpBarStub(followUpStub)
        foldSummaryRows(summaryRows)
        homeButtons?.let(::dropHomeButton)
        holdComposerButtons(composer)
        holdOptionalInboxRow(inbox)
        dropAboutSummary(about)
        holdAskMetaAiBox(askBox)
        holdShareTarget(shareTarget)
        holdMuseBanner(museBanner)
        holdQuickPromotion(museCard)
        holdMuseButton(museButton)
        holdBlendButton(blend)
        enableStatus("metaAi")
    }
}

/**
 * Every place the app loads one of [SEARCH_FLAGS] and reads it as a boolean (see [findFlagReads]).
 * Fails when a flag is never read, or is loaded for any other use.
 */
internal fun BytecodePatchContext.findSearchFlagReads(): List<FlagRead> = findFlagReads(PATCH, SEARCH_FLAGS)

/**
 * Where the switch answers [reads] (#152). [RESULTS_FLAG]'s read sits in a static wrapper that hands
 * the answer straight back, called from 26 places on 450, and the search results page is among them.
 * The page builds its rows from that answer and opens a tapped reel by its row's place in the list
 * the server sent, so with Meta AI's rows left out, a reel past them opened another one. So the
 * page keeps Instagram's answer, and everything else that reads it, the Search tab's bar and Home's
 * included, gets the switch's.
 *
 * Each call of the wrapper whose answer is kept is a site, as is a read of the flag outside a
 * wrapper. A site is on the results page when its class is one of [RESULTS_PAGES] or names either
 * one's type anywhere, or its method loads [SUMMARY_ROWS] (the row builder), held or pooled. The other flags'
 * reads are answered where they are. Fails when either fragment doesn't read the flag, the row
 * builder doesn't, or nothing off the page does.
 */
internal fun BytecodePatchContext.searchFlagSites(reads: List<FlagRead>): List<FlagRead> {
    val (results, others) = reads.partition { it.flag == RESULTS_FLAG }
    val sites = results.flatMap { read -> if (handsBack(read)) wrapperCalls(read) else listOf(read) }
    val callers = sites.map { it.type }.distinct().map { classDefBy(it) }
    val pages = callers.filter { it.originalName() in RESULTS_PAGES }.map { it.type }.toSet()
    if (pages.size != RESULTS_PAGES.size) {
        refuse("${pages.size} of the search results page's ${RESULTS_PAGES.size} fragments read ${RESULTS_FLAG.toString(16)}")
    }
    val onPage = callers.filter { it.type in pages || it.names(pages) }.map { it.type }.toSet()
    fun buildsRows(site: FlagRead): Boolean {
        val code = methodOf(site).implementation?.instructions?.toList().orEmpty()
        return code.indices.any { stringLoadedAt(code, it) == SUMMARY_ROWS }
    }
    val (kept, answered) = sites.partition { it.type in onPage || buildsRows(it) }
    if (kept.none(::buildsRows)) refuse("the search results page's row builder doesn't read ${RESULTS_FLAG.toString(16)}")
    if (answered.isEmpty()) refuse("only the search results page reads ${RESULTS_FLAG.toString(16)}")
    return others + answered
}

/** Passes each site's answer through MetaAi.searchFlag, right after its move-result. */
internal fun BytecodePatchContext.answerSearchFlagReads(reads: List<FlagRead>) = answerFlagReads(reads, SEARCH_FLAG)

private fun BytecodePatchContext.methodOf(read: FlagRead): Method = classDefBy(read.type).methods.single {
    it.name == read.name && it.returnType == read.returnType && it.parameterTypes.map(CharSequence::toString) == read.parameters
}

/** Whether [read] is a static method's, which returns the answer right after reading it. */
private fun BytecodePatchContext.handsBack(read: FlagRead): Boolean {
    val method = methodOf(read)
    val next = method.implementation!!.instructions.toList().getOrNull(read.moveResult + 1)
    return AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" &&
        next?.opcode == Opcode.RETURN && (next as OneRegisterInstruction).registerA == read.register
}

/**
 * Each call of [read]'s method outside the extension whose answer is kept, as a read of
 * [read]'s flag at the call's move-result. Fails when nothing calls it.
 */
private fun BytecodePatchContext.wrapperCalls(read: FlagRead): List<FlagRead> {
    val calls = mutableListOf<FlagRead>()
    classesCalling(read.type, read.name).forEach { classDef ->
        classDef.methods.forEach { method ->
            val code = method.implementation?.instructions?.toList() ?: return@forEach
            code.forEachIndexed { index, instruction ->
                val call = instruction.methodReference() ?: return@forEachIndexed
                if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) return@forEachIndexed
                if (call.definingClass != read.type || call.name != read.name || call.returnType != read.returnType ||
                    call.parameterTypes.map(CharSequence::toString) != read.parameters
                ) {
                    return@forEachIndexed
                }
                val result = code.getOrNull(index + 1)
                if (result?.opcode != Opcode.MOVE_RESULT) return@forEachIndexed
                calls += FlagRead(
                    read.flag, classDef.type, method.name, method.parameterTypes.map(CharSequence::toString), method.returnType,
                    index + 1, (result as OneRegisterInstruction).registerA,
                )
            }
        }
    }
    if (calls.isEmpty()) refuse("nothing uses ${read.type}->${read.name}'s answer for ${read.flag.toString(16)}")
    return calls
}

/** Whether this class names any of [types]: as its superclass, a field's, in a signature or in its code. */
private fun ClassDef.names(types: Set<String>): Boolean =
    superclass in types || fields.any { it.type in types } || methods.any { method ->
        method.returnType in types || method.parameterTypes.any { it.toString() in types } ||
            method.implementation?.instructions?.any { (it as? ReferenceInstruction)?.reference?.names(types) == true } == true
    }

private fun Reference.names(types: Set<String>): Boolean = when (this) {
    is TypeReference -> type in types
    is FieldReference -> definingClass in types || type in types
    is MethodReference -> definingClass in types || returnType in types || parameterTypes.any { it.toString() in types }
    else -> false
}

/** A value one of the hooks passes through: the method, and the index and register of the instruction that last sets it. */
internal class HookSite(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val moveResult: Int,
    val register: Int,
) {
    /** Whether [other] passes the same value through: the same method's same instruction. */
    fun sameSiteAs(other: HookSite) =
        type == other.type && name == other.name && parameters == other.parameters && moveResult == other.moveResult
}

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
 * On 450 the bar's own stub goes to a static helper that inflates it and answers a View, so no
 * stub lookup here answers a ViewStub but the pills' one, and the check this finds is the null test
 * of the bar the pills are searched in, just before that lookup. The site is the instruction that
 * sets that view, with nothing jumping in between, and a null there leaves only the pills out. The
 * bar itself is left out at its own stub, see [findFollowUpBarStub].
 *
 * The lookup is a static (View, int) call answering a ViewStub. The x86_64 build 385611440 has it
 * inlined, a findViewById whose answer is cast to ViewStub, which then counts as the lookup. 449
 * finds the pills' stub inside the bar that way too, so an inlined lookup is only taken when the
 * method makes no static (View, int) ViewStub call at all, whether or not that call's stub is
 * inflated where the finder looks, and only when the view it searches was last set by an
 * iget-object, as 385611440's bar is.
 */
internal fun BytecodePatchContext.findFollowUpBarCheck(): HookSite? {
    val (classDef, method) = followUpSetup()
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
    // 453 inflates three stubs here: the follow-up bar, which is kept as a plain View, and two
    // ViewSwitcher stubs that are cast. Keep the one whose inflated view is stored as it is.
    // What the lookup at [index]'s inflated view goes to first: an iput-object keeps it as it is, a check-cast narrows it.
    fun afterInflate(index: Int): Opcode? {
        val ready = if (code.indices.any(::staticLookup)) index + 1 else index + 2
        val stub = (code[ready] as OneRegisterInstruction).registerA
        val inflate = (ready + 1..minOf(code.lastIndex, ready + CHECK_WITHIN)).firstOrNull {
            code[it].methodReference()?.toString() == INFLATE && code[it].arguments() == listOf(stub)
        } ?: return null
        val result = code.getOrNull(inflate + 1) ?: return null
        if (result.opcode != Opcode.MOVE_RESULT_OBJECT) return null
        val view = (result as OneRegisterInstruction).registerA
        // Some 453 builds check the view isn't null with a message first: a const-string of
        // the message into another register, then a static void call handed the view and it.
        var next = inflate + 2
        val message = code.getOrNull(next)
        if (message?.opcode == Opcode.CONST_STRING && (message as OneRegisterInstruction).registerA != view) {
            val check = code.getOrNull(next + 1)
            val call = check?.methodReference()
            if (check?.opcode == Opcode.INVOKE_STATIC && call?.returnType == "V" &&
                check.arguments() == listOf(view, message.registerA)
            ) {
                next += 2
            }
        }
        return code.getOrNull(next)?.opcode
    }
    fun plainView(index: Int) = afterInflate(index) == Opcode.IPUT_OBJECT
    fun castView(index: Int) = afterInflate(index) == Opcode.CHECK_CAST
    val plain = lookups.filter(::plainView)
    // 453's 32-bit builds hand the bar to a static (View, int) helper that inflates it and answers a
    // plain View, so the only ViewStub lookups left here are the pills' two ViewSwitchers, both cast.
    // The bar's stub is hooked where it is found, in findFollowUpBarStub, instead. One lookup is
    // still the bar's, as on 449 and 450.
    if (plain.isEmpty() && lookups.size > 1 && lookups.all(::castView)) return null
    val bars = plain.ifEmpty { lookups }
    val lookup = bars.singleOrNull() ?: refuse("$where looks up a stub to inflate ${bars.size} times, not once")
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

/** Whether this sets [register], a wide value's second half included. */
private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val first = (this as? OneRegisterInstruction)?.registerA ?: return false
    return first == register || (opcode.setsWideRegister() && first + 1 == register)
}

/** Passes the stub check's answer through MetaAi.followUpBar, right after its move-result. */
internal fun BytecodePatchContext.dropFollowUpBar(check: HookSite) = passThrough(check, FOLLOW_UP_BAR)

/** The search results page's bottom bar setup: the one method holding [FOLLOW_UP_SETUP]. */
private fun BytecodePatchContext.followUpSetup(): Pair<ClassDef, Method> {
    val setups = mutableListOf<Pair<ClassDef, Method>>()
    // 452 reads the pills helper through its owner's null-checking getter, which holds the second
    // string itself, so a method holds the pair when it calls that getter as well.
    val (first, second) = FOLLOW_UP_SETUP
    classesHolding(first).forEach { classDef ->
        classDef.methods.forEach { method ->
            val strings = method.strings()
            if (first !in strings) return@forEach
            val getter = method.implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                .orEmpty().distinct().any { call ->
                    call.parameterTypes.isEmpty() && classDefByOrNull(call.definingClass)?.methods?.any {
                        it.name == call.name && it.parameterTypes.isEmpty() && it.returnType == call.returnType &&
                            (it.implementation?.instructions?.count() ?: 99) <= 12 && second in it.strings()
                    } == true
                }
            if (second in strings || getter) setups += classDef to method
        }
    }
    return setups.singleOrNull()
        ?: refuse("${setups.size} methods hold ${FOLLOW_UP_SETUP.joinToString(" and ")}, not one")
}

/**
 * Where the search results page's bottom bar setup (the one method holding [FOLLOW_UP_SETUP])
 * finds the "Ask a follow-up…" bar's stub on 450, before it inflates it. The page looks the stub
 * up in its own view with a findViewById (the bottom bar's id, or the floating bar's in the
 * floating design), tests the answer for null, and only then hands the same view and id to a
 * static (View, int) helper answering a View, which looks the stub up again and inflates it. A null
 * at the test skips the helper and takes the path for a bar set up earlier, which reads the bar
 * back, none on a new page, and checks it for null, as does every later use of the bar, its search
 * field and its buttons. So the page is built without the bar, and without the topic pills, which
 * are looked up inside it. The rest of the page, its header with Back and the query included, is
 * set up elsewhere.
 *
 * The site is that findViewById's move-result: the only findViewById in the method whose answer
 * is tested for null right away and whose view and id go, unchanged, to such a helper within
 * [INFLATE_WITHIN] instructions of the test. Nothing may jump to the test, or the hook would be
 * skipped on that path.
 */
internal fun BytecodePatchContext.findFollowUpBarStub(): HookSite {
    val (classDef, method) = followUpSetup()
    val where = "${classDef.type}->${method.name}"
    val code = method.implementation!!.instructions.toList()
    fun inflates(index: Int): Boolean {
        val call = code[index].methodReference() ?: return false
        return (code[index].opcode == Opcode.INVOKE_STATIC || code[index].opcode == Opcode.INVOKE_STATIC_RANGE) &&
            (call.returnType == VIEW || call.returnType == VIEW_STUB) &&
            call.parameterTypes.map(CharSequence::toString) == listOf(VIEW, "I")
    }
    fun inflateAt(find: Int): Int? {
        val result = code.getOrNull(find + 1)
        val test = code.getOrNull(find + 2)
        val searched = code[find].arguments()
        if (code[find].methodReference()?.toString() != FIND_VIEW || searched.size != 2) return null
        if (result?.opcode != Opcode.MOVE_RESULT_OBJECT || test?.opcode != Opcode.IF_EQZ) return null
        if ((test as OneRegisterInstruction).registerA != (result as OneRegisterInstruction).registerA) return null
        val inflate = (find + 3..minOf(code.lastIndex, find + 2 + INFLATE_WITHIN)).firstOrNull { inflates(it) }
            ?: return null
        if (code[inflate].arguments() != searched || (find + 3 until inflate).any { at -> searched.any { code[at].writes(it) } }) return null
        return inflate
    }
    val finds = code.indices.filter { inflateAt(it) != null }
    // 453 also sets up two ViewSwitcher stubs in this method; the bar's inflated view is kept as a plain View.
    val plain = finds.filter { find ->
        val inflate = inflateAt(find) ?: return@filter false
        code.getOrNull(inflate + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT && code.getOrNull(inflate + 2)?.opcode == Opcode.IPUT_OBJECT
    }
    val candidates = plain.ifEmpty { finds }
    val find = candidates.singleOrNull() ?: refuse("$where finds and inflates a stub of its page ${candidates.size} times, not once")
    val flow = ControlFlow.of(method)
    val jumpedTo = code.indices.any { from ->
        (flow.normal[from].filter { it != from + 1 } + flow.exceptional[from]).contains(find + 2)
    }
    if (jumpedTo) refuse("$where jumps to the test of the bar's stub")
    return HookSite(
        classDef.type, method.name, method.parameterTypes.map(CharSequence::toString), method.returnType,
        find + 1, (code[find + 1] as OneRegisterInstruction).registerA,
    )
}

/**
 * Passes the bar's stub through MetaAi.followUpStub, right after its move-result. [dropFollowUpBar]
 * goes into the same method first, later in it, so the site is where it was found; a site that
 * moved anyway fails the patch rather than hooking another instruction.
 */
internal fun BytecodePatchContext.dropFollowUpBarStub(stub: HookSite) {
    val code = mutableClassDefBy(stub.type).methods.single {
        it.name == stub.name && it.returnType == stub.returnType && it.parameterTypes.map(CharSequence::toString) == stub.parameters
    }.implementation!!.instructions.toList()
    val result = code.getOrNull(stub.moveResult)
    val find = code.getOrNull(stub.moveResult - 1)
    if (result?.opcode != Opcode.MOVE_RESULT_OBJECT || (result as OneRegisterInstruction).registerA != stub.register ||
        find?.methodReference()?.toString() != FIND_VIEW
    ) {
        refuse("${stub.type}->${stub.name} moved the bar's stub before it was hooked")
    }
    passThrough(stub, FOLLOW_UP_STUB)
}

/**
 * Where Home's top bar setup (the one method holding [HOME_BAR_SETUP]) takes each button name from
 * the server's list: an Iterator.next() cast to String, tested for null and then for an empty name,
 * the name the "meta_ai" or "hatch" case compares (453 switches on the name's hash first, and its
 * "meta_ai" case still builds the button). A null there is skipped like an empty name, so the bar is
 * built without that button. Another loop in the method, over the names the bar ended up with, casts
 * them without the null test. Null when the setup compares neither name nor switches on its hash.
 */
internal fun BytecodePatchContext.findHomeButtonNames(): HookSite? {
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
        name in HOME_BUTTON_NAMES && code.getOrNull(index + 1)?.methodReference()?.toString() == STRING_EQUALS &&
            code[index + 1].arguments() == listOf(register, (code[index] as OneRegisterInstruction).registerA)
    }
    // 453 switches on the name's hash and loads the name it then compares from a string table, so
    // "meta_ai" shows up only as its hash among the switch's keys.
    val hashes = HOME_BUTTON_NAMES.map(String::hashCode).toSet()
    val switchedToMetaAi = code.indices.any { index ->
        code[index].methodReference()?.toString() == STRING_HASH && code[index].arguments() == listOf(register)
    } && code.any { payload -> payload is SwitchPayload && payload.switchElements.any { it.key in hashes } }
    if (!comparedToMetaAi && !switchedToMetaAi) {
        // A setup that holds neither name nor its hash builds no Meta AI button from the server's list.
        // A name it holds but compares with something else is a loop this patch doesn't know.
        val comparesName = code.indices.any { index ->
            ((code[index] as? ReferenceInstruction)?.reference as? StringReference)?.string in HOME_BUTTON_NAMES &&
                code.getOrNull(index + 1)?.methodReference()?.toString() == STRING_EQUALS
        }
        if (comparesName) refuse("$where never compares the name it takes with ${HOME_BUTTON_NAMES.joinToString(" or ") { "\"$it\"" }}")
        return null
    }
    return HookSite(
        classDef.type, method.name, method.parameterTypes.map(CharSequence::toString), method.returnType,
        cast, register,
    )
}

/** Passes each of Home's button names through MetaAi.homeButton, right after its cast. */
internal fun BytecodePatchContext.dropHomeButton(names: HookSite) = passThrough(names, HOME_BUTTON)

/** Puts [hook] on [site]'s register right after the instruction that sets it, and the answer back in it. */
internal fun BytecodePatchContext.passThrough(site: HookSite, hook: String) {
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

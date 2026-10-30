/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.partnership

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.reels.ailabel.AI_PAGE_FILTER
import app.morphe.patches.instagram.reels.captionwords.CAPTION_PAGE_FILTER
import app.morphe.patches.instagram.reels.suggested.TO_CLIPS_ITEM
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
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
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.util.IdentityHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HidePaidPartnershipReelsHookTest {
    private val converter = "Lfixture/Converter;"
    private val item = "Lfixture/ClipsItem;"
    private val source = "Lfixture/Source;"
    private val otherSource = "Lfixture/OtherSource;"
    private val direct = "Lfixture/DirectSource;"
    private val helped = "Lfixture/HelpedSource;"
    private val helpers = "Lfixture/Helpers;"
    private val other = "Lfixture/Other;"
    private val callback = "Lfixture/Callback;"
    private val fetcher = "Lfixture/Fetcher;"
    private val viewer = "Lfixture/Viewer;"
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val list = "Ljava/util/List;"
    private val string = "Ljava/lang/String;"
    private val convert = "$converter->convert(Lfixture/ServerItem;$session)$item"
    private val fill = "$helpers->fill(${session}Ljava/util/AbstractCollection;Ljava/util/Iterator;)V"

    /** The hook and the three stubs the patch fills are in the PaidPartnershipReels the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(PAID_PARTNERSHIP_REELS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (wanted in listOf(
            PARTNERSHIP_PAGE_FILTER.substringAfter("->"),
            "media(Ljava/lang/Object;)Ljava/lang/Object;",
            "paidPartnership(Ljava/lang/Object;)Ljava/lang/Boolean;",
            "boostedStatus(Ljava/lang/Object;)Ljava/lang/String;",
        )) {
            assertTrue("$wanted is not in the extension: $declared", wanted in declared)
        }
        assertFalse("the page filter without a module is still there: $declared", "page(Ljava/util/List;)Ljava/util/List;" in declared)
    }

    /**
     * The viewer's callback hands the list to the filter, with the module name its fetcher gave it
     * from getModuleName, where the page and its fallback meet, the branch from the page's check
     * included. The sources answering a page, the converter and everything else stay as they were,
     * and the stubs read the item's post and the post's two fields.
     */
    @Test
    fun theCallbackFiltersEachPageWithItsModuleAndTheStubsAreFilled() {
        val context = PatchContexts.of(classes())

        val sites = context.findPaidPartnershipReels()
        context.hidePaidPartnershipReels(sites)

        assertEquals(source, sites.pageInterface)
        assertEquals(callback, sites.callback)
        assertEquals("module", sites.moduleField)
        assertEquals("the page's register", 2, sites.list)
        assertEquals("the first local nothing reads after the join", 0, sites.scratch)
        assertHookAtJoin("the stand-in", context.mutableClassDefBy(callback).methods.single { it.name == "success" }, sites, "page")

        val unhooked = listOf(
            direct to "page", direct to "older", helped to "page", converter to "convert", helpers to "fill",
            other to "list", callback to "<init>", fetcher to "fetch",
        )
        for ((type, name) in unhooked) {
            val method = context.mutableClassDefBy(type).methods.single { it.name == name }
            assertTrue("$type->$name was hooked", method.code().none { it.referenceText() == PARTNERSHIP_PAGE_FILTER })
        }
        assertStubsFilled("the stand-in", context, item, "post", "partnership", "boost")
    }

    /**
     * Hide AI-labeled reels finds the same join after this patch has hooked it, and its filter goes
     * in first at the join's label, so the page and its fallback pass through both filters once.
     */
    @Test
    fun hideAiLabeledReelsFiltersTheSamePageFirst() {
        val context = PatchContexts.of(classes())
        val sites = context.findPaidPartnershipReels()
        context.hidePaidPartnershipReels(sites)

        val again = context.findReelsPage("Hide AI-labeled reels")
        assertEquals("the join", sites.join, again.join)
        assertEquals("the page's register", sites.list, again.list)
        assertEquals("the module field", sites.moduleField, again.moduleField)
        context.filterReelsPage(again, AI_PAGE_FILTER)

        val success = context.mutableClassDefBy(callback).methods.single { it.name == "success" }
        val code = success.code()
        assertEquals(
            "both filters at the join",
            listOf(AI_PAGE_FILTER, PARTNERSHIP_PAGE_FILTER),
            code.drop(sites.join).take(8).mapNotNull { it.referenceText()?.takeIf { text -> text.startsWith("Lapp/") } },
        )
        assertEquals("one AI filter call", 1, code.count { it.referenceText() == AI_PAGE_FILTER })
        assertEquals("one partnership filter call", 1, code.count { it.referenceText() == PARTNERSHIP_PAGE_FILTER })
        assertEquals("the AI filter's answer in the page's register", sites.list, (code[sites.join + 3] as OneRegisterInstruction).registerA)
        val jumps = success.implementation!!.instructions.withIndex()
            .filter { (_, instruction) -> (instruction as? BuilderOffsetInstruction)?.target?.location?.index == sites.join }.map { it.index }
        assertTrue("the page's check doesn't land on the first filter: $jumps", jumps.isNotEmpty())
    }

    /**
     * Hide reels by caption words finds the same join after both other filters, and its filter goes
     * in first, so the page and its fallback pass through all three once (#192).
     */
    @Test
    fun hideCaptionWordReelsFiltersTheSamePageFirst() {
        val context = PatchContexts.of(classes())
        val sites = context.findPaidPartnershipReels()
        context.hidePaidPartnershipReels(sites)
        context.filterReelsPage(context.findReelsPage("Hide AI-labeled reels"), AI_PAGE_FILTER)

        val again = context.findReelsPage("Hide reels by caption words")
        assertEquals("the join", sites.join, again.join)
        assertEquals("the page's register", sites.list, again.list)
        context.filterReelsPage(again, CAPTION_PAGE_FILTER)

        val success = context.mutableClassDefBy(callback).methods.single { it.name == "success" }
        val code = success.code()
        assertEquals(
            "three filters at the join",
            listOf(CAPTION_PAGE_FILTER, AI_PAGE_FILTER, PARTNERSHIP_PAGE_FILTER),
            code.drop(sites.join).take(12).mapNotNull { it.referenceText()?.takeIf { text -> text.startsWith("Lapp/") } },
        )
        for (filter in listOf(CAPTION_PAGE_FILTER, AI_PAGE_FILTER, PARTNERSHIP_PAGE_FILTER)) {
            assertEquals("one call to $filter", 1, code.count { it.referenceText() == filter })
        }
        assertEquals("the caption filter's answer in the page's register", sites.list, (code[sites.join + 3] as OneRegisterInstruction).registerA)
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val success = "$callback->success($source)V"
        val cases = listOf(
            classes(converters = 0) to "Reels item converter holding \"$TO_CLIPS_ITEM\" in this Instagram build, found none",
            classes(converters = 2) to "Reels item converter holding \"$TO_CLIPS_ITEM\" in this Instagram build, found Lfixture/",
            classes(itemPublic = false) to "$item isn't public, so the extension can't reach it",
            classes(mediaFields = listOf("cover", "post")) to "expected $item to keep its post in one Media field, found [cover, post]",
            classes(mediaFields = emptyList()) to "expected $item to keep its post in one Media field, found []",
            classes(mediaFieldPublic = false) to "$item's post field post isn't public",
            classes(pageAnswers = "Ljava/util/Collection;") to "nothing answering a page of Reels items from a UserSession calls the Reels item converter",
            classes(helpedName = "items") to "expected every page of Reels items to share one name, found [page, items]",
            classes(helpedInterface = otherSource) to "expected every page of Reels items to be one interface's page, found",
            classes(helpedInterface = null) to "expected every page of Reels items to be one interface's page, found",
            classes(successMarks = 0) to "expected one method marked $REELS_PAGE_ARRIVED, found []",
            classes(successMarks = 2) to "expected one method marked $REELS_PAGE_ARRIVED, found [Lfixture/",
            classes(successTakes = otherSource) to "expected $callback->success($otherSource)V to take a $source and answer nothing",
            classes(fallback = false) to "expected $success to ask for the page page and then one other list, found [page]",
            classes(fallbackInto = 4) to "$success keeps the page in v2 and its fallback in v4",
            classes(pageOverwritten = true) to "$success writes over the page in v2 at instruction",
            classes(earlyJump = true) to "expected $success to reach instruction",
            classes(thisReused = true) to "the Reels page's callback: $callback->success writes over this",
            classes(listRegister = 16) to "$success keeps the page in v16, past what the hook's call can name",
            classes(fetchMarked = false) to "expected one method marked $REELS_FETCH, found []",
            classes(fetchMakes = false) to "$fetcher->fetch()V doesn't make $callback",
            classes(fetchNamed = false) to "expected $fetcher->fetch()V to make $callback with one module name at instruction",
            classes(moduleKeptTwice = true) to "expected $callback's constructor to keep its module name in one field, found 2",
            classes(moduleWrittenElsewhere = true) to "$callback->module is written by [",
            classes(partnershipField = "is_paid_partnership_v2") to "expected one getter on $MEDIA answering Ljava/lang/Boolean; for is_paid_partnership, found none",
            classes(boostedPublic = false) to "$MEDIA->boost isn't public, so the extension can't reach it",
            classes(extension = false) to "the extension has no $PAID_PARTNERSHIP_REELS",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) {
                context.hidePaidPartnershipReels(context.findPaidPartnershipReels())
            }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == PARTNERSHIP_PAGE_FILTER || it.referenceText()?.startsWith(MEDIA) == true } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /**
     * On the declared build and each other build of 451, the one method hooked is the Reels viewer's
     * network callback, where its page and fallback meet, with the field its fetcher fills from the
     * viewer's getModuleName. The twelve sources answering a page, found here by scanning the whole
     * build for callers of the converter and its helpers, are left as they are, the one holding a
     * search's SearchContext and the one the profile's mapper makes among them. The module names the
     * extension filters are exactly the Reels tab's: "clips_viewer_" and the source of each lane
     * Instagram's lane enum lists, and the names its tab container answers. The names of
     * search, profile, audio, hashtag, location, Saved and message viewers aren't among them.
     */
    @Test
    fun eachBuildFiltersOnlyTheReelsTab() {
        val tabModules = ExtensionDex.classDef(PAID_PARTNERSHIP_REELS).methods.single { it.name == "<clinit>" }.code()
            .mapNotNull { it.string() }.filter { it.startsWith("clips_viewer_") }.toSet()
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        val wanted = setOf(REELS_PAGE_ARRIVED, REELS_FETCH, LANE_SOURCE, TAB_CONTAINER_MODULE, PROFILE_MAPPER)
        var checked = 0
        for (bundle in bundles) {
            val converterClasses = FixtureDex.classesHolding(bundle, TO_CLIPS_ITEM)
            val converterMethod = converterClasses.flatMap { it.methods }.single { method -> method.code().any { it.string() == TO_CLIPS_ITEM } }
            val itemType = converterMethod.returnType

            val parents = mutableMapOf<String, List<String>>()
            val callers = mutableListOf<ClassDef>()
            val marked = mutableMapOf<String, MutableList<ClassDef>>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    parents.putIfAbsent(classDef.type, listOfNotNull(classDef.superclass) + classDef.interfaces)
                }
                if (dex.stringSection.any { string -> wanted.any { string.endsWith("_$it") } }) {
                    for (classDef in dex.classes) {
                        val found = classDef.methods.flatMap { it.markers() }.filter { it in wanted }.toSet()
                        if (found.isEmpty()) continue
                        val copy = ImmutableClassDef.of(classDef)
                        found.forEach { marked.getOrPut(it) { mutableListOf() } += copy }
                    }
                }
                if (dex.methodSection.none { it.calls(converterMethod) }) return@forEach
                for (classDef in dex.classes) {
                    if (classDef.methods.any { method -> method.code().any { (it.reference() as? MethodReference)?.calls(converterMethod) == true } }) {
                        callers += ImmutableClassDef.of(classDef)
                    }
                }
            }
            val helperMethods = callers.flatMap { it.methods }.filter { method ->
                AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
                    method.code().any { (it.reference() as? MethodReference)?.calls(converterMethod) == true }
            }
            val helped = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                if (dex.methodSection.none { reference -> helperMethods.any { reference.calls(it) } }) return@forEach
                for (classDef in dex.classes) {
                    val callsHelper = classDef.methods.any { method ->
                        method.code().any { instruction -> (instruction.reference() as? MethodReference)?.let { ref -> helperMethods.any { ref.calls(it) } } == true }
                    }
                    if (callsHelper) helped += ImmutableClassDef.of(classDef)
                }
            }
            val pageClasses = (callers + helped).filter { classDef -> classDef.methods.any { it.isPageShaped() && it.callsAny(listOf(converterMethod) + helperMethods) } }
            val ancestors = mutableSetOf<String>()
            val next = ArrayDeque(pageClasses.map { it.type })
            while (next.isNotEmpty()) parents[next.removeFirst()].orEmpty().filter { it in parents && ancestors.add(it) }.forEach(next::addLast)
            fun markedBy(name: String): List<Method> = marked[name].orEmpty().flatMap { it.methods }.filter { name in it.markers() }
            val success = markedBy(REELS_PAGE_ARRIVED).single()
            val fetch = markedBy(REELS_FETCH).single()
            val callbackClass = marked.getValue(REELS_PAGE_ARRIVED).single()
            val kept = (converterClasses + callers + helped + callbackClass + marked.getValue(REELS_FETCH) +
                FixtureDex.classes(bundle, ancestors + itemType + MEDIA).values).distinctBy { it.type } +
                ExtensionDex.classDef(PAID_PARTNERSHIP_REELS)
            val context = PatchContexts.of(kept)

            val sites = context.findPaidPartnershipReels()
            context.hidePaidPartnershipReels(sites)

            val hooked = kept.filter { it.type != PAID_PARTNERSHIP_REELS }.flatMap { context.mutableClassDefBy(it.type).methods }
                .filter { method -> method.code().any { it.referenceText() == PARTNERSHIP_PAGE_FILTER } }
            assertEquals("${bundle.name}: the methods hooked", listOf("${success.definingClass}->${success.name}"), hooked.map { "${it.definingClass}->${it.name}" })
            assertEquals("${bundle.name}: the callback's page source", success.parameterTypes.single().toString(), sites.pageInterface)
            val pageName = pageClasses.flatMap { it.methods }.filter { it.isPageShaped() && it.callsAny(listOf(converterMethod) + helperMethods) }
                .map { it.name }.distinct().single()
            assertHookAtJoin(bundle.name, hooked.single(), sites, pageName)
            assertEquals("${bundle.name}: the field the fetcher fills from getModuleName", moduleFieldOf(fetch, callbackClass), sites.moduleField)
            assertEquals("${bundle.name}: the item", itemType, sites.itemType)
            assertStubsFilled(bundle.name, context, itemType, sites.mediaField, sites.partnershipGetter.name, sites.boostedGetter.name)
            assertTrue("${bundle.name}: the partnership getter's field", sites.partnershipGetter.loads(PAID_PARTNERSHIP_FIELD.hashCode()))
            assertTrue("${bundle.name}: the boost getter's field", sites.boostedGetter.loads(BOOSTED_STATUS_FIELD.hashCode()))

            assertEquals("${bundle.name}: the sources answering a page", 12, pageClasses.size)
            val search = pageClasses.filter { classDef -> classDef.fields.any { it.type == SEARCH_CONTEXT } }.map { it.type }
            val profile = markedBy(PROFILE_MAPPER).single().returnType
            assertEquals("${bundle.name}: the search's source", 1, search.size)
            assertTrue("${bundle.name}: the profile's source $profile answers no page", profile in pageClasses.map { it.type })
            for (type in pageClasses.map { it.type }) {
                assertTrue("${bundle.name}: $type was hooked", context.mutableClassDefBy(type).methods.none { method ->
                    method.code().any { it.referenceText() == PARTNERSHIP_PAGE_FILTER }
                })
            }

            val values = sourceValues(FixtureDex.classes(bundle, setOf(VIEWER_SOURCE)).values.single())
            val lane = marked.getValue(LANE_SOURCE).single()
            val lanes = lane.methods.single { it.name == "<clinit>" }.code().mapNotNull { instruction ->
                (instruction.reference() as? FieldReference)?.takeIf { instruction.opcode == Opcode.SGET_OBJECT && it.definingClass == VIEWER_SOURCE }
                    ?.let { "clips_viewer_" + values[it.name] }
            }.toSet()
            // 451's Redex inlines getClipsTabContainerModule into its callers, so its names are those of every marked copy.
            val container = markedBy(TAB_CONTAINER_MODULE).flatMap { method -> method.code().mapNotNull { it.string() } }.filter { it.startsWith("clips_viewer_") }
            assertTrue("${bundle.name}: the tab container answers no name", container.isNotEmpty())
            // 453's builds don't all offer the same lanes (some have no blend lane), so the list may name
            // a lane this build doesn't offer, as long as it's one of the viewer's sources here.
            assertEquals("${bundle.name}: the Reels tab's module names", lanes + container, tabModules.intersect(lanes + container))
            for (extra in tabModules - lanes - container) {
                assertTrue("${bundle.name}: $extra is a viewer's source", extra.removePrefix("clips_viewer_") in values.values)
            }
            for (chosen in listOf(
                "clips_profile", "self_clips_profile", "serp_top_clips_unit", "audio_page", "feed_hashtag",
                "discovery_map_location_detail", "feed_saved_collections_clips", "direct",
            )) {
                assertTrue("${bundle.name}: $chosen isn't a viewer's source", chosen in values.values)
                assertFalse("${bundle.name}: the viewer opened from $chosen is filtered", "clips_viewer_$chosen" in tabModules)
            }
            checked++
        }
        assertEquals("every build of 452 is read", Fixtures.BUILDS, checked)
    }

    /**
     * Where the page and its fallback meet: the fallback's answer falls into the hook and the
     * page's check jumps to it. The hook reads the module field off this into the scratch local,
     * passes the list and the name to the filter and keeps its answer in the list's register.
     */
    private fun assertHookAtJoin(what: String, method: MutableMethod, sites: PaidPartnershipSites, pageName: String) {
        val code = method.code()
        val at = sites.join
        val self = method.implementation!!.registerCount - 1 - method.parameterTypes.size
        assertTrue("$what: the scratch v${sites.scratch} next to the list v${sites.list}", sites.scratch <= 15 && sites.scratch != sites.list)
        assertEquals(
            "$what: the hook at $at",
            listOf(
                "move-object/from16", "iget-object ${sites.callback}->${sites.moduleField}:$string",
                "invoke-static $PARTNERSHIP_PAGE_FILTER", "move-result-object",
            ),
            code.subList(at, at + 4).map { listOfNotNull(it.opcode.name, it.referenceText()).joinToString(" ") },
        )
        assertEquals("$what: this into the scratch", listOf(sites.scratch, self), (code[at] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
        assertEquals("$what: the field read", listOf(sites.scratch, sites.scratch), (code[at + 1] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
        assertEquals("$what: the filter's registers", listOf(sites.list, sites.scratch), (code[at + 2] as FiveRegisterInstruction).let {
            listOf(it.registerC, it.registerD).take(it.registerCount)
        })
        assertEquals("$what: the answer's register", sites.list, (code[at + 3] as OneRegisterInstruction).registerA)
        assertEquals("$what: one filter call", 1, code.count { it.referenceText() == PARTNERSHIP_PAGE_FILTER })

        assertEquals("$what: the fallback's answer before the hook", sites.list, (code[at - 1] as OneRegisterInstruction).registerA)
        val fallback = code[at - 2].reference() as MethodReference
        assertEquals("$what: the fallback's source", sites.pageInterface, fallback.definingClass)
        val page = code.indexOfFirst { (it.reference() as? MethodReference)?.let { ref -> ref.definingClass == sites.pageInterface && ref.name == pageName } == true }
        val jumps = method.implementation!!.instructions.withIndex()
            .filter { (_, instruction) -> (instruction as? BuilderOffsetInstruction)?.target?.location?.index == at }.map { it.index }
        assertTrue("$what: the page's check doesn't land on the hook: $jumps", jumps.isNotEmpty() && jumps.all { it in page + 2 until at - 2 })
    }

    /**
     * The field of [callback]'s constructor parameter that [fetch] fills straight from the
     * viewer's getModuleName, read off the code here rather than the patch's finder.
     */
    private fun moduleFieldOf(fetch: Method, callback: ClassDef): String {
        val code = fetch.code()
        val fields = code.indices.filter { at ->
            (code[at].reference() as? MethodReference)?.let { it.definingClass == callback.type && it.name == "<init>" } == true
        }.map { at ->
            val constructor = code[at].reference() as MethodReference
            val parameters = constructor.parameterTypes.map(Any::toString)
            val registers = code[at].invokeRegisters()
            val slot = parameters.indices.single { index ->
                val register = registers[1 + parameters.take(index).sumOf { it.width() }]
                val writer = (at - 1 downTo 1).first { code[it].writes(register) }
                code[writer].opcode == Opcode.MOVE_RESULT_OBJECT &&
                    (code[writer - 1].reference() as? MethodReference)?.name == "getModuleName"
            }
            val body = callback.methods.single { it.name == "<init>" && it.parameterTypes.map(Any::toString) == parameters }
            val held = body.implementation!!.registerCount - 1 - parameters.sumOf { it.width() } + 1 + parameters.take(slot).sumOf { it.width() }
            body.code().filter { it.opcode == Opcode.IPUT_OBJECT && (it as TwoRegisterInstruction).registerA == held }
                .map { (it.reference() as FieldReference).name }.single()
        }
        return fields.distinct().single()
    }

    /** Each ClipsViewerSource constant's source string, by field, as its static initializer makes them. */
    private fun sourceValues(source: ClassDef): Map<String, String> {
        val values = mutableMapOf<String, String>()
        val held = mutableMapOf<Int, Any>()
        val valueOf = IdentityHashMap<Any, String>()
        for (instruction in source.methods.single { it.name == "<clinit>" }.code()) {
            val opcode = instruction.opcode
            val reference = instruction.reference()
            when {
                opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO ->
                    held[(instruction as OneRegisterInstruction).registerA] = (reference as StringReference).string
                opcode == Opcode.NEW_INSTANCE -> held[(instruction as OneRegisterInstruction).registerA] = Any()
                opcode == Opcode.MOVE_OBJECT || opcode == Opcode.MOVE_OBJECT_FROM16 || opcode == Opcode.MOVE_OBJECT_16 -> {
                    val move = instruction as TwoRegisterInstruction
                    held[move.registerB]?.let { held[move.registerA] = it } ?: held.remove(move.registerA)
                }
                (opcode == Opcode.INVOKE_DIRECT || opcode == Opcode.INVOKE_DIRECT_RANGE) &&
                    (reference as? MethodReference)?.let { it.definingClass == source.type && it.name == "<init>" } == true -> {
                    val registers = instruction.invokeRegisters()
                    val strings = registers.drop(1).mapNotNull { held[it] as? String }
                    val made = held[registers.first()]
                    if (made != null && strings.size >= 2) valueOf[made] = strings[1]
                }
                opcode == Opcode.SPUT_OBJECT && (reference as? FieldReference)?.definingClass == source.type -> {
                    held[(instruction as OneRegisterInstruction).registerA]?.let { valueOf[it] }
                        ?.let { values[reference.name] = it }
                }
                opcode.setsRegister() && instruction is OneRegisterInstruction -> {
                    held.remove(instruction.registerA)
                    if (opcode.setsWideRegister()) held.remove(instruction.registerA + 1)
                }
            }
        }
        return values
    }

    /** The media stub answers the item's post for an item, null otherwise, and the two getter stubs read the post's fields. */
    private fun assertStubsFilled(what: String, context: BytecodePatchContext, itemType: String, field: String, partnership: String, boosted: String) {
        val methods = context.mutableClassDefBy(PAID_PARTNERSHIP_REELS).methods
        val media = methods.single { it.name == "media" }
        assertEquals("$what: the media stub's registers", 2, media.implementation!!.registerCount)
        assertEquals(
            "$what: the media stub",
            listOf(
                "instance-of $itemType", "if-nez", "const/4", "return-object", "check-cast $itemType",
                "iget-object $itemType->$field:$MEDIA", "return-object",
            ),
            media.code().map { listOfNotNull(it.opcode.name, it.referenceText()).joinToString(" ") },
        )
        for ((stub, getter, returns) in listOf(
            Triple("paidPartnership", partnership, "Ljava/lang/Boolean;"),
            Triple("boostedStatus", boosted, string),
        )) {
            val method = methods.single { it.name == stub }
            assertEquals("$what: the $stub stub's registers", 1, method.implementation!!.registerCount)
            assertEquals(
                "$what: the $stub stub",
                listOf("check-cast $MEDIA", "invoke-virtual $MEDIA->$getter()$returns", "move-result-object", "return-object"),
                method.code().map { listOfNotNull(it.opcode.name, it.referenceText()).joinToString(" ") },
            )
        }
    }

    // ---- stand-ins shaped like Instagram 450's -------------------------------------------------

    /**
     * The converter; the item with its post; Media with its getters; the source interface; a source
     * whose page calls the converter, and one whose page calls the static helper that does; a static
     * method answering a List that isn't a page; the viewer's network callback, asking its source
     * for the page and, when that's empty, for the fallback; the fetcher making the callback with the
     * viewer's module name; and the extension.
     */
    private fun classes(
        converters: Int = 1,
        itemPublic: Boolean = true,
        mediaFields: List<String> = listOf("post"),
        mediaFieldPublic: Boolean = true,
        pageAnswers: String = list,
        helpedName: String = "page",
        helpedInterface: String? = source,
        successMarks: Int = 1,
        successTakes: String = source,
        fallback: Boolean = true,
        fallbackInto: Int? = null,
        pageOverwritten: Boolean = false,
        earlyJump: Boolean = false,
        thisReused: Boolean = false,
        listRegister: Int = 2,
        fetchMarked: Boolean = true,
        fetchMakes: Boolean = true,
        fetchNamed: Boolean = true,
        moduleKeptTwice: Boolean = false,
        moduleWrittenElsewhere: Boolean = false,
        partnershipField: String = PAID_PARTNERSHIP_FIELD,
        boostedPublic: Boolean = true,
        extension: Boolean = true,
    ): List<ClassDef> {
        val converterClasses = (0 until converters).map { copy ->
            val type = if (copy == 0) converter else "Lfixture/OtherConverter;"
            classDef(type, listOf(method(type, "convert", listOf("Lfixture/ServerItem;", session), item, 1, static = true, body = """
                const-string v0, "$TO_CLIPS_ITEM"
                new-instance v0, $item
                invoke-direct { v0 }, $item-><init>()V
                return-object v0
            """)))
        }
        val itemClass = classDef(
            item, emptyList(), public = itemPublic,
            fields = mediaFields.map { it to MEDIA }, fieldsPublic = mediaFieldPublic,
        )
        val mediaClass = classDef(MEDIA, listOf(
            getter("partnership", partnershipField, "Ljava/lang/Boolean;"),
            getter("boost", BOOSTED_STATUS_FIELD, string, public = boostedPublic),
            getter("caption", "caption", string),
        ))
        val interfaces = listOf(source, otherSource).map { type ->
            interfaceDef(type, listOf("page", "older", "items").map { abstractMethod(type, it, listOf(session), pageAnswers) })
        }
        val directSource = classDef(direct, listOf(
            method(direct, "page", listOf(session), pageAnswers, 2, body = """
                iget-object v0, p0, $direct->items:$pageAnswers
                if-nez v0, :done
                const/4 v1, 0x0
                invoke-static { v1, p1 }, $convert
                move-result-object v1
                new-instance v0, Ljava/util/ArrayList;
                invoke-direct { v0 }, Ljava/util/ArrayList;-><init>()V
                :done
                return-object v0
            """),
            method(direct, "older", listOf(session), pageAnswers, 1, body = """
                iget-object v0, p0, $direct->items:$pageAnswers
                return-object v0
            """),
        ), fields = listOf("items" to pageAnswers), interfaces = listOf(source))
        val helperClass = classDef(helpers, listOf(
            method(helpers, "fill", listOf(session, "Ljava/util/AbstractCollection;", "Ljava/util/Iterator;"), "V", 1, static = true, body = """
                const/4 v0, 0x0
                invoke-static { v0, p0 }, $convert
                return-void
            """),
        ))
        val helpedSource = classDef(helped, listOf(
            method(helped, helpedName, listOf(session), pageAnswers, 2, body = """
                new-instance v0, Ljava/util/ArrayList;
                invoke-direct { v0 }, Ljava/util/ArrayList;-><init>()V
                const/4 v1, 0x0
                invoke-static { p1, v0, v1 }, $fill
                return-object v0
            """),
        ), interfaces = listOfNotNull(helpedInterface))
        val otherClass = classDef(other, listOf(
            method(other, "list", listOf(session), list, 2, static = true, body = """
                const/4 v0, 0x0
                invoke-static { v0, p0 }, $convert
                new-instance v1, Ljava/util/ArrayList;
                invoke-direct { v1 }, Ljava/util/ArrayList;-><init>()V
                return-object v1
            """),
        ))

        // v0 the marker and the check, v1 the session, v3 the source, v4 this's copy, the page in [listRegister].
        val page = "v$listRegister"
        val successBody = buildString {
            if (successMarks > 0) appendLine("const-string v0, \"android_purge_26_q2_$REELS_PAGE_ARRIVED\"")
            appendLine("move-object/from16 v4, p0")
            appendLine("iget-object v1, v4, $callback->session:$session")
            appendLine("move-object/from16 v3, p1")
            if (earlyJump) appendLine("if-eqz v3, :join")
            appendLine("invoke-interface { v3, v1 }, $successTakes->page($session)$list")
            appendLine("move-result-object $page")
            if (thisReused) appendLine("move-object/from16 p0, v1")
            appendLine("invoke-interface/range { $page .. $page }, $list->isEmpty()Z")
            appendLine("move-result v0")
            if (pageOverwritten) appendLine("const/4 $page, 0x0")
            appendLine("if-eqz v0, :join")
            if (fallback) {
                appendLine("invoke-interface { v3, v1 }, $successTakes->older($session)$list")
                appendLine("move-result-object v${fallbackInto ?: listRegister}")
            }
            appendLine(":join")
            appendLine("invoke-static/range { $page .. $page }, $viewer->show($list)V")
            appendLine("return-void")
        }
        val callbackMethods = listOfNotNull(
            method(callback, "<init>", listOf(session, string, string), "V", 0, body = """
                invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
                iput-object p1, p0, $callback->session:$session
                iput-object p2, p0, $callback->path:$string
                iput-object p3, p0, $callback->module:$string
                ${if (moduleKeptTwice) "iput-object p3, p0, $callback->path:$string" else ""}
                return-void
            """),
            method(callback, "success", listOf(successTakes), "V", maxOf(5, listRegister + 1), body = successBody),
            if (moduleWrittenElsewhere) method(callback, "rename", listOf(string), "V", 0, body = """
                iput-object p1, p0, $callback->module:$string
                return-void
            """) else null,
        )
        val callbackClass = classDef(
            callback, callbackMethods,
            fields = listOf("session" to session, "path" to string, "module" to string),
        )
        val otherCallback = if (successMarks > 1) listOf(classDef("Lfixture/OtherCallback;", listOf(
            method("Lfixture/OtherCallback;", "success", listOf(source), "V", 1, body = """
                const-string v0, "android_purge_26_q2_$REELS_PAGE_ARRIVED"
                return-void
            """),
        ))) else emptyList()
        val fetchBody = buildString {
            if (fetchMarked) appendLine("const-string v0, \"android_purge_26_q2_$REELS_FETCH\"")
            appendLine("iget-object v1, p0, $fetcher->viewer:$viewer")
            if (fetchNamed) {
                appendLine("invoke-interface { v1 }, $viewer->getModuleName()$string")
                appendLine("move-result-object v2")
            } else {
                appendLine("const-string v2, \"clips_viewer_clips_tab\"")
            }
            appendLine("const-string v3, \"clips/discover/\"")
            appendLine("iget-object v4, p0, $fetcher->session:$session")
            if (fetchMakes) {
                appendLine("new-instance v0, $callback")
                appendLine("invoke-direct { v0, v4, v3, v2 }, $callback-><init>($session$string$string)V")
            }
            appendLine("return-void")
        }
        val fetcherClass = classDef(
            fetcher, listOf(method(fetcher, "fetch", emptyList(), "V", 5, body = fetchBody)),
            fields = listOf("viewer" to viewer, "session" to session),
        )
        return converterClasses + itemClass + mediaClass + interfaces + directSource + helperClass + helpedSource + otherClass +
            callbackClass + otherCallback + fetcherClass +
            if (extension) listOf(ExtensionDex.classDef(PAID_PARTNERSHIP_REELS)) else emptyList()
    }

    /** A Media getter reading [field] the way 450's do: by the hash of its name, loaded as a literal. */
    private fun getter(name: String, field: String, returns: String, public: Boolean = true): Method =
        method(MEDIA, name, emptyList(), returns, 1, public = public, body = """
            const v0, ${field.hashCode()}
            const/4 v0, 0x0
            return-object v0
        """)

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        static: Boolean = false,
        public: Boolean = true,
        body: String,
    ): Method {
        var flags = if (public) AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value
        if (static) flags = flags or AccessFlags.STATIC.value
        if (name == "<init>") flags = flags or AccessFlags.CONSTRUCTOR.value
        val total = registers + (if (static) 0 else 1) + parameters.size
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun abstractMethod(owner: String, name: String, parameters: List<String>, returns: String): Method =
        ImmutableMethod(
            owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
            AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null,
        )

    private fun classDef(
        type: String,
        methods: List<Method>,
        public: Boolean = true,
        fields: List<Pair<String, String>> = emptyList(),
        fieldsPublic: Boolean = true,
        interfaces: List<String> = emptyList(),
    ): ClassDef = ImmutableClassDef(
        type, (if (public) AccessFlags.PUBLIC.value else 0) or AccessFlags.FINAL.value, "Ljava/lang/Object;", interfaces, null, null,
        fields.map { (name, fieldType) ->
            ImmutableField(type, name, fieldType, if (fieldsPublic) AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value, null, null, null)
        },
        methods,
    )

    private fun interfaceDef(type: String, methods: List<Method>): ClassDef = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value, "Ljava/lang/Object;",
        null, null, null, null, methods,
    )

    // ---- reading code ---------------------------------------------------------------------------

    private fun Method.isPageShaped() = !AccessFlags.STATIC.isSet(accessFlags) && returnType == list &&
        parameterTypes.map(Any::toString) == listOf(session)

    private fun Method.callsAny(targets: List<Method>) =
        code().any { instruction -> (instruction.reference() as? MethodReference)?.let { ref -> targets.any { ref.calls(it) } } == true }

    private fun MethodReference.calls(target: Method) = definingClass == target.definingClass && name == target.name &&
        returnType == target.returnType && parameterTypes.map(Any::toString) == target.parameterTypes.map(Any::toString)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = when (val reference = reference()) {
        is TypeReference -> reference.type
        is FieldReference, is MethodReference -> reference.toString()
        else -> null
    }

    private fun Instruction.string(): String? = (reference() as? StringReference)?.string

    private fun Instruction.invokeRegisters(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    private fun Instruction.writes(register: Int): Boolean {
        if (!opcode.setsRegister()) return false
        val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
        return destination == register || (opcode.setsWideRegister() && destination + 1 == register)
    }

    private fun String.width() = if (this == "J" || this == "D") 2 else 1

    /** Whether a getter loads [value], the hash of the field it reads. */
    private fun Method.loads(value: Int): Boolean = code().any { (it as? NarrowLiteralInstruction)?.narrowLiteral == value }

    private companion object {
        /** The marker of the lane enum's answer for which viewer source a lane of the Reels tab shows. */
        const val LANE_SOURCE = "ClipsContentLaneEnum_getContentSource"

        /** The marker of the Reels tab's container module name, For you's under the homecoming test or not. */
        const val TAB_CONTAINER_MODULE = "ClipsViewerSource_getClipsTabContainerModule"

        /** The marker of the mapper making a profile's Reels source from its GraphQL answer. */
        const val PROFILE_MAPPER = "ClipsProfileApiUtil_mapGraphQLResponseToREST"

        const val VIEWER_SOURCE = "Lcom/instagram/clips/intf/ClipsViewerSource;"
        const val SEARCH_CONTEXT = "Lcom/instagram/search/common/analytics/SearchContext;"
    }
}

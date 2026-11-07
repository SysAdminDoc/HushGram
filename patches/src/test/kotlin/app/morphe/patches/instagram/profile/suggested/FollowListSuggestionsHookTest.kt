/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Hide Suggested for you on follow lists, Hide suggested people on profiles' second switch: the list's read, found and answered. */
class FollowListSuggestionsHookTest {
    private val arguments = "Landroid/os/Bundle;"
    private val list = "Lfixture/FollowList;"
    private val opener = "Lfixture/FollowListOpener;"
    private val getBoolean = "Landroid/os/BaseBundle;->getBoolean(Ljava/lang/String;Z)Z"
    /** The tab key is read without a default (#302). */
    private val getBooleanNoDefault = "Landroid/os/BaseBundle;->getBoolean(Ljava/lang/String;)Z"

    /** The hooks the patch writes are in the ProfileSuggestions the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(FOLLOW_LIST.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(FOLLOW_LIST, SUGGESTED_TAB, SUGGESTED_SUBSCRIPTIONS, MORE_SUGGESTED_SUBSCRIPTIONS, FRIENDING_BUTTON)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /** The list's read gets its answer passed through the extension, the other read and the opener left alone. */
    @Test
    fun theListsReadIsAnswered() {
        val context = PatchContexts.of(classes())
        val before = classes().single { it.type == list }.methods.single().code().size

        context.hideFollowListSuggestions(context.findFollowListSuggestions())

        val patched = context.mutableClassDefBy(list).methods.single()
        assertAnswered("stand-in", patched)
        assertEquals("what else was added", before + 2, patched.code().size)
        assertTrue("the opener", context.mutableClassDefBy(opener).methods.single().code().none { it.referenceText() == FOLLOW_LIST })
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(reads = 0) to "expected one read of the follow list's $FETCH_SUGGESTED, found 0",
            classes(reads = 2) to "expected one read of the follow list's $FETCH_SUGGESTED, found 2",
            classes(keyFrom = "v1") to "expected one read of the follow list's $FETCH_SUGGESTED, found 0",
            classes(kept = false) to "$list->onCreate reads $FETCH_SUGGESTED into nothing",
            classes(jumpPast = true) to "something in $list->onCreate jumps past its read of $FETCH_SUGGESTED",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) {
                context.hideFollowListSuggestions(context.findFollowListSuggestions())
            }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == FOLLOW_LIST } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /** #302: a build without the Suggested tab's read refuses that part at patch time, and the follow list's hook still goes in. */
    @Test
    fun aBuildWithoutTheTabsReadStillHidesTheSection() {
        val context = PatchContexts.of(classes())
        val failure = assertThrows(PatchException::class.java) { context.findSuggestedTab() }
        val expected = "expected one read of the follow list's $HAS_SIMILAR_ACCOUNTS, found 0"
        assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
        assertNull(context.findSuggestedTabOrWarn())

        context.hideFollowListSuggestions(context.findFollowListSuggestions())

        val patched = context.mutableClassDefBy(list).methods.single()
        assertAnswered("stand-in", patched)
        assertTrue("no tab hook", patched.code().none { it.referenceText() == SUGGESTED_TAB })
    }

    /**
     * In each declared build the one read of the key is the follow list fragment's, in its
     * onCreate, and it's answered. On 450's 385611438 that's LX/0E6X (FollowListFragment).onCreate.
     */
    @Test
    fun eachDeclaredBuildHidesTheSection() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        // The declared build and the other builds of the version, each compiled on its own.
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("every 452 build: ${bundles.map { it.name }}", Fixtures.BUILDS, bundles.size)
        for (bundle in bundles) {
            val holders = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    if (classDef.methods.any { m -> m.code().any { it.string() == FETCH_SUGGESTED } }) holders += ImmutableClassDef.of(classDef)
                }
            }
            val context = PatchContexts.of(holders)

            val site = context.findFollowListSuggestions()
            context.hideFollowListSuggestions(site)

            assertEquals("${bundle.name}: the read", "onCreate($arguments)V", "${site.name}(${site.parameters.joinToString("")})V")
            val patched = context.mutableClassDefBy(site.type).methods.single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }
            assertAnswered(bundle.name, patched)
            assertEquals(
                "${bundle.name}: methods hooked", 1,
                holders.map { it.type }.distinct().sumOf { type -> context.mutableClassDefBy(type).methods.count { m -> m.code().any { it.referenceText() == FOLLOW_LIST } } },
            )
        }
    }

    /**
     * #302: in each declared build the one read of the Suggested tab's key is the follow list pager's,
     * in its onCreate, and it's answered. The places opening the pager put the key or ask whether
     * it's there, and stay as they were. On 452.1 that's UnifiedFollowFragment.onCreate, which reads
     * the key without a default.
     */
    @Test
    fun eachDeclaredBuildHidesTheSuggestedTab() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("every 452 build: ${bundles.map { it.name }}", Fixtures.BUILDS, bundles.size)
        for (bundle in bundles) {
            val holders = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    if (classDef.methods.any { m -> m.code().any { it.string() == HAS_SIMILAR_ACCOUNTS } }) holders += ImmutableClassDef.of(classDef)
                }
            }
            val context = PatchContexts.of(holders)

            val site = context.findSuggestedTab()
            context.hideSuggestedTab(site)

            assertEquals("${bundle.name}: the read", "onCreate($arguments)V", "${site.name}(${site.parameters.joinToString("")})V")
            assertTrue("${bundle.name}: the pager ${site.type}", site.type.endsWith("/UnifiedFollowFragment;"))
            val patched = context.mutableClassDefBy(site.type).methods.single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }
            assertAnswered(bundle.name, patched, HAS_SIMILAR_ACCOUNTS, SUGGESTED_TAB, getBooleanNoDefault)
            assertEquals(
                "${bundle.name}: methods hooked", 1,
                holders.map { it.type }.distinct().sumOf { type -> context.mutableClassDefBy(type).methods.count { m -> m.code().any { it.referenceText() == SUGGESTED_TAB } } },
            )
        }
    }

    /** #539: both Subscriptions tab replies have their creators answered, and the paged one its next page, each once. */
    @Test
    fun theSubscriptionTabsRepliesAreAnswered() {
        val context = PatchContexts.of(subscriptionClasses())
        val sites = context.findSuggestedSubscriptionsOrWarn()
        assertEquals("both requests", listOf("A00", "A02"), sites.map { it.name })

        sites.forEach { context.hideSuggestedSubscriptions(it) }

        val methods = context.mutableClassDefBy(repository).methods
        val paged = sites.single { it.name == "A00" }
        val followed = methods.single { it.name == "A00" }
        assertHooked("followed", followed, paged.result, SUGGESTED_SUBSCRIPTIONS, Opcode.MOVE_RESULT_OBJECT, Opcode.IPUT_OBJECT)
        assertHooked("its next page", followed, paged.more!!.result + 2, MORE_SUGGESTED_SUBSCRIPTIONS, Opcode.MOVE_RESULT, Opcode.IPUT_BOOLEAN)
        val single = sites.single { it.name == "A02" }
        assertNull("the unconnected request doesn't page", single.more)
        val unconnected = methods.single { it.name == "A02" }
        assertHooked("unconnected", unconnected, single.result, SUGGESTED_SUBSCRIPTIONS, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_INTERFACE)
        assertTrue("no page hook there", unconnected.code().none { it.referenceText() == MORE_SUGGESTED_SUBSCRIPTIONS })
    }

    /** #539: a reply the patch can't read fails at patch time, saying what it found, before anything is written, and the other request still goes in. */
    @Test
    fun aSubscriptionReplyThePatchCantReadFailsBeforeAnythingChanges() {
        val path = FOLLOWED_CREATORS_TO_SUBSCRIBE
        val where = "$repository->A00"
        val cases = listOf(
            subscriptionClasses(paths = 0) to "expected one request for $path, found 0",
            subscriptionClasses(paths = 2) to "expected one request for $path, found 2",
            subscriptionClasses(kept = false) to "$where reads the creators $path answers with into nothing",
            subscriptionClasses(jumpPast = true) to "something in $where jumps past its read of the creators $path answers with",
            subscriptionClasses(pages = false) to "$where never reads whether $path has another page",
            subscriptionClasses(pageKept = false) to "$where doesn't keep whether $path has another page",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) {
                context.hideSuggestedSubscriptions(context.findSuggestedSubscriptions(path, pages = true))
            }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = context.mutableClassDefBy(repository).methods.filter { method ->
                method.code().any { it.referenceText() == SUGGESTED_SUBSCRIPTIONS || it.referenceText() == MORE_SUGGESTED_SUBSCRIPTIONS }
            }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
        val context = PatchContexts.of(subscriptionClasses(kept = false))
        assertEquals("the other request", listOf("A02"), context.findSuggestedSubscriptionsOrWarn().map { it.name })
    }

    /** #539: the pager's own-lists check before the Discover people button is answered; the click handler loading the same strings stays. */
    @Test
    fun theDiscoverButtonsCheckIsAnswered() {
        val context = PatchContexts.of(pagerClasses())
        val site = context.findFriendingButton()

        context.hideFriendingButton(site)

        assertEquals("the bar", "$pager->Aez", "${site.type}->${site.name}")
        val patched = context.mutableClassDefBy(pager).methods.single { it.name == "Aez" }
        assertHooked("stand-in", patched, site.result, FRIENDING_BUTTON, Opcode.MOVE_RESULT, Opcode.IF_EQZ)
        assertTrue("the click handler", context.mutableClassDefBy(click).methods.single().code().none { it.referenceText() == FRIENDING_BUTTON })
    }

    /** #539: a bar the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBarThePatchCantReadFailsBeforeAnythingChanges() {
        val where = "$pager->Aez"
        val cases = listOf(
            pagerClasses(tab = false) to "expected one read of the follow list's $HAS_SIMILAR_ACCOUNTS, found 0",
            pagerClasses(seen = false) to "expected one action bar in $pager loading \"$FRIENDING_LOGGER\" and \"$SELF_FOLLOW_LISTS\", found 0",
            pagerClasses(checks = 0) to "expected one check in $where that ends the bar before its button, found 0",
            pagerClasses(checks = 2) to "expected one check in $where that ends the bar before its button, found 2",
            pagerClasses(endsTheBar = false) to "expected one check in $where that ends the bar before its button, found 0",
            pagerClasses(jumpIn = true) to "something in $where jumps past its check before the button",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) {
                context.hideFriendingButton(context.findFriendingButton())
            }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            assertNull(context.findFriendingButtonOrWarn())
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == FRIENDING_BUTTON } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /**
     * #539: in each declared build the Subscriptions tab's two suggestion requests are each read
     * once, the paged one's next page too, and each is answered. On 454 those are the creator list
     * repository's A00 (followed creators, paged) and A02 (creators you don't follow).
     */
    @Test
    fun eachDeclaredBuildHidesSuggestedSubscriptions() {
        for (bundle in declaredBundles()) {
            val holders = (FixtureDex.classesLoading(bundle, FOLLOWED_CREATORS_TO_SUBSCRIBE) + FixtureDex.classesLoading(bundle, UNCONNECTED_CREATORS_TO_SUBSCRIBE))
                .distinctBy { it.type }
            val context = PatchContexts.of(holders)

            val followed = context.findSuggestedSubscriptions(FOLLOWED_CREATORS_TO_SUBSCRIBE, pages = true)
            val unconnected = context.findSuggestedSubscriptions(UNCONNECTED_CREATORS_TO_SUBSCRIBE, pages = false)
            assertTrue("${bundle.name}: two methods", followed.type != unconnected.type || followed.name != unconnected.name)
            context.hideSuggestedSubscriptions(followed)
            context.hideSuggestedSubscriptions(unconnected)

            val paged = methodAt(context, followed.type, followed.name, followed.parameters)
            assertHooked("${bundle.name} followed", paged, followed.result, SUGGESTED_SUBSCRIPTIONS, Opcode.MOVE_RESULT_OBJECT, null)
            assertHooked("${bundle.name} next page", paged, followed.more!!.result + 2, MORE_SUGGESTED_SUBSCRIPTIONS, Opcode.MOVE_RESULT, Opcode.IPUT_BOOLEAN)
            assertHooked(
                "${bundle.name} unconnected", methodAt(context, unconnected.type, unconnected.name, unconnected.parameters),
                unconnected.result, SUGGESTED_SUBSCRIPTIONS, Opcode.MOVE_RESULT_OBJECT, null,
            )
            val hooked = holders.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }.flatMap { it.code() }
            assertEquals("${bundle.name}: creator hooks", 2, hooked.count { it.referenceText() == SUGGESTED_SUBSCRIPTIONS })
            assertEquals("${bundle.name}: page hooks", 1, hooked.count { it.referenceText() == MORE_SUGGESTED_SUBSCRIPTIONS })
        }
    }

    /**
     * #539: in each declared build the follow lists pager's action bar checks the lists are yours
     * once before its Discover people button, and that check is answered. On 454 that's
     * UnifiedFollowFragment's Aez; the button's click handler, which loads the same strings, stays.
     */
    @Test
    fun eachDeclaredBuildHidesTheDiscoverButton() {
        for (bundle in declaredBundles()) {
            val holders = (FixtureDex.classesLoading(bundle, FRIENDING_LOGGER) + FixtureDex.classesLoading(bundle, HAS_SIMILAR_ACCOUNTS)).distinctBy { it.type }
            val context = PatchContexts.of(holders)

            val site = context.findFriendingButton()
            context.hideFriendingButton(site)

            assertTrue("${bundle.name}: the pager ${site.type}", site.type.endsWith("/UnifiedFollowFragment;"))
            assertHooked(bundle.name, methodAt(context, site.type, site.name, site.parameters), site.result, FRIENDING_BUTTON, Opcode.MOVE_RESULT, Opcode.IF_EQZ)
            assertEquals(
                "${bundle.name}: methods hooked", 1,
                holders.map { it.type }.distinct().sumOf { type -> context.mutableClassDefBy(type).methods.count { m -> m.code().any { it.referenceText() == FRIENDING_BUTTON } } },
            )
        }
    }

    private fun declaredBundles(): List<File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("every declared build: ${bundles.map { it.name }}", Fixtures.BUILDS, bundles.size)
        return bundles
    }

    private fun methodAt(context: app.morphe.patcher.patch.BytecodePatchContext, type: String, name: String, parameters: List<String>): Method =
        context.mutableClassDefBy(type).methods.single { it.name == name && it.parameterTypes.map(CharSequence::toString) == parameters }

    /**
     * The read's move-result at [result], then the range call handing [hook] that register, the
     * answer back in it by [answer], and [next] (when given) where the method carries on.
     */
    private fun assertHooked(what: String, method: Method, result: Int, hook: String, answer: Opcode, next: Opcode?) {
        val code = method.code()
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == hook })
        val register = (code[result] as OneRegisterInstruction).registerA
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[result + 1].opcode)
        assertEquals("$what: the hook", hook, code[result + 1].referenceText())
        assertEquals("$what: what it's handed", register, (code[result + 1] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the answer", answer, code[result + 2].opcode)
        assertEquals("$what: the register", register, (code[result + 2] as OneRegisterInstruction).registerA)
        if (next != null) assertEquals("$what: what comes next", next, code[result + 3].opcode)
    }

    private val repository = "Lfixture/CreatorRepository;"
    private val reply = "Lfixture/CreatorReply;"

    /**
     * Shaped like 454's creator list repository: one request for followed creators that pages, read
     * after another call on its reply, and one for creators you don't follow, handed to a flow.
     */
    private fun subscriptionClasses(
        paths: Int = 1,
        kept: Boolean = true,
        jumpPast: Boolean = false,
        pages: Boolean = true,
        pageKept: Boolean = true,
    ): List<ClassDef> {
        val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val followed = buildList {
            repeat(paths) {
                add("const-string v0, \"$FOLLOWED_CREATORS_TO_SUBSCRIBE\"")
                add("iput-object v0, p0, $repository->path:Ljava/lang/String;")
            }
            add("invoke-static {p1}, Lfixture/Api;->send(Ljava/lang/String;)$reply")
            add("move-result-object v1")
            add("invoke-interface {v1}, $reply->getValue()Ljava/lang/Object;")
            add("move-result-object v2")
            if (jumpPast) add("if-eqz v2, :after")
            add("invoke-interface {v1}, $reply->creators()Ljava/util/List;")
            if (kept) add("move-result-object v0")
            if (jumpPast) add(":after")
            add("iput-object v0, p0, $repository->creators:Ljava/util/List;")
            add("invoke-interface {v1}, $reply->cursor()Ljava/lang/String;")
            add("move-result-object v0")
            if (pages) {
                add("invoke-interface {v1}, $reply->more()Z")
                add("move-result v0")
                if (pageKept) add("iput-boolean v0, p0, $repository->more:Z")
            }
            add("return-object v1")
        }.joinToString("\n")
        val unconnected = listOf(
            "const-string v0, \"$UNCONNECTED_CREATORS_TO_SUBSCRIBE\"",
            "invoke-static {v0}, Lfixture/Api;->send(Ljava/lang/String;)$reply",
            "move-result-object v1",
            "invoke-interface {v1}, $reply->creators()Ljava/util/List;",
            "move-result-object v0",
            "invoke-interface {p1, v0}, Lfixture/Flow;->set(Ljava/lang/Object;)V",
            "return-object v1",
        ).joinToString("\n")
        return listOf(
            classDef(
                repository, "Ljava/lang/Object;",
                listOf(
                    method(repository, "A00", listOf(repository, "Ljava/lang/String;"), "Ljava/lang/Object;", 3, static, followed),
                    method(repository, "A02", listOf(repository, "Lfixture/Flow;"), "Ljava/lang/Object;", 2, static, unconnected),
                ),
            ),
        )
    }

    private val pager = "Lfixture/FollowPager;"
    private val click = "Lfixture/FriendingClick;"
    private val bar = "Lfixture/ActionBar;"

    /**
     * Shaped like 454's UnifiedFollowFragment: onCreate reads whether to add the Suggested tab, and
     * the action bar reads whether to show itself, sets its title, checks the lists are yours (ending
     * there when they aren't), adds the button and says it was seen. The click handler, a class of
     * its own, loads the same two strings.
     */
    private fun pagerClasses(
        tab: Boolean = true,
        seen: Boolean = true,
        checks: Int = 1,
        endsTheBar: Boolean = true,
        jumpIn: Boolean = false,
    ): List<ClassDef> {
        val onCreate = listOfNotNull(
            if (tab) "const-string v0, \"$HAS_SIMILAR_ACCOUNTS\"" else "const-string v0, \"UnifiedFollowFragment.OTHER\"",
            "invoke-virtual {p1, v0}, $getBooleanNoDefault",
            "move-result v0",
            "iput-boolean v0, p0, $pager->similar:Z",
            "return-void",
        ).joinToString("\n")
        val actionBar = buildList {
            if (jumpIn) add("if-eqz p1, :test0")
            add("iget-object v1, p0, $pager->arguments:$arguments")
            add("const-string v0, \"UnifiedFollowFragment.SHOW_ACTION_BAR\"")
            add("invoke-virtual {v1, v0}, $getBooleanNoDefault")
            add("move-result v0")
            add("if-nez v0, :bar")
            add(":end")
            add("return-void")
            add(":bar")
            add("invoke-virtual {p1}, $bar->setTitle()V")
            repeat(checks) {
                add("iget-object v0, p0, $pager->userId:Ljava/lang/String;")
                add("invoke-static {v0}, Lfixture/Users;->isSelf(Ljava/lang/String;)Z")
                add("move-result v0")
                add(":test$it")
                add(if (endsTheBar) "if-eqz v0, :end" else "if-eqz v0, :log")
            }
            add("invoke-static {p1}, $bar->addButton($bar)V")
            add(":log")
            add("const-string v0, \"$FRIENDING_LOGGER\"")
            add("invoke-static {v0}, Lfixture/Checks;->fail(Ljava/lang/String;)V")
            if (seen) add("const-string v0, \"$SELF_FOLLOW_LISTS\"")
            add("invoke-static {v0}, Lfixture/Log;->seen(Ljava/lang/String;)V")
            add("return-void")
        }.joinToString("\n")
        val onClick = listOf(
            "invoke-static {p1}, Lfixture/Users;->isSelf(Ljava/lang/Object;)Z",
            "move-result v0",
            "if-eqz v0, :done",
            "const-string v0, \"$FRIENDING_LOGGER\"",
            "invoke-static {v0}, Lfixture/Checks;->fail(Ljava/lang/String;)V",
            "const-string v0, \"$SELF_FOLLOW_LISTS\"",
            "invoke-static {v0}, Lfixture/Log;->tapped(Ljava/lang/String;)V",
            ":done",
            "return-void",
        ).joinToString("\n")
        return listOf(
            classDef(
                pager, "Ljava/lang/Object;",
                listOf(
                    method(pager, "onCreate", listOf(arguments), "V", 2, AccessFlags.PUBLIC.value, onCreate),
                    method(pager, "Aez", listOf(bar), "V", 2, AccessFlags.PUBLIC.value, actionBar),
                ),
            ),
            classDef(click, "Ljava/lang/Object;", listOf(method(click, "onClick", listOf("Landroid/view/View;"), "V", 2, AccessFlags.PUBLIC.value, onClick))),
        )
    }

    /** Right after the read's move-result: the range call with that register, and its answer back in it. */
    private fun assertAnswered(what: String, method: Method, argument: String = FETCH_SUGGESTED, hook: String = FOLLOW_LIST, reads: String = getBoolean) {
        val code = method.code()
        val key = code.indexOfFirst { it.string() == argument }
        assertTrue("$what: no load of $argument", key >= 0)
        val read = (key + 1 until code.size).first { code[it].referenceText() == reads }
        val result = read + 1
        assertEquals("$what: the read's result", Opcode.MOVE_RESULT, code[result].opcode)
        val register = (code[result] as OneRegisterInstruction).registerA
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == hook })
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[result + 1].opcode)
        assertEquals("$what: the hook", hook, code[result + 1].referenceText())
        assertEquals("$what: what it's handed", register, (code[result + 1] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[result + 2].opcode)
        assertEquals("$what: the register", register, (code[result + 2] as OneRegisterInstruction).registerA)
    }

    /**
     * Shaped like 450's: the follow list's onCreate reads another argument and then the key out of
     * its arguments, keeping each in a field, and an opener puts the key into the arguments.
     */
    private fun classes(reads: Int = 1, keyFrom: String = "v0", kept: Boolean = true, jumpPast: Boolean = false): List<ClassDef> {
        val read = List(reads) {
            listOfNotNull(
                "const-string v0, \"$FETCH_SUGGESTED\"",
                "invoke-virtual {p1, $keyFrom, v2}, $getBoolean",
                if (kept) "move-result v0" else null,
                if (jumpPast && it == 0) ":after" else null,
                "iput-boolean v0, p0, $list->b$it:Z",
            ).joinToString("\n")
        }.joinToString("\n")
        val body = listOfNotNull(
            "const/4 v2, 0x0",
            "const-string v1, \"FollowListFragment.ShowActionBar\"",
            "invoke-virtual {p1, v1, v2}, $getBoolean",
            "move-result v0",
            if (jumpPast) "if-eqz v0, :after" else null,
            "iput-boolean v0, p0, $list->a:Z",
            read,
            "return-void",
        ).joinToString("\n")
        val onCreate = method(list, "onCreate", listOf(arguments), "V", 3, AccessFlags.PUBLIC.value, body)
        val open = method(
            opener, "A00", listOf(arguments), "V", 2, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            "const-string v0, \"$FETCH_SUGGESTED\"\nconst/4 v1, 0x1\ninvoke-virtual {p0, v0, v1}, Landroid/os/BaseBundle;->putBoolean(Ljava/lang/String;Z)V\nreturn-void",
        )
        return listOf(
            classDef(list, "Ljava/lang/Object;", listOf(onCreate)),
            classDef(opener, "Ljava/lang/Object;", listOf(open)),
        )
    }

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, flags: Int, body: String): Method {
        val total = registers + parameters.size + if (AccessFlags.STATIC.isSet(flags)) 0 else 1
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim() })
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, superclass: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, superclass, null, null, null, null, methods)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
}

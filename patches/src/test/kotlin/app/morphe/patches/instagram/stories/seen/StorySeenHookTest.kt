/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.seen

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StorySeenHookTest {
    /** The hooks the patch writes and the stubs it fills are in the extension the bundle ships, static. */
    @Test
    fun theHooksAndStubsAreInTheExtension() {
        fun declared(type: String, public: Boolean) = ExtensionDex.classDef(type).methods
            .filter { AccessFlags.STATIC.isSet(it.accessFlags) && (!public || AccessFlags.PUBLIC.isSet(it.accessFlags)) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue(TO_SEND.substringAfter("->") in declared(STORY_SEEN, public = true))
        assertTrue(TO_RETRY.substringAfter("->") in declared(STORY_SEEN, public = true))
        assertTrue(BIND_BUTTON.substringAfter("->") in declared(STORY_SEEN_BUTTON, public = true))
        val stubs = declared(STORY_SEEN, public = false) + declared(STORY_SEEN_BUTTON, public = false)
        for (stub in listOf(
            "emptyBatch()Ljava/lang/Object;", "seenStories(Ljava/lang/Object;)Ljava/util/Map;",
            "send(Ljava/lang/Object;Ljava/lang/Object;)V", "storeAccount(Ljava/lang/Object;)Ljava/lang/String;",
            "sessionAccount(Ljava/lang/Object;)Ljava/lang/String;", "storyId(Ljava/lang/Object;)Ljava/lang/String;",
            "itemView(Ljava/lang/Object;)Landroid/view/View;",
        )) {
            assertTrue("$stub is not in the extension: $stubs", stub in stubs)
        }
    }

    /**
     * The send asks first and goes on with what it's told, in the batch's own register, or returns.
     * The retry asks before the native claim. The store's other methods taking a batch, and the
     * Reset NUX route making a batch of its own, stay as they are.
     */
    @Test
    fun theSendAsksBeforeItBuildsTheRequest() {
        val context = PatchContexts.of(standIns())

        context.holdBackStoryViews()

        val patched = context.mutableClassDefBy(STORE)
        assertSendHooked("the send", patched.methods.single { it.name == "A0O" }, BATCH)
        val retry = patched.methods.single { it.name == "A0J" }
        assertRetryHooked("the retry", retry, BATCH, build = 6)
        for (name in listOf("A0P", "A0L", "A00")) {
            assertEquals("$name was touched", 0, patched.methods.single { it.name == name }.code().count { it.referenceText() in HOOKS })
        }
        assertEquals("the Reset NUX route was touched", 0, context.mutableClassDefBy(RESET_NUX).methods.sumOf { method -> method.code().count { it.referenceText() in HOOKS } })
    }

    /** The header binder hands the button its account, story and view holder before anything else. */
    @Test
    fun theHeaderBinderHandsTheButtonItsStoryFirst() {
        val context = PatchContexts.of(standIns())

        context.holdBackStoryViews()

        val binder = context.mutableClassDefBy(BINDER).methods.single { it.name == "A06" }
        assertHeaderHooked("stand-in", binder, session = 1, item = 2, holder = 4)
    }

    @Test
    fun theStubsReachTheBatchTheStoreTheAccountTheStoryAndItsView() {
        val context = PatchContexts.of(standIns())
        context.holdBackStoryViews()

        assertStubsFilled(
            context, BATCH, "$BATCH->stories:Ljava/util/HashMap;", "$STORE->A00($USER_SESSION)$STORE", "$STORE->A0O($BATCH)V",
            "$STORE_BASE->A0H()$USER_SESSION", "$VIEW_HOLDER->itemView:$VIEW",
        )
    }

    /** A filled stub answering something narrower than Object returns on its one way out (see FriendshipStatusHookTest). */
    @Test
    fun noStubJoinsTwoWaysAtOneReturn() {
        val context = PatchContexts.of(standIns())
        context.holdBackStoryViews()

        for ((type, names) in listOf(
            STORY_SEEN to listOf("emptyBatch", "seenStories", "send", "storeAccount", "sessionAccount"),
            STORY_SEEN_BUTTON to listOf("storyId", "itemView"),
        )) {
            for (stub in context.mutableClassDefBy(type).methods.filter { it.name in names }) {
                val code = stub.code()
                val first = code.indexOfFirst { it.opcode == Opcode.RETURN_OBJECT || it.opcode == Opcode.RETURN_VOID }
                assertTrue("${stub.name}: no way out", first > 0)
                assertTrue("${stub.name}: a branch before its return", code.take(first).none { it is OffsetInstruction })
            }
        }
    }

    @Test
    fun aBuildWithoutTheRequestOrTheStoreFails() {
        assertRefused(standIns(request = false), "story seen request")
        assertRefused(standIns(storeReader = false), "pending story seen store")
    }

    @Test
    fun aBuildWithoutOneSendFails() {
        assertRefused(standIns(sends = 0), "found 0")
        assertRefused(standIns(sends = 2), "found 2")
    }

    /** The hook gives the send its batch in the parameter's register; written over first, the request is built from something else. */
    @Test
    fun aSendWritingOverItsBatchFails() = assertRefused(standIns(sendOverwrites = true), "writes over parameter 0")

    /** The hook's answer replaces the batch before the send touches it, so nothing the send does before the request may add to it. */
    @Test
    fun aSendChangingItsBatchFirstFails() {
        assertRefused(standIns(sendChangesBatch = true), "hands its batch to $BATCH->A0B()V at instruction 1")
        assertRefused(standIns(sendHandsBatchOut = true), "hands its batch to $ELSEWHERE->keep(Ljava/lang/Object;)V")
    }

    /** move-result-object, if-nez and check-cast name a register in a byte. */
    @Test
    fun aBatchPastV255Fails() = assertRefused(standIns(sendRegisters = 258), "keeps its batch in v257, past v255")

    @Test
    fun aRequestNamingTheStoriesTwiceFails() = assertRefused(standIns(reelsTwice = true), "to name \"reels\" once")

    /** Stories read from anywhere but a map of the batch itself can't be filtered by changing the batch. */
    @Test
    fun storiesFromOutsideTheBatchFail() = assertRefused(standIns(storiesFromStatic = true), "from a map of its own batch")

    /** A branch landing between the read and the add could bring another value to "reels". */
    @Test
    fun aBranchIntoTheStoriesFails() = assertRefused(standIns(branchIntoStories = true), "lands between")

    /** A request that hands its batch to another method may send what that method reads. */
    @Test
    fun aRequestHandingItsBatchOnFails() = assertRefused(standIns(requestHandsBatchOn = true), "hands its batch on")

    /** A static field the request sends could hold stories a new batch doesn't. */
    @Test
    fun staticStateInTheRequestFails() = assertRefused(standIns(staticInRequest = true), "reads $BATCH->shared:Ljava/util/HashMap; into what it sends")

    /** The request's parameter only starts the request; sent, it could carry anything. */
    @Test
    fun aRequestSendingItsParameterFails() = assertRefused(standIns(requestSendsItsParameter = true), "reads its parameter 0 into what it sends")

    /** A call the request makes elsewhere could hand it what a new batch doesn't hold. */
    @Test
    fun aRequestCallingOutFails() = assertRefused(standIns(requestCallsOut = true), "calls $ELSEWHERE->latest()Ljava/lang/String;")

    /** A new batch that starts with something the request sends would send it along with the marked stories. */
    @Test
    fun aBatchThatStartsFullFails() {
        assertRefused(standIns(constructorFillsStories = true), "starts stories, which the seen request sends")
        assertRefused(standIns(constructorFillsModule = true), "starts module, which the seen request sends")
        assertRefused(standIns(constructorStartsStories = false), "doesn't start stories, its stories, as a new empty map")
    }

    /** The new map put in another object's field too could be filled through that object. */
    @Test
    fun aConstructorSharingItsStoriesFails() =
        assertRefused(standIns(constructorSharesStories = true), "starts stories, which the seen request sends, as something other")

    /** A constructor handing the new batch to another as more than its receiver may fill it there. */
    @Test
    fun aConstructorHandingItselfOnFails() = assertRefused(standIns(constructorHandsItselfOn = true), "constructor hands the batch on at instruction 2")

    /** A batch reachable under another name, or built partly by a superclass, isn't the one the patch proved empty. */
    @Test
    fun aBatchThatIsntSealedFails() {
        assertRefused(standIns(batchFinal = false), "isn't final")
        assertRefused(standIns(batchSuperclass = BATCH_BASE), "extends $BATCH_BASE")
        assertRefused(standIns(batchInterface = true), "is also $SEEN_STATE->A04")
    }

    @Test
    fun aBatchTheExtensionCantStartFails() = assertRefused(standIns(publicConstructor = false), "no public constructor taking nothing")

    /** Any call of the request that isn't hooked, or proved to send only constants, could post the stories you watched. */
    @Test
    fun anotherRouteToTheRequestFails() =
        assertRefused(standIns(otherRoute = true), "$STORE->A0Q builds the seen request at instruction 4 from a batch that doesn't go through $STORE->A0O")

    /** A batch made on the spot is safe only while what goes in it is constant and the batch goes nowhere else. */
    @Test
    fun aFreshRouteSendingMoreThanConstantsFails() {
        assertRefused(standIns(freshRouteNotConstant = true), "$RESET_NUX->A00 builds the seen request")
        assertRefused(standIns(freshRouteHandsBatchOn = true), "$RESET_NUX->A00 builds the seen request")
    }

    /** The retry's hook goes in right before its build, and its answer stands in for the batch from there on. */
    @Test
    fun aRetryTheHookCantFollowFails() {
        assertRefused(standIns(retryOverwrites = true), "$STORE->A0J writes over the batch it rebuilds")
        assertRefused(standIns(retryBranchesToBuild = true), "lands on its build of the seen request")
        assertRefused(standIns(retryReadsAfter = true), "reads its batch again after building the seen request")
        assertRefused(standIns(twoRetries = true), "in one place at most, found 2")
    }

    @Test
    fun aStoreWithoutAGetterFails() = assertRefused(standIns(getter = false), "one public static getter")

    /** Marks are kept per account, so the store's account and the session's user ID must both be readable. */
    @Test
    fun aStoreOrSessionWithoutItsAccountFails() {
        assertRefused(standIns(storeSession = false), "$STORE has no public getter of the account it sends for")
        assertRefused(standIns(userId = false), "$USER_SESSION has no public getUserId()")
    }

    @Test
    fun aBuildWithoutTheHeaderBinderFails() = assertRefused(standIns(binder = false), "story header binder")

    @Test
    fun aBinderOfAnotherShapeFails() {
        assertRefused(standIns(binderParameters = listOf(DELEGATE, USER_SESSION, USER_SESSION, REEL_ITEM, HOLDER, "Z")), "to take one $USER_SESSION, found 2")
        assertRefused(standIns(binderParameters = listOf(DELEGATE, USER_SESSION, REEL_ITEM, VIEWER, VIEWER, "Z")), "to take one view holder, found 0")
    }

    /** Code put in front of an instruction a branch lands on is skipped by the branch. */
    @Test
    fun aBranchToTheBindersStartFails() = assertRefused(standIns(binderLoops = true), "lands on its first instruction")

    @Test
    fun aBinderWithoutThreeLocalsFails() = assertRefused(standIns(binderLocals = 2), "needs 3")

    @Test
    fun aStoryWithoutAnIdFails() {
        assertRefused(standIns(storyId = false), "has no public getId()")
        assertRefused(standIns(reelItemInterface = true), "$REEL_ITEM is an interface")
    }

    @Test
    fun anItemViewTheExtensionCantReadFails() = assertRefused(standIns(itemViewPublic = false), "isn't a public field")

    /**
     * On each declared build the send, the retry and the header binder are found and hooked and the
     * stubs filled, every other call of the seen request is left as it is, and the same classes with
     * the binder copied, or without the story class, are refused untouched.
     */
    @Test
    fun eachDeclaredBuildHooksTheSendAndTheHeader() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val classes = fixtureClasses(bundle)
                val found = PatchContexts.of(classes).findStorySeen()
                assertEquals("${bundle.name}: the stories are a map of the batch", found.batch, found.reels.definingClass)
                assertEquals("${bundle.name}: the binder's account", USER_SESSION, found.binderParameters[found.session])
                assertEquals("${bundle.name}: the binder's story", REEL_ITEM, found.binderParameters[found.item])
                assertEquals("${bundle.name}: the item view", VIEW, found.itemView.type)
                val retry = found.retry ?: throw AssertionError("${bundle.name}: no retry found")

                val context = PatchContexts.of(classes)
                context.holdBackStoryViews()
                val store = classes.single { it.type == found.store }
                val before = store.methods.single { it.name == found.send && it.parameterTypes.map(Any::toString) == listOf(found.batch) }
                val send = context.mutableClassDefBy(found.store).methods.single { it.name == found.send && it.parameterTypes.map(Any::toString) == listOf(found.batch) }
                assertEquals("${bundle.name}: the send's size", before.code().size + 5, send.code().size)
                assertSendHooked("${bundle.name}: the send", send, found.batch)
                val retryBefore = store.methods.single { it.name == retry.name && it.parameterTypes.map(Any::toString) == retry.parameters }
                val retryAfter = context.mutableClassDefBy(found.store).methods.single { it.name == retry.name && it.parameterTypes.map(Any::toString) == retry.parameters }
                assertEquals("${bundle.name}: the native retry bridge stays unchanged", retryBefore.code().map { it.opcode to it.referenceText() }, retryAfter.code().map { it.opcode to it.referenceText() })
                assertRetryHooked("${bundle.name}: the retry", retryAfter, found.batch, retry.build)
                val binder = context.mutableClassDefBy(found.binder).methods.single { it.name == found.binderName && it.parameterTypes.map(Any::toString) == found.binderParameters }
                assertHeaderHooked("${bundle.name}: the header binder", binder, found.session, found.item, found.holder)
                assertStubsFilled(
                    context, found.batch, found.reels.toString(), "${found.store}->${found.getter}($USER_SESSION)${found.store}",
                    "${found.store}->${found.send}(${found.batch})V", "${found.sessionOwner}->${found.sessionGetter}()$USER_SESSION",
                    found.itemView.toString(),
                )
                val hooked = mutableListOf<String>()
                context.classDefForEach { classDef ->
                    for (method in classDef.methods) {
                        method.code().filter { it.referenceText() in HOOKS }.forEach { hooked += "${classDef.type}->${method.name} ${it.referenceText()}" }
                    }
                }
                assertEquals(
                    "${bundle.name}: the hooks are in the send, the retry and the binder, and nowhere else",
                    setOf("${found.store}->${found.send} $TO_SEND", "${found.queue!!.owner}->${found.queue.run} $TO_RETRY",
                        "${found.binder}->${found.binderName} $BIND_BUTTON"),
                    hooked.toSet(),
                )
                assertEquals("${bundle.name}: one call of each hook", 3, hooked.size)

                val binderClass = classes.single { it.type == found.binder }
                assertRefused(classes + copyOf(binderClass, "Lfixture/SecondBinder;"), "story header binder")
                assertRefused(classes.filter { it.type != REEL_ITEM }, "$REEL_ITEM isn't in this build")
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * The classes the patch reads on [bundle]: those holding its four strings in one pass, then every
     * class calling the seen request, then the header binder's parameter types, the story class and
     * the account's, then their superclasses up to the view holder base and the store's, and the
     * extension's two classes.
     */
    internal fun fixtureClasses(bundle: java.io.File): List<ClassDef> {
        val strings = setOf("media/seen/?reel=%s&live_vod=0", "pending_reel_seen_states_", "ReelViewerItemBinder.bindHeaderViews", "itemView may not be null")
        val found = mutableMapOf<String, ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.stringSection.none { it in strings }) return@forEach
            for (classDef in dex.classes) {
                if (classDef.methods.any { method -> method.code().any { it.string() in strings } }) found[classDef.type] = ImmutableClassDef.of(classDef)
            }
        }
        val methods = found.values.flatMap { it.methods }
        val binder = methods.single { method -> method.code().any { it.string() == "ReelViewerItemBinder.bindHeaderViews" } }
        val request = methods.single { method -> method.code().any { it.string() == "media/seen/?reel=%s&live_vod=0" } }
        val requestKey = "${request.definingClass}->${request.name}(${request.parameterTypes.joinToString("")})${request.returnType}"
        val callers = FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.toString() == requestKey } }) { method ->
            method.code().any { it.referenceText() == requestKey }
        }
        var wanted = (binder.parameterTypes.map(Any::toString) + REEL_ITEM + USER_SESSION + callers.map { it.definingClass } + found.values.mapNotNull { it.superclass })
            .filter { it.startsWith("L") && it !in found && it != OBJECT }.toSet()
        repeat(4) {
            if (wanted.isEmpty()) return@repeat
            val loaded = FixtureDex.classes(bundle, wanted)
            found += loaded
            wanted = loaded.values.mapNotNull { it.superclass }.filter { it !in found && it != OBJECT }.toSet()
        }
        // Scan the original bundle for every reference to either dispatch entry, not just direct
        // callers of the concrete batch request. A later caller can't bypass queue protection.
        val bridges = found.values.flatMap { it.methods }.filter { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.map(Any::toString) == listOf(OBJECT) &&
                method.returnType == request.returnType && method.code().any { it.referenceText() == requestKey }
        }
        val builderKeys = bridges.flatMap { bridge ->
            listOf(bridge.toString(), "${found[bridge.definingClass]!!.superclass}->${bridge.name}($OBJECT)${bridge.returnType}",
                "$OBJECT->${bridge.name}($OBJECT)${bridge.returnType}")
        }.toSet()
        val dispatchEntries = bridges.map { bridge -> bridge to found[found[bridge.definingClass]!!.superclass]!!.methods.single {
            it.name == bridge.name && it.parameterTypes == bridge.parameterTypes && it.returnType == bridge.returnType } }
        val builderCallers = FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.toString() in builderKeys } }) { method ->
            method.code().any { instruction -> instruction.referenceText() in builderKeys || dispatchEntries.any { (bridge, build) ->
                instruction.indirectlyCalls(bridge, build) } }
        }
        found += FixtureDex.classes(bundle, builderCallers.map { it.definingClass }.filter { it !in found }.toSet())
        val reader = found.values.flatMap { it.methods }.single { method -> method.code().any { it.string() == "PendingReelSeenStateStore.deserializeFromDisk" } }
        val claim = found.values.flatMap { it.methods }.single { method -> method.code().any {
            it.string() == "null cannot be cast to non-null type T of com.instagram.store.PendingActionStore" } }
        val helpers = (reader.code().filter { it.opcode == Opcode.INVOKE_VIRTUAL || it.opcode == Opcode.INVOKE_STATIC }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }.filter {
                it.parameterTypes.map(Any::toString) == listOf(STRING) && it.returnType == "V" ||
                    it.parameterTypes.map(Any::toString) == listOf(STRING, STRING) && it.returnType == STRING
            } + claim.code().filter { it.opcode == Opcode.INVOKE_STATIC }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }).map { it.definingClass }.toSet()
        found += FixtureDex.classes(bundle, helpers.filter { it !in found }.toSet())
        val extra = mutableSetOf<String>()
        for (owner in helpers.mapNotNull { found[it] }) {
            owner.methods.filter { it.parameterTypes.map(Any::toString) == listOf(STRING) && it.returnType == "V" && it.code().size == 7 }
                .flatMap { it.code() }.forEach { instruction ->
                    when (val reference = (instruction as? ReferenceInstruction)?.reference) {
                        is TypeReference -> if (instruction.opcode == Opcode.NEW_INSTANCE) extra += reference.type
                        is FieldReference -> if (instruction.opcode == Opcode.IGET_OBJECT) extra += reference.type
                    }
                }
            owner.methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.map(Any::toString) == listOf(owner.type) }
                .forEach { extra += it.returnType }
        }
        found += FixtureDex.classes(bundle, extra.filter { it !in found }.toSet())
        return (FixtureDex.withStringPools(bundle, found.values) + ExtensionDex.classDef(STORY_SEEN) + ExtensionDex.classDef(STORY_SEEN_BUTTON))
            .map { ImmutableClassDef.of(it) }.distinctBy { it.type }
    }

    /** The patch refuses [classes] saying [why], and nothing calls a hook or has a filled stub. */
    private fun assertRefused(classes: List<ClassDef>, why: String) {
        val context = PatchContexts.of(classes)
        val refusal = assertThrows(PatchException::class.java) { context.holdBackStoryViews() }
        assertTrue(refusal.message, refusal.message!!.startsWith("$PATCH: ") && why in refusal.message!!)
        assertUntouched(context)
    }

    private fun assertUntouched(context: BytecodePatchContext) {
        context.classDefForEach { classDef ->
            classDef.methods.forEach { method ->
                val calls = method.code().count { it.referenceText() in HOOKS }
                assertEquals("${classDef.type}->${method.name} calls a hook", 0, calls)
            }
        }
        fun Method.key() = "$name(${parameterTypes.joinToString("")})$returnType"
        for (type in listOf(STORY_SEEN, STORY_SEEN_BUTTON)) {
            val stock = ExtensionDex.classDef(type).methods.associate { it.key() to it.implementation?.instructions?.count() }
            context.classDefByOrNull(type)?.methods?.forEach {
                assertEquals("${it.key()} was filled", stock[it.key()], it.implementation?.instructions?.count())
            }
        }
    }

    /**
     * The send's first five instructions: a range call handing the store and the batch to [TO_SEND],
     * its answer moved into the batch's own register, a return when it's null, and a cast back to
     * the batch where the non-null answer lands, right before the send's own first instruction. The
     * hook is called nowhere else in the send.
     */
    private fun assertSendHooked(what: String, send: Method, batch: String) {
        val code = send.code()
        assertEquals(
            "$what: the hook's opcodes",
            listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.IF_NEZ, Opcode.RETURN_VOID, Opcode.CHECK_CAST),
            code.take(5).map { it.opcode },
        )
        assertEquals("$what: the hook called", TO_SEND, code[0].referenceText())
        val register = send.parameterRegisterNumber(0)
        val call = code[0] as RegisterRangeInstruction
        assertEquals("$what: the hook takes the store and the batch", send.localRegisterCount() to 2, call.startRegister to call.registerCount)
        assertEquals("$what: the batch follows the store", register, call.startRegister + 1)
        assertEquals("$what: the answer replaces the batch", register, (code[1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the null check reads it", register, (code[2] as OneRegisterInstruction).registerA)
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        assertEquals("$what: a batch lands on the cast", addresses[4], addresses[2] + (code[2] as OffsetInstruction).codeOffset)
        assertEquals("$what: cast back to the batch", batch, code[4].referenceText())
        assertEquals("$what: the cast is of the batch's register", register, (code[4] as OneRegisterInstruction).registerA)
        assertEquals("$what: calls of the hook", 1, code.count { it.referenceText() == TO_SEND })
        for ((index, instruction) in code.withIndex()) {
            if (index == 2 || instruction !is OffsetInstruction) continue
            val target = addresses[index] + instruction.codeOffset
            assertTrue("$what: the branch at $index lands in the hook", target !in addresses.take(5))
        }
    }

    /**
     * The retry's six instructions at [build], where its build of the request was: a range call
     * handing the store and the batch to [TO_RETRY], its answer moved into the batch's register and
     * canceled with a typed null request, or cast back to the batch right before the native build.
     */
    private fun assertRetryHooked(what: String, retry: Method, batch: String, build: Int) {
        val code = retry.code()
        assertEquals("$what: native builder still receives the original typed batch", batch,
            code.first { it.opcode == Opcode.CHECK_CAST }.referenceText())
        assertTrue("$what: native builder still occupies the original location",
            code[build].referenceText()!!.startsWith("$batch->"))
        assertEquals("$what: the bridge holds no extension hook", 0, code.count { it.referenceText() in HOOKS })
    }

    private fun assertHeaderHooked(what: String, binder: Method, session: Int, item: Int, holder: Int) {
        val code = binder.code()
        assertEquals(
            "$what: the hook's opcodes",
            listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC),
            code.take(4).map { it.opcode },
        )
        for ((local, parameter) in listOf(session, item, holder).withIndex()) {
            val move = code[local] as TwoRegisterInstruction
            assertEquals("$what: v$local is borrowed", local, move.registerA)
            assertEquals("$what: v$local holds parameter $parameter", binder.parameterRegisterNumber(parameter), move.registerB)
        }
        val call = code[3] as FiveRegisterInstruction
        assertEquals("$what: the hook called", BIND_BUTTON, code[3].referenceText())
        assertEquals("$what: the hook takes v0 to v2", listOf(0, 1, 2), listOf(call.registerC, call.registerD, call.registerE).take(call.registerCount))
        assertTrue("$what: three locals to borrow", binder.localRegisterCount() >= 3)
        assertEquals("$what: calls of the hook", 1, code.count { it.referenceText() == BIND_BUTTON })
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        for ((index, instruction) in code.withIndex()) {
            if (instruction !is OffsetInstruction) continue
            val target = addresses[index] + instruction.codeOffset
            assertTrue("$what: the branch at $index lands in the hook", target !in addresses.take(4))
        }
    }

    private fun assertStubsFilled(
        context: BytecodePatchContext, batch: String, stories: String, getter: String, send: String, session: String, itemView: String,
    ) {
        fun reads(type: String, stub: String) = context.mutableClassDefBy(type).methods.single { it.name == stub }.code().mapNotNull { it.referenceText() }
        val userId = "$USER_SESSION->getUserId()Ljava/lang/String;"
        assertTrue(reads(STORY_SEEN, "emptyBatch").containsAll(listOf(batch, "$batch-><init>()V")))
        assertTrue(reads(STORY_SEEN, "seenStories").containsAll(listOf(batch, stories)))
        assertTrue(reads(STORY_SEEN, "send").containsAll(listOf(USER_SESSION, getter, batch, send)))
        assertTrue(reads(STORY_SEEN, "storeAccount").containsAll(listOf(session.substringBefore("->"), session, userId)))
        assertTrue(reads(STORY_SEEN, "sessionAccount").containsAll(listOf(USER_SESSION, userId)))
        assertTrue(reads(STORY_SEEN_BUTTON, "storyId").containsAll(listOf(REEL_ITEM, "$REEL_ITEM->getId()Ljava/lang/String;")))
        assertTrue(reads(STORY_SEEN_BUTTON, "itemView").contains(itemView))
    }

    // ---- stand-ins shaped like Instagram 449's -------------------------------------------------

    /**
     * The batch, its request builder, its constructors and empty check; the store with its disk
     * reader, getter, send, retry and the methods the patch leaves alone, and the class above it that
     * answers its account; the account; the Reset NUX route; the story header binder; the view holder
     * base and a holder two classes down; the story class; and the extension's two classes.
     */
    internal fun standIns(
        request: Boolean = true,
        storeReader: Boolean = true,
        sends: Int = 1,
        sendOverwrites: Boolean = false,
        sendChangesBatch: Boolean = false,
        sendHandsBatchOut: Boolean = false,
        sendRegisters: Int = 3,
        reelsTwice: Boolean = false,
        storiesFromStatic: Boolean = false,
        branchIntoStories: Boolean = false,
        requestHandsBatchOn: Boolean = false,
        staticInRequest: Boolean = false,
        requestSendsItsParameter: Boolean = false,
        requestCallsOut: Boolean = false,
        constructorFillsStories: Boolean = false,
        constructorFillsModule: Boolean = false,
        constructorStartsStories: Boolean = true,
        constructorSharesStories: Boolean = false,
        constructorHandsItselfOn: Boolean = false,
        batchFinal: Boolean = true,
        batchSuperclass: String = OBJECT,
        batchInterface: Boolean = false,
        publicConstructor: Boolean = true,
        otherRoute: Boolean = false,
        freshRouteNotConstant: Boolean = false,
        freshRouteHandsBatchOn: Boolean = false,
        retryOverwrites: Boolean = false,
        retryBranchesToBuild: Boolean = false,
        retryReadsAfter: Boolean = false,
        twoRetries: Boolean = false,
        getter: Boolean = true,
        storeSession: Boolean = true,
        userId: Boolean = true,
        binder: Boolean = true,
        binderParameters: List<String> = listOf(DELEGATE, USER_SESSION, REEL_ITEM, VIEWER, HOLDER, "Z"),
        binderLoops: Boolean = false,
        binderLocals: Int = 11,
        storyId: Boolean = true,
        reelItemInterface: Boolean = false,
        itemViewPublic: Boolean = true,
    ): List<ClassDef> {
        val stories = if (storiesFromStatic) "sget-object v0, $BATCH->shared:Ljava/util/HashMap;" else "iget-object v0, p0, $BATCH->stories:Ljava/util/HashMap;"
        val builder = """
            sget-object v1, $FACTORY->A01:$FACTORY
            sget-object v0, $CONFIG->A00:$CONFIG
            invoke-virtual { v1, v0, p1 }, $FACTORY->A03($CONFIG$USER_SESSION)$REQUEST
            move-result-object v3
            sget-object v0, $SETTINGS->A01:Ljava/lang/Integer;
            invoke-virtual { v3, v0 }, $REQUEST->A07(Ljava/lang/Integer;)V
            ${if (branchIntoStories) "const/4 v0, 0x0" else "nop"}
            ${if (branchIntoStories) "if-eqz v0, :late" else "nop"}
            iget-object v0, p0, $BATCH->stories:Ljava/util/HashMap;
            invoke-virtual { v0 }, Ljava/util/HashMap;->size()I
            move-result v0
            const-string v0, "1"
            const-string v1, "media/seen/?reel=%s&live_vod=0"
            invoke-virtual { v3, v1, v0 }, $REQUEST->path(Ljava/lang/String;Ljava/lang/String;)V
            $stories
            invoke-static { v0 }, $BATCH->A00(Ljava/util/Map;)Ljava/lang/String;
            move-result-object v5
            iget-object v0, p0, $BATCH->skipped:Ljava/util/HashMap;
            invoke-static { v0 }, $BATCH->A00(Ljava/util/Map;)Ljava/lang/String;
            move-result-object v4
            ${if (requestHandsBatchOn) "invoke-virtual { p0 }, $BATCH->A0A()Z" else "nop"}
            ${if (requestSendsItsParameter) "invoke-virtual { v3, p1 }, $REQUEST->session($USER_SESSION)V" else "nop"}
            ${if (requestCallsOut) "invoke-static { }, $ELSEWHERE->latest()Ljava/lang/String;" else "nop"}
            :late
            if-eqz v5, :skipped
            const-string v0, "reels"
            invoke-virtual { v3, v0, v5 }, $REQUEST->add(Ljava/lang/String;Ljava/lang/String;)V
            :skipped
            ${if (reelsTwice) "const-string v0, \"reels\"" else "nop"}
            if-eqz v4, :forced
            const-string v0, "reel_media_skipped"
            invoke-virtual { v3, v0, v4 }, $REQUEST->add(Ljava/lang/String;Ljava/lang/String;)V
            :forced
            iget-object v0, p0, $BATCH->forced:Ljava/util/List;
            const-string v1, "force_seen_story_ids"
            invoke-static { v0 }, Ljava/lang/String;->valueOf(Ljava/lang/Object;)Ljava/lang/String;
            move-result-object v0
            invoke-virtual { v3, v1, v0 }, $REQUEST->add(Ljava/lang/String;Ljava/lang/String;)V
            ${if (staticInRequest) "sget-object v1, $BATCH->shared:Ljava/util/HashMap;" else "iget-object v1, p0, $BATCH->module:Ljava/lang/String;"}
            if-eqz v1, :done
            const-string v0, "container_module"
            invoke-virtual { v3, v0, v1 }, $REQUEST->add(Ljava/lang/String;Ljava/lang/String;)V
            :done
            return-object v3
        """
        val synthetic = """
            invoke-static { }, Ljava/util/UUID;->randomUUID()Ljava/util/UUID;
            move-result-object v0
            invoke-virtual { v0 }, Ljava/lang/Object;->toString()Ljava/lang/String;
            move-result-object v4
            new-instance v3, Ljava/util/HashMap;
            invoke-direct { v3 }, Ljava/util/HashMap;-><init>()V
            ${if (constructorFillsStories) "invoke-virtual { v3, v4, v4 }, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;" else "nop"}
            ${if (constructorSharesStories) "new-instance v5, $BATCH" else "nop"}
            ${if (constructorSharesStories) "iput-object v3, v5, $BATCH->stories:Ljava/util/HashMap;" else "nop"}
            new-instance v2, Ljava/util/HashMap;
            invoke-direct { v2 }, Ljava/util/HashMap;-><init>()V
            const/4 v0, 0x1
            if-ge p1, v0, :sized
            const/4 p1, 0x1
            :sized
            new-instance v1, Ljava/util/ArrayList;
            invoke-direct { v1 }, Ljava/util/ArrayList;-><init>()V
            const/4 v0, 0x0
            invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
            iput-object v4, p0, $BATCH->id:Ljava/lang/String;
            ${if (constructorStartsStories) "iput-object v3, p0, $BATCH->stories:Ljava/util/HashMap;" else "nop"}
            iput-object v2, p0, $BATCH->skipped:Ljava/util/HashMap;
            iput-object v1, p0, $BATCH->forced:Ljava/util/List;
            iput-object ${if (constructorFillsModule) "v4" else "v0"}, p0, $BATCH->module:Ljava/lang/String;
            return-void
        """
        val start = if (constructorHandsItselfOn) {
            "invoke-direct { p0, p0 }, $BATCH-><init>(Ljava/lang/Object;)V"
        } else {
            "invoke-direct { p0, v0, v1 }, $BATCH-><init>(II)V"
        }
        val batchClass = classOf(
            BATCH,
            listOf(
                field(BATCH, "stories", "Ljava/util/HashMap;"), field(BATCH, "skipped", "Ljava/util/HashMap;"),
                field(BATCH, "forced", "Ljava/util/List;"), field(BATCH, "module", "Ljava/lang/String;"),
                field(BATCH, "id", "Ljava/lang/String;"), field(BATCH, "shared", "Ljava/util/HashMap;", static = true),
            ),
            listOfNotNull(
                method(BATCH, "<init>", emptyList(), "V", 3, """
                    const/16 v1, 0x3ff
                    const/4 v0, 0x0
                    $start
                    return-void
                """, static = false, constructor = true, public = publicConstructor),
                method(BATCH, "<init>", listOf("I", "I"), "V", 9, synthetic, static = false, constructor = true),
                if (constructorHandsItselfOn) method(BATCH, "<init>", listOf(OBJECT), "V", 2, """
                    invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
                    return-void
                """, static = false, constructor = true) else null,
                if (request) method(BATCH, "A04", listOf(USER_SESSION), REQUEST, 8, builder, static = false) else null,
                method(BATCH, "A00", listOf("Ljava/util/Map;"), "Ljava/lang/String;", 2, """
                    const/4 v0, 0x0
                    return-object v0
                """),
                method(BATCH, "A0A", emptyList(), "Z", 2, """
                    iget-object v0, p0, $BATCH->stories:Ljava/util/HashMap;
                    invoke-virtual { v0 }, Ljava/util/HashMap;->isEmpty()Z
                    move-result v0
                    return v0
                """, static = false),
                method(BATCH, "A0B", emptyList(), "V", 2, """
                    iget-object v0, p0, $BATCH->stories:Ljava/util/HashMap;
                    invoke-virtual { v0 }, Ljava/util/HashMap;->clear()V
                    return-void
                """, static = false),
            ),
            superclass = batchSuperclass,
            final = batchFinal,
            interfaces = if (batchInterface) listOf(SEEN_STATE) else emptyList(),
        )
        val sendBody = if (sendRegisters > 16) {
            """
                invoke-virtual/range { p1 .. p1 }, $BATCH->A0A()Z
                move-result v0
                if-nez v0, :done
                invoke-virtual/range { p0 .. p0 }, $STORE_BASE->A0H()$USER_SESSION
                move-result-object v1
                move-object/from16 v0, p1
                invoke-virtual { v0, v1 }, $BATCH->A04($USER_SESSION)$REQUEST
                :done
                return-void
            """
        } else {
            """
                ${if (sendOverwrites) "const/4 p1, 0x0" else "nop"}
                ${if (sendChangesBatch) "invoke-virtual { p1 }, $BATCH->A0B()V" else "nop"}
                ${if (sendHandsBatchOut) "invoke-static { p1 }, $ELSEWHERE->keep(Ljava/lang/Object;)V" else "nop"}
                invoke-virtual { p1 }, $BATCH->A0A()Z
                move-result v0
                if-nez v0, :done
                invoke-virtual { p0 }, $STORE_BASE->A0H()$USER_SESSION
                move-result-object v0
                invoke-virtual { p1, v0 }, $BATCH->A04($USER_SESSION)$REQUEST
                :done
                return-void
            """
        }
        val retryBody = """
            ${if (retryOverwrites) "const/4 p1, 0x0" else "nop"}
            check-cast p1, $BATCH
            invoke-static { p1 }, LX/04Zi;->A0R(Ljava/lang/Object;)V
            invoke-virtual { p0 }, $STORE_BASE->A0H()$USER_SESSION
            move-result-object v0
            ${if (retryBranchesToBuild) "if-eqz v0, :build" else "nop"}
            :build
            invoke-virtual { p1, v0 }, $BATCH->A04($USER_SESSION)$REQUEST
            move-result-object v0
            ${if (retryReadsAfter) "invoke-virtual { p1 }, $BATCH->A0A()Z" else "nop"}
            return-object v0
        """
        val storeClass = classOf(
            STORE,
            emptyList(),
            listOfNotNull(
                if (storeReader) storyQueueReader(STORE, STORE_BASE) else null,
                if (getter) method(STORE, "A00", listOf(USER_SESSION), STORE, 2, """
                    new-instance v0, $STORE
                    return-object v0
                """) else null,
                method(STORE, "A0J", listOf(OBJECT), REQUEST, 3, retryBody, static = false),
                if (twoRetries) method(STORE, "A0M", listOf(OBJECT), REQUEST, 3, retryBody, static = false) else null,
                method(STORE, "A0P", listOf(BATCH), "V", 3, """
                    invoke-virtual { p1 }, $BATCH->A0A()Z
                    return-void
                """, static = false),
                if (otherRoute) method(STORE, "A0Q", listOf(BATCH), "V", 2, """
                    invoke-virtual { p0 }, $BATCH->A0A()Z
                    move-result v0
                    if-nez v0, :done
                    const/4 v0, 0x0
                    invoke-virtual { p0, v0 }, $BATCH->A04($USER_SESSION)$REQUEST
                    :done
                    return-void
                """) else null,
            ) + (0 until sends).map { copy -> method(STORE, if (copy == 0) "A0O" else "A0R", listOf(BATCH), "V", sendRegisters, sendBody, static = false) },
            superclass = STORE_BASE,
        )
        val storeBase = storyQueueBase(STORE_BASE, REQUEST, storeSession)
        val resetNux = classOf(
            RESET_NUX,
            emptyList(),
            listOf(
                method(RESET_NUX, "A00", listOf(USER_SESSION), "V", 5, """
                    const/16 v0, 0x3ff
                    const/4 v1, 0x0
                    new-instance v2, $BATCH
                    invoke-direct { v2, v1, v0 }, $BATCH-><init>(II)V
                    iget-object v0, v2, $BATCH->skipped:Ljava/util/HashMap;
                    const-string v1, "nux_story"
                    ${if (freshRouteNotConstant) "move-object v3, p0" else "const-string v3, \"0\""}
                    invoke-virtual { v0, v1, v3 }, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                    ${if (freshRouteHandsBatchOn) "invoke-static { v2 }, $ELSEWHERE->keep(Ljava/lang/Object;)V" else "nop"}
                    invoke-virtual { v2, p0 }, $BATCH->A04($USER_SESSION)$REQUEST
                    move-result-object v0
                    return-void
                """),
            ),
        )
        val userSession = classOf(
            USER_SESSION,
            listOf(ImmutableField(USER_SESSION, "userId", STRING, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)),
            listOfNotNull(
                if (userId) method(USER_SESSION, "getUserId", emptyList(), "Ljava/lang/String;", 2, """
                    iget-object v0, p0, $USER_SESSION->userId:$STRING
                    return-object v0
                """, static = false) else null,
            ),
        )
        val binderClass = if (binder) {
            val holderAt = binderParameters.indexOf(HOLDER).takeIf { it >= 0 } ?: 4
            classOf(
                BINDER,
                emptyList(),
                listOf(
                    method(BINDER, "A06", binderParameters, "V", binderLocals + binderParameters.size, """
                        :start
                        const-string v0, "ReelViewerItemBinder.bindHeaderViews"
                        move-object/from16 v1, p$holderAt
                        if-eqz v1, ${if (binderLoops) ":start" else ":done"}
                        invoke-virtual { v1 }, Ljava/lang/Object;->hashCode()I
                        :done
                        return-void
                    """),
                ),
            )
        } else {
            null
        }
        val viewHolder = classOf(
            VIEW_HOLDER,
            listOf(field(VIEW_HOLDER, "itemView", VIEW, public = itemViewPublic, final = true)),
            listOf(
                method(VIEW_HOLDER, "<init>", listOf(VIEW), "V", 4, """
                    invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
                    if-nez p1, :kept
                    const-string v1, "itemView may not be null"
                    new-instance v0, Ljava/lang/IllegalArgumentException;
                    invoke-direct { v0, v1 }, Ljava/lang/IllegalArgumentException;-><init>(Ljava/lang/String;)V
                    throw v0
                    :kept
                    iput-object p1, p0, $VIEW_HOLDER->itemView:$VIEW
                    return-void
                """, static = false, constructor = true),
            ),
            abstract = true,
        )
        val reelItem = classOf(
            REEL_ITEM,
            emptyList(),
            listOfNotNull(
                if (storyId) method(REEL_ITEM, "getId", emptyList(), "Ljava/lang/String;", 2, """
                    const/4 v0, 0x0
                    return-object v0
                """, static = false) else null,
            ),
            isInterface = reelItemInterface,
        )
        val seenState = if (batchInterface) {
            ImmutableClassDef(
                SEEN_STATE, AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value, OBJECT, null, null, null,
                emptyList(),
                listOf(
                    ImmutableMethod(
                        SEEN_STATE, "A04", listOf(ImmutableMethodParameter(USER_SESSION, null, null)), REQUEST,
                        AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null,
                    ),
                ),
            )
        } else {
            null
        }
        return listOfNotNull(
            batchClass, storeClass, storeBase, resetNux, userSession, binderClass, viewHolder, reelItem, seenState,
            classOf(HOLDER_BASE, emptyList(), emptyList(), superclass = VIEW_HOLDER, abstract = true),
            classOf(HOLDER, emptyList(), emptyList(), superclass = HOLDER_BASE),
            classOf(VIEWER, emptyList(), emptyList()),
            ImmutableClassDef.of(ExtensionDex.classDef(STORY_SEEN)),
            ImmutableClassDef.of(ExtensionDex.classDef(STORY_SEEN_BUTTON)),
        ) + storyQueueDiskHelpers()
    }

    private fun method(
        owner: String, name: String, parameters: List<String>, returns: String, registers: Int, body: String,
        static: Boolean = true, constructor: Boolean = false, public: Boolean = true,
    ): Method {
        val flags = (if (public) AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value) or
            (if (static) AccessFlags.STATIC.value else 0) or
            (if (constructor) AccessFlags.CONSTRUCTOR.value else AccessFlags.FINAL.value)
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun field(owner: String, name: String, type: String, static: Boolean = false, public: Boolean = true, final: Boolean = false) =
        ImmutableField(
            owner, name, type,
            (if (public) AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value) or (if (static) AccessFlags.STATIC.value else 0) or
                (if (final) AccessFlags.FINAL.value else 0),
            null, null, null,
        )

    private fun classOf(
        type: String, fields: List<ImmutableField>, methods: List<Method>, superclass: String = OBJECT, abstract: Boolean = false,
        final: Boolean = true, isInterface: Boolean = false, interfaces: List<String> = emptyList(),
    ): ClassDef {
        val kind = when {
            isInterface -> AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value
            abstract -> AccessFlags.ABSTRACT.value
            final -> AccessFlags.FINAL.value
            else -> 0
        }
        return ImmutableClassDef(type, AccessFlags.PUBLIC.value or kind, superclass, interfaces, null, null, fields, methods)
    }

    /** [classDef] under another name, its members moved with it. */
    private fun copyOf(classDef: ClassDef, type: String): ClassDef = ImmutableClassDef(
        type, classDef.accessFlags, classDef.superclass, classDef.interfaces, null, null,
        classDef.fields.map { ImmutableField(type, it.name, it.type, it.accessFlags, null, null, null) },
        classDef.methods.map {
            ImmutableMethod(type, it.name, it.parameters, it.returnType, it.accessFlags, null, null, it.implementation)
        },
    )

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    private companion object {
        const val BATCH = "Lfixture/PendingReelSeenState;"
        const val BATCH_BASE = "Lfixture/SeenStateBase;"
        const val SEEN_STATE = "Lfixture/SeenState;"
        const val STORE = "Lfixture/PendingReelSeenStateStore;"
        const val STORE_BASE = "Lfixture/SessionStore;"
        const val RESET_NUX = "Lfixture/ResetNux;"
        const val REQUEST = "Lfixture/Request;"
        const val FACTORY = "Lfixture/RequestFactory;"
        const val CONFIG = "Lfixture/RequestConfig;"
        const val SETTINGS = "Lfixture/Settings;"
        const val ELSEWHERE = "Lfixture/Elsewhere;"
        const val BINDER = "Lfixture/ReelViewerItemBinder;"
        const val VIEW_HOLDER = "Lfixture/ViewHolder;"
        const val HOLDER_BASE = "Lfixture/HolderBase;"
        const val HOLDER = "Lfixture/ReelViewerHolder;"
        const val DELEGATE = "Lfixture/Delegate;"
        const val VIEWER = "Lfixture/Viewer;"
        const val OBJECT = "Ljava/lang/Object;"
        private const val STRING = "Ljava/lang/String;"
        const val VIEW = "Landroid/view/View;"
        val HOOKS = setOf(TO_SEND, TO_RETRY, BIND_BUTTON)
    }
}

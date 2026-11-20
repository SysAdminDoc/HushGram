/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.follow

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.IMAGE_URL
import app.morphe.patches.instagram.download.INSTAGRAM_MEDIA
import app.morphe.patches.instagram.download.USER
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
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableExceptionHandler
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableTryBlock
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Sent follow requests (#690): on every build of the declared version, each object return of the
 * follow button helper's status read hands SentRequests.seen the session, the account and the
 * status, right before it returns, with any branch to that return passing through the call too.
 * The helper's method that shows every button its status, a profile header's too, hands it over
 * right before each time it does. The extension's stubs reach the account's field tree, its string fields and the session's
 * account, and the picture bridges are written. A full User is one of the trees an account holds.
 */
class SentFollowRequestsHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(SENT_REQUESTS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SENT_SEEN is not in the extension: $declared", SENT_SEEN in declared)
    }

    @Test
    fun aBuildWithoutTheFollowButtonFailsBeforeAnythingChanges() {
        val refused = assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(ExtensionDex.classDef(SENT_REQUESTS))).findSentRequests()
        }
        assertTrue(refused.message, refused.message!!.contains(FOLLOW_BUTTON_BASE))
    }

    @Test
    fun eachDeclaredBuildHandsOverEveryStatus() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The same checks on every other build of the version, each compiled on its own. */
    @Test
    fun eachOtherBuildHandsThemOverToo() {
        val others = Fixtures.otherBuilds()
        assertEquals(Fixtures.OTHER_BUILDS, others.size)
        for (base in others) builds(base, base.parentFile.name)
    }

    /** A return inside a try block would have the hook throw into Instagram's handler, so it stops the patch. */
    @Test
    fun aReturnInATryBlockStopsThePatch() {
        val bundle = Fixtures.files { it.extension == "apks" && it.name.contains("-${Fixtures.VERSION}-") }.first()
        val sliced = slice(bundle)
        val status = sliced.status
        val implementation = status.implementation!!
        val code = implementation.instructions.toList()
        val last = code.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
        val address = code.take(last).sumOf { it.codeUnits }
        val covered = ImmutableMethod(
            status.definingClass, status.name, status.parameters, status.returnType, status.accessFlags,
            status.annotations, status.hiddenApiRestrictions,
            ImmutableMethodImplementation(
                implementation.registerCount, implementation.instructions,
                implementation.tryBlocks + ImmutableTryBlock(address, code[last].codeUnits, listOf(ImmutableExceptionHandler(null, 0))),
                implementation.debugItems,
            ),
        )
        val helper = sliced.classes.getValue(status.definingClass)
        val changed = ImmutableClassDef(
            helper.type, helper.accessFlags, helper.superclass, helper.interfaces, helper.sourceFile, helper.annotations,
            helper.fields, helper.methods.map { if (it == status) covered else it },
        )
        val context = PatchContexts.of(sliced.classes.values.map { if (it.type == helper.type) changed else it } + extension())
        val refused = assertThrows(PatchException::class.java) { context.findSentRequests() }
        assertTrue(refused.message, refused.message!!.contains("try block"))
    }

    /** A handler in the method that shows a button its status could read what the hook's copies took, so it stops the patch. */
    @Test
    fun aTryBlockWhereAButtonIsShownItsStatusStopsThePatch() {
        val bundle = Fixtures.files { it.extension == "apks" && it.name.contains("-${Fixtures.VERSION}-") }.first()
        val sliced = slice(bundle)
        val name = PatchContexts.of(sliced.classes.values + extension()).findSentRequests().shown.method.toString()
        val helper = sliced.classes.getValue(sliced.status.definingClass)
        val shown = helper.methods.single { it.toString() == name }
        val implementation = shown.implementation!!
        val covered = ImmutableMethod(
            shown.definingClass, shown.name, shown.parameters, shown.returnType, shown.accessFlags,
            shown.annotations, shown.hiddenApiRestrictions,
            ImmutableMethodImplementation(
                implementation.registerCount, implementation.instructions,
                implementation.tryBlocks + ImmutableTryBlock(0, implementation.instructions.first().codeUnits,
                    listOf(ImmutableExceptionHandler(null, 0))),
                implementation.debugItems,
            ),
        )
        val changed = ImmutableClassDef(
            helper.type, helper.accessFlags, helper.superclass, helper.interfaces, helper.sourceFile, helper.annotations,
            helper.fields, helper.methods.map { if (it == shown) covered else it },
        )
        val context = PatchContexts.of(sliced.classes.values.map { if (it.type == helper.type) changed else it } + extension())
        val refused = assertThrows(PatchException::class.java) { context.findSentRequests() }
        assertTrue(refused.message, refused.message!!.contains("has a try block"))
    }

    private class Slice(val classes: Map<String, ClassDef>, val status: Method)

    private fun extension() = listOf(ExtensionDex.classDef(SENT_REQUESTS), ExtensionDex.classDef(INSTAGRAM_MEDIA))

    /**
     * Slices the build to what the patch reads: FollowButtonBase, its helper and the types its
     * (session, account) methods take and answer, the status enum with its string pools, the account
     * model with the classes it extends and their fields' types, the session, and the full account
     * model and picture types the bridges read. Answers the status read as the contract picks it: the
     * helper's one (session, account) method loading the "id" hash and the follow status field hash,
     * or on 456 calling the helper's static method that loads them.
     */
    private fun slice(bundle: File): Slice {
        val have = LinkedHashMap<String, ClassDef>()
        fun load(types: Set<String>) {
            val wanted = types.filter { it !in have && it.startsWith("L") && !it.startsWith("Ljava/") }.toSet()
            if (wanted.isNotEmpty()) have.putAll(FixtureDex.classes(bundle, wanted))
        }
        load(setOf(FOLLOW_BUTTON_BASE, USER_SESSION, USER, IMAGE_URL))
        val helperType = have.getValue(FOLLOW_BUTTON_BASE).methods.single { it.name == "getHelper" && it.parameterTypes.isEmpty() }.returnType
        load(setOf(helperType))
        val helper = have.getValue(helperType)
        fun Method.loadsAll() = CONTRACT_LITERALS.all { literal -> implementation?.instructions?.any { it.loads(literal) } == true }
        val loaders = helper.methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.loadsAll() }.map { it.toString() }.toSet()
        val status = helper.methods.single { method ->
            val types = method.parameterTypes.map(Any::toString)
            !AccessFlags.STATIC.isSet(method.accessFlags) && types.size == 2 && types[0] == USER_SESSION &&
                (method.loadsAll() || method.instructions().any { it.referencedOrNull() in loaders })
        }
        val user = status.parameterTypes[1].toString()
        val statuses = helper.methods.filter { it.parameterTypes.map(Any::toString) == listOf(USER_SESSION, user) }.map { it.returnType }
        load(statuses.toSet() + helper.fields.map { it.type })
        var at: String? = user
        while (at != null && at != "Ljava/lang/Object;") {
            load(setOf(at))
            val classDef = have[at] ?: break
            load(classDef.fields.map { it.type }.toSet())
            at = classDef.superclass
        }
        val pooled = FixtureDex.withStringPools(bundle, statuses.mapNotNull { have[it] })
        val classes = LinkedHashMap<String, ClassDef>()
        for (classDef in have.values + pooled) classes.putIfAbsent(classDef.type, classDef)
        return Slice(classes, status)
    }

    private fun builds(bundle: File, label: String) {
        val sliced = slice(bundle)
        val stock = sliced.status
        val context = PatchContexts.of(sliced.classes.values + extension())
        val found = runCatching { context.findSentRequests() }.getOrElse { throw AssertionError("$label: ${it.message}", it) }
        assertEquals("$label: the status read the contract picks", stock.name, found.status.name)
        val stockCode = stock.implementation!!.instructions.toList()
        val returns = stockCode.count { it.opcode == Opcode.RETURN_OBJECT }
        assertEquals(label, returns, found.returns.size)

        val bridged = { context.mutableClassDefBy(INSTAGRAM_MEDIA).methods
            .filter { it.instructions().firstOrNull()?.opcode == Opcode.CHECK_CAST }.map { it.name }.toSet() }
        assertFalse("$label: nothing is written while it looks", "profilePicture" in bridged())
        sentRequests(found)

        val registers = stock.implementation!!.registerCount
        val session = registers - 2
        val model = registers - 1
        val code = found.status.instructions()
        assertEquals("$label: once at each return", returns, code.count { it.calls(SENT_SEEN) })
        for ((index, instruction) in code.withIndex()) {
            if (!instruction.calls(SENT_SEEN)) continue
            val call = instruction as FiveRegisterInstruction
            val back = code[index + 1]
            assertEquals("$label: right before a return", Opcode.RETURN_OBJECT, back.opcode)
            assertEquals(label, 3, call.registerCount)
            assertEquals("$label: the session", session, call.registerC)
            assertEquals("$label: the account", model, call.registerD)
            assertEquals("$label: what it returns", (back as OneRegisterInstruction).registerA, call.registerE)
        }
        for (branch in code.filterIsInstance<BuilderOffsetInstruction>()) {
            val target = branch.target.location.index
            assertFalse("$label: a branch skips the hook onto a return", code[target].opcode == Opcode.RETURN_OBJECT &&
                code.getOrNull(target - 1)?.calls(SENT_SEEN) == true)
        }
        fun kinds(of: List<Instruction>) = of.map { kind(it.opcode) }.filter { it != Opcode.NOP }
        assertEquals("$label: Instagram's own code is kept", kinds(stockCode), kinds(code.filterNot { it.calls(SENT_SEEN) }))

        // A profile header's follow button asks the resolver the status read returns the answer of
        // itself, and that answer goes over right after it comes back (#690).
        // On 456 the resolver answers a pair and the status read returns the follow status read off it.
        val last = stockCode.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
        val field = stockCode[last - 1].takeIf { it.opcode == Opcode.IGET_OBJECT }?.referenced()
        val resolver = stockCode[last - if (field == null) 2 else 3].referenced()
        val header = found.header.method
        val headerStock = sliced.classes.getValue(header.definingClass).methods.single { it.toString() == header.toString() }
        assertTrue("$label: ${header.name} is static", AccessFlags.STATIC.isSet(headerStock.accessFlags))
        val headerCode = header.instructions()
        assertEquals("$label: once in ${header.name}", 1, headerCode.count { it.calls(SENT_SEEN) })
        val at = headerCode.indexOfFirst { it.calls(SENT_SEEN) }
        val answer = headerCode[at - 1]
        if (field == null) {
            assertEquals("$label: right after the answer is kept", Opcode.MOVE_RESULT_OBJECT, answer.opcode)
            assertEquals("$label: the resolver's answer", resolver, headerCode[at - 2].referenced())
        } else {
            assertEquals("$label: right after the status is read off the answer", field, answer.referencedOrNull())
            val kept = headerCode[at - 2]
            assertEquals("$label: the answer is kept", Opcode.MOVE_RESULT_OBJECT, kept.opcode)
            assertEquals("$label: off the kept answer", (kept as OneRegisterInstruction).registerA, (answer as TwoRegisterInstruction).registerB)
            assertEquals("$label: the resolver's answer", resolver, headerCode[at - 3].referenced())
        }
        val types = headerStock.parameterTypes.map(Any::toString)
        fun width(type: String) = if (type == "J" || type == "D") 2 else 1
        val first = headerStock.implementation!!.registerCount - types.sumOf(::width)
        fun register(type: String) = first + types.take(types.indexOf(type)).sumOf(::width)
        val headerCall = headerCode[at] as FiveRegisterInstruction
        assertEquals(label, 3, headerCall.registerCount)
        assertEquals("$label: the header's session", register(USER_SESSION), headerCall.registerC)
        assertEquals("$label: the header's account", register(found.user), headerCall.registerD)
        assertEquals("$label: the header's answer", (answer as OneRegisterInstruction).registerA, headerCall.registerE)
        assertEquals("$label: Instagram's own header code is kept", kinds(headerStock.instructions()),
            kinds(headerCode.filterNot { it.calls(SENT_SEEN) }))

        // Every button is shown its status through the helper, a 456 profile header's too, and the
        // status goes over right before each of those calls, with the session and the account
        // copied below v16 (#690).
        val statusType = found.status.returnType
        fun Instruction.shows() = opcode == Opcode.INVOKE_INTERFACE &&
            ((this as ReferenceInstruction).reference as MethodReference).let { call ->
                call.returnType == "V" && call.parameterTypes.map(Any::toString) == listOf(statusType)
            }
        val shown = found.shown.method
        val shownStock = sliced.classes.getValue(shown.definingClass).methods.single { it.toString() == shown.toString() }
        assertFalse("$label: ${shown.name} is an instance method", AccessFlags.STATIC.isSet(shownStock.accessFlags))
        val shownTypes = shownStock.parameterTypes.map(Any::toString)
        val shownLocals = shownStock.implementation!!.registerCount - 1 - shownTypes.sumOf(::width)
        fun shownRegister(type: String) = shownLocals + 1 + shownTypes.take(shownTypes.indexOf(type)).sumOf(::width)
        val shows = shownStock.instructions().count { it.shows() }
        assertTrue("$label: ${shown.name} shows a button its status", shows > 0)
        val shownCode = shown.instructions()
        val hooked = shownCode.indices.filter { shownCode[it].calls(SENT_SEEN) }
        assertEquals("$label: once before each status ${shown.name} shows", shows, hooked.size)
        val added = HashSet<Int>()
        for (at in hooked) {
            val call = shownCode[at] as FiveRegisterInstruction
            val session = shownCode[at - 2] as TwoRegisterInstruction
            val model = shownCode[at - 1] as TwoRegisterInstruction
            val next = shownCode[at + 1]
            assertEquals(label, Opcode.MOVE_OBJECT_FROM16, shownCode[at - 2].opcode)
            assertEquals(label, Opcode.MOVE_OBJECT_FROM16, shownCode[at - 1].opcode)
            assertEquals("$label: the session's copy", shownRegister(USER_SESSION), session.registerB)
            assertEquals("$label: the account's copy", shownRegister(found.user), model.registerB)
            assertEquals(label, 3, call.registerCount)
            assertEquals(label, session.registerA, call.registerC)
            assertEquals(label, model.registerA, call.registerD)
            assertTrue("$label: the copies sit below v16", call.registerC < 16 && call.registerD < 16 && call.registerC != call.registerD)
            assertTrue("$label: right before the button is shown its status", next.shows())
            val button = next as FiveRegisterInstruction
            assertEquals("$label: the status the button is shown", button.registerD, call.registerE)
            assertFalse("$label: the copies leave the button and its status alone",
                setOf(button.registerC, button.registerD).any { it == call.registerC || it == call.registerD })
            added += listOf(at - 2, at - 1, at)
        }
        // The call's own label moves onto the first copy, so a branch to the button runs the hook too.
        val skipped = hooked.flatMap { listOf(it - 1, it, it + 1) }.toSet()
        for (branch in shownCode.filterIsInstance<BuilderOffsetInstruction>()) {
            assertFalse("$label: a branch skips the hook onto the button", branch.target.location.index in skipped)
        }
        assertEquals("$label: Instagram's own ${shown.name} code is kept", kinds(shownStock.instructions()),
            kinds(shownCode.filterIndexed { index, _ -> index !in added }))

        val tree = "${found.tree.definingClass}->${found.tree.name}:${found.tree.type}"
        val treeOf = context.stub(TREE_OF_STUB)
        assertEquals(label, listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT), treeOf.take(3).map { it.opcode })
        assertEquals("$label: the stub reads the account's tree", tree, treeOf[1].referenced())
        val fieldOf = context.stub(FIELD_OF_STUB)
        assertEquals(label, tree, fieldOf[1].referenced())
        assertEquals(label, Opcode.INVOKE_INTERFACE, fieldOf[3].opcode)
        assertEquals("$label: through the getter the helper reads the username with",
            "${found.stringGetter.definingClass}->${found.stringGetter.name}(I)Ljava/lang/String;", fieldOf[3].referenced())
        val ownerOf = context.stub(OWNER_OF_STUB)
        assertEquals("$label: the session's account", "$USER_SESSION->$SESSION_USER_ID:Ljava/lang/String;", ownerOf[1].referenced())
        assertTrue("$label: the picture bridges", bridged().containsAll(listOf("profilePicture", "username", "candidateUrl")))

        // The pictures and the Follow removal need the tree to be a full User, so User has to be one of its kinds.
        assertTrue("$label: a User can be the account's tree", reaches(bundle, USER, found.tree.type))
        println("$label: ${found.status.definingClass}->${found.status.name} hands over $returns statuses, ${header.name} the header's, ${shown.name} the $shows shown; account ${found.user}, " +
            "tree $tree, getter ${found.stringGetter.definingClass}->${found.stringGetter.name}")
    }

    /** Whether [type] extends or implements [wanted], through any of its supertypes. */
    private fun reaches(bundle: File, type: String, wanted: String): Boolean {
        val seen = HashSet<String>()
        var pending = setOf(type)
        while (pending.isNotEmpty()) {
            if (wanted in pending) return true
            seen += pending
            pending = FixtureDex.classes(bundle, pending).values
                .flatMap { listOfNotNull(it.superclass) + it.interfaces }
                .filter { it.startsWith("L") && !it.startsWith("Ljava/") && it !in seen }.toSet()
        }
        return false
    }

    private fun Instruction.loads(literal: Int) = (this as? NarrowLiteralInstruction)?.narrowLiteral == literal &&
        (opcode == Opcode.CONST || opcode == Opcode.CONST_16 || opcode == Opcode.CONST_4 || opcode == Opcode.CONST_HIGH16)

    /** An insert can push a branch past a plain goto's reach, and dexlib2 widens it. */
    private fun kind(opcode: Opcode) = if (opcode == Opcode.GOTO_16 || opcode == Opcode.GOTO_32) Opcode.GOTO else opcode

    private fun Instruction.calls(reference: String) = referencedOrNull() == reference

    private fun Instruction.referencedOrNull(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.referenced(): String = referencedOrNull()!!

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private fun app.morphe.patcher.patch.BytecodePatchContext.stub(name: String): List<Instruction> =
        mutableClassDefBy(SENT_REQUESTS).methods.single { it.name == name }.implementation!!.instructions.toList()

    private companion object {
        const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

        /** What scripts/injected-mutation-contracts.txt picks the status read by: "id" and the two status fields' hashes. */
        val CONTRACT_LITERALS = listOf(0xd1b, 0x46ae0f6e)
    }
}

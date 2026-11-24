/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.blocked

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide posts when you block someone (#861): Home's list tells the extension about itself as it's made, the block
 * request's callback tells it the blocked account as it takes the server's answer, and the bridges read that
 * account's id the way the list's removal reads each post's owner and call that removal, the one Instagram's own
 * code calls when you mute someone's posts. Nothing else of Instagram's changes.
 */
class BlockedPostsHookTest {
    private val constants = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16)

    @Test
    fun theHooksAndBridgesAreInTheExtension() {
        val declared = ExtensionDex.classDef(BLOCKED_POSTS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { it.signature() }
        listOf(BLOCKED_FEED, BLOCKED_ACCOUNT, BLOCKED_USER_ID, BLOCKED_REMOVE).forEach {
            assertTrue("$it is not in the extension: $declared", it in declared)
        }
    }

    @Test
    fun anExtensionWithoutTheHooksFailsThePatch() {
        val missing = assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).findBlockedPosts() }
        assertTrue(missing.message, missing.message!!.contains("the extension has no $BLOCKED_POSTS"))
    }

    @Test
    fun aBuildWithoutHomesListFailsBeforeAnythingChanges() {
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(BLOCKED_POSTS)))
        val stub = context.code(BLOCKED_POSTS, BLOCKED_USER_ID)
        val missing = assertThrows(PatchException::class.java) { context.findBlockedPosts() }
        assertTrue(missing.message, missing.message!!.contains("\"$HOME_LIST_TRACE\""))
        assertEquals("the bridges stay stubs", stub.map { it.opcode }, context.code(BLOCKED_POSTS, BLOCKED_USER_ID).map { it.opcode })
    }

    @Test
    fun eachDeclaredBuildTakesABlockedAccountOutOfHome() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                takesOut(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildTakesItOutToo() {
        for (base in Fixtures.otherBuilds()) takesOut(base, base.parentFile.name)
    }

    private fun takesOut(bundle: File, label: String) {
        val lists = FixtureDex.classesLoading(bundle, HOME_LIST_TRACE)
        val requests = FixtureDex.classesLoading(bundle, BLOCK_PATH)
        val byType = (lists + requests).associateBy { it.type }
        val builder = requests.flatMap { it.methods }.singleOrNull { FixtureDex.loads(it, BLOCK_PATH, byType) }
            ?: throw AssertionError("$label: no one method loads $BLOCK_PATH")
        val callers = classesCalling(bundle, builder.signature())
        assertTrue("$label: nothing calls the block request's builder", callers.isNotEmpty())
        val made = callers.flatMap { it.methods }.flatMap { it.code() }.mapNotNull { instruction ->
            when (instruction.opcode) {
                Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> instruction.method()?.returnType
                Opcode.NEW_INSTANCE -> (instruction.reference() as TypeReference).type
                else -> null
            }
        }.filter { it.startsWith("LX/") }.toSet()
        val candidates = FixtureDex.classes(bundle, made).values
        val models = chain(bundle, candidates.flatMap { it.fields }.map { it.type }.filter { it.startsWith("LX/") }.toSet())
        val list = lists.single { holder -> holder.methods.any { it.name == "<init>" && FixtureDex.loads(it, HOME_LIST_TRACE, byType) } }
        val owners = list.methods.flatMap { it.code() }.mapNotNull { (it.reference() as? FieldReference)?.definingClass }
            .filter { it.startsWith("LX/") }.toSet()
        val reads = list.methods.flatMap { it.code() }.filter { it.opcode == Opcode.INVOKE_INTERFACE }
            .mapNotNull { it.method()?.definingClass }.filter { it.startsWith("LX/") }.toSet()
        val classes = FixtureDex.withStringPools(bundle, (lists + requests + callers + candidates + models.values +
            FixtureDex.classes(bundle, owners + reads).values).distinctBy { it.type })

        val context = PatchContexts.of(classes + ExtensionDex.classDef(BLOCKED_POSTS))
        val site = context.findBlockedPosts()
        assertEquals("$label: Home's list", list.type, site.list)
        val stockMade = context.code(site.list, site.made.signature())
        val stockAnswered = context.code(site.callback, site.answered.signature())
        context.applyBlockedPosts(site)

        // Home's list tells the extension about itself right after its superclass's constructor, on that call's register.
        val madeCode = context.code(site.list, site.made.signature())
        val told = madeCode.indices.single { madeCode[it].method()?.toString() == BLOCKED_FEED }
        val superCall = madeCode[told - 1]
        assertEquals("$label: right after a constructor", "<init>", superCall.method()?.name)
        assertEquals("$label: the list's superclass's", list.superclass, superCall.method()?.definingClass)
        assertEquals("$label: handed the list", superCall.registers().first(), madeCode[told].registers().single())
        assertTrue("$label: the constructor's stock kept in order", kept(stockMade, madeCode))

        // The callback reads its account field into a free register and hands it to the extension, first thing.
        val answered = context.code(site.callback, site.answered.signature())
        val first = if (answered[0].opcode == Opcode.MOVE_OBJECT_FROM16) 1 else 0
        val read = answered[first]
        assertEquals("$label: reads the account first", Opcode.IGET_OBJECT, read.opcode)
        assertEquals("$label: the callback's account", site.account, (read.reference() as FieldReference).toString())
        assertEquals("$label: then tells the extension", BLOCKED_ACCOUNT, answered[first + 1].method()?.toString())
        assertEquals("$label: handed the account", (read as TwoRegisterInstruction).registerA, answered[first + 1].registers().single())
        assertTrue("$label: the answer's stock kept in order", kept(stockAnswered, answered))
        assertTrue("$label: the callback reads the account itself", stockAnswered.any { (it.reference() as? FieldReference)?.toString() == site.account })
        assertTrue("$label: the answer is the block going through, which stores the account's new friendship", stockAnswered.any {
            it.method()?.parameterTypes?.map(Any::toString) == listOf("Lcom/instagram/common/session/UserSession;", "Lcom/instagram/user/model/User;")
        })

        // The id bridge reads an account's id exactly as the removal reads each owner's.
        val removalCode = list.methods.single { it.signature() == site.removal }.code()
        assertEquals("$label: the removal reads ids once", 1, removalCode.count { it.method()?.toString() == site.id.read })
        assertTrue("$label: the removal compares ids", removalCode.any { it.method()?.toString() == "Ljava/lang/String;->equals(Ljava/lang/Object;)Z" })
        assertTrue("$label: the removal reads the field", removalCode.any { (it.reference() as? FieldReference)?.toString() == site.id.field })
        assertTrue("$label: the removal loads the key", removalCode.any { it.opcode in constants && (it as NarrowLiteralInstruction).narrowLiteral == site.id.key })
        val bridge = context.code(BLOCKED_POSTS, BLOCKED_USER_ID)
        assertEquals(label, listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.CONST, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT_OBJECT,
            Opcode.RETURN_OBJECT), bridge.map { it.opcode })
        assertEquals("$label: works on the owner's model", site.id.owner, (bridge[0].reference() as TypeReference).type)
        assertEquals("$label: reads its field", site.id.field, (bridge[1].reference() as FieldReference).toString())
        assertEquals("$label: asks for the key", site.id.key, (bridge[2] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$label: through the removal's read", site.id.read, bridge[3].method()?.toString())

        // The removal bridge calls the list's removal, the one Instagram's own code already calls.
        val remove = context.code(BLOCKED_POSTS, BLOCKED_REMOVE)
        assertEquals(label, listOf(Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID), remove.map { it.opcode })
        assertEquals("$label: works on Home's list", site.list, (remove[0].reference() as TypeReference).type)
        assertEquals("$label: calls its removal", site.removal, remove[1].method()?.toString())
        assertTrue("$label: nothing of Instagram's calls that removal", classesCalling(bundle, site.removal).isNotEmpty())
    }

    /** The bundle's classes calling [method], copied. */
    private fun classesCalling(bundle: File, method: String): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        val owner = method.substringBefore("->")
        FixtureDex.forEach(bundle) { dex ->
            if (dex.typeSection.none { it == owner }) return@forEach
            for (classDef in dex.classes) {
                val calls = classDef.methods.any { m -> m.implementation?.instructions?.any { it.method()?.toString() == method } == true }
                if (calls) found += ImmutableClassDef.of(classDef)
            }
        }
        return found
    }

    /** [types] and each one's superclasses among Instagram's own, by type. */
    private fun chain(bundle: File, types: Set<String>): Map<String, ClassDef> {
        val found = linkedMapOf<String, ClassDef>()
        var wanted = types
        while (wanted.isNotEmpty()) {
            val next = FixtureDex.classes(bundle, wanted)
            found += next
            wanted = next.values.mapNotNull { it.superclass }.filter { it !in found && it.startsWith("LX/") }.toSet()
        }
        return found
    }

    /** Whether every stock instruction is still there, in order, a widened goto counting as the goto. */
    private fun kept(stock: List<Instruction>, patched: List<Instruction>): Boolean {
        val gotos = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)
        fun Instruction.kind() = if (opcode in gotos) Opcode.GOTO else opcode
        val wanted = stock.filter { it.opcode != Opcode.NOP }
        var at = 0
        for (instruction in patched) if (at < wanted.size && instruction.kind() == wanted[at].kind()) at++
        return at == wanted.size
    }

    private fun BytecodePatchContext.code(type: String, signature: String): List<Instruction> =
        mutableClassDefBy(type).methods.single { it.signature() == signature }.implementation!!.instructions.toList()

    private fun Method.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()
    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
    private fun Instruction.method() = reference() as? MethodReference
    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }
}

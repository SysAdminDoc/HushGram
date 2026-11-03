/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.preview

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureCache
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.keep.USER_SESSION
import app.morphe.patches.instagram.direct.keep.findMessageParser
import app.morphe.patches.instagram.direct.seen.NativeMarkReadTest.Companion.markReadClasses
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.fixtures
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.otherBuilds
import app.morphe.patches.instagram.direct.seen.STORE_SEEN_MISSING
import app.morphe.patches.instagram.direct.seen.THREAD_KEY
import app.morphe.patches.instagram.direct.seen.readWithoutSeenReceipt
import app.morphe.patches.instagram.direct.seen.visualCode
import app.morphe.patches.instagram.direct.seen.visualReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
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
 * Preview chats on each build's own dex: the row goes on the chat menu builder that offers Mark as
 * unread, the label hook runs on every path into the labels' return, the tap hook hands the row,
 * the account and the chat key to the extension before Instagram's own code, and the bridges name
 * only what Instagram declares.
 */
class NativePreviewChatsTest {
    @Test fun declaredBuildsOfferPreviewAndReadTheChatStore() = fixtures { check(it) }

    @Test fun otherBuildsOfferPreviewAndReadTheChatStoreToo() = otherBuilds { check(it) }

    private fun check(bundle: File) {
        val name = bundle.parentFile.name
        val classes = previewClasses(bundle)
        val context = PatchContexts.of(classes.values)
        val found = try {
            context.findPreview()
        } catch (failure: PatchException) {
            throw AssertionError("$name: ${failure.message}", failure)
        }
        assertTrue(AccessFlags.STATIC.isSet(found.builder.accessFlags))
        assertEquals("Ljava/util/List;", found.builder.parameterTypes[found.rows].toString())
        assertEquals(found.menu, found.action.parameterTypes[found.chosen].toString())
        assertEquals(THREAD_KEY, found.action.parameterTypes[found.key].toString())
        assertEquals(USER_SESSION, found.session.type)
        assertEquals(found.action.definingClass, found.session.definingClass)
        val store = classes.getValue(found.store.messages.definingClass)
        assertTrue("$name: the store is read through its interface", AccessFlags.INTERFACE.isSet(store.accessFlags))
        assertEquals(store.type, found.store.getter.returnType)
        assertEquals(listOf(THREAD_KEY, "Z"), found.store.messages.parameterTypes.map(Any::toString))
        assertEquals(listOf(USER_SESSION), found.sender.getter.parameterTypes.map(Any::toString))
        assertEquals(found.sender.send.definingClass, found.sender.getter.returnType)

        val builderBefore = found.builder.visualCode().count { it.opcode != Opcode.NOP }
        val labelsBefore = found.labels.visualCode().count { it.opcode != Opcode.NOP }
        val actionBefore = found.action.visualCode().filter { it.opcode != Opcode.NOP }
        writePreview(found)

        val rows = found.builder.visualCode().filter { it.opcode != Opcode.NOP }
        assertEquals("$name: the builder gets the offer and nothing else", builderBefore + 3, rows.size)
        assertEquals(found.menu, (rows[0].visualReference() as TypeReference).type)
        assertEquals(PREVIEW_OFFER, rows[2].visualReference().toString())

        val labels = found.labels.visualCode()
        assertEquals("$name: the labels get one call", labelsBefore + 1, labels.count { it.opcode != Opcode.NOP })
        val returned = labels.indices.single { labels[it].opcode == Opcode.RETURN_OBJECT }
        val hook = labels.indices.single { labels[it].visualReference()?.toString() == PREVIEW_LABEL }
        assertEquals("$name: the label hook sits right before the return", returned - 1, hook)
        val targets = labels.indices.mapNotNull { branchTarget(labels, it) }
        assertTrue("$name: some path jumps onto the label hook", hook in targets)
        assertTrue("$name: no path jumps past the label hook onto the return", returned !in targets)

        val act = found.action.visualCode()
        assertEquals(PREVIEW_OPEN, act[4].visualReference().toString())
        assertEquals(Opcode.IF_EQZ, act[6].opcode)
        assertEquals(Opcode.RETURN_VOID, act[7].opcode)
        assertEquals("$name: a no goes on to Instagram's own first instruction", 8, branchTarget(act, 6))
        assertEquals("$name: Instagram's own code follows untouched", actionBefore.map(::text),
            act.drop(8).filter { it.opcode != Opcode.NOP }.map(::text))

        val bridges = context.mutableClassDefBy(PREVIEW_MESSAGES).methods.filter { it.name != "<init>" }
        assertEquals(9, bridges.size)
        for (bridge in bridges) assertCallsInstagram(name, bridge, classes)
        assertTrue("$name: the account id bridge is written, so the box knows the account's own messages",
            found.accountId.written())
        assertCallsInstagram(name, found.accountId.stub, classes)
    }

    /**
     * Instagram's Mark as read hooks the same builder and tap handler, and patches apply in either
     * order. Both write the account id bridge, and it gets its body (one cast) once.
     */
    @Test fun markAsReadAndPreviewApplyInEitherOrder() = fixtures { bundle ->
        PatchContexts.of(previewClasses(bundle).values).apply {
            val found = findPreview()
            writePreview(found)
            readWithoutSeenReceipt()
            assertEquals(1, found.accountId.stub.visualCode().count { it.opcode == Opcode.CHECK_CAST })
        }
        PatchContexts.of(previewClasses(bundle).values).apply {
            readWithoutSeenReceipt()
            val found = findPreview()
            writePreview(found)
            assertEquals(1, found.accountId.stub.visualCode().count { it.opcode == Opcode.CHECK_CAST })
        }
    }

    /** Without the extension's bridges the patch refuses before any code changes. */
    @Test fun aMissingBridgeClassFailsBeforeAnythingChanges() = fixtures { bundle ->
        val classes = previewClasses(bundle).filterKeys { it != PREVIEW_MESSAGES }
        val context = PatchContexts.of(classes.values)
        val failure = assertThrows(PatchException::class.java) { context.findPreview() }
        assertTrue(failure.message, failure.message!!.startsWith("$PREVIEW_CHATS_PATCH: the extension has no"))
    }

    @Test fun anEmptyBuildFailsNamingThePatch() {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(emptyList<ClassDef>()).findPreview() }
        assertTrue(failure.message, failure.message!!.startsWith(PREVIEW_CHATS_PATCH))
    }

    /** What an instruction does, a goto widened by the insert reading as the goto it was. */
    private fun text(instruction: Instruction): String {
        val opcode = if (instruction.opcode in GOTOS) Opcode.GOTO else instruction.opcode
        return "$opcode ${instruction.visualReference() ?: ""}"
    }

    /** Where the branch at [index] of [code] lands, or null when it isn't a branch. */
    private fun branchTarget(code: List<Instruction>, index: Int): Int? {
        val instruction = code[index] as? OffsetInstruction ?: return null
        if (instruction.opcode in PAYLOADS) return null
        val address = IntArray(code.size + 1)
        code.forEachIndexed { at, each -> address[at + 1] = address[at] + each.codeUnits }
        return (0 until code.size).firstOrNull { address[it] == address[index] + instruction.codeOffset }
    }

    /** Each bridge calls or reads only what the native classes declare, so its body links on a phone. */
    private fun assertCallsInstagram(name: String, bridge: Method, classes: Map<String, ClassDef>) {
        val reaches = bridge.visualCode().mapNotNull { it.visualReference() }.filter { reference ->
            when (reference) {
                is MethodReference -> !reference.definingClass.startsWith("Ljava/")
                is FieldReference -> true
                is TypeReference -> !reference.type.startsWith("Ljava/")
                else -> false
            }
        }
        assertTrue("$name: ${bridge.name} reaches nothing of Instagram's", reaches.isNotEmpty())
        for (reference in reaches) {
            val declared = when (reference) {
                is MethodReference -> classes[reference.definingClass]?.methods?.any { it.toString() == reference.toString() } == true
                is FieldReference -> classes[reference.definingClass]?.fields?.any { it.toString() == reference.toString() } == true
                else -> (reference as TypeReference).type in classes
            }
            assertTrue("$name: ${bridge.name} names $reference, which its class doesn't declare", declared)
        }
    }

    companion object {
        private val cached = FixtureCache<Map<String, ClassDef>>()
        private val PAYLOADS = setOf(Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH, Opcode.FILL_ARRAY_DATA)
        private val GOTOS = setOf(Opcode.GOTO_16, Opcode.GOTO_32)

        /**
         * Mark as read's classes (the chat menu's enum, builder and tap handler, the chat store), plus
         * the menu's labels, the store's interface and its getter, the inbox composer and its sender,
         * the message parser with the classes it stores into, and the extension's hooks and bridges.
         */
        internal fun previewClasses(bundle: File): Map<String, ClassDef> = cached.get(bundle) {
            val classes = markReadClasses(bundle).toMutableMap()
            fun add(found: Collection<ClassDef>) = found.forEach { classes.putIfAbsent(it.type, it) }
            for (anchor in listOf(DEBUG_LABEL, INBOX_COMPOSER, STORE_SEEN_MISSING, "message_item_dict")) {
                add(FixtureDex.withStringPools(bundle, FixtureDex.classesLoading(bundle, anchor)))
            }
            val stores = FixtureDex.classesLoading(bundle, STORE_SEEN_MISSING)
            val faces = stores.flatMap { it.interfaces }.toSet()
            add(FixtureDex.classes(bundle, faces - classes.keys).values)
            val getters = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it in faces } }) { method ->
                AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType in faces &&
                    method.parameterTypes.map(Any::toString) == listOf(USER_SESSION)
            }
            add(FixtureDex.classes(bundle, getters.map { it.definingClass }.toSet() - classes.keys).values)
            val senders = FixtureDex.classesLoading(bundle, INBOX_COMPOSER).flatMap { it.methods }.flatMap { it.visualCode() }
                .mapNotNull { it.visualReference() as? MethodReference }
                .filter { it.parameterTypes.map(Any::toString) == listOf(THREAD_KEY, "Ljava/lang/String;", "Ljava/lang/String;") }
            add(FixtureDex.classes(bundle, senders.map { it.definingClass }.toSet() - classes.keys).values)

            // The classes the message parser stores into, and the classes they extend.
            val parse = PatchContexts.of(classes.values).findMessageParser()
            val named = mutableSetOf<String>()
            for (instruction in parse.visualCode()) {
                val field = instruction.visualReference() as? FieldReference ?: continue
                if (instruction.opcode == Opcode.IPUT_OBJECT || instruction.opcode == Opcode.IPUT_BOOLEAN) {
                    named += field.definingClass
                    named += field.type
                }
            }
            var more = FixtureDex.classes(bundle, named.filter { it.startsWith("L") }.toSet() - classes.keys)
            add(more.values)
            more = FixtureDex.classes(bundle, more.values.mapNotNull { it.superclass }.toSet() - classes.keys)
            add(more.values)

            for (type in listOf(CHAT_PREVIEW, PREVIEW_MESSAGES)) classes[type] = ImmutableClassDef.of(ExtensionDex.classDef(type))
            classes
        }
    }
}

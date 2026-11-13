/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.lock

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureCache
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.preview.DEBUG_LABEL
import app.morphe.patches.instagram.direct.preview.NativePreviewChatsTest.Companion.previewClasses
import app.morphe.patches.instagram.direct.preview.PREVIEW_LABEL
import app.morphe.patches.instagram.direct.preview.PREVIEW_OFFER
import app.morphe.patches.instagram.direct.preview.PREVIEW_OPEN
import app.morphe.patches.instagram.direct.preview.findPreview
import app.morphe.patches.instagram.direct.preview.writePreview
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.fixtures
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.otherBuilds
import app.morphe.patches.instagram.direct.seen.THREAD_KEY
import app.morphe.patches.instagram.direct.seen.visualCode
import app.morphe.patches.instagram.direct.seen.visualReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide chat in a chat's long press menu (#661) on each build's own dex: the menu builder hands its own
 * list of rows to the extension first thing, the menu hands over the chat's key and the name over it
 * first thing, the label hook runs on every path
 * into the labels' return, the tap hook answers before Instagram's own code, and the key reader gets
 * a body that reads the key's own id field.
 */
class HiddenChatMenuHookTest {
    @Test fun declaredBuildsTakeHideChatInTheChatMenu() = fixtures { check(it) }

    @Test fun otherBuildsTakeHideChatInTheChatMenuToo() = otherBuilds { check(it) }

    private fun check(bundle: File) {
        val name = bundle.parentFile.name
        val context = PatchContexts.of(menuClasses(bundle).values)
        val found = try {
            context.findHiddenChatMenu()
        } catch (failure: PatchException) {
            throw AssertionError("$name: ${failure.message}", failure)
        }
        assertEquals(LIST, found.builder.parameterTypes[found.builderRows].toString())
        assertEquals("$name: with Preview chats not in yet, the offer goes in first thing", 0, found.builderAt)
        val show = found.show.parameterTypes.map(Any::toString)
        assertEquals(THREAD_KEY, show[found.showKey])
        assertEquals(STRING, show[found.showTitle])
        assertEquals(found.menu, found.action.parameterTypes[found.chosen].toString())
        assertEquals(THREAD_KEY, found.action.parameterTypes[found.key].toString())
        assertEquals(THREAD_KEY, found.idField.definingClass)
        assertEquals(STRING, found.idField.type)
        assertTrue("$name: the hooks' invoke registers fit four bits",
            (found.builderLocals + found.showLocals + found.actionLocals).all { it in 0..15 })

        val builderBefore = found.builder.visualCode().filter { it.opcode != Opcode.NOP }.map(::text)
        val showBefore = found.show.visualCode().filter { it.opcode != Opcode.NOP }.map(::text)
        val labelsBefore = found.labels.visualCode().count { it.opcode != Opcode.NOP }
        val actionBefore = found.action.visualCode().filter { it.opcode != Opcode.NOP }.map(::text)
        hookHiddenChatMenu(found)

        // The row goes into the builder's own list: the menu is later handed a read-only copy.
        val built = found.builder.visualCode()
        assertEquals(found.menu, (built[0].visualReference() as TypeReference).type)
        assertEquals(MENU_OFFER, built[2].visualReference().toString())
        assertEquals("$name: the builder's own code follows untouched", builderBefore,
            built.drop(3).filter { it.opcode != Opcode.NOP }.map(::text))

        val shown = found.show.visualCode()
        assertEquals(MENU_SHOWN, shown[2].visualReference().toString())
        assertEquals("$name: the menu's own code follows untouched", showBefore,
            shown.drop(3).filter { it.opcode != Opcode.NOP }.map(::text))

        val labels = found.labels.visualCode()
        assertEquals("$name: the labels get one call", labelsBefore + 1, labels.count { it.opcode != Opcode.NOP })
        val returned = labels.indices.single { labels[it].opcode == Opcode.RETURN_OBJECT }
        val hook = labels.indices.single { labels[it].visualReference()?.toString() == MENU_LABEL }
        assertEquals("$name: the label hook sits right before the return", returned - 1, hook)
        assertTrue("$name: no path jumps past the label hook onto the return",
            returned !in labels.indices.mapNotNull { branchTarget(labels, it) })

        val act = found.action.visualCode()
        assertEquals(MENU_TAP, act[2].visualReference().toString())
        assertEquals(Opcode.MOVE_RESULT, act[3].opcode)
        assertEquals(Opcode.IF_EQZ, act[4].opcode)
        assertEquals(Opcode.RETURN_VOID, act[5].opcode)
        assertEquals("$name: a no goes on to Instagram's own first instruction", 6, branchTarget(act, 4))
        assertEquals("$name: Instagram's own code follows untouched", actionBefore,
            act.drop(6).filter { it.opcode != Opcode.NOP }.map(::text))

        val reader = found.threadId.visualCode()
        assertEquals(Opcode.CHECK_CAST, reader[0].opcode)
        assertEquals(THREAD_KEY, (reader[0].visualReference() as TypeReference).type)
        assertEquals(Opcode.IGET_OBJECT, reader[1].opcode)
        val read = reader[1].visualReference() as FieldReference
        assertEquals(listOf(found.idField.definingClass, found.idField.name, found.idField.type),
            listOf(read.definingClass, read.name, read.type))
        assertEquals(Opcode.RETURN_OBJECT, reader[2].opcode)
    }

    /**
     * Preview chats hooks the same builder, label return and tap handler, and the two apply in either
     * order. Its offer stays the builder's first call (its start-call contract), Hide chat's straight
     * after it.
     */
    @Test fun previewChatsAndHideChatShareTheMenuInEitherOrder() = fixtures { bundle ->
        for (previewFirst in listOf(true, false)) {
            val order = "${bundle.parentFile.name}, " + if (previewFirst) "Preview chats first" else "Hide chat first"
            val context = PatchContexts.of(menuClasses(bundle).values)
            if (previewFirst) writePreview(context.findPreview())
            val found = context.findHiddenChatMenu()
            hookHiddenChatMenu(found)
            if (!previewFirst) writePreview(context.findPreview())

            val built = found.builder.visualCode()
            val calls = built.indices.filter { built[it].visualReference() is MethodReference }
            assertEquals(order, PREVIEW_OFFER, built[calls[0]].visualReference().toString())
            assertEquals(order, MENU_OFFER, built[calls[1]].visualReference().toString())
            assertEquals("$order: Preview's offer is first thing", 2, calls[0])
            assertEquals("$order: Hide chat's offer straight after it", 5, calls[1])
            assertEquals(order, 1, built.count { it.visualReference()?.toString() == MENU_OFFER })
            assertEquals(order, 1, built.count { it.visualReference()?.toString() == PREVIEW_OFFER })

            val labels = found.labels.visualCode()
            assertEquals(order, 1, labels.count { it.visualReference()?.toString() == MENU_LABEL })
            assertEquals(order, 1, labels.count { it.visualReference()?.toString() == PREVIEW_LABEL })
            val returned = labels.indices.single { labels[it].opcode == Opcode.RETURN_OBJECT }
            val hooks = labels.indices.filter { labels[it].visualReference()?.toString() in setOf(MENU_LABEL, PREVIEW_LABEL) }
            assertEquals("$order: both label hooks sit right before the return", listOf(returned - 2, returned - 1), hooks)
            assertTrue("$order: no path skips a label hook",
                labels.indices.mapNotNull { branchTarget(labels, it) }.none { it == returned || it == returned - 1 })

            val act = found.action.visualCode()
            assertEquals(order, 1, act.count { it.visualReference()?.toString() == MENU_TAP })
            assertEquals(order, 1, act.count { it.visualReference()?.toString() == PREVIEW_OPEN })
        }
    }

    /** A build whose menu has moved refuses naming the lock, and the lock goes on without the row. */
    @Test fun anEmptyBuildRefusesNamingTheLockAndTheRestGoesOn() {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(emptyList<ClassDef>()).findHiddenChatMenu() }
        assertTrue(failure.message, failure.message!!.startsWith("$LOCK_PATCH: "))
        assertNull(PatchContexts.of(emptyList<ClassDef>()).findHiddenChatMenuOrSkip())
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

    companion object {
        private const val LIST = "Ljava/util/List;"
        private const val STRING = "Ljava/lang/String;"
        private val PAYLOADS = setOf(Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH, Opcode.FILL_ARRAY_DATA)
        private val GOTOS = setOf(Opcode.GOTO_16, Opcode.GOTO_32)
        private val cached = FixtureCache<Map<String, ClassDef>>()

        /**
         * Preview chats' classes (the chat menu's enum, its labels and tap handler, the chat key), plus
         * the method that shows a chat's menu and labels its rows, and the extension's menu hooks.
         */
        internal fun menuClasses(bundle: File): Map<String, ClassDef> = cached.get(bundle) {
            val classes = previewClasses(bundle).toMutableMap()
            val labelCalls = FixtureDex.classesLoading(bundle, DEBUG_LABEL).flatMap { it.methods }
                .filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "[Ljava/lang/String;" }
                .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
                .toSet()
            val shows = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == THREAD_KEY } }) { method ->
                val parameters = method.parameterTypes.map(Any::toString)
                AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && THREAD_KEY in parameters &&
                    LIST in parameters && STRING in parameters &&
                    method.implementation?.instructions?.any { instruction ->
                        ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.toString() in labelCalls
                    } == true
            }
            val wanted = shows.map { it.definingClass }.toSet() + THREAD_KEY - classes.keys
            FixtureDex.withStringPools(bundle, FixtureDex.classes(bundle, wanted).values).forEach { classes.putIfAbsent(it.type, it) }
            classes[HIDDEN_CHATS_MENU] = ImmutableClassDef.of(ExtensionDex.classDef(HIDDEN_CHATS_MENU))
            classes
        }
    }
}

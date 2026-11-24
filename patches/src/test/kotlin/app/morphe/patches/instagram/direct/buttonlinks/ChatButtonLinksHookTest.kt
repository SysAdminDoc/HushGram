/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.buttonlinks

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
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
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Copy links from chat buttons (#852): on every declared build, the maker of a held message's menu
 * model hands its card row list and the message to the extension in place of the list's test, the
 * menu's action dispatcher, which every row's tap reaches, asks the extension first, so does each
 * row of a popup list More opens that hands the dispatcher More's text, and the stubs read the
 * message's buttons the way the model's own code does. The rest of every changed method is
 * Instagram's.
 */
class ChatButtonLinksHookTest {
    private companion object {
        /** What a held message's menu row carries: its action and its text. */
        const val LONG_PRESS_DATA = "Lcom/instagram/direct/messagethread/interaction/longpressaction/LongPressActionData;"
    }

    @Test
    fun theHooksAndStubsAreInTheExtension() {
        val declared = ExtensionDex.classDef(CHAT_BUTTON_LINKS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(BUTTON_ROWS, BUTTON_PICKED)) assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        for ((name, parameters, returns) in BUTTON_STUBS) {
            assertTrue("$name is not in the extension: $declared", "$name(${parameters.joinToString("")})$returns" in declared)
        }
    }

    @Test
    fun eachDeclaredBuildGetsTheRowAndItsTap() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    @Test
    fun withoutTheExtensionThePatchFails() {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).findChatButtonLinks() }
        assertTrue(failure.message!!, failure.message!!.contains(CHAT_BUTTON_LINKS))
    }

    private fun builds(bundle: File, label: String) {
        val classes = slice(bundle)
        val context = PatchContexts.of(classes)
        val site = context.findChatButtonLinks()
        val stockBuilder = classes.single { it.type == site.builderType }.methods.single { it.name == site.builderName && it.parameters() == site.builderParameters }
        val stockDispatcher = classes.single { it.type == site.dispatcherType }.methods.single { it.name == site.dispatcherName && it.parameters() == site.dispatcherParameters }
        val stock = stockBuilder.code()
        val stockStart = stockDispatcher.code()
        val where = "$label ${site.builderType}->${site.builderName}"

        // What the stubs will read is what the model's own code reads.
        assertEquals("$where: the test is the list's", Opcode.IF_EQZ, stock[site.hookAt].opcode)
        assertEquals("$where: right before the copy", listOf(site.list), stock[site.hookAt + 1].arguments())
        assertEquals("$where: the row model takes (words, icon, text)", rowParameters, site.row.parameters())
        assertEquals("$where: the text buttons are a list", "Ljava/util/List;", site.textButtons.type)
        assertEquals("$where: a card is read off the message", site.messageType, site.cards.definingClass)
        // Every row handler of the menu that hands a row's action on with the chat's activity, the
        // rows of the list More opens among them, reaches the guarded dispatcher (#852: those rows
        // went past a guard in the chat's own handler for the menu's bottom row).
        val action = site.dispatcherParameters[site.actionParameter]
        val dispatcherSignature = "${site.dispatcherType}->${site.dispatcherName}(${site.dispatcherParameters.joinToString("")})V"
        val handlers = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == LONG_PRESS_DATA } }) { method ->
            method.code().any { (it.reference() as? FieldReference)?.let { field -> field.definingClass == LONG_PRESS_DATA && field.type == action } == true } &&
                method.code().any { it.handsOnAction(action) }
        }
        assertTrue("$where: no row handler hands a row's action on", handlers.isNotEmpty())
        for (handler in handlers) {
            val targets = handler.code().filter { it.handsOnAction(action) }.map { it.reference().toString() }.toSet()
            assertEquals("$where: ${handler.definingClass}->${handler.name} reaches the guarded dispatcher", setOf(dispatcherSignature), targets)
        }
        assertEquals("$where: the dispatcher takes the activity", "Landroid/app/Activity;", site.dispatcherParameters[site.activityParameter])
        assertEquals("$where: the dispatcher takes the row's text", "Ljava/lang/String;", site.dispatcherParameters[site.textParameter])
        // A row handler that reads no text off its row hands the dispatcher another one (#852: the
        // rows of the popup list More opens hand it More's), so it asks the extension first itself.
        fun Method.readsRowText() = code().any { (it.reference() as? FieldReference)?.let { field -> field.definingClass == LONG_PRESS_DATA && field.type == "Ljava/lang/String;" } == true }
        fun Method.key() = "$definingClass->$name(${parameters().joinToString("")})$returnType"
        val guarded = site.rowHandlers.mapTo(HashSet()) { "${it.type}->${it.name}(${it.parameters.joinToString("")})${it.returns}" }
        assertEquals("$where: every row handler reading no text off its row asks first", handlers.filterNot { it.readsRowText() }.mapTo(HashSet()) { it.key() }, guarded)
        assertTrue("$where: the rows of the popup list More opens ask first", guarded.isNotEmpty())
        for (handler in site.rowHandlers) {
            val name = "${handler.type}->${handler.name}"
            assertEquals("$where: $name keeps its row", listOf(handler.type, LONG_PRESS_DATA), listOf(handler.row.definingClass, handler.row.type))
            assertEquals("$where: $name keeps the activity", listOf(handler.type, "Landroid/app/Activity;"), listOf(handler.activity.definingClass, handler.activity.type))
            assertEquals("$where: $name reads the row's action", listOf(LONG_PRESS_DATA, action), listOf(handler.action.definingClass, handler.action.type))
            assertTrue("$where: $name reads the text a row handler hands the dispatcher", handlers.any { other ->
                other.readsRowText() && other.code().any { it.reference().toString() == handler.text.toString() }
            })
        }
        val stockHandlers = site.rowHandlers.associate { handler ->
            "${handler.type}->${handler.name}" to classes.single { it.type == handler.type }.methods
                .single { it.name == handler.name && it.parameters() == handler.parameters && it.returnType == handler.returns }.code()
        }
        // A list or message past v15 (457) is copied into two free registers in a row and asked with a range.
        val scratch = site.scratch
        if (scratch != null) {
            assertTrue("$where: copies only what a plain call can't name", site.list > 15 || site.message > 15)
            val live = RegisterLiveness.of(stockBuilder).liveInto(site.hookAt)
            assertTrue("$where: the copies go where nothing is read", listOf(scratch, scratch + 1).none { it in live || it == site.list || it == site.message })
        }
        val grow = if (scratch == null) 2 else 4
        val askAt = site.hookAt + grow - 2
        val stockFlow = ControlFlow.of(stockBuilder)
        val intoTest = stockFlow.normal.indices.filter { site.hookAt in stockFlow.normal[it] }.map { if (it < site.hookAt) it else it + grow }

        context.applyChatButtonLinks(site)

        val builder = context.mutableClassDefBy(site.builderType).methods.single { it.name == site.builderName && it.parameters() == site.builderParameters }
        val code = builder.code()
        assertEquals("$where: grew by the answer and the test", stock.size + grow, code.size)
        assertEquals("$where: Instagram's code up to the test stays", stock.take(site.hookAt).map { it.opcode }, code.take(site.hookAt).map { it.opcode })
        val ask = code[askAt]
        assertEquals("$where: asks the extension in the test's place", BUTTON_ROWS, ask.reference().toString())
        if (scratch == null) {
            assertEquals("$where: with the list and the message", listOf(site.list, site.message), ask.arguments())
        } else {
            assertEquals("$where: with copies of the list and the message", listOf(scratch, scratch + 1), ask.arguments())
            assertEquals(Opcode.INVOKE_STATIC_RANGE, ask.opcode)
            for ((at, from) in listOf(site.hookAt to site.list, site.hookAt + 1 to site.message)) {
                assertEquals("$where: copies v$from", Opcode.MOVE_OBJECT_FROM16, code[at].opcode)
                assertEquals("$where: copies v$from", from, (code[at] as TwoRegisterInstruction).registerB)
            }
        }
        assertEquals("$where: keeps the answer in the list", site.list, (code[askAt + 1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, code[askAt + 1].opcode)
        assertEquals("$where: tests the answer", Opcode.IF_EQZ, code[askAt + 2].opcode)
        assertEquals(site.list, (code[askAt + 2] as OneRegisterInstruction).registerA)
        val flow = ControlFlow.of(builder)
        assertEquals("$where: no list goes where none went", site.skipTo + grow, flow.normal[askAt + 2].first())
        assertEquals("$where: the rest is Instagram's", stock.drop(site.hookAt + 1).map { it.opcode }, code.drop(site.hookAt + 1 + grow).map { it.opcode })
        for (at in intoTest) assertTrue("$where: instruction $at still goes to the test, now the ask", site.hookAt in flow.normal[at])

        val dispatcher = context.mutableClassDefBy(site.dispatcherType).methods.single { it.name == site.dispatcherName && it.parameters() == site.dispatcherParameters }
        val first = dispatcher.code()
        // The guard moves the dispatcher's switch payloads, so alignment nops come and go.
        fun List<Instruction>.ops() = filter { it.opcode != Opcode.NOP }.map { it.opcode }
        assertEquals("$where: the dispatcher grew by the guard", stockStart.ops().size + 7, first.ops().size)
        val static = AccessFlags.STATIC.isSet(dispatcher.accessFlags)
        fun width(type: String) = if (type == "J" || type == "D") 2 else 1
        val ins = (if (static) 0 else 1) + site.dispatcherParameters.sumOf { width(it) }
        fun register(index: Int) = dispatcher.implementation!!.registerCount - ins + (if (static) 0 else 1) +
            site.dispatcherParameters.take(index).sumOf { width(it) }
        for ((at, index, what) in listOf(Triple(0, site.activityParameter, "activity"), Triple(1, site.actionParameter, "action"), Triple(2, site.textParameter, "text"))) {
            assertEquals("$where: copies the $what", Opcode.MOVE_OBJECT_FROM16, first[at].opcode)
            assertEquals("$where: into v$at", at, (first[at] as TwoRegisterInstruction).registerA)
            assertEquals("$where: from the $what parameter", register(index), (first[at] as TwoRegisterInstruction).registerB)
        }
        assertEquals("$where: asks the extension first", BUTTON_PICKED, first[3].reference().toString())
        assertEquals("$where: with the activity, action and text", listOf(0, 1, 2), first[3].arguments())
        assertEquals("$where: returns on a yes", Opcode.RETURN_VOID, first[6].opcode)
        assertTrue("$where: goes on to Instagram's code on a no", 7 in ControlFlow.of(dispatcher).normal[5])
        assertEquals("$where: the dispatcher's own code stays", stockStart.ops(), first.drop(7).ops())

        for (handler in site.rowHandlers) {
            val name = "${handler.type}->${handler.name}"
            val method = context.mutableClassDefBy(handler.type).methods
                .single { it.name == handler.name && it.parameters() == handler.parameters && it.returnType == handler.returns }
            val code = method.code()
            val guard = if (handler.unit == null) 9 else 10
            assertEquals("$where: $name grew by the guard", stockHandlers.getValue(name).ops().size + guard, code.ops().size)
            val self = method.implementation!!.registerCount - 1 - handler.parameters.sumOf { width(it) }
            fun read(at: Int, into: Int, from: Int, field: String) {
                assertEquals("$where: $name reads at $at", Opcode.IGET_OBJECT, code[at].opcode)
                assertEquals("$where: $name reads $field", field, code[at].reference().toString())
                assertEquals("$where: $name reads into v$into off v$from", listOf(into, from),
                    listOf((code[at] as TwoRegisterInstruction).registerA, (code[at] as TwoRegisterInstruction).registerB))
            }
            assertEquals("$where: $name copies its own object", Opcode.MOVE_OBJECT_FROM16, code[0].opcode)
            assertEquals("$where: $name copies p0 into v0", listOf(0, self), listOf((code[0] as TwoRegisterInstruction).registerA, (code[0] as TwoRegisterInstruction).registerB))
            read(1, 1, 0, handler.row.toString())
            read(2, 0, 0, handler.activity.toString())
            read(3, 2, 1, handler.text.toString())
            read(4, 1, 1, handler.action.toString())
            assertEquals("$where: $name asks the extension", BUTTON_PICKED, code[5].reference().toString())
            assertEquals("$where: $name with the activity, action and text", listOf(0, 1, 2), code[5].arguments())
            assertEquals("$where: $name tests the answer", Opcode.IF_EQZ, code[7].opcode)
            if (handler.unit == null) {
                assertEquals("$where: $name returns on a yes", Opcode.RETURN_VOID, code[8].opcode)
            } else {
                assertEquals("$where: $name returns what it always does on a yes", handler.unit.toString(), code[8].reference().toString())
                assertEquals(Opcode.RETURN_OBJECT, code[9].opcode)
                assertTrue("$where: $name returns that everywhere", stockHandlers.getValue(name).withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
                    .all { stockHandlers.getValue(name)[it.index - 1].reference().toString() == handler.unit.toString() })
            }
            // The row is read untested, so Instagram's code reads it before it can branch or return too.
            val stockRowRead = stockHandlers.getValue(name).indexOfFirst { it.reference().toString() == handler.row.toString() }
            assertTrue("$where: $name reads its row", stockRowRead >= 0)
            assertTrue("$where: $name reads its row before any branch or return", stockHandlers.getValue(name).take(stockRowRead).none {
                it is OffsetInstruction || !it.opcode.canContinue()
            })
            assertTrue("$where: $name goes on to Instagram's code on a no", guard in ControlFlow.of(method).normal[7])
            assertEquals("$where: $name's own code stays", stockHandlers.getValue(name).ops(), code.drop(guard).ops())
        }

        val extension = context.mutableClassDefBy(CHAT_BUTTON_LINKS)
        fun stub(name: String) = extension.methods.single { it.name == name && BUTTON_STUBS.any { stub -> stub.first == name && stub.second == it.parameters() } }.code()
        assertTrue("$where: text buttons", stub("textButtons").any { it.reference().toString() == site.textButtons.toString() })
        assertTrue("$where: cards", stub("cards").any { it.reference().toString() == site.cards.toString() })
        assertTrue("$where: card buttons", stub("cardButtons").any { it.reference().toString() == site.cardButtons.toString() })
        assertTrue("$where: words", stub("buttonTitle").any { it.reference().toString() == site.title.toString() })
        assertTrue("$where: link", stub("buttonUrl").any { it.reference().toString() == site.url.toString() })
        assertTrue("$where: row", stub("row").any { it.reference().toString() == site.row.toString() })
        assertTrue("$where: the words and the link are two fields", site.title != site.url)
    }

    /**
     * The fixture classes the patch reads: the classes loading the three strings it finds things
     * by, with their string pools, the classes keeping both a menu model and a thread key, the
     * classes those hand the chat's activity to, the message's class and its superclasses, and the
     * extension's class.
     */
    private fun slice(bundle: File): List<ClassDef> {
        val strings = setOf(LONG_PRESS_BUTTON, TEXT_BUTTONS_KEY, CARD_ROW_ACTION)
        val found = FixtureDex.classesLoadingAny(bundle, strings) { classDef ->
            classDef.fields.any { it.type == MESSAGE_ACTIONS } && classDef.fields.any { it.type == "Lcom/instagram/model/direct/DirectThreadKey;" }
        }
        // Some builds ask a string pool for the word, so every list reader in a method making a
        // (words, icon, text) row comes along, the message's class among them.
        val messageTypes = found.flatMap { it.methods }.filter { method ->
            method.code().any { (it.reference() as? MethodReference)?.let { call -> call.name == "<init>" && call.parameters() == rowParameters } == true }
        }.flatMap { method ->
            method.code().mapNotNull { it.reference() as? MethodReference }
                .filter { it.parameterTypes.isEmpty() && it.returnType == "Lcom/google/common/collect/ImmutableList;" && it.definingClass.startsWith("LX/") }
                .map { it.definingClass }
        }.toSet()
        val chain = mutableMapOf<String, ClassDef>()
        var wanted = messageTypes
        while (wanted.isNotEmpty()) {
            val more = FixtureDex.classes(bundle, wanted)
            chain += more
            wanted = more.values.mapNotNull { it.superclass }.filter { it.startsWith("LX/") && it !in chain }.toSet()
        }
        // The class of every void method a menu holder hands the chat's activity and more on to,
        // the menu's action dispatcher among them.
        val handedTo = found.filter { holder -> holder.fields.any { it.type == MESSAGE_ACTIONS } }.flatMap { it.methods }
            .flatMap { method -> method.code().mapNotNull { it.reference() as? MethodReference } }
            .filter { it.returnType == "V" && it.parameters().size > 2 && "Landroid/app/Activity;" in it.parameters() && it.definingClass.startsWith("LX/") }
            .mapTo(HashSet()) { it.definingClass }
        chain += FixtureDex.classes(bundle, handedTo - chain.keys)
        val types = found.mapTo(HashSet()) { it.type }
        return FixtureDex.withStringPools(bundle, found + chain.values.filter { it.type !in types }) + ExtensionDex.classDef(CHAT_BUTTON_LINKS)
    }

    private val rowParameters = listOf("Ljava/lang/String;", "Ljava/lang/Integer;", "Ljava/lang/String;")

    /** Whether this calls a void method taking the chat's activity and a row's [action]. */
    private fun Instruction.handsOnAction(action: String): Boolean {
        val call = reference() as? MethodReference ?: return false
        return call.returnType == "V" && action in call.parameters() && "Landroid/app/Activity;" in call.parameters()
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()
    private fun Method.parameters(): List<String> = parameterTypes.map(Any::toString)
    private fun MethodReference.parameters(): List<String> = parameterTypes.map(Any::toString)
    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
    private fun Instruction.arguments(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }
}

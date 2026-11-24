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
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Copy links from chat buttons (#852): on every declared build, the maker of a held message's menu
 * model hands its card row list and the message to the extension in place of the list's test, the
 * chat's dispatcher for the menu asks the extension first, and the stubs read the message's buttons
 * the way the model's own code does. The rest of both methods is Instagram's.
 */
class ChatButtonLinksHookTest {
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
        assertTrue("$where: the dispatcher keeps the activity", site.activity.definingClass == site.dispatcherType)
        assertTrue("$where: the dispatcher takes (action, text)", site.dispatcherParameters[1] == "Ljava/lang/String;")
        val stockFlow = ControlFlow.of(stockBuilder)
        val intoTest = stockFlow.normal.indices.filter { site.hookAt in stockFlow.normal[it] }.map { if (it < site.hookAt) it else it + 2 }

        context.applyChatButtonLinks(site)

        val builder = context.mutableClassDefBy(site.builderType).methods.single { it.name == site.builderName && it.parameters() == site.builderParameters }
        val code = builder.code()
        assertEquals("$where: grew by the answer and the test", stock.size + 2, code.size)
        assertEquals("$where: Instagram's code up to the test stays", stock.take(site.hookAt).map { it.opcode }, code.take(site.hookAt).map { it.opcode })
        val ask = code[site.hookAt]
        assertEquals("$where: asks the extension in the test's place", BUTTON_ROWS, ask.reference().toString())
        assertEquals("$where: with the list and the message", listOf(site.list, site.message), ask.arguments())
        assertEquals("$where: keeps the answer in the list", site.list, (code[site.hookAt + 1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, code[site.hookAt + 1].opcode)
        assertEquals("$where: tests the answer", Opcode.IF_EQZ, code[site.hookAt + 2].opcode)
        assertEquals(site.list, (code[site.hookAt + 2] as OneRegisterInstruction).registerA)
        val flow = ControlFlow.of(builder)
        assertEquals("$where: no list goes where none went", site.skipTo + 2, flow.normal[site.hookAt + 2].first())
        assertEquals("$where: the rest is Instagram's", stock.drop(site.hookAt + 1).map { it.opcode }, code.drop(site.hookAt + 3).map { it.opcode })
        for (at in intoTest) assertTrue("$where: instruction $at still goes to the test, now the ask", site.hookAt in flow.normal[at])

        val dispatcher = context.mutableClassDefBy(site.dispatcherType).methods.single { it.name == site.dispatcherName && it.parameters() == site.dispatcherParameters }
        val first = dispatcher.code()
        assertEquals("$where: the dispatcher grew by the guard", stockStart.size + 8, first.size)
        assertEquals("$where: reads the activity", site.activity.toString(), first[1].reference().toString())
        assertEquals("$where: asks the extension first", BUTTON_PICKED, first[4].reference().toString())
        assertEquals("$where: returns on a yes", Opcode.RETURN_VOID, first[7].opcode)
        assertEquals("$where: goes on to Instagram's code on a no", 8, ControlFlow.of(dispatcher).normal[6].first())
        assertEquals("$where: the dispatcher's own code stays", stockStart.map { it.opcode }, first.drop(8).map { it.opcode })

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
     * message's class and its superclasses, and the extension's class.
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
        val types = found.mapTo(HashSet()) { it.type }
        return FixtureDex.withStringPools(bundle, found + chain.values.filter { it.type !in types }) + ExtensionDex.classDef(CHAT_BUTTON_LINKS)
    }

    private val rowParameters = listOf("Ljava/lang/String;", "Ljava/lang/Integer;", "Ljava/lang/String;")
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

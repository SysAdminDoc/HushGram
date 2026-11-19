/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.profile

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
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
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Long press a profile picture to view it (#737): each call giving the profile header's picture, or
 * the frame around it, its long press listener goes to ProfilePictureHold.hold on the same registers,
 * in the header's bind and in the Compose avatar binder it calls, and nothing else in either moves.
 */
class ProfilePictureHoldHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(PICTURE_HOLD).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$PICTURE_HOLD->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$HOLD_HOOK is not in the extension: $declared", HOLD_HOOK in declared)
    }

    @Test
    fun aBuildWithoutTheHeaderBindLeavesTheRestOfThePatchIn() {
        assertNull(PatchContexts.of(listOf(ExtensionDex.classDef(PICTURE_HOLD))).findProfilePictureHold())
    }

    @Test
    fun anExtensionWithoutTheHookFailsThePatch() {
        val missing = assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).findProfilePictureHold() }
        assertTrue(missing.message, missing.message!!.contains("the extension has no $PICTURE_HOLD"))
    }

    /** With the Compose binder missing, the legacy header keeps the hook. */
    @Test
    fun aBuildWithoutTheComposeBinderKeepsTheLegacyHeaders() {
        val bundle = Fixtures.files { it.extension == "apks" }.first()
        val context = PatchContexts.of(anchorClasses(bundle) + ExtensionDex.classDef(PICTURE_HOLD))
        val found = context.findProfilePictureHold()!!
        assertEquals("only the header bind", 1, found.size)
        assertEquals("the header bind, which takes a context first", "Landroid/content/Context;", found.single().parameters.first())
    }

    @Test
    fun eachDeclaredBuildHooksBothBinders() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                hooks(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildHooksBothBindersToo() {
        for (base in Fixtures.otherBuilds()) hooks(base, base.parentFile.name)
    }

    @Test
    fun theFlipHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(PICTURE_HOLD).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$PICTURE_HOLD->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$FLIP_HOOK is not in the extension: $declared", FLIP_HOOK in declared)
    }

    @Test
    fun aBuildWithoutTheCoinFlipPictureLeavesTheRestOfThePatchIn() {
        assertNull(PatchContexts.of(listOf(ExtensionDex.classDef(PICTURE_HOLD))).findCoinFlipHold())
    }

    @Test
    fun anExtensionWithoutTheFlipHookFailsThePatch() {
        val missing = assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).findCoinFlipHold() }
        assertTrue(missing.message, missing.message!!.contains("the extension has no"))
    }

    /** #758: the picture that turns between the photo and the avatar opens Instagram's card from its gesture listener. */
    @Test
    fun eachDeclaredBuildHooksTheCoinFlipPicture() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                flips(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildHooksTheCoinFlipPictureToo() {
        for (base in Fixtures.otherBuilds()) flips(base, base.parentFile.name)
    }

    @Test
    fun theCardCallIsFoundOnTheStockShape() {
        val site = longPress().coinFlipSite(CONTROLLER, PICTURE) ?: throw AssertionError("the stock shape wasn't found")
        assertEquals(listOf(LISTENER, 1, 1, 0, PICTURE), listOf(site.owner, site.read, site.controller, site.card, site.picture))
    }

    @Test
    fun aCardRegisterReadAfterTheCallIsLeftAlone() {
        assertNull(longPress(after = "invoke-static { v0 }, Lfixture/Use;->read(Ljava/lang/Object;)V").coinFlipSite(CONTROLLER, PICTURE))
    }

    @Test
    fun aCardWhoseAnswerIsTakenIsLeftAlone() {
        assertNull(longPress(after = "move-result-object v3").coinFlipSite(CONTROLLER, PICTURE))
    }

    @Test
    fun aCardReadOffItsOwnRegisterIsLeftAlone() {
        assertNull(longPress(read = "iget-object v0, v0, $CONTROLLER->A0I:$FUNCTION0_TYPE", controller = 0).coinFlipSite(CONTROLLER, PICTURE))
    }

    @Test
    fun aCardReadSomethingJumpsOntoIsLeftAlone() {
        assertNull(longPress(jump = true).coinFlipSite(CONTROLLER, PICTURE))
    }

    @Test
    fun aCardFromAnotherClassIsLeftAlone() {
        assertNull(longPress().coinFlipSite("Lfixture/Other;", PICTURE))
    }

    /**
     * The listener's onLongPress as 456 has it: the controller off the listener, the card off the
     * controller, the card's call, the controller's animation.
     */
    private fun longPress(
        read: String = "iget-object v0, v1, $CONTROLLER->A0I:$FUNCTION0_TYPE",
        controller: Int = 1,
        after: String = "",
        jump: Boolean = false,
    ): Method {
        val method = MutableMethod(ImmutableMethod(LISTENER, "onLongPress",
            listOf(ImmutableMethodParameter(MOTION_EVENT, null, null)), "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(4, emptyList(), null, null)))
        method.addInstructionsWithLabels(0, """
            iget-object v$controller, v2, $LISTENER->A00:$CONTROLLER
            ${if (jump) "goto :read" else "nop"}
            :read
            $read
            invoke-interface { v0 }, $OPEN_CARD
            $after
            invoke-static { v1 }, $CONTROLLER->A03($CONTROLLER)V
            return-void
        """.trimIndent())
        // Drop the filler so the read sits at 1 when nothing jumps.
        if (!jump) method.removeInstruction(1)
        return method
    }

    /**
     * Finds the card's call on its own, as the one onLongPress(MotionEvent) in the build calling
     * Function0.invoke on a card read off a class holding the coin-flip picture, then checks the
     * patch found that call and hooked it with nothing else in the method moving.
     */
    private fun flips(bundle: File, label: String) {
        val calls = FixtureDex.methodsWhere(bundle, { true }) { method ->
            method.name == "onLongPress" && method.returnType == "V" && method.parameterTypes.map(Any::toString) == listOf(MOTION_EVENT) &&
                method.code().any { it.referenceText() == OPEN_CARD }
        }
        val owners = calls.map { it.code() }.flatMap { code ->
            code.indices.filter { code[it].referenceText() == OPEN_CARD && it > 0 }
                .mapNotNull { (code[it - 1].referenceObject() as? FieldReference)?.definingClass }
        }.toSet()
        val holders = FixtureDex.classes(bundle, owners).values.filter { holder -> holder.fields.any { it.type == COIN_FLIP_VIEW } }
        val holder = holders.singleOrNull() ?: throw AssertionError("$label: expected one class holding the coin-flip picture and a card, found ${holders.map { it.type }}")
        val stock = calls.filter { call ->
            val code = call.code()
            code.indices.any { code[it].referenceText() == OPEN_CARD && it > 0 && (code[it - 1].referenceObject() as? FieldReference)?.definingClass == holder.type }
        }.singleOrNull() ?: throw AssertionError("$label: expected one onLongPress opening the card")
        val stockCode = stock.code()
        val stockCall = stockCode.indices.single { stockCode[it].referenceText() == OPEN_CARD }

        // What the patch reads: the classes creating a GestureDetector, what they create, and the interfaces of those.
        val creators = FixtureDex.classesCreating(bundle, setOf(GESTURE_DETECTOR))
        val created = creators.flatMap { it.methods }.flatMap { it.code() }.filter { it.opcode == Opcode.NEW_INSTANCE }
            .mapNotNull { (it.referenceObject() as? TypeReference)?.type }.toSet()
        val createdClasses = FixtureDex.classes(bundle, created).values
        val interfaces = FixtureDex.classes(bundle, createdClasses.flatMap { it.interfaces }.toSet()).values
        val context = PatchContexts.of((creators + createdClasses + interfaces).distinctBy { it.type } + ExtensionDex.classDef(PICTURE_HOLD))

        val found = context.findCoinFlipHold() ?: throw AssertionError("$label: the patch found no coin-flip long press")
        val where = "$label: ${found.owner}->onLongPress"
        assertEquals("$where: the listener", stock.definingClass, found.owner)
        assertEquals("$where: the card's read, right before its call", stockCall - 1, found.read)
        assertEquals("$where: the picture", COIN_FLIP_VIEW, found.picture.substringAfter(":"))
        assertEquals("$where: the picture is the controller's", holder.type, found.picture.substringBefore("->"))
        val read = stockCode[found.read] as TwoRegisterInstruction
        assertEquals("$where: the card's register", (stockCode[stockCall] as FiveRegisterInstruction).registerC, found.card)
        assertEquals("$where: the controller's register", read.registerB, found.controller)

        val method = context.mutableClassDefBy(found.owner).methods.single { it.name == "onLongPress" && it.parameterTypes.map(Any::toString) == listOf(MOTION_EVENT) }
        val path = NeutralNativePath(method)
        context.applyCoinFlipHold(found)

        val code = method.code()
        val at = found.read
        val picture = code[at] as TwoRegisterInstruction
        assertEquals("$where: the picture read first", Opcode.IGET_OBJECT, code[at].opcode)
        assertEquals("$where: the picture into the card's register, off the controller", listOf(found.card, found.controller), listOf(picture.registerA, picture.registerB))
        assertEquals("$where: the picture field", found.picture, code[at].referenceText())
        assertEquals("$where: then the hook", FLIP_HOOK, code[at + 1].referenceText())
        assertTrue("$where: a static call", code[at + 1].opcode == Opcode.INVOKE_STATIC_RANGE || code[at + 1].opcode == Opcode.INVOKE_STATIC)
        assertEquals("$where: on the picture", listOf(found.card), code[at + 1].arguments())
        assertEquals("$where: its answer", Opcode.MOVE_RESULT, code[at + 2].opcode)
        assertEquals("$where: its answer in the card's register", found.card, (code[at + 2] as OneRegisterInstruction).registerA)
        assertEquals("$where: a yes skips the card", Opcode.IF_NEZ, code[at + 3].opcode)
        assertEquals("$where: on the answer", found.card, (code[at + 3] as OneRegisterInstruction).registerA)
        assertEquals("$where: Instagram's card read is next", stockCode[found.read].referenceText(), code[at + 4].referenceText())
        assertEquals("$where: then its call", OPEN_CARD, code[at + 5].referenceText())
        assertEquals("$where: a yes goes to the instruction after the card's call", at + 6, ControlFlow.of(method).normal[at + 3].first())
        path.assertPreserved(where, method, setOf(at, at + 1, at + 2, at + 3))
    }

    private fun Instruction.referenceObject() = (this as? ReferenceInstruction)?.reference

    private fun anchorClasses(bundle: File): List<ClassDef> =
        (FixtureDex.classesLoading(bundle, LEGACY_AVATAR) + FixtureDex.classesLoading(bundle, COMPOSE_AVATAR)).distinctBy { it.type }

    private fun hooks(bundle: File, label: String) {
        val anchors = anchorClasses(bundle)
        val byType = anchors.associateBy { it.type }
        val binds = anchors.flatMap { it.methods }.filter { method ->
            method.code().any { it.referenceText() == SET_LONG_CLICK } &&
                FixtureDex.loads(method, LEGACY_AVATAR, byType) && FixtureDex.loads(method, COMPOSE_AVATAR, byType)
        }
        val bind = binds.singleOrNull() ?: throw AssertionError("$label: expected one header bind, found ${binds.map { it.name }}")
        // Every class the bind calls with a Fragment first, which the Compose binder is one of.
        val called = bind.code().mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .filter { it.parameterTypes.firstOrNull()?.toString() == FRAGMENT_TYPE }.map { it.definingClass }.toSet()
        val context = PatchContexts.of((anchors + FixtureDex.classes(bundle, called).values).distinctBy { it.type } + ExtensionDex.classDef(PICTURE_HOLD))

        val found = context.findProfilePictureHold() ?: throw AssertionError("$label: the patch found no picture listener")
        assertEquals("$label: the header bind and the Compose binder", 2, found.size)
        val (legacy, compose) = found
        assertEquals("$label: the header bind first", "${bind.definingClass}->${bind.name}", "${legacy.owner}->${legacy.name}")
        assertTrue("$label: the Compose binder is one the bind calls", compose.owner in called)

        // The rules in scripts/injected-mutation-contracts.txt pick these two methods alone.
        assertTrue("$label: the bind is static", AccessFlags.STATIC.isSet(bind.accessFlags))
        assertTrue("$label: the bind's shape ${legacy.parameters}", shaped(LEGACY_SHAPE, legacy.parameters, legacy.returnType))
        val composeRule = FixtureDex.methodsWhere(bundle, { true }) { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && method.parameterTypes.size == 5 &&
                method.parameterTypes.first().toString() == FRAGMENT_TYPE &&
                shaped(COMPOSE_SHAPE, method.parameterTypes.map { it.toString() }, method.returnType) &&
                COMPOSE_NUMBERS.all { number -> method.code().any { it.wideNumber() == number } }
        }.map { "${it.definingClass}->${it.name}" }
        assertEquals("$label: the Compose binder's rule", listOf("${compose.owner}->${compose.name}"), composeRule)

        for (site in found) {
            val where = "$label: ${site.owner}->${site.name}"
            val before = context.method(site)
            val stock = before.code()
            val calls = stock.indices.filter { stock[it].referenceText() == SET_LONG_CLICK }
            assertEquals("$where: both of its long press listener calls are the picture's", calls, site.sites)
            assertEquals("$where: two sites", 2, site.sites.size)
            val registers = site.sites.map { stock[it].arguments() }
            val path = NeutralNativePath(before)

            context.applyProfilePictureHold(listOf(site))

            val method = context.method(site)
            val code = method.code()
            assertTrue("$where: no long press listener is set around the hook", code.none { it.referenceText() == SET_LONG_CLICK })
            assertEquals("$where: the hook in each one's place", site.sites, code.indices.filter { code[it].referenceText() == HOLD_HOOK })
            for ((at, was) in site.sites.zip(registers)) {
                assertTrue("$where: a static call at $at", code[at].opcode == Opcode.INVOKE_STATIC || code[at].opcode == Opcode.INVOKE_STATIC_RANGE)
                assertEquals("$where: the view and the listener at $at", was, code[at].arguments())
            }
            path.assertPreserved(where, method, emptySet(), site.sites.toSet())
        }
    }

    private fun shaped(shape: String, parameters: List<String>, returnType: String): Boolean {
        val pattern = shape.split("*").joinToString("[^;]*") { Regex.escape(it) }
        return Regex(pattern).matches("(${parameters.joinToString("")})$returnType")
    }

    private fun BytecodePatchContext.method(site: PictureHoldMethod): MutableMethod =
        mutableClassDefBy(site.owner).methods.single {
            it.name == site.name && it.returnType == site.returnType && it.parameterTypes.map(CharSequence::toString) == site.parameters
        }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.wideNumber(): Long? =
        if (this is WideLiteralInstruction && opcode.setsWideRegister()) wideLiteral else null

    private fun Instruction.arguments(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }

    private companion object {
        const val FRAGMENT_TYPE = "Landroidx/fragment/app/Fragment;"
        // As the contract rules write them.
        const val LEGACY_SHAPE = "(Landroid/content/Context;Landroid/view/View;LX/*;Lcom/instagram/common/session/UserSession;" +
            "LX/*;LX/*;LX/*;LX/*;LX/*;LX/*;Ljava/lang/Integer;Ljava/lang/Integer;Ljava/lang/Object;Ljava/lang/Object;Ljava/util/List;IZ)V"
        const val COMPOSE_SHAPE = "(Landroidx/fragment/app/Fragment;LX/*;LX/*;LX/*;LX/*;)V"
        val COMPOSE_NUMBERS = listOf(0x8109600006361aL, 0x810f47000a5516L)
        const val LISTENER = "Lfixture/Listener;"
        const val CONTROLLER = "Lfixture/Controller;"
        const val FUNCTION0_TYPE = "Lkotlin/jvm/functions/Function0;"
        const val PICTURE = "$CONTROLLER->A0F:$COIN_FLIP_VIEW"
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.developeroptions

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.originalName
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Open Whitehat settings (#1): HushGram opens Instagram's own Whitehat screen through the same
 * checked navigation as the MetaConfig editor, and only while that screen still has the native
 * switch with its 24-hour lifetime and the check that ends it. Anything else leaves the bridge as
 * it was and fails the patch.
 */
class WhitehatSettingsTest {
    private val screen = "Lfixture/Whitehat;"
    private val base = "Lfixture/IgFragment;"
    private val handler = "Lfixture/Switch;"
    private val store = "Lfixture/Store;"
    private val fragment = "Landroidx/fragment/app/Fragment;"
    private val editor = OverrideEditor(
        getter = "Lcom/instagram/base/activity/IgFragmentActivity;->session()Lfixture/Session;",
        factory = "Lfixture/Native;->factory(Landroidx/fragment/app/FragmentActivity;Lfixture/Session;)Lfixture/Navigation;",
        fragment = "Lfixture/Edit;",
        present = "Lfixture/Native;->present(Landroidx/fragment/app/Fragment;Lfixture/Navigation;)V",
    )
    private val public = AccessFlags.PUBLIC.value
    private val static = public or AccessFlags.STATIC.value

    @Test fun theScreenOpensThroughTheCheckedNativeNavigation() {
        val patch = PatchContexts.of(classes())
        val found = patch.findWhitehatScreen()
        assertEquals(screen, found)
        putStubs(patch, found)
        assertBridge(patch, found)
    }

    @Test fun anythingButTheNativeDaySwitchLeavesTheBridgeAlone() {
        val cases = mapOf(
            "no day" to classes(day = "0x1"),
            "no restart toast" to classes(toast = "Saved"),
            "two switches" to classes() + switchClass("Lfixture/OtherSwitch;"),
            "unmarked screen" to classes(name = "OtherSettingsFragment"),
            "two screens" to classes() + screenClass("Lfixture/OtherWhitehat;"),
            "private constructor" to classes(constructor = AccessFlags.PRIVATE.value),
            "abstract screen" to classes(screenFlags = public or AccessFlags.ABSTRACT.value),
            "switch built elsewhere" to classes(buildsInView = false),
            "not a fragment" to classes(superclass = "Ljava/lang/Object;"),
            "no expiry check" to classes(expiry = false),
            "two expiry checks" to classes() + storeClass("Lfixture/OtherStore;"),
            "expiry without a clock" to classes(clock = false),
        )
        for ((case, classes) in cases) {
            val patch = PatchContexts.of(classes)
            val failure = runCatching { putStubs(patch, patch.findWhitehatScreen()) }.exceptionOrNull()
            assertTrue("$case: ${failure?.message}", failure?.message?.startsWith("Open developer options: ") == true)
            assertTrue(case, patch.bridge().implementation!!.instructions.none { it.opcode == Opcode.NEW_INSTANCE })
        }
        val noBridge = PatchContexts.of(classes().filter { it.type != OVERRIDE_BRIDGE })
        val failure = runCatching { noBridge.prepareWhitehatScreen(editor, noBridge.findWhitehatScreen()) }.exceptionOrNull()
        assertTrue("${failure?.message}", failure?.message?.startsWith("Open developer options: ") == true)
    }

    /** In each declared build: Instagram's Whitehat screen, its day switch and its expiry check. */
    @Test fun everyDeclaredBuildKeepsTheNativeWhitehatScreen() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
            val kept = FixtureDex.classesHolding(bundle, USER_CERTS_TTL).toMutableList()
            FixtureDex.forEach(bundle) { dex ->
                dex.classes.filter { it.originalName() == WHITEHAT_SCREEN }.mapTo(kept) { ImmutableClassDef.of(it) }
            }
            var next = kept.filter { it.originalName() == WHITEHAT_SCREEN }.mapNotNull { it.superclass }.toSet()
            while (next.isNotEmpty() && fragment !in next) {
                val supers = FixtureDex.classes(bundle, next).values
                kept += supers
                next = supers.mapNotNull { it.superclass }.toSet()
            }
            val patch = PatchContexts.of(kept.distinctBy { it.type } + bridgeClass())
            val found = patch.findWhitehatScreen()
            assertEquals(WHITEHAT_SCREEN, patch.classDefBy(found).originalName())
            putStubs(patch, found)
            assertBridge(patch, found)
            checked += version
        }
        assertEquals("declared build has no fixture", versions, checked)
    }

    private fun putStubs(patch: BytecodePatchContext, found: String) = patch.putStubs(patch.prepareWhitehatScreen(editor, found))

    private fun assertBridge(patch: BytecodePatchContext, found: String) {
        val code = patch.bridge().implementation!!.instructions.toList()
        val references = code.mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }
        assertEquals(3, patch.bridge().implementation!!.registerCount)
        assertEquals(listOf("Lcom/instagram/mainactivity/InstagramMainActivity;", "Lcom/instagram/modal/ModalActivity;",
            "Lcom/instagram/base/activity/IgFragmentActivity;", editor.getter, "Lcom/instagram/common/session/UserSession;"),
            references.take(5))
        assertEquals(listOf(editor.factory, found, "$found-><init>()V", editor.present), references.drop(5))
        assertTrue("the screen takes no arguments", references.none { it.contains("setArguments") })
        assertEquals(2, code.count { it.opcode == Opcode.RETURN })
        assertEquals("unavailable return", Opcode.CONST_4, code[code.size - 2].opcode)
    }

    private fun BytecodePatchContext.bridge() = classDefBy(OVERRIDE_BRIDGE).methods.single { it.name == "openWhitehatNative" }

    private fun classes(day: String = "0x5265c00", toast: String = USER_CERTS_RESTART, name: String = WHITEHAT_SCREEN,
                        constructor: Int = public, screenFlags: Int = public, buildsInView: Boolean = true,
                        superclass: String = fragment, expiry: Boolean = true, clock: Boolean = true): List<ClassDef> =
        listOfNotNull(
            switchClass(handler, day, toast),
            screenClass(screen, name, constructor, screenFlags, buildsInView),
            clazz(base, supertype = superclass),
            if (expiry) storeClass(store, clock) else null,
            bridgeClass(),
        )

    private fun switchClass(type: String, day: String = "0x5265c00", toast: String = USER_CERTS_RESTART) = clazz(type, methods = listOf(
        method(type, "<init>", emptyList(), "V", 1, public, "return-void"),
        method(type, "onCheckedChanged", listOf("Landroid/widget/CompoundButton;", "Z"), "V", 6, public, """
            const v0, $day
            const-string v1, "$USER_CERTS"
            const-string v2, "$USER_CERTS_TTL"
            const-string v3, "$toast"
            return-void
        """),
    ))

    private fun screenClass(type: String, name: String = WHITEHAT_SCREEN, constructor: Int = public, flags: Int = public,
                            buildsInView: Boolean = true): ClassDef {
        val build = """
            new-instance v0, $handler
            invoke-direct { v0 }, $handler-><init>()V
            return-void
        """
        return clazz(type, supertype = base, flags = flags, fields = listOf(ImmutableField(type, "__redex_internal_original_name",
            "Ljava/lang/String;", static, ImmutableStringEncodedValue(name), null, null)), methods = listOf(
            method(type, "<init>", emptyList(), "V", 1, constructor, "return-void"),
            method(type, "onViewCreated", listOf("Landroid/view/View;", "Landroid/os/Bundle;"), "V", 4, public,
                if (buildsInView) build else "return-void"),
            method(type, "later", emptyList(), "V", 2, public, if (buildsInView) "return-void" else build),
        ))
    }

    private fun storeClass(type: String, clock: Boolean = true) = clazz(type, methods = listOf(
        method(type, "check", emptyList(), "Z", 5, public, """
            const-string v0, "$USER_CERTS_TTL"
            const-string v1, "$USER_CERTS"
            ${if (clock) "invoke-static { }, Ljava/lang/System;->currentTimeMillis()J" else "nop"}
            const/4 v0, 0x0
            return v0
        """),
    ))

    private fun bridgeClass() = clazz(OVERRIDE_BRIDGE, methods = listOf(method(OVERRIDE_BRIDGE, "openWhitehatNative",
        listOf("Ljava/lang/Object;"), "I", 2, static, "const/4 v0, 0x0\nreturn v0")))

    private fun clazz(type: String, supertype: String = "Ljava/lang/Object;", flags: Int = public,
                      fields: List<ImmutableField> = emptyList(), methods: List<Method> = emptyList()): ClassDef =
        ImmutableClassDef(type, flags, supertype, null, null, null, fields, methods)

    private fun method(owner: String, name: String, parameters: List<String>, result: String, registers: Int, flags: Int,
                       body: String): Method = MutableMethod(ImmutableMethod(owner, name,
        parameters.map { ImmutableMethodParameter(it, null, null) }, result, flags, null, null,
        ImmutableMethodImplementation(registers, emptyList(), null, null))).apply {
        addInstructionsWithLabels(0, body.trimIndent())
    }.let(ImmutableMethod::of)
}

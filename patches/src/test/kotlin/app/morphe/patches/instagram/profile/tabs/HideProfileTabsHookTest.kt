/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.tabs

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HideProfileTabsHookTest {
    private val owner = "Lfixture/TabList;"
    private val user = "Lcom/instagram/user/model/User;"
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val string = "Ljava/lang/String;"
    private val readers = listOf("profileId", "viewerId")

    /** The hook and the two readers the patch writes are in the ProfileTabs the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(PROFILE_TABS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$TABS_HOOK is not in the extension: $declared", TABS_HOOK.substringAfter("->") in declared)
        for (reader in readers) {
            assertTrue("$reader is not in the extension: $declared", "$reader(Ljava/lang/Object;)$string" in declared)
        }
    }

    /**
     * The builder, the user and the list go through the hook right before the one return, the rest
     * of the method stays put, and the readers learn where the two IDs are.
     */
    @Test
    fun theListIsHandedToTheHookJustBeforeItReturns() {
        val context = PatchContexts.of(models() + builder() + ExtensionDex.classDef(PROFILE_TABS))
        val before = context.mutableClassDefBy(owner).methods.single { it.name == "build" }.code().map { it.shape() }

        context.hideProfileTabs(context.findTabList())

        val after = context.mutableClassDefBy(owner).methods.single { it.name == "build" }.code()
        assertEquals(before.size + 2, after.size)
        val at = after.indexOfFirst { it.reference() == TABS_HOOK }
        val call = after[at] as FiveRegisterInstruction
        assertEquals(Opcode.INVOKE_STATIC, after[at].opcode)
        // Two locals, so this is v2 and the user v3, and the list is in v0.
        assertEquals("builder, user, list", listOf(2, 3, 0), listOf(call.registerC, call.registerD, call.registerE))
        assertEquals(3, call.registerCount)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, after[at + 1].opcode)
        assertEquals(0, (after[at + 1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.RETURN_OBJECT, after[at + 2].opcode)
        assertEquals(1, after.count { it.reference() == TABS_HOOK })
        assertEquals("everything else stays as it was", before, after.filterIndexed { i, _ -> i != at && i != at + 1 }.map { it.shape() })

        assertReadersWritten(context, "$user->getId()$string", "$owner->session:$session")
    }

    /** A build the patch can't read fails at patch time, saying what it found, with nothing changed. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            listOf(builder(keys = PROFILE_TAB_KEYS.dropLast(1))) to "expected one builder of a profile's tab keys, found 0",
            listOf(builder(), builder("Lfixture/Other;")) to "expected one builder of a profile's tab keys, found 2",
            listOf(builder(returns = "Ljava/lang/Object;")) to "expected one builder of a profile's tab keys, found 0",
            listOf(builder(secondReturn = true)) to "returns in 2 places",
            listOf(builder(jumpOntoReturn = true)) to "has a jump onto its return",
            listOf(builder(static = true)) to "is static, so it keeps no signed-in session",
            listOf(builder(overwrite = "p0")) to "writes over this",
            listOf(builder(overwrite = "p1")) to "writes over parameter 0",
            listOf(builder(registers = 20)) to "past v15",
            listOf(builder(sessions = 0)) to "to keep one $session, found 0",
            listOf(builder(sessions = 2)) to "to keep one $session, found 2",
            listOf(builder(publicSession = false)) to "isn't public, so the extension can't read the session",
        ).map { (classes, expected) -> (models() + classes) to expected } + listOf(
            (models(sessionId = false) + builder()) to "$session has no public getUserId()",
            (models(hashesId = false) + builder()) to "hashCode() to ask one getter of its ID, found []",
            listOf(builder()) to "isn't in this build",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.findTabList() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
        }
    }

    /** An extension without the readers fails before the builder is touched. */
    @Test
    fun anExtensionWithoutTheReadersFailsBeforeAnythingChanges() {
        val bare = ImmutableClassDef(PROFILE_TABS, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, null)
        val context = PatchContexts.of(models() + builder() + bare)
        val site = context.findTabList()
        val before = context.mutableClassDefBy(owner).methods.single { it.name == "build" }.code().map { it.shape() }
        val failure = assertThrows(PatchException::class.java) { context.hideProfileTabs(site) }
        assertTrue(failure.message, failure.message!!.contains("has no static $string profileId"))
        assertEquals(before, context.mutableClassDefBy(owner).methods.single { it.name == "build" }.code().map { it.shape() })
    }

    /** On the declared build of the app, the builder is found once and the hook lands before its one return. */
    @Test
    fun everyDeclaredBuildTakesTheHookBeforeTheTabListReturns() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                takesTheHook(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** Each other build of the declared version (#77, #95) takes the hook the same way. */
    @Test
    fun eachOtherBuildTakesTheHookToo() {
        val builds = Fixtures.otherBuilds()
        assertEquals("the other builds", Fixtures.OTHER_BUILDS, builds.size)
        for (base in builds) takesTheHook(base, base.parentFile.name)
    }

    private fun takesTheHook(bundle: File, label: String) {
        val holders = FixtureDex.classesLoading(bundle, PROFILE_TAB_KEYS.last())
        val models = FixtureDex.classes(bundle, setOf(user, session)).values
        assertEquals("$label: the user and the session", 2, models.size)
        val classes = (FixtureDex.withStringPools(bundle, holders) + models).distinctBy { it.type }
        val context = PatchContexts.of(classes + ExtensionDex.classDef(PROFILE_TABS))
        val site = context.findTabList()
        fun MutableMethod.isSite() = name == site.name && parameterTypes.map(Any::toString) == site.parameters
        val method = context.mutableClassDefBy(site.type).methods.single { it.isSite() }
        val locals = method.implementation!!.registerCount - 1 - method.parameterTypes.size
        assertEquals("$label: this", locals, site.self)
        assertEquals("$label: the user", locals + 1, site.user)
        val before = method.code().map { it.shape() }

        context.hideProfileTabs(site)

        val after = context.mutableClassDefBy(site.type).methods.single { it.isSite() }.code()
        val at = after.indexOfFirst { it.reference() == TABS_HOOK }
        val call = after[at] as FiveRegisterInstruction
        assertEquals("$label: a call naming each register", Opcode.INVOKE_STATIC, after[at].opcode)
        assertEquals("$label: builder, user, list", listOf(site.self, site.user, site.register), listOf(call.registerC, call.registerD, call.registerE))
        assertEquals("$label: the hook is in front of the return", Opcode.RETURN_OBJECT, after[at + 2].opcode)
        assertEquals("$label: one return", 1, after.count { it.opcode == Opcode.RETURN_OBJECT })
        assertEquals("$label: the rest is untouched", before, after.filterIndexed { i, _ -> i != at && i != at + 1 }.map { it.shape() })
        assertTrue("$label: the session is the builder's own", site.session.startsWith("${site.type}->"))
        assertReadersWritten(context, site.userId, site.session)
    }

    /** The readers ask the user for [userId] and read the session through [sessionField]. */
    private fun assertReadersWritten(context: BytecodePatchContext, userId: String, sessionField: String) {
        val extension = context.mutableClassDefBy(PROFILE_TABS)
        fun reader(name: String) = extension.methods.single { it.name == name && it.parameterTypes.size == 1 }.code()
        val profile = reader("profileId")
        assertEquals(Opcode.CHECK_CAST, profile.first().opcode)
        assertEquals(user, profile.first().reference())
        assertTrue("profileId asks $userId: ${profile.map { it.shape() }}", profile.any { it.opcode == Opcode.INVOKE_VIRTUAL && it.reference() == userId })
        val viewer = reader("viewerId")
        assertTrue("viewerId reads $sessionField: ${viewer.map { it.shape() }}", viewer.any { it.opcode == Opcode.IGET_OBJECT && it.reference() == sessionField })
        assertTrue("viewerId asks the session's ID", viewer.any { it.reference() == "$session->getUserId()$string" })
        // Each way out answers a String or null, never the session itself. The stub's own return
        // stays after them, out of reach.
        val returns = viewer.indices.filter { viewer[it].opcode == Opcode.RETURN_OBJECT }
        assertTrue("viewerId's ways out: ${viewer.map { it.shape() }}", returns.size >= 2)
        assertEquals(Opcode.CONST_4, viewer[returns[0] - 1].opcode)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, viewer[returns[1] - 1].opcode)
    }

    /** The user model, hashing its ID, and the session, which names its ID getter. */
    private fun models(sessionId: Boolean = true, hashesId: Boolean = true): List<ClassDef> {
        val hashCode = if (hashesId) {
            """
                invoke-virtual { p0 }, $user->getId()$string
                move-result-object v0
                invoke-virtual { v0 }, $string->hashCode()I
                move-result v0
                return v0
            """
        } else {
            """
                const/4 v0, 0x0
                return v0
            """
        }
        val userClass = classOf(
            user, emptyList(),
            listOf(
                method(user, "hashCode", emptyList(), "I", 2, hashCode),
                method(user, "getId", emptyList(), string, 2, "const/4 v0, 0x0\nreturn-object v0"),
            ),
        )
        val sessionClass = classOf(
            session, emptyList(),
            if (sessionId) listOf(method(session, "getUserId", emptyList(), string, 2, "const/4 v0, 0x0\nreturn-object v0")) else emptyList(),
        )
        return listOf(userClass, sessionClass)
    }

    private fun builder(
        type: String = owner,
        keys: List<String> = PROFILE_TAB_KEYS,
        returns: String = "Ljava/util/List;",
        secondReturn: Boolean = false,
        jumpOntoReturn: Boolean = false,
        static: Boolean = false,
        overwrite: String? = null,
        registers: Int = 4,
        sessions: Int = 1,
        publicSession: Boolean = true,
    ): ClassDef {
        val loads = keys.joinToString("\n") { "const-string v1, \"$it\"" }
        val listParameter = if (static) "p0" else "p1"
        val body = buildString {
            appendLine("new-instance v0, Ljava/util/ArrayList;")
            appendLine("invoke-direct { v0 }, Ljava/util/ArrayList;-><init>()V")
            appendLine(loads)
            if (secondReturn) {
                appendLine("if-eqz $listParameter, :early")
                appendLine("goto :done")
                appendLine(":early")
                appendLine("return-object v0")
                appendLine(":done")
            }
            if (jumpOntoReturn) {
                appendLine("if-eqz $listParameter, :done")
                appendLine("invoke-virtual { v0, v1 }, Ljava/util/AbstractCollection;->add(Ljava/lang/Object;)Z")
                appendLine(":done")
            }
            if (overwrite != null) appendLine("move-object $overwrite, v0")
            appendLine("return-object v0")
        }
        val flags = AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0)
        val build = ImmutableMethod.of(
            MutableMethod(
                ImmutableMethod(
                    type, "build", listOf(ImmutableMethodParameter(user, null, null)), returns, flags, null, null,
                    ImmutableMethodImplementation(if (static) registers - 1 else registers, emptyList(), null, null),
                ),
            ).apply { addInstructionsWithLabels(0, body) },
        )
        val fieldFlags = if (publicSession) AccessFlags.PUBLIC.value or AccessFlags.FINAL.value else AccessFlags.PRIVATE.value
        val fields = (0 until sessions).map { ImmutableField(type, "session" + (if (it == 0) "" else "$it"), session, fieldFlags, null, null, null) }
        return classOf(type, fields, listOf(build))
    }

    private fun method(type: String, name: String, parameters: List<String>, returns: String, registers: Int, body: String): Method =
        ImmutableMethod.of(
            MutableMethod(
                ImmutableMethod(
                    type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                    ImmutableMethodImplementation(registers, emptyList(), null, null),
                ),
            ).apply { addInstructionsWithLabels(0, body.trimIndent()) },
        )

    private fun classOf(type: String, fields: List<ImmutableField>, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, fields, methods)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty().filter { it.opcode != Opcode.NOP }

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.shape() = opcode to reference()
}

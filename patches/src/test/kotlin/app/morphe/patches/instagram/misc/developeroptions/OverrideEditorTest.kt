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
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.*
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class OverrideEditorTest {
    private val main = "Lcom/instagram/mainactivity/InstagramMainActivity;"
    private val modal = "Lcom/instagram/modal/ModalActivity;"
    private val base = "Lcom/instagram/base/activity/IgFragmentActivity;"
    private val user = "Lcom/instagram/common/session/UserSession;"
    private val session = "Lfixture/Session;"
    private val fragment = "Lfixture/Edit;"
    private val native = "Lfixture/Native;"
    private val navigation = "Lfixture/Navigation;"
    private val activity = "Landroidx/fragment/app/FragmentActivity;"
    private val androidFragment = "Landroidx/fragment/app/Fragment;"
    private val pool = "Lfixture/Strings;"
    private val public = AccessFlags.PUBLIC.value
    private val static = public or AccessFlags.STATIC.value

    @Test fun nativeNavigationKeepsTheOverrideArgumentsAndGuardsItsSession() {
        val patch = PatchContexts.of(classes())
        val editor = patch.findOverrideEditor()
        patch.fillOverrideEditor(editor)
        assertEditor(patch, editor)
    }

    /** A branch asking a string pool for both keys, as 450's 385611395 and 385611400 builds do, is found the same (#77). */
    @Test fun aBranchAskingAPoolForItsKeysIsFound() {
        val patch = PatchContexts.of(classes(pooledKeys = true))
        val editor = patch.findOverrideEditor()
        patch.fillOverrideEditor(editor)
        assertEditor(patch, editor)
    }

    @Test fun missingAmbiguousOrInaccessibleNativePartsLeaveTheBridgeUntouched() {
        val valid = classes()
        val cases = mapOf(
            "missing title" to classes(title = "Other screen"),
            "pooled keys without their pool" to classes(pooledKeys = true).filter { it.type != pool },
            "ambiguous branch" to valid + clazz("Lfixture/Other;", methods = listOf(branch("Lfixture/Other;"))),
            "unmarked editor" to classes(editorName = "OtherFragment"),
            "private constructor" to classes(constructorFlags = AccessFlags.PRIVATE.value),
            "private navigation" to classes(factoryFlags = AccessFlags.PRIVATE.value or AccessFlags.STATIC.value),
            "missing modal support" to classes(modalSupport = false),
            "ambiguous session" to classes(extraGetter = true),
            "missing bridge" to valid.filter { it.type != OVERRIDE_BRIDGE },
        )
        for ((case, classes) in cases) {
            val patch = PatchContexts.of(classes)
            val failure = runCatching { patch.fillOverrideEditor(patch.findOverrideEditor()) }.exceptionOrNull()
            assertTrue(case, failure?.message?.startsWith("Open developer options: ") == true)
            if (classes.any { it.type == OVERRIDE_BRIDGE }) {
                assertTrue(case, patch.bridge().implementation!!.instructions.none { it.opcode == Opcode.NEW_INSTANCE })
            }
        }
    }

    @Test fun signatureMatchesWithoutTheSameNativeObjectsRefuseBeforeMutation() {
        val show = "invoke-static { v1, v0 }, $native->present($androidFragment$navigation)V"
        val cases = mapOf<String, (String) -> String>(
            "discarded result" to { it.replace("move-result-object v0", "move-result-object v0\nconst/4 v0, 0x0") },
            "missing result" to { it.replace("move-result-object v0", "nop") },
            "wrong navigator" to { it.replace(show, show.replace("v1, v0", "v1, v2")) },
            "wrong fragment" to { it.replace(show, show.replace("v1, v0", "v2, v0")) },
            "uninitialized fragment" to { it.replace("invoke-direct { v1 }, $fragment-><init>()V", "nop") },
            "wide overwrite" to { it.replace(show, "const-wide v0, 0x0\n$show") },
            "early return" to { it.replace(show, "return-void\n$show") },
            "conditional flow" to { it.replace(show, "if-eqz v4, :show\n:show\n$show") },
            "incoming jump" to { "if-eqz v4, :show\n" + it.replace(show, ":show\n$show") },
            "incoming switch" to {
                "packed-switch v4, :choices\n" + it.replace(show, ":show\n$show") +
                    "\n:choices\n.packed-switch 0x0\n:show\n.end packed-switch"
            },
        )
        for ((case, alter) in cases) {
            val patch = PatchContexts.of(classes(alterBranch = alter))
            val failure = runCatching { patch.fillOverrideEditor(patch.findOverrideEditor()) }.exceptionOrNull()
            assertTrue(case, failure?.message?.startsWith("Open developer options: ") == true)
            assertTrue(case, patch.bridge().implementation!!.instructions.none { it.opcode == Opcode.NEW_INSTANCE })
        }
    }

    @Test fun exceptionEdgesCannotEnterAfterNativeObjectsWereCreated() {
        val show = "invoke-static { v1, v0 }, $native->present($androidFragment$navigation)V"
        val classes = classes(alterBranch = { it.replace(show, "move-exception v2\n$show") }).map { clazz ->
            if (clazz.type != native) clazz else clazz(native, methods = clazz.methods.map { method ->
                if (method.name != "branch") method else {
                    val implementation = method.implementation!!
                    val code = implementation.instructions.toList()
                    val handler = code.takeWhile { it.opcode != Opcode.MOVE_EXCEPTION }.sumOf { it.codeUnits }
                    ImmutableMethod(method.definingClass, method.name, method.parameters, method.returnType,
                        method.accessFlags, method.annotations, method.hiddenApiRestrictions,
                        ImmutableMethodImplementation(implementation.registerCount, code,
                            listOf(ImmutableTryBlock(0, code.first().codeUnits,
                                listOf(ImmutableExceptionHandler("Ljava/lang/Exception;", handler)))), implementation.debugItems))
                }
            })
        }
        assertEquals(1, classes.single { it.type == native }.methods.single { it.name == "branch" }.implementation!!.tryBlocks.size)
        val patch = PatchContexts.of(classes)
        val failure = runCatching { patch.fillOverrideEditor(patch.findOverrideEditor()) }.exceptionOrNull()
        assertTrue(failure?.message?.startsWith("Open developer options: ") == true)
        assertTrue(patch.bridge().implementation!!.instructions.none { it.opcode == Opcode.NEW_INSTANCE })
    }

    @Test fun nativeObjectAliasesCanMoveWithoutChangingTheirOrigins() {
        val show = "invoke-static { v1, v0 }, $native->present($androidFragment$navigation)V"
        val patch = PatchContexts.of(classes(alterBranch = {
            it.replace(show, """
                move-object v4, v0
                const/4 v0, 0x0
                move-object v3, v1
                const/4 v1, 0x0
                invoke-static { v3, v4 }, $native->present($androidFragment$navigation)V
            """.trimIndent())
        }))
        val editor = patch.findOverrideEditor()
        patch.fillOverrideEditor(editor)
        assertEditor(patch, editor)
    }

    @Test fun everyDeclaredHostBuildSuppliesTheDirectOverrideEditor() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
            suppliesTheDirectOverrideEditor(bundle)
            checked += version
        }
        assertEquals("declared build has no fixture", versions, checked)
    }

    /** The same in the other arm64 builds of each declared version, two of which pool both keys (#77). */
    @Test fun everyOtherBuildSuppliesTheDirectOverrideEditor() {
        for (apk in Fixtures.otherBuilds()) suppliesTheDirectOverrideEditor(apk)
    }

    private fun suppliesTheDirectOverrideEditor(bundle: File) {
        val anchors = mutableListOf<ClassDef>()
        val types = mutableSetOf(main, modal, base, user)
        FixtureDex.forEach(bundle) { dex ->
            for (clazz in dex.classes) {
                if (clazz.originalName() == "QuickExperimentEditFragment" || clazz.methods.any { method ->
                        method.implementation?.instructions?.any {
                            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == OVERRIDE_TITLE
                        } == true
                    }) {
                    anchors += ImmutableClassDef.of(clazz)
                    clazz.methods.flatMap { it.implementation?.instructions?.toList().orEmpty() }.forEach {
                        when (val reference = (it as? ReferenceInstruction)?.reference) {
                            is MethodReference -> types += reference.definingClass
                            is TypeReference -> types += reference.type
                        }
                    }
                }
            }
        }
        val nativeClasses = FixtureDex.classes(bundle, types).values + anchors
        val patch = PatchContexts.of(nativeClasses.distinctBy { it.type } + bridgeClass())
        val editor = patch.findOverrideEditor()
        patch.fillOverrideEditor(editor)
        assertEditor(patch, editor)
    }

    private fun assertEditor(patch: BytecodePatchContext, editor: OverrideEditor) {
        val code = patch.bridge().implementation!!.instructions.toList()
        val references = code.mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }
        assertEquals(6, patch.bridge().implementation!!.registerCount)
        assertEquals(listOf(main, modal, base, editor.getter, user), references.take(5))
        assertTrue(references.indexOf(user) < references.indexOf(editor.factory))
        assertTrue(references.containsAll(listOf(editor.factory, editor.present, "${editor.fragment}-><init>()V",
            "TITLE_KEY", OVERRIDE_TITLE, "IS_OVERRIDE_KEY", "$androidFragment->setArguments(Landroid/os/Bundle;)V")))
        assertEquals(2, code.count { it.opcode == Opcode.RETURN })
        assertEquals("signed-out/unsupported return", Opcode.CONST_4, code[code.size - 2].opcode)
    }

    private fun BytecodePatchContext.bridge() = classDefBy(OVERRIDE_BRIDGE).methods.single { it.name == "openOverridesNative" }

    private fun classes(title: String = OVERRIDE_TITLE, editorName: String = "QuickExperimentEditFragment",
                        constructorFlags: Int = public, factoryFlags: Int = static, modalSupport: Boolean = true,
                        extraGetter: Boolean = false, pooledKeys: Boolean = false,
                        alterBranch: (String) -> String = { it }): List<ClassDef> = listOf(
        clazz(user, supertype = session),
        clazz(main, methods = listOf(method(main, "session", emptyList(), session, 2, public, """
            const/4 v0, 0x0
            return-object v0
        """)) + if (extraGetter) listOf(method(main, "other", emptyList(), session, 2, public, "const/4 v0, 0x0\nreturn-object v0")) else emptyList()),
        clazz(modal),
        clazz(base, methods = listOf(method(base, "session", emptyList(), session, 2, public,
            (if (modalSupport) "check-cast p0, $modal\niget-object v0, p0, $modal->session:$session\n" else "const/4 v0, 0x0\n") + "return-object v0"))),
        clazz(fragment, methods = listOf(method(fragment, "<init>", emptyList(), "V", 1, constructorFlags, "return-void")),
            fields = listOf(ImmutableField(fragment, "__redex_internal_original_name", "Ljava/lang/String;", static,
                ImmutableStringEncodedValue(editorName), null, null))),
        clazz(native, methods = listOf(
            branch(native, title) { code -> alterBranch(if (pooledKeys) poolKeys(code) else code) },
            method(native, "factory", listOf(activity, session), navigation, 3, factoryFlags, "const/4 v0, 0x0\nreturn-object v0"),
            method(native, "present", listOf(androidFragment, navigation), "V", 2, static, "return-void"),
        )), bridgeClass(),
        // A Redex string pool: a packed switch from each key's number to the key, anything else null.
        clazz(pool, methods = listOf(method(pool, "A00", listOf("I"), "Ljava/lang/String;", 2, static, """
            packed-switch p0, :keys
            const/4 v0, 0x0
            return-object v0
            :title
            const-string v0, "TITLE_KEY"
            return-object v0
            :override
            const-string v0, "IS_OVERRIDE_KEY"
            return-object v0
            :keys
            .packed-switch 0x5
                :title
                :override
            .end packed-switch
        """))),
    )

    /** [code] asking the pool for both keys by number, as Redex has it, instead of loading them. */
    private fun poolKeys(code: String): String = listOf("TITLE_KEY" to 5, "IS_OVERRIDE_KEY" to 6).fold(code) { body, (key, number) ->
        body.replace("const-string v3, \"$key\"", "const/16 v3, 0x$number\ninvoke-static { v3 }, $pool->A00(I)Ljava/lang/String;\nmove-result-object v3")
    }

    private fun bridgeClass() = clazz(OVERRIDE_BRIDGE, methods = listOf(method(OVERRIDE_BRIDGE, "openOverridesNative",
        listOf("Ljava/lang/Object;"), "I", 2, static, "const/4 v0, 0x0\nreturn v0")))

    private fun branch(owner: String, title: String = OVERRIDE_TITLE, alter: (String) -> String = { it }) =
        method(owner, "branch", emptyList(), "V", 5, static, alter("""
        invoke-static { v0, v1 }, $native->factory($activity$session)$navigation
        move-result-object v0
        new-instance v1, $fragment
        invoke-direct { v1 }, $fragment-><init>()V
        new-instance v2, Landroid/os/Bundle;
        invoke-direct { v2 }, Landroid/os/Bundle;-><init>()V
        const-string v3, "TITLE_KEY"
        const-string v4, "$title"
        invoke-virtual { v2, v3, v4 }, Landroid/os/BaseBundle;->putString(Ljava/lang/String;Ljava/lang/String;)V
        const-string v3, "IS_OVERRIDE_KEY"
        const/4 v4, 0x1
        invoke-virtual { v2, v3, v4 }, Landroid/os/BaseBundle;->putBoolean(Ljava/lang/String;Z)V
        invoke-virtual { v1, v2 }, $androidFragment->setArguments(Landroid/os/Bundle;)V
        invoke-static { v1, v0 }, $native->present($androidFragment$navigation)V
        return-void
    """.trimIndent()))

    private fun clazz(type: String, supertype: String = "Ljava/lang/Object;", fields: List<ImmutableField> = emptyList(),
                      methods: List<Method> = emptyList()): ClassDef = ImmutableClassDef(type, public, supertype, null, null, null, fields, methods)

    private fun method(owner: String, name: String, parameters: List<String>, result: String, registers: Int, flags: Int,
                       body: String): Method = MutableMethod(ImmutableMethod(owner, name,
        parameters.map { ImmutableMethodParameter(it, null, null) }, result, flags, null, null,
        ImmutableMethodImplementation(registers, emptyList(), null, null))).apply {
        addInstructionsWithLabels(0, body.trimIndent())
    }.let(ImmutableMethod::of)
}

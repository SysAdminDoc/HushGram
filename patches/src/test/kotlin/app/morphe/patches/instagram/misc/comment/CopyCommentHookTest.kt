/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.comment

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.download.INSTAGRAM_MEDIA
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.*
import org.junit.Assert.*
import org.junit.Test

class CopyCommentHookTest {
    private val public = AccessFlags.PUBLIC.value
    private val giphy = "Lcom/instagram/api/schemas/CommentGiphyMediaInfoIntf;"

    @Test fun renamedNativeModelsResolveOriginalTextAndOnlyTheGuardedRendererChanges() {
        for (salt in listOf("First", "Renamed")) {
            val native = CommentWorld.nativeClasses(salt)
            val patch = PatchContexts.of(native + extension())
            val menu = patch.findCommentMenu()
            val unchangedTypes = native.map { it.type }.filter { it != menu.surface.renderer.definingClass }
            val unchanged = CommentWorld.snapshot(patch, unchangedTypes)
            assertEquals("original$salt", menu.text.name)
            assertEquals(0x7f080123, menu.icon)
            assertEquals(0x7f130456, menu.label)
            patch.applyCommentMenu(menu)
            assertWiring(patch, menu)
            assertEquals("stock model, builder and dismissal methods stay intact", unchanged, CommentWorld.snapshot(patch, unchangedTypes))
        }
    }

    @Test fun missingAmbiguousPrivateOrChangedBoundariesRefuseBeforeAnyMutation() {
        val valid = CommentWorld.nativeClasses("First")
        val select = valid.single { it.type.endsWith("Controller;") }
        val cases = mapOf(
            "missing selector" to valid.filter { it != select },
            "ambiguous selector" to valid + CommentWorld.clazz("Ltest/Duplicate;", methods = listOf(CommentWorld.method("Ltest/Duplicate;",
                "other", listOf(STRING, STRING, "F", "Z"), "V", 5, public, "const-string v0, \"$COMMENT_SELECT\"\nreturn-void"))),
            "parser changed" to CommentWorld.nativeClasses("First", textKey = "text_translation"),
            "private text" to CommentWorld.nativeClasses("First", textFlags = AccessFlags.PRIVATE.value),
            "private row constructor" to CommentWorld.nativeClasses("First", rowFlags = AccessFlags.PRIVATE.value),
            "final native row" to CommentWorld.nativeClasses("First", rowTypeFlags = public or AccessFlags.FINAL.value),
            "private resource wrapper" to CommentWorld.nativeClasses("First", labelFlags = AccessFlags.PRIVATE.value),
            "private style base" to CommentWorld.nativeClasses("First", styleTypeFlags = AccessFlags.ABSTRACT.value),
            "non-dismissing callback" to CommentWorld.nativeClasses("First", dismiss = 0),
            "missing popup guards" to CommentWorld.nativeClasses("First", guards = false),
            // The selected comment's register is counted back from the list, so a wide value between them moves it.
            "wide renderer parameter" to CommentWorld.nativeClasses("First", wideState = true),
            "missing bridge" to valid + extension().filter { it.type != COMMENT_NATIVE },
        )
        for ((case, native) in cases) {
            val classes = if (native.any { it.type == COPY_ROW }) native else native + extension()
            // Missing-bridge case deliberately omits it, rather than restoring the valid extension.
            val actual = if (case == "missing bridge") native else classes
            val patch = PatchContexts.of(actual)
            val before = CommentWorld.snapshot(patch, actual.map { it.type })
            val failure = runCatching { patch.applyCommentMenu(patch.findCommentMenu()) }.exceptionOrNull()
            assertTrue("$case: $failure", failure?.message?.startsWith("Copy comment: ") == true)
            assertEquals(case, before, CommentWorld.snapshot(patch, actual.map { it.type }))
        }
    }

    @Test fun wideWriteToTheOriginalParametersHighHalfRefusesBeforeMutation() {
        refusesFlow(CommentWorld.nativeClasses("Wide", constructorFlow = "wide"))
    }

    @Test fun branchCannotBypassTheConstructorOriginalAlias() {
        refusesFlow(CommentWorld.nativeClasses("Branch", constructorFlow = "branch"))
    }

    @Test fun branchCannotEnterTheConverterAfterTheOriginalRead() {
        refusesFlow(CommentWorld.nativeClasses("Entry", converterFlow = "branch"))
    }

    @Test fun exceptionHandlerCannotBypassTheConverterOriginalRead() {
        refusesFlow(CommentWorld.nativeClasses("Handler", converterFlow = "handler"))
    }

    @Test fun validObjectAliasesAndConvergingBranchesKeepTheOriginalText() {
        val patch = PatchContexts.of(CommentWorld.nativeClasses("Alias", constructorFlow = "joined", converterFlow = "alias") + extension())
        val menu = patch.findCommentMenu()
        assertEquals("originalAlias", menu.text.name)
        patch.applyCommentMenu(menu)
        assertWiring(patch, menu)
    }

    @Test fun parsedGetterCannotReplaceItsReturnAfterLoadingThePlainField() {
        refusesFlow(CommentWorld.nativeClasses("GetterReturn", getterFlow = "overwrite"))
    }

    @Test fun parsedGetterCannotSubstituteItsReturnOnABranch() {
        refusesFlow(CommentWorld.nativeClasses("GetterBranch", getterFlow = "branch"))
    }

    @Test fun parsedGetterMustReadTheOriginalReceiver() {
        refusesFlow(CommentWorld.nativeClasses("GetterReceiver", getterFlow = "receiver"))
    }

    @Test fun aNearbyOriginalKeyCannotAuthorizeAReadOfTranslation() {
        refusesFlow(CommentWorld.nativeClasses("NearbyKey", parserFlow = "nearby-translation"))
    }

    @Test fun nativeEqualsDiscriminatorAndReturnedObjectAliasesStaySupported() {
        for (flow in listOf("return-alias", "return-joined", "early-null")) {
            val patch = PatchContexts.of(CommentWorld.nativeClasses("NativeParser", parserFlow = "equals", converterFlow = flow) + extension())
            val menu = patch.findCommentMenu()
            assertEquals("originalNativeParser", menu.text.name)
            patch.applyCommentMenu(menu)
            assertWiring(patch, menu)
        }
    }

    @Test fun aDiscriminatorCannotCompareTheTranslationKeyInstead() {
        refusesFlow(CommentWorld.nativeClasses("WrongKey", parserFlow = "equals-translation"))
    }

    @Test fun comparingAnotherValueToTextCannotEstablishTheCurrentFieldName() {
        refusesFlow(CommentWorld.nativeClasses("ValueCompared", parserFlow = "equals-value"))
    }

    @Test fun nativeReaderBoundariesMustReturnTheirReadFromTheSameReceiverAndAdvanceExactlyOnce() {
        for (flow in listOf("helper-wrong-receiver", "helper-overwrite", "helper-no-advance",
                "helper-extra-advance", "helper-advance-before-name", "helper-bypass", "advance-after-key")) {
            refusesFlow(CommentWorld.nativeClasses("ReaderBoundary", parserFlow = flow))
        }
    }

    @Test fun aBranchCannotBypassTheOriginalKeyDiscriminator() {
        refusesFlow(CommentWorld.nativeClasses("KeyBypass", parserFlow = "equals-bypass"))
    }

    @Test fun anOverwrittenEqualityResultCannotAuthorizeOriginalText() {
        refusesFlow(CommentWorld.nativeClasses("KeyResult", parserFlow = "equals-result"))
    }

    @Test fun anOriginalObjectCannotBeDiscardedForATranslatedObject() {
        refusesFlow(CommentWorld.nativeClasses("Discarded", converterFlow = "discard"))
    }

    @Test fun everyReturnedObjectMustCarryTheVerifiedOriginalText() {
        refusesFlow(CommentWorld.nativeClasses("OtherReturn", converterFlow = "other-return"))
    }

    @Test fun aReturnedObjectCannotBypassInitialization() {
        refusesFlow(CommentWorld.nativeClasses("Uninitialized", converterFlow = "uninitialized"))
    }

    @Test fun anOverwriteCannotReplaceTheVerifiedReturnedObject() {
        refusesFlow(CommentWorld.nativeClasses("ObjectOverwrite", converterFlow = "return-overwrite"))
    }

    @Test fun aConstructorExceptionCannotReturnAnUninitializedObject() {
        refusesFlow(CommentWorld.nativeClasses("InitHandler", converterFlow = "return-handler"))
    }

    private fun refusesFlow(native: List<ClassDef>) {
        val classes = native + extension()
        val patch = PatchContexts.of(classes)
        val before = CommentWorld.snapshot(patch, classes.map { it.type })
        val failure = runCatching { patch.applyCommentMenu(patch.findCommentMenu()) }.exceptionOrNull()
        assertTrue("unsafe original-text flow: $failure", failure?.message?.startsWith("Copy comment: ") == true)
        assertEquals("unsafe flow refuses before mutation", before, CommentWorld.snapshot(patch, classes.map { it.type }))
    }

    @Test fun everyDeclaredFixtureSuppliesTheParserModelRendererAndStockDismissal() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
            val anchors = (FixtureDex.classesHolding(bundle, COMMENT_SELECT) +
                FixtureDex.classesHolding(bundle, JSON_ROOT_FIELD) +
                FixtureDex.classesHolding(bundle, "CopyText") + FixtureDex.classesHolding(bundle, COMMENT_LEGACY))
                .distinctBy { it.type }
            val selects = anchors.flatMap { it.methods }.filter { COMMENT_SELECT in it.strings() &&
                it.parameters() == listOf(STRING, STRING, "F", "Z") && it.returnType == "V" }
            assertEquals("fixture selector count", 1, selects.size)
            val select = selects.single()
            val selected = selectionType(select)
            val model = FixtureDex.classes(bundle, setOf(selected)).getValue(selected)
            val interfaces = FixtureDex.classes(bundle, model.fields.map { it.type }.toSet()).values
            val raws = interfaces.filter { type -> type.methods.any {
                it.parameterTypes.isEmpty() && it.returnType == giphy
            } && AccessFlags.INTERFACE.isSet(type.accessFlags) }
            assertEquals("fixture raw interfaces: ${raws.map { it.type }}", 1, raws.size)
            val raw = raws.single().type
            val parts = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                dex.classes.forEach { type ->
                    if (raw in type.interfaces || type.methods.any { method ->
                            method.returnType == selected || method.parameters().let {
                                it.size == 5 && it[1] == selected && it[3] == LIST && it[4] == "F"
                            }
                        }) parts += ImmutableClassDef.of(type)
                }
            }
            val values = parts.filter { raw in it.interfaces && it.superclass != "Lcom/facebook/pando/TreeJNI;" }.map { it.type }.toSet()
            FixtureDex.forEach(bundle) { dex ->
                dex.classes.forEach { type ->
                    if (type.methods.any { method -> method.name == "unsafeParseFromJson" && "text" in method.strings() &&
                        method.code().any { it.call()?.let { call -> call.name == "<init>" && call.definingClass in values } == true } }) {
                        parts += ImmutableClassDef.of(type)
                    }
                }
            }
            val core = (anchors + model + interfaces + parts).distinctBy { it.type }
            val dependencies = core.mapNotNull { it.superclass }.toMutableSet()
            for (type in core) for (method in type.methods) for (instruction in method.code()) {
                when (val reference = instruction.reference()) {
                    is MethodReference -> {
                        dependencies += reference.definingClass
                        dependencies += reference.returnType
                        dependencies += reference.parameters()
                    }
                    is FieldReference -> { dependencies += reference.definingClass; dependencies += reference.type }
                    is TypeReference -> dependencies += reference.type
                }
            }
            val classes = (core + FixtureDex.classes(bundle, dependencies).values).distinctBy { it.type }
            val patch = PatchContexts.of(classes + extension())
            val legacy = classes.filter { type -> type.methods.any { COMMENT_LEGACY in it.strings() } }.map { it.type }
            assertTrue("fixture lost the separate legacy surface", legacy.isNotEmpty())
            val unchanged = CommentWorld.snapshot(patch, legacy)
            val menu = patch.findCommentMenu()
            val renderer = patch.mutableClassDefBy(menu.surface.renderer.definingClass).methods.single { it.matches(menu.surface.renderer) }
            val original = NeutralNativePath(renderer)
            // #35: who wrote the comment, read through the raw comment like Save comment photo's
            // name is, and the renderer's one getString on a row's label.
            val author = menu.author ?: error("${bundle.name}: Copy username's boundaries weren't found")
            assertEquals(menu.surface.raw, author.user.definingClass)
            assertEquals(USER, author.user.returnType)
            val stockLabel = labelSite(renderer, author.label)
            assertEquals(GET_STRING, renderer.code()[stockLabel.at + 2].call().toString())
            patch.applyCommentMenu(menu)
            assertWiring(patch, menu)
            val hook = renderer.code().indexOfFirst { it.call()?.toString() == COMMENT_HOOK }
            val labelAt = renderer.code().indexOfFirst { it.call()?.toString() == AUTHOR_LABEL }
            assertEquals("one label read goes through Copy username", 1, renderer.code().count { it.call()?.toString() == AUTHOR_LABEL })
            assertEquals("the label read keeps its place", Opcode.IGET, renderer.code()[labelAt - 1].opcode)
            assertEquals(author.label.idField.toString(), renderer.code()[labelAt - 1].field().toString())
            assertEquals(Opcode.MOVE_RESULT_OBJECT, renderer.code()[labelAt + 1].opcode)
            assertEquals("the call keeps the row's context and id, and adds the row",
                renderer.code()[labelAt].arguments(), listOf(stockLabel.context, stockLabel.id, stockLabel.row))
            assertTrue("no row label is read past Copy username", renderer.code().none { it.call()?.toString() == GET_STRING &&
                renderer.code().getOrNull(renderer.code().indexOf(it) - 1)?.field()?.toString() == author.label.idField.toString() })
            val reader = patch.mutableClassDefBy(AUTHOR_NATIVE).methods.single { it.name == "author" }.code()
            assertTrue(reader.any { it.field()?.toString() == menu.surface.rawField.toString() })
            assertTrue(reader.any { it.call()?.toString() == author.user.toString() })
            assertEquals(menu.surface.rowConstructor.definingClass, patch.mutableClassDefBy(AUTHOR_ROW).superclass)
            val username = patch.mutableClassDefBy(INSTAGRAM_MEDIA).methods.single { it.name == "username" }.code()
            assertTrue("User's username bridge is written", username.any { it.call()?.definingClass == USER })
            original.assertPreserved(bundle.name, renderer, (hook - 3..hook + 1).toSet(), setOf(labelAt))
            assertEquals("legacy surface must not be reported as patched", unchanged, CommentWorld.snapshot(patch, legacy))
            checked += version
        }
        assertEquals("declared build has no fixture", versions, checked)
    }

    private fun assertWiring(patch: BytecodePatchContext, menu: CommentMenu) {
        val renderer = patch.mutableClassDefBy(menu.surface.renderer.definingClass).methods.single { it.matches(menu.surface.renderer) }
        assertEquals(1, renderer.code().count { it.call()?.toString() == COMMENT_HOOK })
        val callAt = renderer.code().indexOfFirst { it.call()?.toString() == COMMENT_HOOK }
        assertTrue("hook must follow the popup guards", renderer.code().take(callAt).count { it.opcode == Opcode.IF_NEZ } >= 2)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, renderer.code()[callAt + 1].opcode)
        assertEquals(menu.surface.rowConstructor.definingClass, patch.mutableClassDefBy(COPY_ROW).superclass)
        val bridge = patch.mutableClassDefBy(COMMENT_NATIVE).methods.single { it.name == "originalText" }
        assertTrue(bridge.code().any { it.field()?.toString() == menu.text.toString() })
        assertEquals("separate unsupported/text returns avoid an ART object merge", 2,
            bridge.code().count { it.opcode == Opcode.RETURN_OBJECT })
        val factory = patch.mutableClassDefBy(COMMENT_NATIVE).methods.single { it.name == "newRow" }
        assertTrue(factory.code().any { it.field()?.toString() == menu.surface.style.toString() })
        assertTrue(factory.code().any { it.call()?.toString() == menu.surface.labelConstructor.toString() })
        assertTrue(factory.code().any { (it.reference() as? TypeReference)?.type == COPY_ROW })
    }

    private fun extension() = listOf(COMMENT_NATIVE, COPY_ROW, COMMENT_COPY, COMMENT_ACTIONS,
        AUTHOR_NATIVE, AUTHOR_ROW, COMMENT_AUTHOR, INSTAGRAM_MEDIA).map(ExtensionDex::classDef)
}

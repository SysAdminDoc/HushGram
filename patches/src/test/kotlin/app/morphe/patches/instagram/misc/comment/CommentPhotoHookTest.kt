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
import app.morphe.patches.instagram.download.IMAGE_INFO
import app.morphe.patches.instagram.download.IMAGE_URL
import app.morphe.patches.instagram.download.INSTAGRAM_MEDIA
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.PANDO_IMAGE_INFO
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CommentPhotoHookTest {
    private val private = AccessFlags.PRIVATE.value

    @Test fun renamedModelsResolveTheCommentsOwnPhotoAndOnlyTheRendererChanges() {
        for ((salt, pooled) in listOf("First" to false, "Renamed" to true)) {
            val native = CommentWorld.nativeClasses(salt, photo = PhotoWorld(pooledSave = pooled))
            val patch = PatchContexts.of(native + extension())
            val plan = patch.findCommentPhoto()
            assertEquals(listOf("gif", "info", "media", "kind", "gif", "videos", "duration"),
                listOf(plan.gif, plan.info, plan.media, plan.kind, plan.mediaGif, plan.videoVersions, plan.videoDuration).map { it.name })
            assertEquals("Ltest/${salt}Raw;", plan.info.definingClass)
            assertEquals(MEDIA, plan.kind.definingClass)
            assertEquals(1, plan.photo)
            assertEquals(0x7f080789, plan.icon)
            assertEquals(0x7f130789, plan.label)
            val unchangedTypes = native.map { it.type }.filter { it != plan.surface.renderer.definingClass }
            val unchanged = CommentWorld.snapshot(patch, unchangedTypes)
            patch.applyCommentPhoto(plan)
            assertPhotoWiring(patch, plan)
            assertEquals("Copy's bridges stay as the extension wrote them", stubs(patch, COMMENT_NATIVE),
                stubs(PatchContexts.of(native + extension()), COMMENT_NATIVE))
            assertEquals("stock models, parsers and actions stay intact", unchanged, CommentWorld.snapshot(patch, unchangedTypes))
        }
    }

    @Test fun copyAndPhotoShareTheRenderersOneCallInEitherOrder() {
        for (copyFirst in listOf(true, false)) {
            val patch = PatchContexts.of(CommentWorld.nativeClasses("Both", photo = PhotoWorld()) + extension())
            val steps = listOf<() -> Unit>({ patch.applyCommentMenu(patch.findCommentMenu()) },
                { patch.applyCommentPhoto(patch.findCommentPhoto()) })
            (if (copyFirst) steps else steps.reversed()).forEach { it() }
            val plan = patch.findCommentPhoto()
            assertPhotoWiring(patch, plan)
            assertEquals(plan.surface.rowConstructor.definingClass, patch.mutableClassDefBy(COPY_ROW).superclass)
            val copyRows = patch.mutableClassDefBy(COMMENT_NATIVE).methods.single { it.name == "callback" }
            assertTrue("Copy reads only its own rows", copyRows.code().any { (it.reference() as? TypeReference)?.type == COPY_ROW })
            assertTrue(copyRows.code().none { (it.reference() as? TypeReference)?.type == PHOTO_ROW })
        }
    }

    @Test fun eitherFamilyRefusingLeavesTheOtherWorking() {
        val copyRefuses = PatchContexts.of(CommentWorld.nativeClasses("NoCopy", textKey = "text_translation",
            photo = PhotoWorld()) + extension())
        val untouched = CommentWorld.snapshot(copyRefuses, listOf(COMMENT_NATIVE, COPY_ROW))
        val copy = runCatching { copyRefuses.applyCommentMenu(copyRefuses.findCommentMenu()) }.exceptionOrNull()
        assertTrue("$copy", copy?.message?.startsWith("Copy comment: ") == true)
        val plan = copyRefuses.findCommentPhoto()
        copyRefuses.applyCommentPhoto(plan)
        assertPhotoWiring(copyRefuses, plan)
        assertEquals(untouched, CommentWorld.snapshot(copyRefuses, listOf(COMMENT_NATIVE, COPY_ROW)))

        val photoRefuses = PatchContexts.of(CommentWorld.nativeClasses("NoPhoto",
            photo = PhotoWorld(commentKey = "media_info")) + extension())
        val photoStubs = CommentWorld.snapshot(photoRefuses, listOf(PHOTO_NATIVE, PHOTO_ROW, INSTAGRAM_MEDIA))
        val photo = runCatching { photoRefuses.applyCommentPhoto(photoRefuses.findCommentPhoto()) }.exceptionOrNull()
        assertTrue("$photo", photo?.message?.startsWith("Save comment photo: ") == true)
        val menu = photoRefuses.findCommentMenu()
        photoRefuses.applyCommentMenu(menu)
        assertEquals(1, renderer(photoRefuses, menu.surface).code().count { it.call()?.toString() == COMMENT_HOOK })
        assertEquals(photoStubs, CommentWorld.snapshot(photoRefuses, listOf(PHOTO_NATIVE, PHOTO_ROW, INSTAGRAM_MEDIA)))
    }

    @Test fun missingAmbiguousPrivateOrChangedPhotoBoundariesRefuseBeforeAnyMutation() {
        val cases = mapOf(
            "parent media_info only" to PhotoWorld(commentKey = "media_info"),
            "tree reads another field" to PhotoWorld(treeKey = "media_info"),
            "nested parser lost its media key" to PhotoWorld(mediaKey = "thumbnail"),
            "tree media read from another field" to PhotoWorld(treeMediaKey = "image_versions2"),
            "tree media written from outside" to PhotoWorld(foreignMediaStore = true),
            "GIF getter reads another field" to PhotoWorld(gifKey = "giphy_sticker_info"),
            "media GIF getter missing" to PhotoWorld(mediaGifKey = "sticker_info"),
            "video versions getter missing" to PhotoWorld(videoKey = "video_url"),
            "video duration getter missing" to PhotoWorld(durationKey = "video_length"),
            "private raw comment field" to PhotoWorld(rawFieldFlags = private),
            "private media kind getter" to PhotoWorld(kindFlags = private),
            "raw comment not kept" to PhotoWorld(converterRaw = "null"),
            "key guard bypassed" to PhotoWorld(commentRead = "bypass"),
            "another parser's singleton" to PhotoWorld(commentRead = "other-parser"),
            "second read after the key" to PhotoWorld(commentRead = "extra-read"),
            "PHOTO built on a branch" to PhotoWorld(kindFlow = "branch"),
            "PHOTO value computed" to PhotoWorld(kindFlow = "computed"),
            "no Save action" to PhotoWorld(saveName = "SaveDraft"),
            "two Save actions" to PhotoWorld(saves = 2),
            "pooled name of another action" to PhotoWorld(pooledSave = true, saveName = "SaveDraft"),
        ).mapValues { (_, photo) -> CommentWorld.nativeClasses("Changed", photo = photo) + extension() } + mapOf(
            "missing popup guards" to CommentWorld.nativeClasses("Changed", guards = false, photo = PhotoWorld()) + extension(),
            "missing photo bridge" to CommentWorld.nativeClasses("Changed", photo = PhotoWorld()) +
                extension().filter { it.type != PHOTO_NATIVE },
            "missing one photo read" to CommentWorld.nativeClasses("Changed", photo = PhotoWorld()) +
                extension().map { type -> if (type.type != PHOTO_NATIVE) type else ImmutableClassDef(type.type,
                    type.accessFlags, type.superclass, type.interfaces, type.sourceFile, type.annotations, type.fields,
                    type.methods.filter { it.name != "kind" }) },
            "missing image bridge" to CommentWorld.nativeClasses("Changed", photo = PhotoWorld()) +
                extension().filter { it.type != INSTAGRAM_MEDIA },
        )
        for ((case, classes) in cases) {
            val patch = PatchContexts.of(classes)
            val before = CommentWorld.snapshot(patch, classes.map { it.type })
            val failure = runCatching { patch.applyCommentPhoto(patch.findCommentPhoto()) }.exceptionOrNull()
            assertTrue("$case: $failure", failure?.message?.startsWith("Save comment photo: ") == true)
            assertEquals(case, before, CommentWorld.snapshot(patch, classes.map { it.type }))
        }
    }

    @Test fun everyDeclaredFixtureSuppliesTheCommentsOwnPhotoAndInstagramsSaveAction() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
            val classes = fixtureClasses(bundle)
            val legacy = classes.filter { type -> type.methods.any { COMMENT_LEGACY in it.strings() } }.map { it.type }
            assertTrue("fixture lost the separate legacy surface", legacy.isNotEmpty())
            for (families in listOf("photo", "copy+photo", "photo+copy")) {
                val patch = PatchContexts.of(classes + extension())
                val stockRenderer = renderer(patch, patch.findCommentPhoto().surface)
                val original = NeutralNativePath(stockRenderer)
                val unchanged = CommentWorld.snapshot(patch, legacy)
                if (families == "copy+photo") patch.applyCommentMenu(patch.findCommentMenu())
                val plan = patch.findCommentPhoto()
                assertEquals("media_type 1 is a photo", 1, plan.photo)
                assertEquals(MEDIA, plan.kind.definingClass)
                // A video is told apart by these when media_type is missing: each getter names its own field.
                val media = classes.single { it.type == MEDIA }
                for ((getter, field) in listOf(plan.videoVersions to "video_versions", plan.videoDuration to "video_duration")) {
                    val body = media.methods.single { it.name == getter.name && it.parameterTypes.isEmpty() && it.returnType == getter.returnType }
                    assertTrue("${bundle.name}: $field getter", field in body.strings() &&
                        body.code().any { it is NarrowLiteralInstruction && it.narrowLiteral == field.hashCode() })
                }
                assertEquals(listOf("Ljava/util/List;", "Ljava/lang/Double;"), listOf(plan.videoVersions, plan.videoDuration).map { it.returnType })
                assertEquals(MEDIA, plan.media.returnType)
                assertEquals(plan.surface.raw, plan.info.definingClass)
                assertEquals(plan.surface.raw, plan.gif.definingClass)
                assertNotEquals(plan.icon, plan.label)
                // Who wrote the comment and when, for the save's name: read through the raw comment's
                // interface, each proved on its tree-backed class by the key it reads.
                val author = plan.author ?: error("${bundle.name}: the comment's author and time weren't found")
                assertEquals(listOf(plan.surface.raw, plan.surface.raw), listOf(author.user, author.createdAt).map { it.definingClass })
                assertEquals(listOf(USER, "Ljava/lang/Long;"), listOf(author.user, author.createdAt).map { it.returnType })
                val tree = classes.single { it.type == plan.surface.pando }
                assertTrue("${bundle.name}: created_at getter", tree.methods.single { it.matches(author.createdAt) }.code()
                    .any { it is NarrowLiteralInstruction && it.narrowLiteral == "created_at".hashCode() })
                val userField = tree.methods.single { it.matches(author.user) }.code()[0].field()!!
                assertTrue("${bundle.name}: the user getter answers the tree's \"user\" read", tree.methods.any { method ->
                    val code = method.code()
                    val read = code.indexOfFirst { it is NarrowLiteralInstruction && it.narrowLiteral == "user".hashCode() }
                    read >= 0 && code.drop(read).firstOrNull { it.opcode == Opcode.IPUT_OBJECT && it.field()?.type == USER }
                        ?.field().toString() == userField.toString()
                })
                patch.applyCommentPhoto(plan)
                if (families == "photo+copy") patch.applyCommentMenu(patch.findCommentMenu())
                assertPhotoWiring(patch, plan)
                val hook = stockRenderer.code().indexOfFirst { it.call()?.toString() == COMMENT_HOOK }
                original.assertPreserved("${bundle.name} $families", stockRenderer, (hook - 3..hook + 1).toSet())
                assertEquals("legacy surface must not be reported as patched", unchanged, CommentWorld.snapshot(patch, legacy))
            }
            checked += version
        }
        assertEquals("declared build has no fixture", versions, checked)
    }

    private fun assertPhotoWiring(patch: BytecodePatchContext, plan: CommentPhotoPlan) {
        val surface = plan.surface
        val renderer = renderer(patch, surface).code()
        assertEquals(1, renderer.count { it.call()?.toString() == COMMENT_HOOK })
        val callAt = renderer.indexOfFirst { it.call()?.toString() == COMMENT_HOOK }
        assertTrue("hook must follow the popup guards", renderer.take(callAt).count { it.opcode == Opcode.IF_NEZ } >= 2)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, renderer[callAt + 1].opcode)
        assertEquals(surface.rowConstructor.definingClass, patch.mutableClassDefBy(PHOTO_ROW).superclass)
        val native = patch.mutableClassDefBy(PHOTO_NATIVE).methods
        fun read(name: String) = native.single { it.name == name }.code()
        // Each read is one native step; the extension decides between them and counts where one stops.
        val selected = read("selected")
        assertEquals(listOf(Opcode.INSTANCE_OF, Opcode.RETURN), selected.map { it.opcode })
        assertEquals(surface.selectedType, (selected[0].reference() as TypeReference).type)
        val raw = read("raw")
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT), raw.map { it.opcode })
        assertEquals(surface.selectedType, (raw[0].reference() as TypeReference).type)
        assertEquals(surface.rawField.toString(), raw[1].field().toString())
        for ((name, getter) in listOf("gif" to plan.gif, "info" to plan.info, "media" to plan.media,
                "kind" to plan.kind, "mediaGif" to plan.mediaGif, "videoVersions" to plan.videoVersions,
                "videoDuration" to plan.videoDuration)) {
            val code = read(name)
            val invoke = if (getter.definingClass == MEDIA) Opcode.INVOKE_VIRTUAL else Opcode.INVOKE_INTERFACE
            assertEquals(name, listOf(Opcode.CHECK_CAST, invoke, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT), code.map { it.opcode })
            assertEquals(name, getter.definingClass, (code[0].reference() as TypeReference).type)
            assertEquals(name, getter.toString(), code[1].call().toString())
        }
        assertEquals(listOf(surface.raw, surface.raw, plan.media.definingClass, MEDIA, MEDIA, MEDIA, MEDIA),
            listOf(plan.gif, plan.info, plan.media, plan.kind, plan.mediaGif, plan.videoVersions, plan.videoDuration)
                .map { it.definingClass })
        // The author and time read only once they were found; otherwise the stubs answer null.
        for ((name, getter) in listOf("author" to plan.author?.user, "createdAt" to plan.author?.createdAt)) {
            val code = read(name)
            if (getter == null) {
                assertNotEquals(name, Opcode.CHECK_CAST, code.first().opcode)
                continue
            }
            assertEquals(name, listOf(Opcode.CHECK_CAST, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
                code.map { it.opcode })
            assertEquals(name, surface.raw, (code[0].reference() as TypeReference).type)
            assertEquals(name, getter.toString(), code[1].call().toString())
        }
        val username = patch.mutableClassDefBy(INSTAGRAM_MEDIA).methods.single { it.name == "username" }.code().first().opcode
        assertEquals("the username bridge is filled exactly when the author is read", plan.author != null, username == Opcode.CHECK_CAST)
        val kind = read("photoKind")
        assertEquals(listOf(Opcode.CONST, Opcode.RETURN), kind.map { it.opcode })
        assertEquals(plan.photo, (kind[0] as NarrowLiteralInstruction).narrowLiteral)
        for (call in listOf(plan.gif, plan.info, plan.media, plan.kind, plan.mediaGif, plan.videoVersions, plan.videoDuration)) {
            assertEquals("$call is read once", 1, native.sumOf { method -> method.code().count { it.call()?.toString() == call.toString() } })
        }
        assertTrue("the old all-in-one bridge is gone", native.none { it.name == "photoMedia" })
        val factory = native.single { it.name == "newRow" }.code()
        assertTrue(factory.any { it.field()?.toString() == surface.style.toString() })
        assertTrue(factory.any { (it.reference() as? TypeReference)?.type == PHOTO_ROW })
        assertEquals(listOf(plan.icon, plan.label), factory.filter { it.opcode == Opcode.CONST }.map { (it as NarrowLiteralInstruction).narrowLiteral })
        val callback = native.single { it.name == "callback" }.code()
        assertTrue(callback.any { it.opcode == Opcode.INSTANCE_OF && (it.reference() as? TypeReference)?.type == PHOTO_ROW })
        // Only an owned Save row gives up its callback; a stock or Copy row reads as null.
        assertEquals(listOf(Opcode.INSTANCE_OF, Opcode.IF_EQZ, Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT,
            Opcode.CONST_4, Opcode.RETURN_OBJECT), callback.map { it.opcode })
        assertEquals(surface.callback.toString(), callback[3].field().toString())
        val callbackAddresses = callback.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        assertEquals(callbackAddresses[5], callbackAddresses[1] + (callback[1] as OffsetInstruction).codeOffset)
        val images = patch.mutableClassDefBy(INSTAGRAM_MEDIA).methods
        for (name in listOf("imageVersions", "imageCandidates", "candidateUrl", "candidateWidth", "candidateHeight")) {
            assertEquals(name, Opcode.CHECK_CAST, images.single { it.name == name }.code().first().opcode)
        }
    }

    private fun renderer(patch: BytecodePatchContext, surface: CommentSurface) =
        patch.mutableClassDefBy(surface.renderer.definingClass).methods.single { it.matches(surface.renderer) }

    private fun stubs(patch: BytecodePatchContext, type: String) = CommentWorld.snapshot(patch, listOf(type))

    private fun extension() = listOf(COMMENT_NATIVE, COPY_ROW, COMMENT_COPY, COMMENT_ACTIONS,
        PHOTO_NATIVE, PHOTO_ROW, COMMENT_PHOTO, INSTAGRAM_MEDIA).map(ExtensionDex::classDef)

    /**
     * The fixture classes both families read, without holding a whole APK: the classes holding each
     * anchor and the models around them, then what those reference, as the copy fixture test does,
     * and the nested comment media, its parser, the media kind and the native actions beside Copy.
     */
    private fun fixtureClasses(bundle: File): List<ClassDef> {
        val pool = linkedMapOf<String, ClassDef>()
        fun add(types: Collection<ClassDef>) = types.forEach { pool.putIfAbsent(it.type, it) }
        fun load(types: Collection<String>) = add(FixtureDex.classes(bundle, types.toSet() - pool.keys).values)
        fun references(types: Collection<ClassDef>): Set<String> {
            val found = mutableSetOf<String>()
            for (type in types) {
                type.superclass?.let(found::add)
                found += type.interfaces
                for (method in type.methods) for (instruction in method.code()) when (val reference = instruction.reference()) {
                    is MethodReference -> { found += reference.definingClass; found += reference.returnType; found += reference.parameters() }
                    is FieldReference -> { found += reference.definingClass; found += reference.type }
                    is TypeReference -> found += reference.type
                }
            }
            return found.filter { it.startsWith("L") }.toSet()
        }
        fun scan(wanted: (ClassDef) -> Boolean) {
            val found = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex -> dex.classes.forEach { if (it.type !in pool && wanted(it)) found += ImmutableClassDef.of(it) } }
            add(found)
        }
        for (anchor in listOf(COMMENT_SELECT, JSON_ROOT_FIELD, "CopyText", COMMENT_LEGACY, COMMENT_MEDIA_KEY)) {
            add(FixtureDex.classesHolding(bundle, anchor))
        }
        val select = pool.values.flatMap { it.methods }.single { COMMENT_SELECT in it.strings() &&
            it.parameters() == listOf(STRING, STRING, "F", "Z") && it.returnType == "V" }
        val selected = selectionType(select)
        load(listOf(selected))
        load(pool.getValue(selected).fields.map { it.type })
        val raw = pool.values.single { type -> AccessFlags.INTERFACE.isSet(type.accessFlags) &&
            type.type in pool.getValue(selected).fields.map { it.type } && type.methods.any { it.returnType == GIPHY } }.type
        scan { type -> raw in type.interfaces || type.methods.any { method -> method.returnType == selected ||
            method.parameters().let { it.size == 5 && it[1] == selected && it[3] == LIST && it[4] == "F" } } }
        val parsed = pool.values.filter { raw in it.interfaces }.map { it.type }.toSet()
        scan { type -> type.methods.any { method -> method.name == "unsafeParseFromJson" &&
            method.code().any { it.call()?.let { call -> call.name == "<init>" && call.definingClass in parsed } == true } } }
        load(references(pool.values.toList()))
        // The comment's own media: its interface, both of its classes and the parser of the parsed one.
        val info = pool.values.filter { raw in it.interfaces }.flatMap { it.methods }
            .filter { method -> method.parameterTypes.isEmpty() && method.returnType.startsWith("L") &&
                method.code().any { it is NarrowLiteralInstruction && it.narrowLiteral == COMMENT_MEDIA_KEY.hashCode() } }
            .map { it.returnType }.distinct().single()
        scan { info in it.interfaces }
        val infoModels = pool.values.filter { info in it.interfaces }.map { it.type }.toSet()
        scan { type -> type.methods.any { method -> method.name == "unsafeParseFromJson" &&
            method.code().any { it.call()?.let { call -> call.name == "<init>" && call.definingClass in infoModels } == true } } }
        // Instagram's native actions beside Copy, and the pool their names come from.
        val family = pool.values.single { type -> type.methods.any { it.name == "toString" && "CopyText" in it.strings() } }.superclass!!
        scan { it.superclass == family }
        load(listOf(MEDIA, IMAGE_INFO, PANDO_IMAGE_INFO, IMAGE_URL))
        val focus = pool.values.filter { it.superclass == family || it.type in infoModels ||
            it.methods.any { method -> method.name == "unsafeParseFromJson" &&
                method.code().any { instruction -> instruction.call()?.definingClass in infoModels } } }
        load(references(focus))
        // The media itself references most of the app, so only the kinds its media_type reads map through.
        load(pool.getValue(MEDIA).methods.filter { method ->
            method.code().any { it is NarrowLiteralInstruction && it.narrowLiteral == "media_type".hashCode() }
        }.flatMap { method -> method.code().mapNotNull { it.call() }.filter { it.parameters() == listOf(INTEGER) } }
            .map { it.returnType })
        return pool.values.toList()
    }
}

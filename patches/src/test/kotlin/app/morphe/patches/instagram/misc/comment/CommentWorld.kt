/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.comment

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.download.IMAGE_INFO
import app.morphe.patches.instagram.download.IMAGE_URL
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.PANDO_IMAGE_INFO
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.*
import com.android.tools.smali.dexlib2.immutable.instruction.*
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference

/**
 * What the comment photo's native boundaries look like in [CommentWorld], and the one each refusal
 * case changes: the keys the parsers and trees read, how the selected comment keeps its raw comment,
 * how the PHOTO kind is built, and Instagram's Save action beside Copy.
 */
internal data class PhotoWorld(
    val commentKey: String = COMMENT_MEDIA_KEY,
    val treeKey: String = COMMENT_MEDIA_KEY,
    val mediaKey: String = "media",
    val treeMediaKey: String = "media",
    val gifKey: String = "giphy_media_info",
    val mediaGifKey: String = "giphy_media_info",
    val videoKey: String = "video_versions",
    val durationKey: String = "video_duration",
    val rawFieldFlags: Int = AccessFlags.PUBLIC.value,
    val kindFlags: Int = AccessFlags.PUBLIC.value,
    val converterRaw: String = "kept",
    val commentRead: String = "guarded",
    val kindFlow: String = "constant",
    val saveName: String = SAVE_ACTION,
    val pooledSave: Boolean = false,
    val saves: Int = 1,
    val foreignMediaStore: Boolean = false,
)

/**
 * A small renamed Instagram: the selected comment, its raw and parsed models, the JSON reader, the
 * row builder, the common renderer with its popup guards and the stock row callback, and Copy's
 * native action. With a [PhotoWorld], the comment's own media and the Save action are there too.
 */
internal object CommentWorld {
    private val public = AccessFlags.PUBLIC.value
    private val static = public or AccessFlags.STATIC.value
    private val iface = public or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value
    private val context = "Landroid/content/Context;"
    private val giphy = GIPHY

    fun nativeClasses(salt: String, textKey: String = "text", textFlags: Int = public,
                              rowFlags: Int = public, dismiss: Int = 1, guards: Boolean = true,
                              rowTypeFlags: Int = public or AccessFlags.ABSTRACT.value, labelFlags: Int = public,
                              constructorFlow: String = "alias", converterFlow: String = "direct",
                              getterFlow: String = "alias", styleTypeFlags: Int = public or AccessFlags.ABSTRACT.value,
                              parserFlow: String = "direct", wideState: Boolean = false,
                              photo: PhotoWorld? = null): List<ClassDef> {
        fun t(name: String) = "Ltest/$salt$name;"
        val raw = t("Raw")
        val value = t("Value")
        val view = t("View")
        val row = t("Row")
        val style = t("Style")
        val normal = t("Normal")
        val icon = t("Icon")
        val label = t("Label")
        val controller = t("Controller")
        val renderer = t("Renderer")
        val wrapper = t("Callback")
        val json = t("Json")
        val reader = t("Reader")
        val token = t("Token")
        val expected = t("Expected")
        val info = t("Info")
        val infoParser = t("InfoParser")
        fun field(owner: String, name: String, type: String, flags: Int = public) =
            ImmutableField(owner, name, type, flags, null, null, null)
        fun constructor(owner: String, parameters: List<String>, body: String, flags: Int = public) =
            method(owner, "<init>", parameters, "V", parameters.size + 1, flags, body)
        val getterBody = when (getterFlow) {
            "overwrite" -> "iget-object v0, p0, $value->plain:$STRING\nconst-string v0, \"translated\"\nreturn-object v0"
            "branch" -> "iget-object v0, p0, $value->plain:$STRING\nif-eqz v0, :done\nconst-string v0, \"translated\"\n:done\nreturn-object v0"
            "receiver" -> "const/4 v1, 0x0\niget-object v0, v1, $value->plain:$STRING\nreturn-object v0"
            else -> "iget-object v0, p0, $value->plain:$STRING\nmove-object v1, v0\ncheck-cast v1, $STRING\nreturn-object v1"
        }
        val originalAlias = when (constructorFlow) {
            "wide" -> "const-wide/16 p1, 0x0\nmove-object v0, p2"
            "branch" -> "const-string v0, \"translated\"\nif-eqz p1, :store\nmove-object v0, p2\n:store"
            "joined" -> "if-eqz p1, :other\nmove-object v0, p2\ngoto :store\n:other\nmove-object v0, p2\n:store\ncheck-cast v0, $STRING"
            else -> "move-object v0, p2"
        }
        val beforeRead = if (converterFlow == "branch")
            "const-string v2, \"translated\"\nif-eqz p0, :build" else if (converterFlow == "handler")
            "const-string v2, \"translated\"" else if (converterFlow == "early-null") "if-eqz p0, :unsupported" else ""
        val afterRead = when (converterFlow) {
            "branch" -> ":build"
            "handler" -> "goto :build\nmove-exception v1\n:build"
            "alias" -> "move-object v1, v2\nconst/4 v2, 0x0"
            else -> ""
        }
        val returnFlow = when (converterFlow) {
            "discard" -> "new-instance v1, $view\nconst-string v2, \"translated\"\ninvoke-direct { v1, p0, v2, v3 }, $view-><init>($raw$STRING$STRING)V\nreturn-object v1"
            "discard-raw" -> "new-instance v1, $view\nconst/4 v0, 0x0\nconst-string v2, \"translated\"\ninvoke-direct { v1, v0, v2, v3 }, $view-><init>($raw$STRING$STRING)V\nreturn-object v1"
            "other-return" -> "if-eqz p0, :original\nnew-instance v1, $view\nconst-string v2, \"translated\"\ninvoke-direct { v1, p0, v2, v3 }, $view-><init>($raw$STRING$STRING)V\nreturn-object v1\n:original\nreturn-object v0"
            "uninitialized" -> "new-instance v1, $view\nreturn-object v1"
            "return-overwrite" -> "const/4 v0, 0x0\nreturn-object v0"
            "return-alias" -> "move-object v1, v0\nconst/4 v0, 0x0\ncheck-cast v1, $view\nreturn-object v1"
            "return-joined" -> "if-eqz p0, :other\nmove-object v1, v0\ngoto :done\n:other\nmove-object v1, v0\n:done\ncheck-cast v1, $view\nreturn-object v1"
            "early-null" -> "return-object v0\n:unsupported\nconst/4 v0, 0x0\nreturn-object v0"
            "return-handler" -> "return-object v0\nmove-exception v1\nreturn-object v0"
            else -> "return-object v0"
        }
        var conversion = method(t("Converter"), "convert", listOf(raw), view, 5, static, """
            $beforeRead
            invoke-interface { p0 }, $raw->original()$STRING
            move-result-object v2
            $afterRead
            ${if (photo?.converterRaw == "null") "const/4 v1, 0x0" else ""}
            new-instance v0, $view
            const-string v3, "translated"
            invoke-direct { v0, ${if (photo?.converterRaw == "null") "v1" else "p0"}, ${if (converterFlow == "alias") "v1" else "v2"}, v3 }, $view-><init>($raw$STRING$STRING)V
            $returnFlow
        """)
        if (converterFlow in setOf("handler", "return-handler")) {
            val code = conversion.code()
            val read = code.indexOfFirst { it.call()?.let { call ->
                if (converterFlow == "handler") call.definingClass == raw else call.definingClass == view && call.name == "<init>"
            } == true }
            val handler = code.indexOfFirst { it.opcode == Opcode.MOVE_EXCEPTION }
            fun address(at: Int) = code.take(at).sumOf { it.codeUnits }
            conversion = ImmutableMethod(conversion.definingClass, conversion.name, conversion.parameters,
                conversion.returnType, conversion.accessFlags, null, null,
                ImmutableMethodImplementation(5, code, listOf(ImmutableTryBlock(address(read), code[read].codeUnits,
                    listOf(ImmutableExceptionHandler("Ljava/lang/Exception;", address(handler))))), null))
        }
        val parserRead = """
                const-string v0, "$textKey"
                ${if (parserFlow == "equals-translation") "const-string v0, \"text_translation\"" else ""}
                ${if (parserFlow == "equals-bypass") "if-eqz p0, :read" else ""}
                invoke-static { p0 }, $reader->${if (parserFlow == "equals-value") "text" else "key"}($json)$STRING
                move-result-object v1
                ${if (parserFlow == "nearby-translation") "const-string v3, \"text_translation\"" else ""}
                ${if (parserFlow == "advance-after-key") "invoke-virtual { p0 }, $json->next()$token" else ""}
                invoke-virtual { v1, ${if (parserFlow == "nearby-translation") "v3" else "v0"} }, $STRING->equals($OBJECT)Z
                move-result v1
                ${if (parserFlow == "equals-result") "const/4 v1, 0x1" else ""}
                if-eqz v1, :empty
                :read
                invoke-static { p0 }, $reader->text($json)$STRING
            """.trimIndent()
        val keyHelper = """
            ${if (parserFlow == "helper-bypass") "if-eqz p0, :done" else ""}
            ${if (parserFlow == "helper-wrong-receiver") "const/4 v1, 0x0" else ""}
            ${if (parserFlow == "helper-advance-before-name") "invoke-virtual { p0 }, $json->next()$token" else ""}
            invoke-virtual { ${if (parserFlow == "helper-wrong-receiver") "v1" else "p0"} }, $json->name()$STRING
            move-result-object v0
            if-eqz v0, :bad
            ${if (parserFlow == "helper-no-advance") "" else "invoke-virtual { p0 }, $json->next()$token"}
            ${if (parserFlow == "helper-extra-advance") "invoke-virtual { p0 }, $json->next()$token" else ""}
            ${if (parserFlow == "helper-overwrite") "const-string v0, \"text\"" else ""}
            :done
            return-object v0
            :bad
            const/4 v0, 0x0
            throw v0
        """
        val nested = if (photo?.commentRead == "other-parser") t("OtherParser") else infoParser
        val photoRead = if (photo == null) "" else """
            invoke-static { p0 }, $reader->key($json)$STRING
            move-result-object v1
            const-string v5, "${photo.commentKey}"
            invoke-virtual { v1, v5 }, $STRING->equals($OBJECT)Z
            move-result v1
            const/4 v4, 0x0
            ${if (photo.commentRead == "bypass") "if-eqz p0, :attached" else ""}
            if-eqz v1, :built
            :attached
            invoke-virtual { p0 }, $json->current()$token
            ${if (photo.commentRead == "extra-read") "invoke-static { p0 }, $reader->text($json)$STRING" else ""}
            sget-object v1, $nested->INSTANCE:$nested
            invoke-virtual { v1, p0 }, $infoParser->parse($json)$OBJECT
            move-result-object v4
            check-cast v4, $info
            :built
        """.trimIndent()
        return listOf(
            clazz(token, flags = public or AccessFlags.ENUM.value or AccessFlags.FINAL.value, superclass = "Ljava/lang/Enum;",
                fields = listOf(field(token, "FIELD", token, static), field(token, "STRING", token, static)), methods = listOf(
                    constructor(token, listOf(STRING, "I"), "invoke-direct { p0, p1, p2 }, Ljava/lang/Enum;-><init>(${STRING}I)V\nreturn-void"),
                    method(token, "<clinit>", emptyList(), "V", 3, static, """
                        const-string v1, "FIELD_NAME"
                        const/4 v2, 0x0
                        new-instance v0, $token
                        invoke-direct { v0, v1, v2 }, $token-><init>(${STRING}I)V
                        sput-object v0, $token->FIELD:$token
                        const-string v1, "VALUE_STRING"
                        const/4 v2, 0x1
                        new-instance v0, $token
                        invoke-direct { v0, v1, v2 }, $token-><init>(${STRING}I)V
                        sput-object v0, $token->STRING:$token
                        return-void
                    """))),
            clazz(json, flags = public or AccessFlags.ABSTRACT.value,
                fields = listOf(field(json, "token", token), field(json, "name", STRING), field(json, "value", STRING)),
                methods = listOf(
                    method(json, "name", emptyList(), STRING, 2, public, "iget-object v0, p0, $json->name:$STRING\nreturn-object v0"),
                    method(json, "next", emptyList(), token, 2, public, "sget-object v0, $token->STRING:$token\niput-object v0, p0, $json->token:$token\nreturn-object v0"),
                    ImmutableMethod(json, "current", emptyList(), token, public or AccessFlags.ABSTRACT.value, null, null, null),
                    method(json, "value", emptyList(), STRING, 3, public, """
                        iget-object v0, p0, $json->token:$token
                        sget-object v1, $token->STRING:$token
                        if-eq v0, v1, :value
                        sget-object v1, $token->FIELD:$token
                        if-eq v0, v1, :name
                        const/4 v0, 0x0
                        return-object v0
                        :name
                        iget-object v0, p0, $json->name:$STRING
                        return-object v0
                        :value
                        iget-object v0, p0, $json->value:$STRING
                        return-object v0
                    """),
                    // Jackson's nextTextValue, which the patch finds the value accessor through.
                    method(json, "nextText", emptyList(), STRING, 3, public, """
                        invoke-virtual { p0 }, $json->next()$token
                        move-result-object v0
                        sget-object v1, $token->STRING:$token
                        if-ne v0, v1, :other
                        invoke-virtual { p0 }, $json->value()$STRING
                        move-result-object v0
                        return-object v0
                        :other
                        const/4 v0, 0x0
                        return-object v0
                    """))),
            clazz(expected, fields = listOf(field(expected, "name", STRING))),
            clazz(t("Root"), methods = listOf(method(t("Root"), "unwrap", listOf(json, expected), OBJECT, 7, static, """
                iget-object v4, p1, $expected->name:$STRING
                invoke-virtual { p0 }, $json->next()$token
                move-result-object v0
                sget-object v1, $token->FIELD:$token
                if-eq v0, v1, :name
                const-string v0, "$JSON_ROOT_FIELD"
                const/4 v0, 0x0
                throw v0
                :name
                invoke-virtual { p0 }, $json->name()$STRING
                move-result-object v3
                invoke-virtual { v4, v3 }, $STRING->equals($OBJECT)Z
                move-result v0
                if-nez v0, :matched
                const-string v0, "$JSON_ROOT_MISMATCH"
                const/4 v0, 0x0
                throw v0
                :matched
                invoke-virtual { p0 }, $json->next()$token
                return-object p0
            """))),
            // A bare Reader(String) call has no implementation proving a native JSON boundary.
            clazz(reader, methods = listOf(method(reader, "key", listOf(json), STRING, 3, static, keyHelper),
                method(reader, "text", listOf(json), STRING, 2, static,
                    "invoke-virtual { p0 }, $json->value()$STRING\nmove-result-object v0\nreturn-object v0"))),
            clazz(raw, flags = iface, methods = listOf(
                ImmutableMethod(raw, "original", emptyList(), STRING, iface, null, null, null),
                ImmutableMethod(raw, "gif", emptyList(), giphy, iface, null, null, null)) +
                listOfNotNull(photo?.let { ImmutableMethod(raw, "info", emptyList(), info, iface, null, null, null) })),
            clazz(t("TreeBase"), superclass = "Lcom/facebook/pando/TreeJNI;"),
            clazz(t("Pando"), superclass = t("TreeBase"), interfaces = listOf(raw), methods = listOf(
                method(t("Pando"), "original", emptyList(), STRING, 2, public,
                    "const v0, ${"text".hashCode()}\nconst/4 v0, 0x0\nreturn-object v0")) + if (photo == null) emptyList() else listOf(
                method(t("Pando"), "info", emptyList(), info, 2, public,
                    "const v0, ${photo.treeKey.hashCode()}\nconst/4 v0, 0x0\nreturn-object v0"),
                method(t("Pando"), "gif", emptyList(), giphy, 2, public,
                    "const v0, ${photo.gifKey.hashCode()}\nconst/4 v0, 0x0\nreturn-object v0"))),
            clazz(value, interfaces = listOf(raw), fields = listOf(field(value, "plain", STRING), field(value, "translation", STRING)) +
                listOfNotNull(photo?.let { field(value, "attached", info) }), methods = listOf(
                method(value, "original", emptyList(), STRING, 3, public, getterBody),
                constructor(value, listOf(STRING, STRING) + listOfNotNull(photo?.let { info }),
                    "iput-object p1, p0, $value->plain:$STRING\niput-object p2, p0, $value->translation:$STRING\n" +
                        (if (photo == null) "" else "iput-object p3, p0, $value->attached:$info\n") + "return-void")) +
                listOfNotNull(photo?.let { method(value, "info", emptyList(), info, 2, public, "iget-object v0, p0, $value->attached:$info\nreturn-object v0") })),
            clazz(view, fields = listOf(field(view, "raw", raw, photo?.rawFieldFlags ?: public), field(view, "original$salt", STRING, textFlags), field(view, "translated$salt", STRING)),
                methods = listOf(method(view, "<init>", listOf(raw, STRING, STRING), "V", 6, public, """
                    iput-object p1, p0, $view->raw:$raw
                    $originalAlias
                    iput-object v0, p0, $view->original$salt:$STRING
                    iput-object p3, p0, $view->translated$salt:$STRING
                    return-void
                """))),
            clazz(t("Parser"), methods = listOf(method(t("Parser"), "unsafeParseFromJson", listOf(json), OBJECT, if (photo == null) 5 else 7, static, """
                $parserRead
                move-result-object v2
                $photoRead
                new-instance v0, $value
                const-string v3, "translated"
                invoke-direct { v0, v2, v3${if (photo == null) "" else ", v4"} }, $value-><init>($STRING$STRING${if (photo == null) "" else info})V
                return-object v0
                :empty
                const/4 v0, 0x0
                return-object v0
            """))),
            clazz(t("Converter"), methods = listOf(conversion)),
            clazz(row, flags = rowTypeFlags, fields = listOf(field(row, "callback", FUNCTION)), methods = listOf(
                constructor(row, listOf(style, icon, label, FUNCTION), "iput-object p4, p0, $row->callback:$FUNCTION\nreturn-void", rowFlags))),
            clazz(style, flags = styleTypeFlags, fields = listOf(field(style, "color", "Ljava/lang/Integer;"))),
            clazz(normal, superclass = style, fields = listOf(field(normal, "INSTANCE", normal, static)), methods = listOf(
                method(normal, "<clinit>", emptyList(), "V", 2, static, """
                    const/4 v0, 0x0
                    new-instance v1, $normal
                    invoke-direct { v1 }, Ljava/lang/Object;-><init>()V
                    iput-object v0, v1, $style->color:Ljava/lang/Integer;
                    sput-object v1, $normal->INSTANCE:$normal
                    return-void
                """))),
            clazz(icon, methods = listOf(constructor(icon, listOf("I"), "return-void"))),
            clazz(label, flags = labelFlags, methods = listOf(constructor(label, listOf("I"), "return-void"))),
            clazz(controller, methods = listOf(
                method(controller, "select", listOf(STRING, STRING, "F", "Z"), "V", 8, public, """
                    const-string v0, "$COMMENT_SELECT"
                    const/4 v0, 0x0
                    invoke-static { v0, p1, p2 }, Ltest/Resolver;->find(Ltest/State;$STRING$STRING)$view
                    move-result-object v1
                    invoke-direct { p0 }, $controller->rows()Ljava/util/ArrayList;
                    return-void
                """),
                method(controller, "rows", emptyList(), "Ljava/util/ArrayList;", 7, AccessFlags.PRIVATE.value, """
                    const-string v0, "$COMMENT_ROWS"
                    sget-object v1, $normal->INSTANCE:$normal
                    new-instance v2, $icon
                    const v3, 0x7f080123
                    invoke-direct { v2, v3 }, $icon-><init>(I)V
                    new-instance v4, $label
                    const v3, 0x7f130456
                    invoke-direct { v4, v3 }, $label-><init>(I)V
                    const/4 v5, 0x0
                    new-instance v0, Ltest/ConcreteRow;
                    invoke-direct { v0, v1, v2, v4, v5 }, $row-><init>($style$icon$label$FUNCTION)V
                    return-object v0
                """))),
            clazz(renderer, fields = listOf(field(renderer, "popup", OBJECT)), methods = listOf(
                method(renderer, "show", listOf("Landroidx/fragment/app/Fragment;", view, if (wideState) "J" else "Ltest/State;", LIST, "F"), "V", if (wideState) 13 else 12, public, """
                    iget-object v0, p0, $renderer->popup:$OBJECT
                    ${if (guards) "if-nez v0, :done" else "nop"}
                    iget-object v0, p0, $renderer->popup:$OBJECT
                    ${if (guards) "if-nez v0, :done" else "nop"}
                    const/4 v2, 0x0
                    invoke-virtual { p1 }, Landroidx/fragment/app/Fragment;->requireContext()$context
                    move-result-object v3
                    invoke-static { ${if (wideState) "p5" else "p4"} }, Ltest/Rows;->size(Ljava/lang/Iterable;)I
                    move-result v0
                    const/4 v1, 0x0
                    new-instance v0, $wrapper
                    invoke-direct { v0, v2, v1 }, $wrapper-><init>(${OBJECT}I)V
                    check-cast v2, $row
                    :done
                    return-void
                """))),
            clazz(wrapper, methods = listOf(constructor(wrapper, listOf(OBJECT, "I"), "return-void"),
                method(wrapper, "click", emptyList(), "V", 2, public, """
                    const/4 v0, 0x0
                    check-cast v0, $row
                    iget-object v0, v0, $row->callback:$FUNCTION
                    invoke-interface { v0 }, $FUNCTION->invoke()$OBJECT
                    return-void
                """), method(wrapper, "dismiss", emptyList(), "Z", 2, public, "const/4 v0, $dismiss\nreturn v0"))),
            clazz(t("CopyText"), superclass = "Ltest/CopyBase;", methods = listOf(
                method(t("CopyText"), "toString", emptyList(), STRING, 2, public, "const-string v0, \"CopyText\"\nreturn-object v0"),
                method(t("CopyText"), "<init>", emptyList(), "V", 5, public, """
                    const/4 v0, 0x0
                    const v1, 0x7f080123
                    const v2, 0x7f130456
                    const/4 v3, 0x0
                    invoke-direct { p0, v0, v1, v2, v3 }, Ltest/CopyBase;-><init>(${OBJECT}IIZ)V
                    return-void
                """))),
            clazz("Ltest/CopyBase;", methods = listOf(constructor("Ltest/CopyBase;", listOf(OBJECT, "I", "I", "Z"), "return-void"))),
        ) + (photo?.let { photoParts(it, salt, json, reader) } ?: emptyList())
    }

    fun clazz(type: String, flags: Int = public, superclass: String = OBJECT,
                      interfaces: List<String> = emptyList(), fields: List<ImmutableField> = emptyList(),
                      methods: List<Method> = emptyList()) =
        ImmutableClassDef(type, flags, superclass, interfaces, null, null, fields, methods)
    fun method(type: String, name: String, parameters: List<String>, result: String,
                       registers: Int, flags: Int, body: String): Method =
        MutableMethod(ImmutableMethod(type, name, parameters.map { ImmutableMethodParameter(it, null, null) },
            result, flags, null, null, ImmutableMethodImplementation(registers, emptyList(), null, null))).apply {
            addInstructionsWithLabels(0, body.trimIndent())
        }.let(ImmutableMethod::of)

    /** Each class's superclass and every method's body, to show a refusal changed nothing. */
    fun snapshot(patch: BytecodePatchContext, types: Collection<String>) = types.associateWith { type ->
        patch.mutableClassDefBy(type).let { clazz -> clazz.superclass to clazz.methods.map { method ->
            method.toString() to (method.implementation?.registerCount to method.code().map { instruction ->
                listOf(instruction.opcode, instruction.reference(), instruction.arguments(),
                    (instruction as? OneRegisterInstruction)?.registerA,
                    (instruction as? TwoRegisterInstruction)?.registerB,
                    (instruction as? NarrowLiteralInstruction)?.narrowLiteral,
                    (instruction as? OffsetInstruction)?.codeOffset)
            })
        }.sortedBy { it.first } }
    }
    private fun photoParts(photo: PhotoWorld, salt: String, json: String, reader: String): List<ClassDef> {
        fun t(name: String) = "Ltest/$salt$name;"
        val info = t("Info")
        val infoValue = t("InfoValue")
        val infoTree = t("InfoTree")
        val infoParser = t("InfoParser")
        val mediaParser = t("MediaParser")
        val kind = t("Kind")
        val pool = t("Pool")
        fun field(owner: String, name: String, type: String, flags: Int = public) =
            ImmutableField(owner, name, type, flags, null, null, null)
        fun getter(owner: String, name: String, returns: String, key: String, flags: Int = public) =
            method(owner, name, emptyList(), returns, 2, flags, "const v0, ${key.hashCode()}\nconst/4 v0, 0x0\nreturn-object v0")
        val kindValue = when (photo.kindFlow) {
            "branch" -> "const/4 v2, 0x1\nif-eqz v1, :photo\nconst/4 v2, 0x2\n:photo"
            "computed" -> "const/4 v2, 0x0\nadd-int/lit8 v2, v2, 0x1"
            else -> "const/4 v2, 0x1"
        }
        val saveName = if (photo.pooledSave) """
            const/16 v0, 0x130
            invoke-static { v0 }, $pool->name(I)$STRING
            move-result-object v0
            return-object v0
        """ else "const-string v0, \"${photo.saveName}\"\nreturn-object v0"
        val saves = List(photo.saves) { index ->
            val save = t("Save$index")
            clazz(save, superclass = "Ltest/CopyBase;", methods = listOf(
                method(save, "toString", emptyList(), STRING, 2, public, saveName),
                method(save, "<init>", emptyList(), "V", 5, public, """
                    const/4 v0, 0x0
                    const v1, 0x7f080789
                    const v2, 0x7f130789
                    const/4 v3, 0x0
                    invoke-direct { p0, v0, v1, v2, v3 }, Ltest/CopyBase;-><init>(${OBJECT}IIZ)V
                    return-void
                """)))
        }
        // The app's string pool: a packed switch from each key straight to a returned constant.
        //   0 packed-switch p0, +10   3 const-string "Default"   5 return-object
        //   6 const-string <name>     8 return-object            9 nop   10 payload
        val pooled = ImmutableMethod(pool, "name", listOf(ImmutableMethodParameter("I", null, null)), STRING, static,
            null, null, ImmutableMethodImplementation(2, listOf(
                ImmutableInstruction31t(Opcode.PACKED_SWITCH, 1, 10),
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("Default")),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(photo.saveName)),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                ImmutableInstruction10x(Opcode.NOP),
                ImmutablePackedSwitchPayload(listOf(ImmutableSwitchElement(0x130, 6))),
            ), null, null))
        return saves + listOf(
            clazz(pool, methods = listOf(pooled)),
            clazz(info, flags = iface, methods = listOf(ImmutableMethod(info, "media", emptyList(), MEDIA, iface, null, null, null))),
            clazz(infoValue, interfaces = listOf(info), fields = listOf(field(infoValue, "media", MEDIA)), methods = listOf(
                method(infoValue, "media", emptyList(), MEDIA, 2, public, "iget-object v0, p0, $infoValue->media:$MEDIA\nreturn-object v0"),
                method(infoValue, "<init>", listOf(MEDIA), "V", 2, public, "iput-object p1, p0, $infoValue->media:$MEDIA\nreturn-void"))),
            clazz(infoTree, superclass = t("TreeBase"), interfaces = listOf(info), fields = listOf(field(infoTree, "cached", MEDIA)), methods = listOf(
                method(infoTree, "media", emptyList(), MEDIA, 2, public, "iget-object v0, p0, $infoTree->cached:$MEDIA\nreturn-object v0"),
                method(infoTree, "resolve", listOf(OBJECT), "V", 3, public,
                    "const v0, ${photo.treeMediaKey.hashCode()}\nconst/4 v0, 0x0\niput-object v0, p0, $infoTree->cached:$MEDIA\nreturn-void"))),
            clazz(t("Elsewhere"), methods = listOfNotNull(if (!photo.foreignMediaStore) null else method(t("Elsewhere"), "store",
                listOf(infoTree), "V", 2, static, "const/4 v0, 0x0\niput-object v0, p0, $infoTree->cached:$MEDIA\nreturn-void"))),
            clazz(infoParser, flags = public or AccessFlags.FINAL.value, fields = listOf(
                field(infoParser, "INSTANCE", infoParser, static or AccessFlags.FINAL.value)), methods = listOf(
                method(infoParser, "parse", listOf(json), OBJECT, 3, public,
                    "invoke-virtual { p0, p1 }, $infoParser->unsafeParseFromJson($json)$OBJECT\nmove-result-object v0\nreturn-object v0"),
                method(infoParser, "unsafeParseFromJson", listOf(json), OBJECT, 5, public, """
                    invoke-static { p1 }, $reader->key($json)$STRING
                    move-result-object v0
                    const-string v1, "${photo.mediaKey}"
                    invoke-virtual { v0, v1 }, $STRING->equals($OBJECT)Z
                    move-result v0
                    const/4 v2, 0x0
                    if-eqz v0, :build
                    invoke-static { p1 }, $mediaParser->read($json)$MEDIA
                    move-result-object v2
                    :build
                    new-instance v0, $infoValue
                    invoke-direct { v0, v2 }, $infoValue-><init>($MEDIA)V
                    return-object v0
                """))),
            clazz(mediaParser, methods = listOf(method(mediaParser, "read", listOf(json), MEDIA, 2, static, "const/4 v0, 0x0\nreturn-object v0"))),
            clazz(MEDIA, flags = public or AccessFlags.FINAL.value, methods = listOf(
                getter(MEDIA, "kind", INTEGER, "media_type", photo.kindFlags),
                getter(MEDIA, "gif", GIPHY, photo.mediaGifKey),
                getter(MEDIA, "versions", IMAGE_INFO, "image_versions2"),
                getter(MEDIA, "videos", "Ljava/util/List;", photo.videoKey),
                getter(MEDIA, "duration", "Ljava/lang/Double;", photo.durationKey),
                method(MEDIA, "isPhoto", emptyList(), "Z", 2, public, """
                    const v0, ${"media_type".hashCode()}
                    const/4 v0, 0x0
                    invoke-static { v0 }, $kind->of($INTEGER)$kind
                    const/4 v0, 0x0
                    return v0
                """))),
            clazz(kind, flags = public or AccessFlags.FINAL.value or AccessFlags.ENUM.value, superclass = "Ljava/lang/Enum;",
                fields = listOf(field(kind, "value", "I"), field(kind, "PHOTO", kind, static), field(kind, "VIDEO", kind, static)),
                methods = listOf(
                    method(kind, "<init>", listOf(STRING, "I", "I"), "V", 4, AccessFlags.PRIVATE.value,
                        "invoke-direct { p0, p1, p2 }, Ljava/lang/Enum;-><init>(${STRING}I)V\niput p3, p0, $kind->value:I\nreturn-void"),
                    method(kind, "of", listOf(INTEGER), kind, 2, static, "const/4 v0, 0x0\nreturn-object v0"),
                    method(kind, "<clinit>", emptyList(), "V", 4, static, """
                        const-string v0, "PHOTO"
                        const/4 v1, 0x0
                        $kindValue
                        new-instance v3, $kind
                        invoke-direct { v3, v0, v1, v2 }, $kind-><init>(${STRING}II)V
                        sput-object v3, $kind->PHOTO:$kind
                        const-string v0, "VIDEO"
                        const/4 v1, 0x1
                        const/4 v2, 0x2
                        new-instance v3, $kind
                        invoke-direct { v3, v0, v1, v2 }, $kind-><init>(${STRING}II)V
                        sput-object v3, $kind->VIDEO:$kind
                        return-void
                    """))),
            clazz(IMAGE_INFO, flags = iface, methods = listOf(
                ImmutableMethod(IMAGE_INFO, "candidates", emptyList(), LIST, iface, null, null, null))),
            clazz(PANDO_IMAGE_INFO, superclass = t("TreeBase"), interfaces = listOf(IMAGE_INFO), methods = listOf(
                getter(PANDO_IMAGE_INFO, "candidates", LIST, "candidates"))),
            clazz(IMAGE_URL, flags = iface, methods = listOf(
                ImmutableMethod(IMAGE_URL, "getUrl", emptyList(), STRING, iface, null, null, null),
                ImmutableMethod(IMAGE_URL, "getWidth", emptyList(), "I", iface, null, null, null),
                ImmutableMethod(IMAGE_URL, "getHeight", emptyList(), "I", iface, null, null, null))),
        )
    }
}

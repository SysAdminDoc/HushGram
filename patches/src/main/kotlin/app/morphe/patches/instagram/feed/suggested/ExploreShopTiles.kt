/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.explore.SECTIONS
import app.morphe.patches.instagram.explore.findExploreParser
import app.morphe.patches.instagram.feed.requireOneKindField
import app.morphe.patches.instagram.misc.analytics.stringLoadedAt
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference

private const val PATCH = "Hide suggested posts"

internal const val EXPLORE_SHOPPING = "$EXTENSION_PACKAGE/explore/ExploreShopping;"
internal const val EXPLORE_SECTION_FILTER = "$EXPLORE_SHOPPING->section(Ljava/lang/Object;)Ljava/lang/Object;"

/** The kind Instagram gives an Explore tile it reads from "shopping": a shop's tile, its posts as the cover. */
internal const val SHOP_TILE = "SHOPPING"

private const val ENUM = "Ljava/lang/Enum;"
private const val OBJECT = "Ljava/lang/Object;"

/**
 * Explore's sections, found: the parser building one section from Explore's JSON, the index and
 * register of its return of a section it built, the section's type, the section's field keeping its
 * content (the object holding its tiles, one per slot and in lists) and the tile's type. [write]
 * hands each section through ExploreShopping.section and fills the two stubs it reads a section
 * with.
 */
internal class ExploreShopSections(
    val parser: String,
    val parameters: List<String>,
    val returnAt: Int,
    val register: Int,
    val section: String,
    val content: FieldReference,
    val tile: String,
) {
    fun write(context: BytecodePatchContext) {
        val method = context.mutableClassDefBy(parser).methods.single {
            it.name == "unsafeParseFromJson" && it.parameterTypes.map(CharSequence::toString) == parameters
        }
        // At the return's own label, so a branch straight to it goes past the extension too.
        method.addInstructionsAtControlFlowLabel(
            returnAt,
            """
                invoke-static { v$register }, $EXPLORE_SECTION_FILTER
                move-result-object v$register
            """,
        )
        // Each body uses its parameter register alone and goes in ahead of the stub's own return,
        // which is then never reached. The section the hook hands over is always one of [section],
        // and content answers an Object.
        context.stub("content", listOf(OBJECT), OBJECT).addInstructions(
            0,
            """
                check-cast p0, $section
                iget-object p0, p0, ${content.definingClass}->${content.name}:${content.type}
                return-object p0
            """,
        )
        context.stub("isTile", listOf(OBJECT), "Z").addInstructions(
            0,
            """
                instance-of p0, p0, $tile
                return p0
            """,
        )
    }
}

private fun BytecodePatchContext.stub(name: String, parameters: List<String>, returns: String): MutableMethod =
    mutableClassDefBy(EXPLORE_SHOPPING).methods.singleOrNull {
        it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == returns &&
            it.parameterTypes.map(CharSequence::toString) == parameters
    } ?: throw PatchException("$PATCH: $EXPLORE_SHOPPING has no static $name(${parameters.joinToString("")})$returns")

/**
 * Finds Explore's sections from Explore's page parser (the one Hide the Explore grid empties): the
 * parser the page reads each of its sections with, right after the sections' key, is the section
 * parser, and its one return of a section it has just built is where the hook goes. Every reader of
 * that parser skips a null section, as it does one that didn't parse.
 *
 * The section's tiles are in one of its fields: the one whose class keeps fields of a class with an
 * enum field naming [SHOP_TILE], and that class is the tile's. Each is checked here, since the
 * extension casts to the section and the tile and reads the content field, so all three have to be
 * public. Anything else is an update this patch hasn't seen, and it stops.
 */
internal fun BytecodePatchContext.findExploreShopSections(): ExploreShopSections {
    val page = try {
        findExploreParser()
    } catch (moved: PatchException) {
        refuse("Explore's page parser can't be told (${moved.message})")
    }
    val pageMethod = classDefBy(page.type).methods.single {
        it.name == "unsafeParseFromJson" && it.parameterTypes.map(CharSequence::toString) == page.parameters
    }
    val pageCode = pageMethod.implementation!!.instructions.toList()
    val key = pageCode.indices.firstOrNull { stringLoadedAt(pageCode, it) == SECTIONS }
        ?: refuse("${page.type} doesn't read $SECTIONS")
    val stored = (key + 1 until pageCode.size).firstOrNull { at ->
        pageCode[at].opcode == Opcode.IPUT_OBJECT && pageCode[at].field() == page.sections.toString()
    } ?: refuse("${page.type} never keeps its sections")
    val parsers = (key + 1 until stored).mapNotNull { at ->
        val read = pageCode[at]
        if (read.opcode != Opcode.SGET_OBJECT) return@mapNotNull null
        val instance = (read as ReferenceInstruction).reference as FieldReference
        val call = (pageCode[at + 1] as? ReferenceInstruction)?.reference as? MethodReference
        instance.type.takeIf { it == instance.definingClass && call?.name == "parseFromJsonParser" }
    }.distinct()
    val parser = parsers.singleOrNull()
        ?: refuse("${page.type} reads its sections through ${parsers.size} parsers, expected one")

    val methods = classDefBy(parser).methods.filter {
        it.name == "unsafeParseFromJson" && it.parameterTypes.size == 1 && it.returnType == OBJECT
    }
    val method = methods.singleOrNull() ?: refuse("$parser has ${methods.size} unsafeParseFromJson(1)Object, expected one")
    val code = method.implementation!!.instructions.toList()
    val built = code.indices.filter { code.buildsAndReturns(it) }
    val returnAt = built.singleOrNull() ?: refuse("$parser returns a section it built ${built.size} times, expected once")
    val register = (code[returnAt] as OneRegisterInstruction).registerA
    if (register > 15) refuse("$parser keeps the section it returns in v$register, past v15")
    val section = ((code[returnAt - 2] as ReferenceInstruction).reference as TypeReference).type

    val sectionClass = classDefBy(section)
    val holders = sectionClass.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.flatMap { holder ->
        val content = classDefByOrNull(holder.type)?.takeIf { it.superclass != ENUM } ?: return@flatMap emptyList()
        content.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.map { it.type }.distinct()
            .filter { namesKind(it, SHOP_TILE) }.map { holder to it }
    }
    val (holder, tile) = holders.singleOrNull()
        ?: refuse("expected one field of $section keeping tiles of a kind named $SHOP_TILE, found ${holders.map { "${it.first.name}:${it.second}" }}")
    requireOneKindField(PATCH, tile, listOf(SHOP_TILE))
    if (!AccessFlags.PUBLIC.isSet(sectionClass.accessFlags) || !AccessFlags.PUBLIC.isSet(holder.accessFlags)) {
        refuse("$section or its field ${holder.name} isn't public, so the extension can't read it")
    }
    if (!AccessFlags.PUBLIC.isSet(classDefBy(tile).accessFlags)) refuse("$tile isn't public, so the extension can't test for it")
    return ExploreShopSections(
        parser, method.parameterTypes.map(CharSequence::toString), returnAt, register, section,
        ImmutableFieldReference(section, holder.name, holder.type), tile,
    )
}

/**
 * Whether the return at [at] hands back what the two instructions before it built: a new instance
 * of a class and that class's constructor on it.
 */
private fun List<Instruction>.buildsAndReturns(at: Int): Boolean {
    if (this[at].opcode != Opcode.RETURN_OBJECT || at < 2) return false
    val returned = (this[at] as OneRegisterInstruction).registerA
    val made = this[at - 2]
    if (made.opcode != Opcode.NEW_INSTANCE || (made as OneRegisterInstruction).registerA != returned) return false
    val type = ((made as ReferenceInstruction).reference as TypeReference).type
    val init = this[at - 1]
    val constructor = (init as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    val receiver = when (init) {
        is RegisterRangeInstruction -> init.startRegister
        is FiveRegisterInstruction -> init.registerC
        else -> return false
    }
    return (init.opcode == Opcode.INVOKE_DIRECT || init.opcode == Opcode.INVOKE_DIRECT_RANGE) &&
        constructor.name == "<init>" && constructor.definingClass == type && receiver == returned
}

/** Whether [type] has an instance field typed by an enum whose constants' names include [kind]. */
private fun BytecodePatchContext.namesKind(type: String, kind: String): Boolean =
    (classDefByOrNull(type)?.fields ?: emptyList()).filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.any { field ->
        val enum = classDefByOrNull(field.type)?.takeIf { it.superclass == ENUM } ?: return@any false
        enum.methods.filter { it.name == "<clinit>" }.any { it.loads(kind) }
    }

private fun Method.loads(string: String): Boolean = implementation?.instructions?.any {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
} == true

private fun Instruction.field(): String? = ((this as? ReferenceInstruction)?.reference as? FieldReference)?.toString()

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: Explore's shop tiles: $detail")

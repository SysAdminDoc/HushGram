/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.banners

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.metaai.MuseBannerBuilder
import app.morphe.patches.instagram.metaai.findMuseBannerBuilder
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide profile banners"

internal const val PROFILE_BANNERS = "$EXTENSION_PACKAGE/profile/ProfileBanners;"
internal const val FILTER_BANNERS =
    "$PROFILE_BANNERS->filter(Ljava/util/List;Ljava/lang/String;Ljava/lang/Object;)Ljava/util/List;"

/** The extension's stubs the patch fills: a banner's kind, and the signed-in account's user ID. */
internal const val BANNER_TYPE_STUB = "bannerType"
internal const val VIEWER_ID_STUB = "viewerId"

/** The signed-in session and its user ID getter, both names Instagram keeps. */
internal const val BANNER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val GET_USER_ID = "getUserId"

private const val LIST = "Ljava/util/List;"
private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"
private const val INIT = "<init>"

/** The field every banner keeps its kind in: the banners' common class, and the field's name. */
internal class BannerKind(val base: String, val field: String) {
    val reference get() = "$base->$field:$STRING"
}

/**
 * Where the row of banners under a profile's bio is wrapped for the header: the method, the index of
 * the wrapper's new-instance, and the registers holding the list, the profile's user ID, and the
 * object whose [session] field is the signed-in session.
 */
internal class BannerRow(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val at: Int,
    val list: Int,
    val profileId: Int,
    val holder: Int,
    val session: FieldReference,
)

/**
 * Adds switches that take banners out of the row under the bio on other people's profiles, one per
 * kind (#627). Included in the default selection with each switch initially off, so the row stays
 * as Instagram builds it until the user picks a kind.
 *
 * Only the list the header draws the row from changes. The extension leaves out the kinds whose
 * switches are on and keeps the rest in their order, so nothing is left blank. Your own profile and
 * Edit featured keep every banner.
 */
@Suppress("unused")
val hideProfileBannersPatch = bytecodePatch(
    name = "Hide profile banners",
    description = "Adds switches that take the Threads badge, Facebook and WhatsApp links, the profile song, interests, " +
        "channel invites and other banners from under the bio on other people's profiles. Each starts off. Turn them " +
        "on in HushGram settings > Profiles.",
    default = true,
) {
    category("Profiles")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("profileBanners")
        val muse = museBuilder()
        val kind = findBannerKind(muse)
        val row = findBannerRow(muse)
        hideProfileBanners(kind, row)
        enableStatus("profileBanners")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun BytecodePatchContext.museBuilder(): MuseBannerBuilder =
    try {
        findMuseBannerBuilder()
    } catch (missing: PatchException) {
        refuse(missing.message.orEmpty())
    }

/**
 * Every banner in the row is a subclass of one abstract class whose constructor takes the banner's
 * ID and its kind as two strings, and keeps each in a field. The kind is an enum constant's name,
 * such as THREADS or FACEBOOK_PAGE. The Muse banner's builder (Hide Meta AI's, see
 * [findMuseBannerBuilder]) makes one of them, so the common class is the one its banner extends, and
 * the kind is the string argument it passes the constructor that's an enum constant's toString().
 * The constructor (or the chain of them up to the common class) says which field that lands in.
 *
 * Fails unless exactly one field of the common class takes the kind, and it and the class are public,
 * since the extension's stub reads it from outside Instagram's packages.
 */
internal fun BytecodePatchContext.findBannerKind(muse: MuseBannerBuilder): BannerKind {
    val banner = classDefByOrNull(muse.returnType) ?: refuse("${muse.returnType}, the Muse banner, isn't in this build")
    val base = banner.superclass?.takeIf { it != OBJECT }
        ?: refuse("${muse.returnType}, the Muse banner, extends no banner class")
    val baseClass = classDefByOrNull(base) ?: refuse("$base, the banners' class, isn't in this build")

    val builder = classDefBy(muse.type).methods.single {
        it.name == muse.name && it.returnType == muse.returnType && it.parameterTypes.map(CharSequence::toString) == muse.parameters
    }
    val code = builder.code()
    val fields = code.withIndex().flatMap { (at, call) ->
        val target = call.methodReference()
        if (call.opcode != Opcode.INVOKE_DIRECT && call.opcode != Opcode.INVOKE_DIRECT_RANGE) return@flatMap emptyList<FieldReference>()
        if (target == null || target.name != INIT || target.definingClass !in setOf(base, muse.returnType)) {
            return@flatMap emptyList<FieldReference>()
        }
        val parameters = target.parameterTypes.map(CharSequence::toString)
        val arguments = call.arguments()
        parameters.indices.filter { parameters[it] == STRING }.mapNotNull { index ->
            val register = arguments.getOrNull(1 + parameters.take(index).slots()) ?: return@mapNotNull null
            if (!holdsEnumName(code, at, register)) return@mapNotNull null
            fieldTaking(target.definingClass, parameters, index, 0)
        }
    }.distinctBy { "${it.definingClass}->${it.name}" }
    val field = fields.singleOrNull()
        ?: refuse("expected the Muse banner's builder to hand its kind to one string field, found ${fields.size}")
    if (field.definingClass != base) refuse("${field.definingClass}->${field.name} isn't kept by $base, the banners' class")
    if (!AccessFlags.PUBLIC.isSet(baseClass.accessFlags)) refuse("$base isn't public, so the extension can't reach it")
    val declared = baseClass.fields.singleOrNull { it.name == field.name && it.type == STRING }
        ?: refuse("$base has no string field ${field.name}")
    if (!AccessFlags.PUBLIC.isSet(declared.accessFlags) || AccessFlags.STATIC.isSet(declared.accessFlags)) {
        refuse("$base->${field.name} isn't a public instance field, so the extension can't read it")
    }
    return BannerKind(base, field.name)
}

/**
 * Whether [register] holds an enum constant's name at instruction [at]: its last write before it is
 * the answer of a toString() or name() whose receiver was last written by a read of a static field of
 * the field's own class, the shape of an enum constant.
 */
private fun holdsEnumName(code: List<Instruction>, at: Int, register: Int): Boolean {
    val answered = lastWrite(code, at, register) ?: return false
    if (code[answered].opcode != Opcode.MOVE_RESULT_OBJECT || answered == 0) return false
    val call = code[answered - 1]
    val named = call.methodReference() ?: return false
    if (call.opcode != Opcode.INVOKE_VIRTUAL || named.name !in setOf("toString", "name") ||
        named.parameterTypes.isNotEmpty() || named.returnType != STRING
    ) return false
    val receiver = call.arguments().firstOrNull() ?: return false
    val loaded = lastWrite(code, answered - 1, receiver) ?: return false
    val constant = code[loaded].fieldReference() ?: return false
    return code[loaded].opcode == Opcode.SGET_OBJECT && constant.type == constant.definingClass
}

/**
 * The string field the constructor [owner]<init>([parameters]) keeps its parameter [index] in,
 * following it up the constructors it hands it to, at most three deep. Null when it isn't kept.
 */
private fun BytecodePatchContext.fieldTaking(owner: String, parameters: List<String>, index: Int, depth: Int): FieldReference? {
    if (depth > 3) return null
    val constructor = classDefByOrNull(owner)?.methods?.singleOrNull {
        it.name == INIT && it.parameterTypes.map(CharSequence::toString) == parameters
    } ?: return null
    val registers = constructor.implementation?.registerCount ?: return null
    val self = registers - 1 - parameters.slots()
    val parameter = self + 1 + parameters.take(index).slots()
    for (instruction in constructor.code()) {
        if (instruction.opcode == Opcode.IPUT_OBJECT) {
            val put = instruction as TwoRegisterInstruction
            val field = instruction.fieldReference() ?: continue
            if (put.registerA == parameter && put.registerB == self && field.type == STRING) return field
        }
        val call = instruction.methodReference() ?: continue
        if (instruction.opcode != Opcode.INVOKE_DIRECT && instruction.opcode != Opcode.INVOKE_DIRECT_RANGE) continue
        if (call.name != INIT || call.definingClass == owner) continue
        val arguments = instruction.arguments()
        if (arguments.firstOrNull() != self) continue
        val passed = call.parameterTypes.map(CharSequence::toString)
        val at = passed.indices.firstOrNull { arguments.getOrNull(1 + passed.take(it).slots()) == parameter } ?: continue
        return fieldTaking(call.definingClass, passed, at, depth + 1)
    }
    return null
}

/**
 * The profile header's row of banners comes from one static method that also adds the Muse banner
 * through [findMuseBannerBuilder], and that answers its one result by wrapping the list in a new
 * object whose constructor takes the header's data and the list: `new-instance`, then
 * `<init>(header, List)`, then `return-object`. The header's data is made just before, and its
 * constructor takes one string, the profile's user ID. Of the methods calling the Muse builder, only
 * the row's builder has that ending (the other ordering of the list answers a bare ArrayList, and
 * Edit featured answers nothing).
 *
 * The signed-in session is the last [BANNER_SESSION] the method reads from a field before the
 * wrapping, from an object nothing replaces on the way there.
 *
 * Fails unless exactly one such method exists and its registers fit the hook, since that's an
 * update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findBannerRow(muse: MuseBannerBuilder): BannerRow {
    val rows = classesCalling(muse.type, muse.name, muse.parameters, muse.returnType).flatMap { classDef ->
        classDef.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.code().any { it.calls(muse) }
        }.mapNotNull { method -> rowEnding(method)?.let { method to it } }
    }
    val (method, ending) = rows.singleOrNull()
        ?: refuse("expected one method to wrap the row of profile banners, found ${rows.size}")
    val (at, list, profileId) = ending
    val code = method.code()

    val reads = (0 until at).filter { index ->
        code[index].opcode == Opcode.IGET_OBJECT && code[index].fieldReference()?.type == BANNER_SESSION
    }
    val read = reads.lastOrNull() ?: refuse("${method.definingClass}->${method.name} reads no $BANNER_SESSION before the row")
    val holder = (code[read] as TwoRegisterInstruction).registerB
    if ((read + 1 until at).any { code[it].writes(holder) }) {
        refuse("${method.definingClass}->${method.name} replaces v$holder between reading the session and the row")
    }
    if (method.jumpTargets().any { it in read + 1..at }) {
        refuse("${method.definingClass}->${method.name} jumps in between reading the session and the row")
    }
    for ((what, register) in listOf("list" to list, "profile ID" to profileId, "session holder" to holder)) {
        if (register > 15) refuse("${method.definingClass}->${method.name} keeps the row's $what in v$register, past v15")
    }
    if (classDefByOrNull(BANNER_SESSION)?.methods?.none {
            it.name == GET_USER_ID && it.parameterTypes.isEmpty() && it.returnType == STRING &&
                AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
        } != false
    ) {
        refuse("$BANNER_SESSION has no public $GET_USER_ID()")
    }
    return BannerRow(
        method.definingClass, method.name, method.parameterTypes.map(CharSequence::toString), method.returnType,
        at, list, profileId, holder, code[read].fieldReference()!!,
    )
}

/**
 * The index of the wrapper's new-instance and the registers of the list and the profile's user ID,
 * when [method] ends as the row's builder does, or null.
 */
private fun rowEnding(method: Method): Triple<Int, Int, Int>? {
    if (!method.returnType.startsWith("L") || method.returnType.startsWith("Ljava/")) return null
    val code = method.code()
    val returned = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }.singleOrNull() ?: return null
    if (returned < 3) return null
    val wrap = code[returned - 1]
    val wrapper = wrap.methodReference() ?: return null
    val takes = wrapper.parameterTypes.map(CharSequence::toString)
    if (wrap.opcode != Opcode.INVOKE_DIRECT || wrapper.name != INIT || wrapper.definingClass != method.returnType) return null
    if (takes.size != 2 || takes[1] != LIST || !takes[0].startsWith("L")) return null
    val (row, header, list) = wrap.arguments().takeIf { it.size == 3 } ?: return null
    if ((code[returned] as OneRegisterInstruction).registerA != row) return null
    val made = code[returned - 2]
    if (made.opcode != Opcode.NEW_INSTANCE || (made as OneRegisterInstruction).registerA != row ||
        ((made as ReferenceInstruction).reference as TypeReference).type != method.returnType
    ) return null
    val built = code[returned - 3]
    val headerInit = built.methodReference() ?: return null
    if (built.opcode != Opcode.INVOKE_DIRECT || headerInit.name != INIT || headerInit.definingClass != takes[0]) return null
    val arguments = built.arguments()
    if (arguments.firstOrNull() != header) return null
    val parameters = headerInit.parameterTypes.map(CharSequence::toString)
    val strings = parameters.indices.filter { parameters[it] == STRING }
    val id = strings.singleOrNull() ?: return null
    val profileId = arguments.getOrNull(1 + parameters.take(id).slots()) ?: return null
    if (profileId == list || profileId == row || list == row) return null
    return Triple(returned - 2, list, profileId)
}

/**
 * Fills the extension's two stubs, the banner's kind from [kind] and the user ID from the session,
 * and hands the row's list to [FILTER_BANNERS] just before it's wrapped, with the profile's user ID
 * and the session read again from where the method read it. The list the extension answers is the
 * one wrapped. One free local below v16 carries the session.
 */
internal fun BytecodePatchContext.hideProfileBanners(kind: BannerKind, row: BannerRow) {
    val stubs = mutableClassDefBy(PROFILE_BANNERS).methods
    fun stub(name: String) = stubs.singleOrNull {
        it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == STRING &&
            it.parameterTypes.map(CharSequence::toString) == listOf(OBJECT)
    } ?: refuse("$PROFILE_BANNERS has no static $name(Object)String")
    stub(BANNER_TYPE_STUB).addInstructions(
        0,
        """
            check-cast p0, ${kind.base}
            iget-object p0, p0, ${kind.reference}
            return-object p0
        """,
    )
    stub(VIEWER_ID_STUB).addInstructions(
        0,
        """
            check-cast p0, $BANNER_SESSION
            invoke-virtual { p0 }, $BANNER_SESSION->$GET_USER_ID()$STRING
            move-result-object p0
            return-object p0
        """,
    )

    val method = mutableClassDefBy(row.type).methods.single {
        it.name == row.name && it.returnType == row.returnType && it.parameterTypes.map(CharSequence::toString) == row.parameters
    }
    val session = method.freeLocalsAt(PATCH, row.at, 1, except = listOf(row.list, row.profileId, row.holder)).single()
    val field = row.session
    method.addInstructions(
        row.at,
        """
            iget-object v$session, v${row.holder}, ${field.definingClass}->${field.name}:${field.type}
            invoke-static { v${row.list}, v${row.profileId}, v$session }, $FILTER_BANNERS
            move-result-object v${row.list}
        """,
    )
}

/** The registers these parameter types take: two for a long or a double, one for the rest. */
private fun List<String>.slots(): Int = sumOf { type: String -> if (type == "J" || type == "D") 2 else 1 }

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.arguments(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val set = (this as? OneRegisterInstruction)?.registerA ?: return false
    return set == register || (opcode.setsWideRegister() && set + 1 == register)
}

private fun Instruction.calls(builder: MuseBannerBuilder): Boolean {
    val called = methodReference() ?: return false
    return called.definingClass == builder.type && called.name == builder.name && called.returnType == builder.returnType &&
        called.parameterTypes.map(CharSequence::toString) == builder.parameters
}

/** The last instruction before [before] that writes [register], or null. */
private fun lastWrite(code: List<Instruction>, before: Int, register: Int): Int? =
    (before - 1 downTo 0).firstOrNull { code[it].writes(register) }

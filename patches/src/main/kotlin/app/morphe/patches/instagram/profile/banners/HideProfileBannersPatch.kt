/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.banners

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.metaai.MuseBannerBuilder
import app.morphe.patches.instagram.metaai.findMuseBannerBuilder
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesAccessing
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.profile.friendship.ProfileUser
import app.morphe.patches.instagram.profile.friendship.USER
import app.morphe.patches.instagram.profile.friendship.VIEW_MODEL
import app.morphe.patches.instagram.profile.friendship.findProfileUser
import app.morphe.patches.instagram.profile.friendship.instanceFields
import app.morphe.patches.instagram.stories.seen.hashedIdGetters
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

/** The trace section Instagram names the classic binder of a profile's links row with. */
internal const val BIND_LINKS = "ProfileLinksViewBinder.bindLinksButton"
internal const val FILTER_LINKS = "$PROFILE_BANNERS->links(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
internal const val PROFILE_LINKS_STATUS = "profileLinks"

/** The extension's stubs for Hide links: the profile's user ID and the signed-in one, each from the header. */
internal const val LINKS_PROFILE_ID_STUB = "linksProfileId"
internal const val LINKS_VIEWER_ID_STUB = "linksViewerId"

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
 *
 * The row of links under the bio (a website, or a business's links) is read on its own, so Hide
 * links hooks where the header reads it (see [findProfileLinks]). A build where that has moved goes
 * in with the banner switches only, and settings don't offer Hide links.
 */
@Suppress("unused")
val hideProfileBannersPatch = bytecodePatch(
    name = "Hide profile banners",
    description = "Adds switches that take the Threads badge, Facebook and WhatsApp links, the profile song, interests, " +
        "channel invites, the row of links and other banners from under the bio on other people's profiles. Each " +
        "starts off. Turn them on in HushGram settings > Profiles.",
    default = true,
) {
    category("Profiles")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("profileBanners")
        requireStatusMethod(PROFILE_LINKS_STATUS)
        val muse = museBuilder()
        val kind = findBannerKind(muse)
        val row = findBannerRow(muse)
        val links = profileLinksOrWarn()
        hideProfileBanners(kind, row)
        if (links != null) {
            hideProfileLinks(links)
            enableStatus(PROFILE_LINKS_STATUS)
        }
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

/** The extension's static [name](Object)String stub, to be filled. */
private fun BytecodePatchContext.stub(name: String) = mutableClassDefBy(PROFILE_BANNERS).methods.singleOrNull {
    it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == STRING &&
        it.parameterTypes.map(CharSequence::toString) == listOf(OBJECT)
} ?: refuse("$PROFILE_BANNERS has no static $name(Object)String")

/**
 * Where a profile's header reads its row of links for drawing: the method, the index of the
 * instance-of asking whether the header has links, the register holding them and the field they're
 * read from, and the header parameter with its fields of the view model and the signed-in session,
 * the view model's way to the profile's user, and the user model's getter of its ID.
 */
internal class ProfileLinks(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val at: Int,
    val links: Int,
    val field: FieldReference,
    val header: Int,
    val headerType: String,
    val viewModel: String,
    val session: String,
    val user: ProfileUser,
    val userId: String,
)

/**
 * The row of links under a bio (a website, or a business's links) is one field of the header's
 * data, and the header draws it in Compose whenever there are any: the classic binder, the one
 * method naming its trace section [BIND_LINKS], only runs for a profile with none, and it fails on a
 * missing value, so it's no place to take them away. That binder says which field and which kind of
 * links to look for: the first instance-of after the section's name asks it of a register last
 * written by reading the field. The one place outside the binder reading that field straight into an
 * instance-of of the same kind is where the Compose header decides whether to draw the row, and an
 * empty answer there draws none.
 *
 * The hook goes in front of that instance-of and reads the method's one parameter whose class keeps
 * one [VIEW_MODEL] and one [BANNER_SESSION], the profile screen's header, from which the stubs read
 * the profile's user ID (through [findProfileUser] and the user model's ID getter that hashCode()
 * asks) and the signed-in one.
 *
 * Fails when any of it isn't there, isn't one, or doesn't fit the hook, since that's an update this
 * patch hasn't seen.
 */
internal fun BytecodePatchContext.findProfileLinks(): ProfileLinks {
    val (field, model) = findLinksField()

    // The binder itself asks the same question, and is never the Compose row.
    val readers = classesAccessing(field.definingClass, field.name, Opcode.IGET_OBJECT).flatMap { classDef ->
        classDef.methods.filterNot { loadsString(it, BIND_LINKS) }.flatMap { method -> linksReadsIn(method, field, model).map { method to it } }
    }
    val (method, at) = readers.singleOrNull()
        ?: refuse("expected one place reading ${field.definingClass}->${field.name} into an instance-of $model, found ${readers.size}")
    return profileLinksAt(method, at, field)
}

/** The field of the header's data keeping its links, and the kind of links the binder asks it for. */
internal fun BytecodePatchContext.findLinksField(): Pair<FieldReference, String> {
    val reads = classesLoadingString(BIND_LINKS).flatMap { classDef ->
        classDef.methods.filter { loadsString(it, BIND_LINKS) }.mapNotNull { binder ->
            val code = binder.code()
            val named = code.indices.firstOrNull { stringLoadedAt(code, it) == BIND_LINKS } ?: return@mapNotNull null
            val asked = (named + 1 until code.size).firstOrNull { code[it].opcode == Opcode.INSTANCE_OF } ?: return@mapNotNull null
            val read = uncastWrite(code, asked, (code[asked] as TwoRegisterInstruction).registerB) ?: return@mapNotNull null
            val field = code[read].fieldReference()?.takeIf { code[read].opcode == Opcode.IGET_OBJECT } ?: return@mapNotNull null
            field to ((code[asked] as ReferenceInstruction).reference as TypeReference).type
        }
    }.distinctBy { (field, model) -> "${field.definingClass}->${field.name}:${field.type} $model" }
    return reads.singleOrNull()
        ?: refuse("expected the method loading $BIND_LINKS to ask one field what its links are, found ${reads.size}")
}

/** The indexes in [method] of each instance-of asking whether what was just read from [field] is a [model]. */
internal fun linksReadsIn(method: Method, field: FieldReference, model: String): List<Int> {
    val code = method.code()
    return code.indices.filter { at ->
        val get = code[at]
        val ask = code.getOrNull(at + 1)
        val read = get.fieldReference()
        get.opcode == Opcode.IGET_OBJECT && read != null && read.definingClass == field.definingClass && read.name == field.name &&
            ask?.opcode == Opcode.INSTANCE_OF && (ask as TwoRegisterInstruction).registerB == (get as TwoRegisterInstruction).registerA &&
            ((ask as ReferenceInstruction).reference as TypeReference).type == model
    }.map { it + 1 }
}

/** The hook's place in [method] at the instance-of [at] asking about [field]'s links, checked against everything it needs. */
private fun BytecodePatchContext.profileLinksAt(method: Method, at: Int, field: FieldReference): ProfileLinks {
    val where = "${method.definingClass}->${method.name}"
    val code = method.code()
    val links = (code[at] as TwoRegisterInstruction).registerB
    if (links > 15) refuse("$where keeps the profile's links in v$links, past v15")
    if (at in method.jumpTargets()) refuse("$where jumps onto the instance-of asking for the profile's links")

    val headers = method.parameterTypes.withIndex().mapNotNull { (index, parameter) ->
        val classDef = classDefByOrNull(parameter.toString()) ?: return@mapNotNull null
        val viewModel = classDef.instanceFields(VIEW_MODEL).singleOrNull() ?: return@mapNotNull null
        val session = classDef.instanceFields(BANNER_SESSION).singleOrNull() ?: return@mapNotNull null
        Triple(index, classDef, viewModel to session)
    }
    val (index, headerClass, kept) = headers.singleOrNull()
        ?: refuse("expected one parameter of $where keeping a $VIEW_MODEL and a $BANNER_SESSION, found ${headers.size}")
    method.requireParameterIntact(PATCH, index, listOf(at))

    val user = findProfileUser(::refuse)
    val userClass = classDefByOrNull(USER) ?: refuse("$USER isn't in this build")
    val hashCode = userClass.methods.singleOrNull {
        it.name == "hashCode" && it.parameterTypes.isEmpty() && it.returnType == "I" && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("$USER has no hashCode()")
    val idGetters = hashedIdGetters(userClass.methods, hashCode)
    val userId = idGetters.singleOrNull()
        ?: refuse("expected $USER's hashCode() to ask one getter of its ID, found $idGetters")
    userClass.methods.singleOrNull {
        it.name == userId && it.parameterTypes.isEmpty() && it.returnType == STRING &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("$USER->$userId() isn't a public getter, so the extension can't ask it")
    // The stubs reach these from the extension, outside Instagram's packages.
    val viewModelClass = classDefByOrNull(VIEW_MODEL) ?: refuse("$VIEW_MODEL isn't in this build")
    val sessionClass = classDefByOrNull(BANNER_SESSION) ?: refuse("$BANNER_SESSION isn't in this build")
    listOf(headerClass, viewModelClass, userClass, sessionClass).forEach { reachable ->
        if (!AccessFlags.PUBLIC.isSet(reachable.accessFlags)) refuse("${reachable.type} isn't public, so the extension can't reach it")
    }

    fun FieldReference.smali() = "$definingClass->$name:$type"
    return ProfileLinks(
        method.definingClass, method.name, method.parameterTypes.map(CharSequence::toString), method.returnType,
        at, links, field, method.parameterRegisterNumber(index), headerClass.type,
        kept.first.smali(), kept.second.smali(), user, userId,
    )
}

/**
 * [findProfileLinks], or null with a warning when the links row has moved. Hide profile banners then
 * goes in with its banner switches only, and settings don't offer Hide links.
 */
internal fun BytecodePatchContext.profileLinksOrWarn(): ProfileLinks? = try {
    findProfileLinks()
} catch (moved: PatchException) {
    patchLog.warning("${moved.message}. $PATCH goes in without Hide links.")
    null
}

/**
 * Fills the two Hide links stubs, the profile's user ID and the signed-in one, each read from the
 * header, and hands the links to [FILTER_LINKS] with the header right before the instance-of asks
 * about them. What the extension answers is cast back to the field's type and asked instead, so a
 * null draws no row. One free local below v16 carries the header.
 */
internal fun BytecodePatchContext.hideProfileLinks(found: ProfileLinks) {
    // Each way out returns on its own, as FriendshipStatusPatch's stubs do: joined at one return, what
    // p0 held on each path merges, and ART can reject the class for it.
    stub(LINKS_PROFILE_ID_STUB).addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${found.headerType}
            iget-object p0, p0, ${found.viewModel}
            if-eqz p0, :none
            ${found.user.load("p0")}
            if-eqz p0, :none
            invoke-virtual { p0 }, $USER->${found.userId}()$STRING
            move-result-object p0
            return-object p0
            :none
            const/4 p0, 0x0
            return-object p0
        """,
    )
    stub(LINKS_VIEWER_ID_STUB).addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${found.headerType}
            iget-object p0, p0, ${found.session}
            if-eqz p0, :none
            invoke-virtual { p0 }, $BANNER_SESSION->$GET_USER_ID()$STRING
            move-result-object p0
            return-object p0
            :none
            const/4 p0, 0x0
            return-object p0
        """,
    )

    val method = mutableClassDefBy(found.type).methods.single {
        it.name == found.name && it.returnType == found.returnType && it.parameterTypes.map(CharSequence::toString) == found.parameters
    }
    val header = method.freeLocalsAt(PATCH, found.at, 1, except = listOf(found.links)).single()
    method.addInstructions(
        found.at,
        """
            move-object/from16 v$header, v${found.header}
            invoke-static { v${found.links}, v$header }, $FILTER_LINKS
            move-result-object v${found.links}
            check-cast v${found.links}, ${found.field.type}
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

/**
 * Like [lastWrite], looking past any check-cast of [register] itself to the write that put the value
 * there. Some 454 builds cast the links before the binder asks about them.
 */
private fun uncastWrite(code: List<Instruction>, before: Int, register: Int): Int? {
    var at = lastWrite(code, before, register) ?: return null
    while (code[at].opcode == Opcode.CHECK_CAST) at = lastWrite(code, at, register) ?: return null
    return at
}

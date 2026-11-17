/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.blocked

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesAccessing
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

internal const val BLOCKED_SEARCH_PATCH = "Search blocked accounts"
internal const val BLOCKED_SEARCH_STATUS = "blockedSearch"
internal const val BLOCKED_SEARCH = "$EXTENSION_PACKAGE/misc/BlockedSearch;"
internal const val BLOCKED_FILTER = "$BLOCKED_SEARCH->filter(Ljava/util/List;)Ljava/util/List;"
internal const val BLOCKED_ATTACH = "$BLOCKED_SEARCH->attach(Ljava/lang/Object;Landroid/view/View;Landroid/view/View;)V"

/** What the blocked list's model factory calls itself, in its class's own name field. */
internal const val BLOCKED_FACTORY_NAME = "GlobalBlocksViewModelFactory"

/** The start of the path the blocked list's pages come from, which only its page loader asks for. */
internal const val BLOCKED_LIST_PATH = "users/blocked_list/"

/** The keys an account's JSON reader looks for before it writes the full name and the id. */
internal const val FULL_NAME_KEY = "full_name"
internal const val USER_ID_KEY = "user_id"
internal const val USERNAME_KEY = "username"

/** The bits the pages bridge answers, the same as BlockedSearch.MORE, LOADING and FAILED. */
internal const val PAGES_MORE = 1
internal const val PAGES_LOADING = 2
internal const val PAGES_FAILED = 4

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"
private const val ITERATOR = "Ljava/util/Iterator;"
private const val VIEW = "Landroid/view/View;"
private const val BUNDLE = "Landroid/os/Bundle;"
private const val RECYCLER_VIEW = "Landroidx/recyclerview/widget/RecyclerView;"

/** A bridge stub in the extension and the body the patch writes into it, in [registers] registers. */
internal class BlockedBridge(val name: String, val returns: String, val registers: Int, val body: String)

/** Everything the patch changes, found and checked before any of it is changed. */
internal class BlockedSearchTargets(
    /** The blocked list's model build, which takes the list's state and the accounts loaded so far. */
    val build: MutableMethod,
    /** Where [build] starts walking the accounts: the filter goes in front, taking its label. */
    val iterator: Int,
    /** The register [build] keeps the accounts in, its list parameter's. */
    val list: Int,
    /** The account class and its user name and full name fields, which the matching reads. */
    val item: String,
    val username: FieldReference,
    val fullName: FieldReference,
    /** The blocked list's screen and its onViewCreated, where the box goes in after it keeps its list. */
    val fragment: String,
    val created: MutableMethod,
    val attachAt: Int,
    val recycler: Int,
    /** The extension class, its bridge stubs and the bodies the patch writes into them. */
    val extension: MutableClass,
    val bridges: List<BlockedBridge>,
)

private fun refuse(why: String): Nothing = throw PatchException("$BLOCKED_SEARCH_PATCH: $why")

private fun Method.params() = parameterTypes.map(CharSequence::toString)

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.where() = "$definingClass->$name"

private fun Method.static() = AccessFlags.STATIC.isSet(accessFlags)

private fun Instruction.method() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.target() = (this as? OneRegisterInstruction)?.registerA

private fun Instruction.objectRegister() = (this as? TwoRegisterInstruction)?.registerB

private fun FieldReference.smali() = "$definingClass->$name:$type"

private fun MethodReference.smali() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun BytecodePatchContext.mutable(method: Method): MutableMethod {
    val types = method.params()
    return mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.returnType == method.returnType && it.params() == types
    }
}

/** The instruction before [at] that last wrote [register], reading straight up the code. */
private fun List<Instruction>.lastWrite(register: Int, at: Int): Int? =
    (at - 1 downTo 0).firstOrNull { this[it].opcode.setsRegister() && this[it].target() == register }

private fun invokeKind(call: Instruction, what: String): String = when (call.opcode) {
    Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE -> "invoke-virtual"
    Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE -> "invoke-interface"
    else -> refuse("$what is called with ${call.opcode}")
}

/**
 * Finds the blocked accounts list (#731) on its three sides and checks each of them.
 *
 * The model build, the one (state, List) method of the class naming itself
 * [BLOCKED_FACTORY_NAME], walks the accounts loaded so far into rows after it has decided between
 * its loading, empty and error screens; the filter goes on the list right where the walk starts,
 * so those screens still go by the whole list. The account class is what the walk casts each
 * entry to, and its JSON reader says which of its three text fields is the full name and which the
 * id, so the third is the user name.
 *
 * The page loader, the one method asking for [BLOCKED_LIST_PATH], first tests its state's loading
 * flag and then its more-pages flag, clears the failure flag and marks a page loading, and builds
 * the model from its factory, its state and a copy of the accounts its store holds, which it hands
 * its screen. The redraw bridge does those same steps, so a search shows at once; the pages bridge
 * reads the three flags, and the load more bridge calls the loader, which loads nothing while a
 * page is loading or when there are no more.
 *
 * The screen is the class the model is handed to. Its onViewCreated keeps the RecyclerView in a
 * field, and the box goes in right after that, with the screen, its root view and the list.
 */
internal fun BytecodePatchContext.findBlockedSearch(): BlockedSearchTargets {
    val extension = requireExtension()

    // The model build and the walk of its accounts.
    val factories = classesLoadingString(BLOCKED_FACTORY_NAME).filter { holder ->
        holder.methods.any { it.name == "<clinit>" && loadsString(it, BLOCKED_FACTORY_NAME) }
    }
    val factory = factories.singleOrNull()
        ?: refuse("expected one class naming itself $BLOCKED_FACTORY_NAME, found ${factories.size}")
    val builds = factory.methods.filter { method ->
        val types = method.params()
        !method.static() && method.implementation != null && types.size == 2 && types[0].startsWith("L") &&
            types[1] == LIST && method.returnType.startsWith("L") && method.returnType != LIST
    }
    val stock = builds.singleOrNull()
        ?: refuse("expected one model build in ${factory.type} taking a state and a list, found ${builds.size}")
    val build = mutable(stock)
    val code = build.code()
    val list = build.parameterRegisterNumber(1)
    val walks = code.indices.filter { at ->
        val call = code[at].method()
        (code[at].opcode == Opcode.INVOKE_INTERFACE || code[at].opcode == Opcode.INVOKE_INTERFACE_RANGE) &&
            call?.definingClass == LIST && call.name == "iterator" && call.parameterTypes.isEmpty() &&
            code[at].namedRegisters().firstOrNull() == list
    }
    val iterator = walks.singleOrNull() ?: refuse("${build.where()} walks its list ${walks.size} times, expected once")
    if (list > 255) refuse("${build.where()} keeps its list in v$list, past v255")
    build.requireParameterIntact(BLOCKED_SEARCH_PATCH, 1, listOf(iterator))

    // The account class, from the walk's cast.
    val walker = code.getOrNull(iterator + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT }?.target()
        ?: refuse("${build.where()} doesn't keep its list's iterator")
    val next = (iterator + 2 until code.size).firstOrNull { at ->
        val call = code[at].method()
        call?.definingClass == ITERATOR && call.name == "next" && code[at].namedRegisters().firstOrNull() == walker
    } ?: refuse("${build.where()} never takes an account from its list")
    val taken = code.getOrNull(next + 1)
    val cast = code.getOrNull(next + 2)
    if (taken?.opcode != Opcode.MOVE_RESULT_OBJECT || cast?.opcode != Opcode.CHECK_CAST || cast.target() != taken.target()) {
        refuse("${build.where()} doesn't cast what it takes from its list")
    }
    val item = ((cast as ReferenceInstruction).reference as TypeReference).type
    val (username, fullName) = accountNames(item)

    // The page loader: its state's flags, and the steps it takes to show the list.
    val starts = classesLoadingString(BLOCKED_LIST_PATH).flatMap { it.methods }.filter { method ->
        !method.static() && method.parameterTypes.isEmpty() && method.returnType == "V" && loadsString(method, BLOCKED_LIST_PATH)
    }
    val start = starts.singleOrNull() ?: refuse("expected one loader asking for \"$BLOCKED_LIST_PATH\", found ${starts.size}")
    val loader = start.definingClass
    val steps = start.code()
    val self = start.localRegisterCount()
    val stateType = stock.params()[0]
    val stateAt = steps.indices.firstOrNull { at ->
        steps[at].opcode == Opcode.IGET_OBJECT && steps[at].objectRegister() == self &&
            steps[at].field()?.let { it.definingClass == loader && it.type == stateType } == true
    } ?: refuse("${start.where()} doesn't read its list's state")
    val state = steps[stateAt].field()!!
    val held = steps[stateAt].target()!!
    val reads = mutableListOf<Int>()
    val writes = mutableListOf<Int>()
    var overwritten = steps.size
    for (at in stateAt + 1 until steps.size) {
        val step = steps[at]
        if (step.opcode == Opcode.IGET_BOOLEAN && step.objectRegister() == held) reads += at
        if (step.opcode == Opcode.IPUT_BOOLEAN && step.objectRegister() == held) writes += at
        if (step.opcode.setsRegister() && step.target() == held) {
            overwritten = at
            break
        }
    }
    fun tested(at: Int?, test: Opcode) =
        at != null && steps.getOrNull(at + 1)?.opcode == test && steps[at + 1].target() == steps[at].target()
    val loadingAt = reads.getOrNull(0)
    val moreAt = reads.getOrNull(1)
    if (!tested(loadingAt, Opcode.IF_NEZ)) refuse("${start.where()} doesn't first test whether a page is loading")
    if (!tested(moreAt, Opcode.IF_EQZ)) refuse("${start.where()} doesn't then test whether there are more pages")
    val loading = steps[loadingAt!!].field()!!
    val more = steps[moreAt!!].field()!!
    val failed = writes.firstOrNull()?.let { steps[it].field() } ?: refuse("${start.where()} doesn't clear its list's failure")
    if (writes.none { steps[it].field()?.smali() == loading.smali() }) refuse("${start.where()} doesn't mark a page loading")
    val flags = listOf(loading, more, failed)
    if (flags.map { it.smali() }.toSet().size != 3 || flags.any { it.definingClass != stateType || it.type != "Z" }) {
        refuse("${start.where()} doesn't keep three flags of its own in its list's state")
    }

    val renders = steps.indices.filter { steps[it].method()?.smali() == stock.smali() }
    val at = renders.singleOrNull() ?: refuse("${start.where()} builds its list's model ${renders.size} times, expected once")
    val made = steps[at]
    val buildKind = invokeKind(made, "the model build")
    val arguments = made.namedRegisters()
    if (arguments.size != 3 || arguments[1] != held || at > overwritten) refuse("${start.where()} doesn't build the model from its list's state")
    val copied = steps.getOrNull(at - 1)
    val copy = steps.getOrNull(at - 2)
    val items = steps.getOrNull(at - 3)
    val store = steps.getOrNull(at - 4)
    val copyCall = copy?.method()
    if (copied?.opcode != Opcode.MOVE_RESULT_OBJECT || copied.target() != arguments[2] || copy == null ||
        (copy.opcode != Opcode.INVOKE_STATIC && copy.opcode != Opcode.INVOKE_STATIC_RANGE) || copyCall == null ||
        copyCall.parameterTypes.size != 1 || copy.namedRegisters().size != 1 ||
        items?.opcode != Opcode.IGET_OBJECT || items.target() != copy.namedRegisters().single() ||
        store?.opcode != Opcode.IGET_OBJECT || store.target() != items.objectRegister() || store.objectRegister() != self
    ) {
        refuse("${start.where()} doesn't build the model from a copy of the accounts its store holds")
    }
    val storeField = store.field()!!
    val itemsField = items.field()!!
    if (storeField.definingClass != loader || itemsField.definingClass != storeField.type) {
        refuse("${start.where()} doesn't read the accounts from its own store")
    }
    val factoryAt = steps.lastWrite(arguments[0], at)
    val factoryField = factoryAt?.let { steps[it] }?.takeIf { read ->
        read.opcode == Opcode.IGET_OBJECT && read.objectRegister() == self &&
            read.field()?.let { it.definingClass == loader && it.type == factory.type } == true
    }?.field() ?: refuse("${start.where()} doesn't build the model with its own factory")
    val model = steps.getOrNull(at + 1)
    val shown = steps.getOrNull(at + 2)
    val show = shown?.method()
    if (model?.opcode != Opcode.MOVE_RESULT_OBJECT || show == null || show.returnType != "V" ||
        show.parameterTypes.map(CharSequence::toString) != listOf(stock.returnType) ||
        shown.namedRegisters().size != 2 || shown.namedRegisters()[1] != model.target()
    ) {
        refuse("${start.where()} doesn't hand the model straight to its screen")
    }
    val showKind = invokeKind(shown, "the screen's update")
    val fragment = show.definingClass

    // The screen and where it keeps its list.
    val screen = classDefByOrNull(fragment) ?: refuse("the blocked list's screen $fragment is missing")
    val loaderFields = screen.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == loader }
    val loaderField = loaderFields.singleOrNull() ?: refuse("$fragment keeps ${loaderFields.size} page loaders, expected one")
    val onViewCreated = screen.methods.singleOrNull {
        it.name == "onViewCreated" && !it.static() && it.params() == listOf(VIEW, BUNDLE) && it.returnType == "V"
    } ?: refuse("$fragment has no onViewCreated of its own")
    val created = mutable(onViewCreated)
    val createdCode = created.code()
    val keeps = createdCode.indices.filter { index ->
        createdCode[index].opcode == Opcode.IPUT_OBJECT && createdCode[index].objectRegister() == created.localRegisterCount() &&
            createdCode[index].field()?.let { it.definingClass == fragment && it.type == RECYCLER_VIEW } == true
    }
    val keep = keeps.singleOrNull() ?: refuse("${created.where()} keeps its RecyclerView ${keeps.size} times, expected once")
    val attachAt = keep + 1
    if (attachAt in created.jumpTargets()) refuse("something jumps to right after ${created.where()} keeps its RecyclerView")
    created.requireThisIntact(BLOCKED_SEARCH_PATCH, listOf(attachAt))
    created.requireParameterIntact(BLOCKED_SEARCH_PATCH, 0, listOf(attachAt))
    val recycler = createdCode[keep].target()!!
    if (maxOf(created.localRegisterCount(), created.parameterRegisterNumber(0), recycler) > 15) {
        refuse("${created.where()} keeps the screen, its view or its list past v15")
    }

    // The extension reaches all of these from another package.
    listOf(item, fragment, loader, factory.type, stateType, storeField.type).forEach { requirePublicClass(it) }
    listOf(username, fullName, loaderField, factoryField, state, storeField, itemsField, loading, more, failed).forEach { requirePublic(it) }
    listOf<MethodReference>(stock, show, start).forEach { requirePublic(it) }

    val bridges = listOf(
        BlockedBridge(
            "redraw", "V", 5,
            """
                check-cast p0, $fragment
                iget-object v0, p0, ${loaderField.smali()}
                if-eqz v0, :done
                iget-object v1, v0, ${factoryField.smali()}
                iget-object v2, v0, ${state.smali()}
                iget-object v3, v0, ${storeField.smali()}
                iget-object v3, v3, ${itemsField.smali()}
                invoke-static { v3 }, ${copyCall.smali()}
                move-result-object v3
                $buildKind { v1, v2, v3 }, ${stock.smali()}
                move-result-object v3
                $showKind { p0, v3 }, ${show.smali()}
                :done
                return-void
            """,
        ),
        BlockedBridge(
            "loadMore", "V", 2,
            """
                check-cast p0, $fragment
                iget-object v0, p0, ${loaderField.smali()}
                if-eqz v0, :done
                invoke-virtual { v0 }, ${start.smali()}
                :done
                return-void
            """,
        ),
        BlockedBridge(
            "pages", "I", 4,
            """
                check-cast p0, $fragment
                iget-object v0, p0, ${loaderField.smali()}
                if-eqz v0, :none
                iget-object v0, v0, ${state.smali()}
                if-eqz v0, :none
                const/4 v1, 0x0
                iget-boolean v2, v0, ${more.smali()}
                if-eqz v2, :loading
                or-int/lit8 v1, v1, $PAGES_MORE
                :loading
                iget-boolean v2, v0, ${loading.smali()}
                if-eqz v2, :failed
                or-int/lit8 v1, v1, $PAGES_LOADING
                :failed
                iget-boolean v2, v0, ${failed.smali()}
                if-eqz v2, :done
                or-int/lit8 v1, v1, $PAGES_FAILED
                :done
                return v1
                :none
                const/4 v1, -0x1
                return v1
            """,
        ),
        BlockedBridge("username", STRING, 1, readText(item, username)),
        BlockedBridge("fullName", STRING, 1, readText(item, fullName)),
    )
    return BlockedSearchTargets(
        build, iterator, list, item, username, fullName, fragment, created, attachAt, recycler, extension, bridges,
    )
}

private fun readText(item: String, field: FieldReference) = """
    check-cast p0, $item
    iget-object p0, p0, ${field.smali()}
    return-object p0
"""

/**
 * The account's user name and full name, by its JSON reader: the one method writing the account's
 * text fields that loads both [FULL_NAME_KEY] and [USER_ID_KEY]. Each text field it writes is
 * matched to the last key loaded before the write, and a write clears the key so it never carries
 * over to the next write. A key Redex asks a string pool for can't always be read here, so a write
 * may show the last readable key instead of its own. The full name and the id must each be written
 * to one field and only after their own key. The third text field is the user name: its key can come
 * from a pool (on 386214574 the last readable key before it is "interop_messaging_user_fbid"), so
 * it isn't required, but no other field may follow "username".
 */
private fun BytecodePatchContext.accountNames(item: String): Pair<FieldReference, FieldReference> {
    val account = classDefByOrNull(item) ?: refuse("the account class $item is missing")
    val texts = account.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == STRING }
    if (texts.size != 3) refuse("the account class $item has ${texts.size} text fields, expected a user name, a full name and an id")
    val names = texts.map { it.name }.toSet()
    fun writesText(instruction: Instruction) = instruction.opcode == Opcode.IPUT_OBJECT &&
        instruction.field()?.let { it.definingClass == item && it.name in names && it.type == STRING } == true
    val readers = names.flatMap { classesAccessing(item, it, Opcode.IPUT_OBJECT) }.distinctBy { it.type }
        .flatMap { it.methods }.filter { method ->
            method.code().any(::writesText) && loadsString(method, FULL_NAME_KEY) && loadsString(method, USER_ID_KEY)
        }
    val reader = readers.singleOrNull() ?: refuse("expected one reader of an account from its JSON, found ${readers.size}")
    val code = reader.code()
    val keys = names.associateWith { mutableSetOf<String?>() }
    var key: String? = null
    for (at in code.indices) {
        val loaded = stringLoadedAt(code, at)
        if (loaded != null) {
            key = loaded
        } else if (writesText(code[at])) {
            keys.getValue(code[at].field()!!.name) += key
            key = null
        }
    }
    fun after(json: String): String {
        val fields = keys.filterValues { json in it }.keys
        return fields.singleOrNull()?.takeIf { keys.getValue(it) == setOf(json) }
            ?: refuse("${reader.where()} doesn't write one text field of the account, and only it, after \"$json\"")
    }
    val full = after(FULL_NAME_KEY)
    val id = after(USER_ID_KEY)
    val user = (names - full - id).single()
    if (keys.getValue(user).isEmpty()) refuse("${reader.where()} never writes the account's $user")
    val named = keys.filterValues { USERNAME_KEY in it }.keys
    if (named.any { it != user }) refuse("${reader.where()} writes $named after \"$USERNAME_KEY\", not only the account's $user")
    return texts.single { it.name == user } to texts.single { it.name == full }
}

/** Throws unless the extension has the two hooks and the five bridge stubs, all public and static. */
private fun BytecodePatchContext.requireExtension(): MutableClass {
    val extension = classDefByOrNull(BLOCKED_SEARCH) ?: refuse("the extension has no $BLOCKED_SEARCH")
    val wanted = listOf(
        BLOCKED_FILTER.substringAfter("->"),
        BLOCKED_ATTACH.substringAfter("->"),
        "redraw($OBJECT)V",
        "loadMore($OBJECT)V",
        "pages($OBJECT)I",
        "username($OBJECT)$STRING",
        "fullName($OBJECT)$STRING",
    )
    for (signature in wanted) {
        if (extension.methods.none {
                "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == signature &&
                    AccessFlags.PUBLIC.isSet(it.accessFlags) && it.static()
            }
        ) refuse("the extension has no public static $signature")
    }
    return mutableClassDefBy(BLOCKED_SEARCH)
}

private fun BytecodePatchContext.requirePublicClass(type: String): ClassDef {
    val classDef = classDefByOrNull(type) ?: refuse("$type is missing")
    if (!AccessFlags.PUBLIC.isSet(classDef.accessFlags)) refuse("$type isn't public, so the extension can't reach it")
    return classDef
}

private fun BytecodePatchContext.requirePublic(field: FieldReference) {
    val declared = requirePublicClass(field.definingClass).fields.firstOrNull { it.name == field.name && it.type == field.type }
        ?: refuse("${field.definingClass} doesn't declare ${field.smali()}")
    if (!AccessFlags.PUBLIC.isSet(declared.accessFlags)) refuse("${field.smali()} isn't public, so the extension can't reach it")
}

private fun BytecodePatchContext.requirePublic(method: MethodReference) {
    val types = method.parameterTypes.map(CharSequence::toString)
    val declared = requirePublicClass(method.definingClass).methods.firstOrNull {
        it.name == method.name && it.returnType == method.returnType && it.params() == types
    } ?: refuse("${method.definingClass} doesn't declare ${method.smali()}")
    if (!AccessFlags.PUBLIC.isSet(declared.accessFlags)) refuse("${method.smali()} isn't public, so the extension can't reach it")
}

/**
 * Puts the filter on the build's accounts where its walk starts, taking the walk's label so the
 * branch past the empty test runs it too, hands the screen, its root view and its list to the
 * extension once onViewCreated keeps the list, and writes the five bridges. A compiled stub may
 * have only its parameter's register, so each body goes in a method of its own size.
 */
internal fun addBlockedSearch(targets: BlockedSearchTargets) {
    val list = "v${targets.list}"
    targets.build.addInstructionsAtControlFlowLabel(
        targets.iterator,
        """
            invoke-static/range { $list .. $list }, $BLOCKED_FILTER
            move-result-object $list
        """,
    )
    targets.created.addInstructions(targets.attachAt, "invoke-static { p0, p1, v${targets.recycler} }, $BLOCKED_ATTACH")
    for (bridge in targets.bridges) {
        val stub = targets.extension.methods.single {
            it.name == bridge.name && it.params() == listOf(OBJECT) && it.returnType == bridge.returns
        }
        val written = ImmutableMethod(
            stub.definingClass, stub.name, stub.parameters, stub.returnType, stub.accessFlags, null,
            stub.hiddenApiRestrictions, ImmutableMethodImplementation(bridge.registers, emptyList(), null, null),
        ).toMutable().apply { addInstructions(0, bridge.body) }
        targets.extension.methods.remove(stub)
        targets.extension.methods.add(written)
    }
}

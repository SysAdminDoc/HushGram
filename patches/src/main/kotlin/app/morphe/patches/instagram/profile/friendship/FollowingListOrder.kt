/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.friendship

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val ORDERED = "$FOLLOWING_LIST->ordered($OBJECT$OBJECT)$OBJECT"

/** The trace name a follow list's row builder loads once its accounts are on screen. */
internal const val FOLLOW_LIST_ADDED = "follow_list_users_added_to_view"

private const val LIST = "Ljava/util/List;"

/**
 * Where a follow list builds its rows from the accounts it has loaded (#40): the builder method, the
 * place right before it walks that list, the registers holding the list and the builder's `this`,
 * and the builder's field of the row binder.
 */
internal class FollowOrder(
    val type: String,
    val name: String,
    val parameters: List<String>,
    /** The instruction asking the list for its iterator, where the hook goes in front of. */
    val at: Int,
    val users: Int,
    val self: Int,
    /** The builder's field of the row binder, which knows the kind of list, its owner and the account signed in. */
    val binder: FieldReference,
)

/**
 * Finds the one method outside the extension that loads [FOLLOW_LIST_ADDED], the follow list state
 * holder's `(boolean, boolean)` row builder, which clears Instagram's rows and adds one for each
 * account it has loaded, so each page and each change builds them all again. In it:
 * - the loaded accounts, the one `List` register that Instagram asks whether it is empty right before
 *   it loads that name;
 * - the read of it, the one field read into that register that is asked for its iterator straight away
 *   and is the only way the register gets its value at the emptiness check. The hook goes right after
 *   the read, where no jump lands.
 *
 * The builder's one public field of the row binder's class ([findFollowRow]) is the binder.
 * Fails when any of them isn't there, or there's more than one, since that's an update this patch
 * hasn't seen, and when `this` might be written over before the hook.
 */
internal fun BytecodePatchContext.findFollowOrder(row: FollowRow): FollowOrder {
    val builders = mutableListOf<Pair<ClassDef, Method>>()
    val holders = classesHolding(FOLLOW_LIST_ADDED).mapTo(HashSet()) { it.type }
    classDefForEach { classDef ->
        if (classDef.type !in holders || classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.methods.filter { it.holdsString(FOLLOW_LIST_ADDED) }.forEach { builders += classDef to it }
    }
    val (builderClass, builder) = builders.singleOrNull()
        ?: refuse("expected one method loading \"$FOLLOW_LIST_ADDED\", found ${builders.size}")
    val where = "${builderClass.type}->${builder.name}"
    val shape = builder.parameterTypes.joinToString("", "(", ")") + builder.returnType
    if (AccessFlags.STATIC.isSet(builder.accessFlags) || !AccessFlags.PUBLIC.isSet(builder.accessFlags) || shape != "(ZZ)V") {
        refuse("$where isn't a public instance method (ZZ)V, the follow list's row builder")
    }
    val code = builder.implementation!!.instructions.toList()

    val loads = code.indices.filter { code[it].loadsString(FOLLOW_LIST_ADDED) }
    val named = loads.singleOrNull() ?: refuse("expected $where to load $FOLLOW_LIST_ADDED once, found ${loads.size}")
    val empty = (named - 1 downTo maxOf(0, named - 8)).firstOrNull { code[it].callsList("isEmpty") }
        ?: refuse("$where doesn't ask its accounts whether there are none right before $FOLLOW_LIST_ADDED")
    val users = code[empty].namedRegisters().first()

    val reads = code.indices.filter { at ->
        val read = code[at]
        at < empty && read.opcode == Opcode.IGET_OBJECT && (read as TwoRegisterInstruction).registerA == users &&
            read.fieldReference()?.type == LIST && code.getOrNull(at + 1)?.let { it.callsList("iterator") && it.namedRegisters().first() == users } == true &&
            builder.holdsAt(users, at, empty)
    }
    val read = reads.singleOrNull()
        ?: refuse("expected $where to read its accounts into one register that it asks for its iterator and for being empty, found ${reads.size}")
    val at = read + 1
    if (at in builder.jumpTargets()) refuse("$where has no place right after reading its accounts that only that read leads to")

    val self = builder.localRegisterCount()
    if (maxOf(users, self) > 15) refuse("$where keeps its accounts or this past v15")
    builder.requireThisIntact(PATCH, listOf(at))

    val binders = builderClass.fields.filter {
        it.type == row.type && !AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
    }
    val binder = binders.singleOrNull()
        ?: refuse("expected ${builderClass.type} to keep one public ${row.type}, the row binder, found ${binders.size}")
    // The stubs reach these from the extension, outside Instagram's packages.
    if (!AccessFlags.PUBLIC.isSet(builderClass.accessFlags)) refuse("${builderClass.type} isn't public, so the extension can't reach it")

    return FollowOrder(
        builderClass.type, builder.name, builder.parameterTypes.map(CharSequence::toString), at, users, self,
        binder,
    )
}

private fun Instruction.callsList(name: String): Boolean {
    val called = methodReference() ?: return false
    return (opcode == Opcode.INVOKE_INTERFACE || opcode == Opcode.INVOKE_INTERFACE_RANGE) && called.definingClass == LIST &&
        called.name == name && called.parameterTypes.isEmpty()
}

/**
 * Right where the builder asks its loaded accounts for an iterator, hands them and `this` to
 * [ORDERED] and walks what it answers instead, typed back to a `List`.
 */
internal fun BytecodePatchContext.orderFollowRows(found: FollowOrder) {
    val method = mutableClassDefBy(found.type).methods.single {
        it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
    }
    method.addInstructions(
        found.at,
        """
            invoke-static { v${found.users}, v${found.self} }, $ORDERED
            move-result-object v${found.users}
            check-cast v${found.users}, $LIST
        """,
    )
}

/** Fills the stubs reaching the builder from the extension: its row binder, and building its rows again. */
internal fun FollowingStubs.fillOrder(found: FollowOrder) {
    orderBinder.addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${found.type}
            iget-object p0, p0, ${found.binder}
            return-object p0
        """,
    )
    // The stub's own registers are its three parameters, so the plain invoke names them.
    rebuildRows.addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${found.type}
            invoke-virtual { p0, p1, p2 }, ${found.type}->${found.name}(ZZ)V
            return-void
        """,
    )
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.extension

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * A fingerprint's legacy strings list makes the patcher test every string in the app for each entry,
 * once per fingerprint, and each entry needs a load of its own whose literal contains it. A fingerprint
 * can find its classes by one string() filter through the patcher's string index instead, and check its
 * other strings in its custom block with these, when that anchor is loaded exactly as written, sits
 * inside no longer literal, and no literal the method loads holds two of the fingerprint's strings.
 * These read the method's own instructions, not the Redex string pool loadsString(method, value) follows.
 */

/** Whether this method's code loads exactly [value]. */
internal fun Method.loadsConstString(value: String) = stringsLoaded().any { it == value }

/** Whether this method's code loads a string with [part] anywhere in it, the substring test a legacy strings entry makes. */
internal fun Method.loadsConstStringContaining(part: String) = stringsLoaded().any { part in it }

private fun Method.stringsLoaded(): Sequence<String> =
    implementation?.instructions?.asSequence().orEmpty().mapNotNull { instruction ->
        if (instruction.opcode != Opcode.CONST_STRING && instruction.opcode != Opcode.CONST_STRING_JUMBO) null
        else ((instruction as ReferenceInstruction).reference as StringReference).string
    }

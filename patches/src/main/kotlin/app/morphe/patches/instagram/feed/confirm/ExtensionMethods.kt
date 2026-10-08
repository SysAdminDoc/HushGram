/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.confirm

import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method

/** The public static method [descriptor] names, written `Lclass;->name(parameters)returns`, or null. */
internal fun ClassDef.publicStatic(descriptor: String): Method? = methods.singleOrNull {
    "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == descriptor &&
        AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
}

/** The public instance method [name] taking [parameters] and answering [returns], or null. */
internal fun ClassDef.publicInstance(name: String, parameters: List<String>, returns: String): Method? = methods.singleOrNull {
    it.name == name && it.returnType == returns && it.parameterTypes.map(Any::toString) == parameters &&
        AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
}

/**
 * The extension's static stub [name] taking [parameters] and answering [returns], with at least
 * [registers] registers for the body the patch writes, or null.
 */
internal fun MutableClass.staticStub(name: String, parameters: List<String>, returns: String, registers: Int): MutableMethod? =
    methods.singleOrNull {
        it.name == name && it.returnType == returns && it.parameterTypes.map(Any::toString) == parameters &&
            AccessFlags.STATIC.isSet(it.accessFlags) && (it.implementation?.registerCount ?: 0) >= registers
    }

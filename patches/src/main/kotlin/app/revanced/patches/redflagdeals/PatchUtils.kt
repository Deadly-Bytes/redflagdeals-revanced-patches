package app.revanced.patches.redflagdeals

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException

internal fun BytecodePatchContext.requireSingleMethod(
    label: String,
    definingClass: String,
    name: String,
    returnType: String,
    vararg parameterTypes: String,
): MutableMethod {
    val matches = classDefs.asSequence()
        .flatMap { it.methods.asSequence() }
        .filter {
            it.definingClass == definingClass &&
                it.name == name &&
                it.returnType == returnType &&
                it.parameterTypes.toList() == parameterTypes.toList()
        }
        .toList()

    if (matches.size != 1) {
        throw PatchException("$label fingerprint expected exactly one match, found ${matches.size}")
    }

    return firstMethod(matches.single())
}

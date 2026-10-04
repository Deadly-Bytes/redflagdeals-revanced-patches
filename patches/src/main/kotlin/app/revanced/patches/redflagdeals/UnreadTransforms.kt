package app.revanced.patches.redflagdeals

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.string
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val YID_ADAPTER = "Lcom/ypg/rfdapilib/api/adapter/YidAdapter;"
private const val YID_ACCOUNT_MANAGER = "Lcom/ypg/rfdapilib/auth/YidAccountManager;"
private const val API_REQUEST = "Lcom/ypg/rfdapilib/api/ApiRequest;"
private const val API_RESPONSE_LISTENER = "Lcom/ypg/rfdapilib/api/ApiResponseListener;"
private const val TOPIC_LIST_REQUESTS =
    "Lapp/revanced/extension/redflagdeals/TopicListRequests;"

/** Bypass shared topic-list responses while retaining the app's authenticated request path. */
internal fun BytecodePatchContext.applyUnreadFix() {
    val request = requireSingleMethod(
        "YidAdapter topic-list request",
        YID_ADAPTER,
        "request",
        "V",
        API_REQUEST,
        API_RESPONSE_LISTENER,
    )
    val instructions = request.implementation!!.instructions
    val cookieGetter = "$YID_ACCOUNT_MANAGER->getRequestCookies()Ljava/lang/String;"
    val noCache = "$API_REQUEST->setNoCache(Z)V"
    val cookieCalls = instructions.withIndex().filter {
        it.value.methodReference?.toString() == cookieGetter
    }.toList()
    val noCacheCalls = instructions.withIndex().filter {
        it.value.methodReference?.toString() == noCache
    }.toList()
    val cookieStrings = instructions.withIndex().filter { it.value.string == "Cookie" }.toList()

    if (request.implementation!!.registerCount != 5 || cookieCalls.size != 1 ||
        noCacheCalls.size != 1 || cookieStrings.size != 1 ||
        instructions.any { it.methodReference?.definingClass == TOPIC_LIST_REQUESTS }
    ) {
        throw PatchException("YidAdapter request stock fingerprint did not match")
    }

    val cookieIndex = cookieCalls.single().index
    val cookieString = cookieStrings.single()
    val cookieRegister = cookieString.value as? OneRegisterInstruction
        ?: throw PatchException("YidAdapter Cookie anchor has an unexpected format")
    if (cookieIndex + 2 != cookieString.index ||
        instructions[cookieIndex + 1].opcode != Opcode.MOVE_RESULT_OBJECT ||
        cookieRegister.registerA != 1
    ) {
        throw PatchException("YidAdapter Cookie anchor did not match")
    }

    val noCacheIndex = noCacheCalls.single().index
    val noCacheCall = noCacheCalls.single().value as? FiveRegisterInstruction
        ?: throw PatchException("YidAdapter no-cache call has an unexpected format")
    val noCacheValue = instructions.getOrNull(noCacheIndex - 1) as? NarrowLiteralInstruction
        ?: throw PatchException("YidAdapter no-cache value anchor has an unexpected format")
    if (noCacheValue.narrowLiteral != 1 || instructions[noCacheIndex - 1].opcode != Opcode.CONST_4 ||
        noCacheCall.registerCount != 2 || noCacheCall.registerC != 3 || noCacheCall.registerD != 0
    ) {
        throw PatchException("YidAdapter no-cache value anchor did not match")
    }

    // p1 is v3 in this five-register method; range form remains valid if locals change.
    request.addInstruction(
        0,
        "invoke-static/range { p1 .. p1 }, $TOPIC_LIST_REQUESTS->bypassSharedCache(Ljava/lang/Object;)V",
    )
}

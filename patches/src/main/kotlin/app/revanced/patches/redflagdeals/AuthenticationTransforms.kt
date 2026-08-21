package app.revanced.patches.redflagdeals

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.extensions.string
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val YID_ADAPTER = "Lcom/ypg/rfdapilib/api/adapter/YidAdapter;"
private const val YID_ADAPTER_LISTENER = "Lcom/ypg/rfdapilib/api/adapter/YidAdapter${'$'}1;"
private const val YID_ACCOUNT_MANAGER = "Lcom/ypg/rfdapilib/auth/YidAccountManager;"
private const val DIAGNOSTICS = "Lapp/revanced/extension/redflagdeals/Diagnostics;"

private const val STOCK_SID_REGEX = "^phpbb3_([a-z0-9-]+)_sid=([a-z0-9-]+).*"
private const val WIDENED_SID_REGEX = "^phpbb3_([a-z0-9-]+)_sid=([a-zA-Z0-9,-]+);?.*"

internal fun BytecodePatchContext.applyAuthenticationFixes() {
    widenSidParser()
    addSafeRequestDiagnostics()
    addSafeCookieParseDiagnostics()
    fixCookieAssemblyOrder()
    addCookiePersistenceDiagnostics()
    addExplicitLogoutDiagnostic()
}

private fun BytecodePatchContext.widenSidParser() {
    val constructor = requireSingleMethod(
        "YidAdapter constructor",
        YID_ADAPTER,
        "<init>",
        "V",
        "Lcom/ypg/rfdapilib/api/ApiAdapter;",
        YID_ACCOUNT_MANAGER,
    )
    val matches = constructor.implementation!!.instructions.withIndex()
        .filter { it.value.string == STOCK_SID_REGEX }
        .toList()
    if (matches.size != 1) {
        throw PatchException("SID parser fingerprint expected one stock regex, found ${matches.size}")
    }
    val instruction = matches.single().value as? OneRegisterInstruction
        ?: throw PatchException("SID parser regex is not a one-register const-string")
    constructor.replaceInstruction(
        matches.single().index,
        "const-string v${instruction.registerA}, \"$WIDENED_SID_REGEX\"",
    )
}

private fun BytecodePatchContext.addSafeRequestDiagnostics() {
    val request = requireSingleMethod(
        "YidAdapter request",
        YID_ADAPTER,
        "request",
        "V",
        "Lcom/ypg/rfdapilib/api/ApiRequest;",
        "Lcom/ypg/rfdapilib/api/ApiResponseListener;",
    )
    val instructions = request.implementation!!.instructions
    val cookieCall = instructions.withIndex().filter {
        it.value.methodReference?.toString() ==
            "$YID_ACCOUNT_MANAGER->getRequestCookies()Ljava/lang/String;"
    }.toList()
    if (cookieCall.size != 1 ||
        instructions[cookieCall.single().index + 1].opcode != Opcode.MOVE_RESULT_OBJECT
    ) {
        throw PatchException("Request-cookie diagnostic anchor did not match exactly")
    }

    val insertionIndex = cookieCall.single().index + 2
    val cookieHeader = instructions[insertionIndex] as? OneRegisterInstruction
        ?: throw PatchException("Cookie header fingerprint did not expose its destination register")
    if (instructions[insertionIndex].string != "Cookie" || cookieHeader.registerA != 1) {
        throw PatchException("Request listener safety fingerprint expected v1 to be free at Cookie header")
    }

    request.addInstructions(
        insertionIndex,
        """
            iget-object v1, p0, $YID_ADAPTER->mAccountManager:$YID_ACCOUNT_MANAGER
            invoke-static { p1, v1, p2 }, $DIAGNOSTICS->logAuthRequest(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)V
        """.trimIndent(),
    )
}

private fun BytecodePatchContext.addSafeCookieParseDiagnostics() {
    val onSuccess = requireSingleMethod(
        "YidAdapter response listener",
        YID_ADAPTER_LISTENER,
        "onSuccess",
        "V",
        "Lcom/ypg/rfdapilib/api/ApiResponse;",
    )
    val instructions = onSuccess.implementation!!.instructions
    val persistCalls = instructions.withIndex().filter {
        it.value.methodReference?.toString() ==
            "$YID_ACCOUNT_MANAGER->setPhpbbCookie(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"
    }.toList()
    if (persistCalls.size != 1) {
        throw PatchException("Response-cookie persistence fingerprint found ${persistCalls.size} matches")
    }
    val call = persistCalls.single().value as? FiveRegisterInstruction
        ?: throw PatchException("Response-cookie persistence call has an unexpected invoke format")
    val parsedRegisters = listOf(call.registerD, call.registerE, call.registerF)
    if (call.registerCount != 4 || parsedRegisters != listOf(1, 2, 3)) {
        throw PatchException("Response-cookie parsed registers changed: $parsedRegisters")
    }
    onSuccess.addInstruction(
        persistCalls.single().index,
        "invoke-static { v1, v2, v3 }, $DIAGNOSTICS->logCookieParse(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
    )
}

private fun BytecodePatchContext.fixCookieAssemblyOrder() {
    val getCookies = requireSingleMethod(
        "YidAccountManager cookie assembly",
        YID_ACCOUNT_MANAGER,
        "getRequestCookies",
        "Ljava/lang/String;",
    )
    val instructions = getCookies.implementation!!.instructions
    val sidStrings = instructions.withIndex().filter { it.value.string == "_sid=" }.toList()
    val userStrings = instructions.withIndex().filter { it.value.string == "_u=" }.toList()
    if (sidStrings.size != 1 || userStrings.size != 1) {
        throw PatchException("Cookie assembly string fingerprint was not unique")
    }
    val start = sidStrings.single().index
    if (userStrings.single().index != start + 5) {
        throw PatchException("Cookie assembly stock order changed")
    }
    val expected = listOf(
        Opcode.CONST_STRING,
        Opcode.INVOKE_VIRTUAL,
        Opcode.INVOKE_VIRTUAL,
        Opcode.INVOKE_VIRTUAL,
        Opcode.INVOKE_VIRTUAL,
        Opcode.CONST_STRING,
        Opcode.INVOKE_VIRTUAL,
        Opcode.INVOKE_VIRTUAL,
        Opcode.INVOKE_VIRTUAL,
    )
    if (instructions.drop(start).take(expected.size).map { it.opcode } != expected) {
        throw PatchException("Cookie assembly opcode fingerprint changed")
    }

    getCookies.replaceInstruction(start, "const-string v5, \"_u=\"")
    getCookies.replaceInstruction(
        start + 2,
        "invoke-virtual { v3, v1 }, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;",
    )
    getCookies.replaceInstruction(start + 5, "const-string v5, \"_sid=\"")
    getCookies.replaceInstruction(
        start + 6,
        "invoke-virtual { v3, v5 }, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;",
    )
    getCookies.replaceInstruction(
        start + 7,
        "invoke-virtual { v3, v0 }, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;",
    )
}

private fun BytecodePatchContext.addCookiePersistenceDiagnostics() {
    val setCookie = requireSingleMethod(
        "YidAccountManager cookie persistence",
        YID_ACCOUNT_MANAGER,
        "setPhpbbCookie",
        "V",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
    )
    setCookie.addInstruction(
        0,
        "invoke-static { p1, p2, p3 }, $DIAGNOSTICS->logCookieTupleInput(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
    )

    val storeCalls = setCookie.implementation!!.instructions.withIndex().filter {
        it.value.methodReference?.toString() ==
            "Lcom/ypg/rfdapilib/auth/CookieStore;->putPhpbbCookie(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"
    }.toList()
    if (storeCalls.size != 1) {
        throw PatchException("CookieStore persistence fingerprint found ${storeCalls.size} matches")
    }
    setCookie.addInstruction(
        storeCalls.single().index + 1,
        "invoke-static {}, $DIAGNOSTICS->logCookieTupleAccepted()V",
    )
}

private fun BytecodePatchContext.addExplicitLogoutDiagnostic() {
    val logout = requireSingleMethod(
        "YidAccountManager logout",
        YID_ACCOUNT_MANAGER,
        "logout",
        "V",
    )
    val instructions = logout.implementation!!.instructions
    val expectedPrefix = listOf(Opcode.IGET_BOOLEAN, Opcode.IF_NEZ, Opcode.RETURN_VOID, Opcode.CONST_4)
    if (instructions.take(expectedPrefix.size).map { it.opcode } != expectedPrefix) {
        throw PatchException("Logout control-flow fingerprint changed")
    }
    logout.replaceInstruction(3, "invoke-static {}, $DIAGNOSTICS->logLogout()V")
    logout.addInstruction(4, "const/4 v0, 0x0")
}

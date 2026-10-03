package app.revanced.patches.redflagdeals

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private const val REPLY_BINDING = "Lcom/ypg/rfdforums/databinding/PostItemBinding;"
private const val REPLY_BINDING_IMPL = "Lcom/ypg/rfdforums/databinding/PostItemBindingImpl;"
private const val VOTE_MODEL = "Lcom/ypg/rfdforums/databinding/viewmodels/VoteViewModel;"
private const val REPLY_VOTING = "Lapp/revanced/extension/redflagdeals/ReplyVoting;"

internal fun BytecodePatchContext.applyReplyVoting() {
    // The extension uses public bindings rather than app classes as compile dependencies.
    // Verify those reflective entry points as part of the stock fingerprint.
    requireSingleMethod("Reply vote model getter", REPLY_BINDING, "getVoteViewModel", VOTE_MODEL)
    requireSingleMethod("Reply current vote", VOTE_MODEL, "getVote", "I")
    requireSingleMethod("Reply vote availability", VOTE_MODEL, "isVotesEnabled", "Z")
    requireSingleMethod("Native reply downvote", VOTE_MODEL, "onVoteDown", "V", "Landroid/view/View;")
    for ((name, type) in listOf("voteUp" to "Landroid/widget/ImageView;", "votes" to "Landroid/widget/LinearLayout;")) {
        val matches = classDefs.singleOrNull { it.type == REPLY_BINDING }?.fields?.filter {
            it.name == name && it.type == type && AccessFlags.PUBLIC.isSet(it.accessFlags)
        } ?: emptyList()
        if (matches.size != 1) throw PatchException("Reply binding field $name did not match")
    }

    val bind = requireSingleMethod("Reply row binding", REPLY_BINDING_IMPL, "executeBindings", "V")
    val instructions = bind.implementation!!.instructions
    val returns = instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }
    if (bind.implementation!!.registerCount != 63 || returns.size != 1 ||
        instructions.count { it.methodReference?.toString() == "$VOTE_MODEL->getVote()I" } != 1 ||
        instructions.count { it.methodReference?.toString() == "$VOTE_MODEL->getNetVotes()Ljava/lang/String;" } != 1 ||
        instructions.none { it.fieldReference?.toString() == "$REPLY_BINDING_IMPL->votes:Landroid/widget/LinearLayout;" } ||
        instructions.any { it.methodReference?.definingClass == REPLY_VOTING }
    ) {
        throw PatchException("Reply row stock binding fingerprint did not match")
    }

    // Replace the return itself so branches to it also run the hook. p0 is v62,
    // beyond the short invoke register range; no existing local is clobbered.
    val end = returns.single().index
    bind.replaceInstruction(end, "invoke-static/range { p0 .. p0 }, $REPLY_VOTING->bind(Ljava/lang/Object;)V")
    bind.addInstruction(end + 1, "return-void")
}

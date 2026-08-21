package app.revanced.patches.redflagdeals

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.removeInstructions
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10t

private const val TOPIC_FRAGMENT = "Lcom/ypg/rfdforums/sections/topic/TopicFragment;"
private const val TOPIC_REFRESH_LISTENER =
    "Lcom/ypg/rfdforums/sections/topic/TopicFragment${'$'}onRefresh${'$'}1;"
private const val TOPIC = "Lcom/ypg/rfdapilib/forums/model/Topic;"
private const val TOPIC_DIAGNOSTICS = "Lapp/revanced/extension/redflagdeals/Diagnostics;"

internal fun BytecodePatchContext.applyTopicFixes() {
    hardenQuickReplyState()
    refreshExactTopicOnEntry()
    keepReplyHiddenDuringRefresh()
    preserveThreadOnRefreshFailure()
    rebuildReplyUiAfterExactSuccess()
}

private fun BytecodePatchContext.hardenQuickReplyState() {
    val setup = requireSingleMethod(
        "TopicFragment quick-reply setup",
        TOPIC_FRAGMENT,
        "setupQuickReplyUI",
        "V",
    )
    setup.accessFlags = setup.accessFlags
        .or(AccessFlags.PUBLIC.value)
        .and(AccessFlags.PRIVATE.value.inv())

    setup.addInstruction(
        0,
        "invoke-static { p0 }, $TOPIC_DIAGNOSTICS->hideQuickReply(Ljava/lang/Object;)V",
    )

    val canReplyCalls = setup.implementation!!.instructions.withIndex().filter {
        it.value.methodReference?.toString() == "$TOPIC->getCanReply()Z"
    }.toList()
    if (canReplyCalls.size != 3) {
        throw PatchException("Quick-reply fingerprint expected three canReply checks, found ${canReplyCalls.size}")
    }

    // The first check historically opened LogoutDialog. Preserve its existing branch target,
    // make the jump unconditional, and remove the entire automatic-logout block.
    val firstResultIndex = canReplyCalls[0].index + 1
    if (setup.implementation!!.instructions[firstResultIndex].opcode != Opcode.MOVE_RESULT) {
        throw PatchException("Quick-reply logout check lost its move-result anchor")
    }
    val branchIndex = firstResultIndex + 1
    val branch = setup.implementation!!.instructions[branchIndex] as? BuilderOffsetInstruction
        ?: throw PatchException("Quick-reply logout branch has an unexpected instruction format")
    if (branch.opcode != Opcode.IF_NEZ) {
        throw PatchException("Quick-reply logout branch expected IF_NEZ, found ${branch.opcode}")
    }
    val logoutShowCalls = setup.implementation!!.instructions.withIndex().filter {
        it.value.methodReference?.toString() ==
            "Lcom/ypg/rfdforums/ui/dialogs/LogoutDialog;->show()V"
    }.toList()
    if (logoutShowCalls.size != 1 || logoutShowCalls.single().index <= branchIndex) {
        throw PatchException("Quick-reply LogoutDialog block fingerprint was not unique")
    }
    val showIndex = logoutShowCalls.single().index
    setup.replaceInstruction(branchIndex, BuilderInstruction10t(Opcode.GOTO, branch.target))
    setup.removeInstructions(branchIndex + 1, showIndex - branchIndex)

    val remainingCanReplyCalls = setup.implementation!!.instructions.withIndex().filter {
        it.value.methodReference?.toString() == "$TOPIC->getCanReply()Z"
    }.toList()
    if (remainingCanReplyCalls.size != 3) {
        throw PatchException("Quick-reply checks expected three matches after logout removal")
    }
    remainingCanReplyCalls.drop(1).forEach { match ->
        setup.replaceInstruction(
            match.index,
            "invoke-static { v0 }, $TOPIC_DIAGNOSTICS->isReplyAllowed(Ljava/lang/Object;)Z",
        )
    }

    val logoutReferences = setup.implementation!!.instructions.count {
        it.methodReference?.definingClass == "Lcom/ypg/rfdforums/ui/dialogs/LogoutDialog;"
    }
    if (logoutReferences != 0) {
        throw PatchException("Quick-reply transformation left $logoutReferences LogoutDialog references")
    }
}

private fun BytecodePatchContext.refreshExactTopicOnEntry() {
    val onCreate = requireSingleMethod(
        "TopicFragment onCreate",
        TOPIC_FRAGMENT,
        "onCreate",
        "V",
        "Landroid/os/Bundle;",
    )

    val topicAssignments = onCreate.implementation!!.instructions.withIndex().filter {
        it.value.opcode == Opcode.IPUT_OBJECT &&
            it.value.fieldReference?.toString() == "$TOPIC_FRAGMENT->mTopic:$TOPIC"
    }.toList()
    if (topicAssignments.size != 1) {
        throw PatchException("Parcel topic assignment fingerprint found ${topicAssignments.size} matches")
    }
    onCreate.addInstructions(
        topicAssignments.single().index + 1,
        """
            const-string v2, "parcel"
            invoke-static { v1, v2 }, $TOPIC_DIAGNOSTICS->logTopicState(Ljava/lang/Object;Ljava/lang/String;)V
        """.trimIndent(),
    )

    val onCreateView = requireSingleMethod(
        "TopicFragment onCreateView",
        TOPIC_FRAGMENT,
        "onCreateView",
        "Landroid/view/View;",
        "Landroid/view/LayoutInflater;",
        "Landroid/view/ViewGroup;",
        "Landroid/os/Bundle;",
    )
    val setupCalls = onCreateView.implementation!!.instructions.withIndex().filter {
        it.value.methodReference?.toString() == "$TOPIC_FRAGMENT->setupQuickReplyUI()V"
    }.toList()
    if (setupCalls.size != 1) {
        throw PatchException("Initial quick-reply setup fingerprint found ${setupCalls.size} matches")
    }
    onCreateView.replaceInstruction(
        setupCalls.single().index,
        "invoke-static { p0 }, $TOPIC_DIAGNOSTICS->hideQuickReply(Ljava/lang/Object;)V",
    )

    val onViewCreated = requireSingleMethod(
        "TopicFragment onViewCreated",
        TOPIC_FRAGMENT,
        "onViewCreated",
        "V",
        "Landroid/view/View;",
        "Landroid/os/Bundle;",
    )
    val setAdapterCalls = onViewCreated.implementation!!.instructions.withIndex().filter {
        it.value.methodReference?.toString() ==
            "Landroidx/recyclerview/widget/RecyclerView;->setAdapter(Landroidx/recyclerview/widget/RecyclerView${'$'}Adapter;)V"
    }.toList()
    if (setAdapterCalls.size != 1) {
        throw PatchException("Topic adapter installation fingerprint found ${setAdapterCalls.size} matches")
    }
    onViewCreated.addInstruction(
        setAdapterCalls.single().index + 1,
        // onViewCreated has 20 locals, so p0 is above the 4-bit invoke register limit.
        // The stock prologue already aliases p0 into v0 for this purpose.
        "invoke-virtual { v0 }, $TOPIC_FRAGMENT->onRefresh()V",
    )
}

private fun BytecodePatchContext.keepReplyHiddenDuringRefresh() {
    val onRefresh = requireSingleMethod(
        "TopicFragment exact refresh",
        TOPIC_FRAGMENT,
        "onRefresh",
        "V",
    )
    val exactTopicCalls = onRefresh.implementation!!.instructions.count {
        it.methodReference?.toString() ==
            "Lcom/ypg/rfdapilib/forums/DealerDecorator;->getTopic(ILcom/ypg/rfdapilib/forums/response/OnGetTopicResponse${'$'}ClientListener;)V"
    }
    if (exactTopicCalls != 1) {
        throw PatchException("Exact-topic refresh fingerprint found $exactTopicCalls calls")
    }
    onRefresh.addInstruction(
        0,
        "invoke-static { p0 }, $TOPIC_DIAGNOSTICS->hideQuickReply(Ljava/lang/Object;)V",
    )
}

private fun BytecodePatchContext.preserveThreadOnRefreshFailure() {
    val onError = requireSingleMethod(
        "Exact-topic refresh error",
        TOPIC_REFRESH_LISTENER,
        "onError",
        "V",
        "Ljava/lang/Exception;",
    )
    val oldInstructions = onError.implementation!!.instructions
    val destructiveReferences = oldInstructions.count {
        val reference = it.methodReference?.toString()
        reference?.contains("HomeRoute;->go") == true ||
            reference?.contains("FragmentActivity;->finish") == true ||
            reference?.contains("FragmentActivity;->onBackPressed") == true
    }
    if (destructiveReferences != 3) {
        throw PatchException("Refresh-error fingerprint expected three navigation exits, found $destructiveReferences")
    }

    onError.removeInstructions(0, oldInstructions.size)
    onError.addInstructions(
        0,
        """
            const-string v0, "e"
            invoke-static { p1, v0 }, Lkotlin/jvm/internal/Intrinsics;->checkParameterIsNotNull(Ljava/lang/Object;Ljava/lang/String;)V
            iget-object p1, p0, $TOPIC_REFRESH_LISTENER->this${'$'}0:$TOPIC_FRAGMENT
            invoke-virtual { p1 }, $TOPIC_FRAGMENT->getMSwipeRefreshLayout${'$'}rfd_forums_productionRelease()Landroidx/swiperefreshlayout/widget/SwipeRefreshLayout;
            move-result-object p1
            const/4 v0, 0x0
            invoke-virtual { p1, v0 }, Landroidx/swiperefreshlayout/widget/SwipeRefreshLayout;->setRefreshing(Z)V
            invoke-static {}, $TOPIC_DIAGNOSTICS->logExactRefreshFailed()V
            return-void
        """.trimIndent(),
    )
}

private fun BytecodePatchContext.rebuildReplyUiAfterExactSuccess() {
    val onSuccess = requireSingleMethod(
        "Exact-topic refresh success",
        TOPIC_REFRESH_LISTENER,
        "onSuccess",
        "V",
        TOPIC,
        "Landroidx/collection/SparseArrayCompat;",
    )
    val setTopicCalls = onSuccess.implementation!!.instructions.withIndex().filter {
        it.value.methodReference?.toString() ==
            "$TOPIC_FRAGMENT->setMTopic${'$'}rfd_forums_productionRelease($TOPIC)V"
    }.toList()
    if (setTopicCalls.size != 1) {
        throw PatchException("Exact-topic replacement fingerprint found ${setTopicCalls.size} matches")
    }
    onSuccess.addInstructions(
        setTopicCalls.single().index + 1,
        """
            const-string v0, "exact"
            invoke-static { p1, v0 }, $TOPIC_DIAGNOSTICS->logTopicState(Ljava/lang/Object;Ljava/lang/String;)V
        """.trimIndent(),
    )

    val refreshAdapterCalls = onSuccess.implementation!!.instructions.withIndex().filter {
        it.value.methodReference?.toString() ==
            "Lcom/ypg/rfdforums/sections/topic/PostsListAdapter;->refreshTopic($TOPIC" +
            "Lcom/ypg/rfdapilib/forums/model/User;" +
            "Lcom/ypg/rfdforums/databinding/viewmodels/TopicViewModel;)V"
    }.toList()
    if (refreshAdapterCalls.size != 1) {
        throw PatchException("Exact-topic adapter refresh fingerprint found ${refreshAdapterCalls.size} matches")
    }
    onSuccess.addInstructions(
        refreshAdapterCalls.single().index + 1,
        """
            iget-object p1, p0, $TOPIC_REFRESH_LISTENER->this${'$'}0:$TOPIC_FRAGMENT
            invoke-virtual { p1 }, $TOPIC_FRAGMENT->setupQuickReplyUI()V
        """.trimIndent(),
    )
}

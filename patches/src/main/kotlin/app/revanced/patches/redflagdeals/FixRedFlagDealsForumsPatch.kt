package app.revanced.patches.redflagdeals

import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.removeInstructions
import app.revanced.patcher.extensions.replaceInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode

private const val TOPIC_LIST_ADAPTER =
    "Lcom/ypg/rfdforums/sections/topics/TopicListAdapter;"
private const val VIEW_HOLDER =
    "Landroidx/recyclerview/widget/RecyclerView${'$'}ViewHolder;"
private const val VIEW_GROUP = "Landroid/view/ViewGroup;"
private const val PROGRESS_HOLDER_FIELD =
    "$TOPIC_LIST_ADAPTER->progressViewHolder:" +
        "Lcom/ypg/rfdforums/databinding/viewholders/ProgressViewHolder;"
private const val PROGRESS_HOLDER_CREATE =
    "Lcom/ypg/rfdforums/databinding/viewholders/ProgressViewHolder;->" +
        "create(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;)" +
        "Lcom/ypg/rfdforums/databinding/viewholders/ProgressViewHolder;"

@Suppress("unused")
val fixRedFlagDealsForumsPatch = bytecodePatch(
    name = "Fix RedFlagDeals Forums",
    description = "Fixes authentication, topic permissions, exact-topic refresh, and pagination stability.",
) {
    compatibleWith("com.ypg.rfdforums"("1.11.7"))

    extendWith("extensions/rfd-diagnostics.rve")

    apply {
        applyAuthenticationFixes()
        applyTopicFixes()

        val immutableMatches = classDefs.asSequence()
            .flatMap { it.methods.asSequence() }
            .filter {
                it.definingClass == TOPIC_LIST_ADAPTER &&
                    it.name == "onCreateViewHolder" &&
                    it.returnType == VIEW_HOLDER &&
                    it.parameterTypes.toList() == listOf(VIEW_GROUP, "I")
            }
            .toList()

        if (immutableMatches.size != 1) {
            throw PatchException(
                "Pagination fingerprint expected exactly one " +
                    "TopicListAdapter.onCreateViewHolder match, found ${immutableMatches.size}",
            )
        }

        val method = firstMethod(immutableMatches.single())
        val instructions = method.implementation?.instructions
            ?: throw PatchException("Pagination target has no implementation")

        val cacheReadIndices = instructions.withIndex()
            .filter {
                it.value.opcode == Opcode.IGET_OBJECT &&
                    it.value.fieldReference?.toString() == PROGRESS_HOLDER_FIELD
            }
            .map { it.index }

        val cacheWriteIndices = instructions.withIndex()
            .filter {
                it.value.opcode == Opcode.IPUT_OBJECT &&
                    it.value.fieldReference?.toString() == PROGRESS_HOLDER_FIELD
            }
            .map { it.index }

        val createIndices = instructions.withIndex()
            .filter {
                it.value.opcode == Opcode.INVOKE_STATIC &&
                    it.value.methodReference?.toString() == PROGRESS_HOLDER_CREATE
            }
            .map { it.index }

        if (cacheReadIndices.size != 2 || cacheWriteIndices.size != 1 || createIndices.size != 1) {
            throw PatchException(
                "Pagination stock shape mismatch: expected two cache reads, one cache write, " +
                    "and one ProgressViewHolder.create call; found " +
                    "${cacheReadIndices.size}/${cacheWriteIndices.size}/${createIndices.size}",
            )
        }

        val start = cacheReadIndices.first()
        val expectedOpcodes = listOf(
            Opcode.IGET_OBJECT,
            Opcode.IF_NEZ,
            Opcode.INVOKE_VIRTUAL,
            Opcode.MOVE_RESULT_OBJECT,
            Opcode.INVOKE_STATIC,
            Opcode.MOVE_RESULT_OBJECT,
            Opcode.INVOKE_STATIC,
            Opcode.MOVE_RESULT_OBJECT,
            Opcode.IPUT_OBJECT,
            Opcode.IGET_OBJECT,
            Opcode.IF_EQZ,
            Opcode.CHECK_CAST,
            Opcode.RETURN_OBJECT,
            Opcode.NEW_INSTANCE,
            Opcode.CONST_STRING,
            Opcode.INVOKE_DIRECT,
            Opcode.THROW,
        )
        val actualOpcodes = instructions.drop(start)
            .take(expectedOpcodes.size)
            .map { it.opcode }

        if (actualOpcodes != expectedOpcodes ||
            cacheReadIndices != listOf(start, start + 9) ||
            cacheWriteIndices.single() != start + 8 ||
            createIndices.single() != start + 6
        ) {
            throw PatchException("Pagination stock instruction block did not match exactly")
        }

        // Replace the first eight instructions in place so the existing branch label remains
        // attached to the fresh-holder path, then remove the obsolete cache/null-cast tail.
        method.replaceInstructions(
            start,
            """
                invoke-virtual { p1 }, Landroid/view/ViewGroup;->getContext()Landroid/content/Context;
                move-result-object p2
                invoke-static { p2 }, Landroid/view/LayoutInflater;->from(Landroid/content/Context;)Landroid/view/LayoutInflater;
                move-result-object p2
                invoke-static { p2, p1 }, Lcom/ypg/rfdforums/databinding/viewholders/ProgressViewHolder;->create(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;)Lcom/ypg/rfdforums/databinding/viewholders/ProgressViewHolder;
                move-result-object p1
                check-cast p1, Landroidx/recyclerview/widget/RecyclerView${'$'}ViewHolder;
                return-object p1
            """.trimIndent(),
        )
        method.removeInstructions(start + 8, expectedOpcodes.size - 8)

        val patchedInstructions = method.implementation!!.instructions
        if (patchedInstructions.any { it.fieldReference?.toString() == PROGRESS_HOLDER_FIELD }) {
            throw PatchException("Pagination transformation left a progress-holder cache access behind")
        }
        if (patchedInstructions.count { it.methodReference?.toString() == PROGRESS_HOLDER_CREATE } != 1) {
            throw PatchException("Pagination transformation did not preserve exactly one fresh-holder creation")
        }
    }
}

package io.memoryos.chat.tools;

import com.embabel.agent.api.annotation.LlmTool;
import io.memoryos.chat.ChatMemory;
import io.memoryos.chat.ChatMemoryService;
import io.memoryos.chat.ChatToolActivity;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;

/**
 * MEM-250: edits what Chat remembers about the member who owns the conversation, one passage at a time (Dust's
 * {@code edit(oldStr, newStr)} in place of Onyx's {@code add_memory}, which asks a second model where a fact goes).
 * What the model reads back is the outcome; the memory text itself is returned only when an edit did not apply, so
 * the model can retry against what is stored. Nothing it writes reaches the activity timeline.
 */
public final class UpdateMemoryTool {
    private final ChatMemoryService memories;
    private final TenantId tenant;
    private final ActorId actor;
    private final Runnable checkActive;
    private final ChatToolActivity activity;

    public UpdateMemoryTool(ChatMemoryService memories, TenantId tenant, ActorId actor, Runnable checkActive,
                            ChatToolActivity activity) {
        this.memories = memories;
        this.tenant = tenant;
        this.actor = actor;
        this.checkActive = checkActive;
        this.activity = activity;
    }

    @LlmTool(name = "update_memory", description = "Remember, change or forget a fact about the user for future "
            + "conversations. An empty oldText adds newText as a new line; an empty newText deletes oldText; "
            + "otherwise the exact passage oldText is replaced by newText.")
    public String updateMemory(
            @LlmTool.Param(description = "Exact passage of the current memory to change or delete; empty to add") String oldText,
            @LlmTool.Param(description = "Replacement text, or the new fact to add; empty to delete") String newText) {
        checkActive.run();
        if (isBlank(oldText) && isBlank(newText)) {
            activity.fail();
            return "Nothing to change: give newText to add a fact, or oldText to change or delete one.";
        }
        var edit = memories.edit(tenant, actor, oldText, newText);
        checkActive.run();
        return switch (edit) {
            case ChatMemoryService.Edit.Applied _ -> "Memory updated. It applies from the next message.";
            case ChatMemoryService.Edit.PassageNotFound miss -> {
                activity.fail();
                yield "oldText was not found. Copy the passage exactly from the current memory:\n" + shown(miss.memory());
            }
            case ChatMemoryService.Edit.PassageNotUnique twice -> {
                activity.fail();
                yield "oldText occurs more than once. Include more of the surrounding text. Current memory:\n"
                        + shown(twice.memory());
            }
            case ChatMemoryService.Edit.TooLong tooLong -> {
                activity.fail();
                yield "Not saved: the memory would be " + tooLong.length() + " characters, over the limit of "
                        + ChatMemory.MAX_CONTENT + ". Condense or remove older facts first. Current memory:\n"
                        + shown(tooLong.memory());
            }
        };
    }

    private static String shown(ChatMemory memory) {
        return memory.content().isEmpty() ? "(empty)" : memory.content();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

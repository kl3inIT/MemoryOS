package io.memoryos.chat.tools;

import com.embabel.agent.api.annotation.LlmTool;
import io.memoryos.chat.ChatArtifact;
import io.memoryos.chat.ChatArtifacts;
import java.util.UUID;

/** Uses the native backend tool loop; it cannot invoke application mutations or execute code. */
public final class ArtifactTool {
    private final ChatArtifacts artifacts;
    private final Runnable checkActive;

    public ArtifactTool(ChatArtifacts artifacts, Runnable checkActive) {
        this.artifacts = artifacts;
        this.checkActive = checkActive;
    }

    @LlmTool(description = "Create a read-only visual card or table only when the user requests a visual presentation or it materially clarifies the answer. No computation or code execution. Use verified data, retain citations in your accompanying answer, and explain uncertainty. At most 3 artifacts per answer. Write labels and values in the user's requested language. After this tool, provide an accompanying text answer; do not repeat the JSON.")
    public String render_gui(
            @LlmTool.Param(description = "Short display title, 1 to 120 characters") String title,
            @LlmTool.Param(description = "JSON string shaped {root:{component,props,children}}. Components: Card props {title}, Heading/Text/Cell props {text}, Metric props {label,value}, Table/Row props {}. All prop values are strings. Card may contain components; Table only Row; Row only Cell; leaves have no children. No other keys, HTML, URLs, actions or styles. At most 16384 UTF-8 bytes, 80 nodes, depth 8, 24 children per node, 2048 characters per text.") String spec) {
        checkActive.run();
        ChatArtifact artifact;
        try { artifact = new ChatArtifact(UUID.randomUUID(), title, spec); }
        catch (RuntimeException invalid) { return "Invalid read-only UI specification. Follow the documented components and limits, or use Markdown instead."; }
        checkActive.run();
        if (!artifacts.add(artifact)) return "The artifact limit was reached or the turn ended. Use the accompanying text answer.";
        return "Read-only artifact accepted, id=" + artifact.id() + ". It will be published with this answer. Now provide your accompanying answer and citations.";
    }
}

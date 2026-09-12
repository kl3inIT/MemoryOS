package io.memoryos.chat.execution;

import com.embabel.chat.ContentPart;
import com.embabel.chat.ImagePart;
import com.embabel.chat.Message;
import com.embabel.chat.TextPart;
import com.embabel.chat.UserMessage;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatFileContentService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Resolves private bytes after database admission, within the execution's cancellable IO scope. */
final class ChatFileInputs {
    static List<Message> materialize(ChatTurnSetup setup, ChatFileContentService content, Runnable checkActive) {
        var messages = new ArrayList<>(setup.messages());
        if (!setup.images().isEmpty() && !setup.binding().vision())
            throw ChatException.invalid("Choose a vision-capable model to read images.");
        for (var entry : setup.images().entrySet()) {
            var parts = new ArrayList<ContentPart>();
            String text = messages.get(entry.getKey()).getContent();
            if (!text.isEmpty()) parts.add(new TextPart(text));
            for (var file : entry.getValue()) {
                checkActive.run();
                byte[] bytes = content.image(setup.actor(), setup.tenant(), file.id());
                checkActive.run();
                var source = setup.evidence().file(file.id(), file.filename());
                if (source != null) parts.add(new TextPart("Image citation [" + source.citationId() + "] identifies file " + file.id()));
                parts.add(new ImagePart(file.mediaType(), bytes));
            }
            messages.set(entry.getKey(), new UserMessage(parts, null, Instant.now()));
        }
        return List.copyOf(messages);
    }
    private ChatFileInputs() {}
}

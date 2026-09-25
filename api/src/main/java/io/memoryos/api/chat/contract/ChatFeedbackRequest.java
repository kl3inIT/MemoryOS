package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(name = "FeedbackInput")
public record ChatFeedbackRequest(@Nullable Boolean positive, String comment, String reason) {}

package io.memoryos.chat;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One row of a person's list of assistants (MEM-273): the built-in assistant, then their pins in order. An assistant
 * they can no longer use, because it was deleted or unshared, keeps its row with only its name and face, so its
 * topics stay reachable; {@code description} is then empty and {@code owner} has neither actor nor group.
 * {@code latestTopic} is their newest topic on the web, or null before the first one.
 */
public record ChatAssistant(UUID id, String name, String description, String avatarSeed, boolean hasAvatar,
                            boolean builtin, AgentOwner owner, boolean usable, @Nullable ChatAssistantTopic latestTopic) {}

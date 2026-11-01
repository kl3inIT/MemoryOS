/**
 * Chat bots of the Tenant on messaging platforms, the Lark bot first (MEM-246): their connections, what they
 * receive and how a question becomes a Chat turn. The administration of a bot and the rules of answering live
 * here; the platform's transport is a {@link io.memoryos.channel.ChannelAdapter} in the platform's integration
 * module.
 */
@ApplicationModule(displayName = "Channels", type = ApplicationModule.Type.CLOSED, allowedDependencies = {"shared", "iam", "audit", "chat", "chat::streaming"})
@NullMarked
package io.memoryos.channel;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;

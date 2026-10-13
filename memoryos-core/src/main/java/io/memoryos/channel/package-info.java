/**
 * Chat bots of the Tenant on messaging platforms, the Lark bot first (MEM-246): their connections and, as the
 * capability grows, what they receive and how a question becomes a Chat turn. The administration of a bot lives
 * here; the platform's transport is an adapter in the platform's integration module.
 */
@ApplicationModule(displayName = "Channels", type = ApplicationModule.Type.CLOSED, allowedDependencies = {"shared", "iam", "audit"})
@NullMarked
package io.memoryos.channel;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;

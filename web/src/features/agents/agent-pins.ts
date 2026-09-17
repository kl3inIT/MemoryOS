import { useQueryClient } from "@tanstack/react-query";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { sameOriginMutationHeaders } from "@/lib/api";
import { listChatPersonaPins, replaceChatPersonaPins } from "@/lib/hey-api/sdk.gen";
import { personaSchema } from "@/features/chat/chat-workspace-api";

// Pin writes replace the whole ordered list, so they run one at a time on the latest server list.
let queue: Promise<unknown> = Promise.resolve();

/**
 * Changes the actor's pinned agents: each change receives the current ordered IDs after every earlier change has
 * finished, so concurrent pin, unpin and reorder actions never overwrite one another.
 */
export function usePinUpdates() {
  const cache = useQueryClient();
  const { actorId, authorizationVersion } = useApplicationSession();
  const pinsKey = ["chat-persona-pins", actorId, authorizationVersion];
  return (change: (current: string[]) => string[]) => {
    const run = queue.then(async () => {
      const current = personaSchema
        .array()
        .parse(
          (
            await listChatPersonaPins({
              signal: AbortSignal.timeout(30000),
              throwOnError: true,
            })
          ).data,
        )
        .map((agent) => agent.id);
      const saved = personaSchema.array().parse(
        (
          await replaceChatPersonaPins({
            body: { personaIds: change(current) },
            headers: sameOriginMutationHeaders,
            signal: AbortSignal.timeout(30000),
            throwOnError: true,
          })
        ).data,
      );
      cache.setQueryData(pinsKey, saved);
      await cache.invalidateQueries({ queryKey: ["chat-personas"] });
    });
    queue = run.catch(() => undefined);
    return run;
  };
}

import { useQueryClient } from "@tanstack/react-query";
import { invalidateAgents } from "@/features/chat/chat-personas-api";
import { listChatPersonaPinsQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import { listChatPersonaPins, replaceChatPersonaPins } from "@/lib/hey-api/sdk.gen";

// Pin writes replace the whole ordered list, so they run one at a time on the latest server list.
let queue: Promise<unknown> = Promise.resolve();

/**
 * Changes the actor's pinned agents: each change receives the current ordered IDs after every earlier change has
 * finished, so concurrent pin, unpin and reorder actions never overwrite one another.
 */
export function usePinUpdates() {
  const cache = useQueryClient();
  return (change: (current: string[]) => string[]) => {
    const run = queue.then(async () => {
      const { data: current } = await listChatPersonaPins({ signal: AbortSignal.timeout(30000) });
      const { data: saved } = await replaceChatPersonaPins({
        body: {
          personaIds: change(
            current.flatMap((agent) => (agent.id === undefined ? [] : [agent.id])),
          ),
        },
        signal: AbortSignal.timeout(30000),
      });
      cache.setQueryData(listChatPersonaPinsQueryKey(), saved);
      await invalidateAgents(cache);
    });
    queue = run.catch(() => undefined);
    return run;
  };
}

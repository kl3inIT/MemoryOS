import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { AudioLines } from "lucide-react";
import { Button } from "@/components/ui/button";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { Skeleton } from "@/components/ui/skeleton";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  listChatVoiceConnections,
  listChatVoiceProviders,
  selectChatVoiceProvider,
} from "@/lib/hey-api/sdk.gen";
import type { VoiceConnectionResponse, VoiceProviderResponse } from "@/lib/hey-api/types.gen";
import type { ErrorMessage } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { VoiceProviderCard } from "./voice-provider-card";
import {
  isDefault,
  voiceProblem,
  voiceQueryKey,
  type VoiceFunction,
  type VoiceProviderId,
} from "./voice-providers";

/** `/admin/voice`: one list per function, as in Onyx; a connection row serves both functions. */
export function VoiceAdminPage() {
  const ui = useAppTranslation();
  const session = useApplicationSession();
  const manager = session.capabilities.includes("MODELS_MANAGE");
  const cache = useQueryClient();
  const providers = useQuery({
    queryKey: [...voiceQueryKey, "providers", session.actorId, session.authorizationVersion],
    enabled: manager,
    queryFn: async ({ signal }) =>
      (await listChatVoiceProviders({ signal, throwOnError: true })).data,
    retry: false,
  });
  const connections = useQuery({
    queryKey: [...voiceQueryKey, "connections", session.actorId, session.authorizationVersion],
    enabled: manager,
    queryFn: async ({ signal }) =>
      (await listChatVoiceConnections({ signal, throwOnError: true })).data,
    retry: false,
  });
  if (!manager)
    return (
      <p role="alert" className="p-6">
        {ui("Bạn không có quyền quản lý mô hình.")}
      </p>
    );
  const changed = () => cache.invalidateQueries({ queryKey: voiceQueryKey });
  return (
    <SettingsLayout>
      <PageHeader
        title={ui("Giọng nói")}
        icon={<AudioLines />}
        description={ui(
          "Kết nối nhà cung cấp để thành viên nói thay vì gõ và nghe câu trả lời được đọc thành tiếng. Âm thanh chỉ đi qua máy chủ MemoryOS và không được lưu.",
        )}
      />
      {providers.isError || connections.isError ? (
        <div
          role="alert"
          className="flex flex-wrap items-center gap-3 rounded-xl border border-border-default bg-surface-sunken px-4 py-3"
        >
          <p className="mr-auto">{ui("Không tải được cấu hình giọng nói.")}</p>
          <Button
            size="sm"
            prominence="secondary"
            onClick={() => {
              void providers.refetch();
              void connections.refetch();
            }}
          >
            {ui("Tải lại")}
          </Button>
        </div>
      ) : providers.isPending || connections.isPending ? (
        <div role="status" className="space-y-8">
          <span className="sr-only">{ui("Đang tải…")}</span>
          {[0, 1].map((index) => (
            <div key={index} className="space-y-3">
              <Skeleton className="h-6 w-64" />
              <Skeleton className="h-4 w-80 max-w-full" />
              <Skeleton className="h-32 w-full rounded-2xl" />
            </div>
          ))}
        </div>
      ) : (
        (["STT", "TTS"] as const).map((fn) => (
          <VoiceFunctionSection
            key={fn}
            fn={fn}
            providers={providers.data}
            connections={connections.data}
            onChanged={changed}
          />
        ))
      )}
    </SettingsLayout>
  );
}

function VoiceFunctionSection({
  fn,
  providers,
  connections,
  onChanged,
}: {
  fn: VoiceFunction;
  providers: VoiceProviderResponse[];
  connections: VoiceConnectionResponse[];
  onChanged: () => Promise<void>;
}) {
  const ui = useAppTranslation();
  const problemMessage = useProblemMessage();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ErrorMessage>();
  const active = connections.find((connection) => isDefault(connection, fn));
  const title =
    fn === "STT" ? ui("Chuyển giọng nói thành văn bản") : ui("Đọc văn bản thành giọng nói");
  async function select(provider: VoiceProviderId | null) {
    setPending(true);
    setError(undefined);
    try {
      await selectChatVoiceProvider({
        body: { function: fn, provider: provider ?? undefined },
        headers: sameOriginMutationHeaders,
        throwOnError: true,
      });
      await onChanged();
    } catch (failed) {
      setError(voiceProblem(failed));
    } finally {
      setPending(false);
    }
  }
  return (
    <section aria-label={title} className="space-y-3">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="font-heading-h3 text-content-primary">{title}</h2>
          <p className="mt-1 text-sm text-content-muted">
            {fn === "STT"
              ? ui("Nhận dạng lời nói khi thành viên dùng micro trong Chat và Tìm kiếm.")
              : ui("Đọc câu trả lời của trợ lý thành tiếng.")}
          </p>
        </div>
        {active && (
          <Button
            size="sm"
            prominence="secondary"
            pending={pending}
            onClick={() => void select(null)}
          >
            {fn === "STT" ? ui("Tắt nhận dạng giọng nói") : ui("Tắt đọc thành tiếng")}
          </Button>
        )}
      </div>
      {!active && (
        <p className="rounded-xl border border-border-default bg-surface-sunken px-4 py-3 text-sm text-content-secondary">
          {fn === "STT"
            ? ui("Chưa có nhà cung cấp mặc định, nên micro trong Chat và Tìm kiếm đang tắt.")
            : ui("Chưa có nhà cung cấp mặc định, nên đọc thành tiếng đang tắt.")}
        </p>
      )}
      <ul className="divide-y divide-border-subtle overflow-hidden rounded-2xl border border-border-subtle bg-surface-raised">
        {providers.map((provider) => {
          const connection = connections.find((item) => item.provider === provider.provider);
          return (
            <VoiceProviderCard
              key={`${provider.provider}:${connection?.revision ?? "new"}`}
              fn={fn}
              provider={provider}
              connection={connection}
              autoSelect={!active}
              disabled={pending}
              onSelect={select}
              onChanged={onChanged}
            />
          );
        })}
      </ul>
      {error && (
        <p role="alert" className="text-sm text-status-danger-content">
          {problemMessage(error)}
        </p>
      )}
    </section>
  );
}

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AudioLines, WifiOff } from "lucide-react";
import { EmptyState } from "@/components/composites/empty-state";
import { SectionHeader } from "@/components/composites/section-header";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Alert, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  listChatVoiceConnectionsOptions,
  listChatVoiceProvidersOptions,
  selectChatVoiceProviderMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { VoiceConnectionResponse, VoiceProviderResponse } from "@/lib/hey-api/types.gen";
import { useProblemMessage } from "@/lib/use-problem-message";
import { VoiceProviderCard } from "./voice-provider-card";
import {
  invalidateVoice,
  isDefault,
  voiceProblem,
  type VoiceFunction,
  type VoiceProviderId,
} from "./voice-providers";

/** `/admin/voice`: one list per function, as in Onyx; a connection row serves both functions. */
export function VoiceAdminPage() {
  const ui = useAppTranslation();
  const session = useApplicationSession();
  const manager = session.capabilities.includes("MODELS_MANAGE");
  const providers = useQuery({
    ...listChatVoiceProvidersOptions(),
    enabled: manager,
    retry: false,
  });
  const connections = useQuery({
    ...listChatVoiceConnectionsOptions(),
    enabled: manager,
    retry: false,
  });
  if (!manager)
    return (
      <p role="alert" className="p-6">
        {ui("Bạn không có quyền quản lý mô hình.")}
      </p>
    );
  return (
    <SettingsLayout>
      <PageHeader
        title={ui("Giọng nói")}
        icon={<AudioLines />}
        description={ui(
          "Cấu hình nhà cung cấp nhận dạng giọng nói và đọc câu trả lời thành tiếng.",
        )}
      />
      {providers.isError || connections.isError ? (
        <EmptyState
          role="alert"
          icon={<WifiOff />}
          title={ui("Không tải được cấu hình giọng nói.")}
          action={
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
          }
        />
      ) : providers.isPending || connections.isPending ? (
        <div role="status" className="flex flex-col gap-8">
          <span className="sr-only">{ui("Đang tải…")}</span>
          {[0, 1].map((index) => (
            <div key={index} className="flex flex-col gap-3">
              <Skeleton className="h-6 w-64" />
              <Skeleton className="h-4 w-80 max-w-full" />
              <Skeleton className="h-32 w-full" />
            </div>
          ))}
        </div>
      ) : (
        <div className="flex flex-col gap-8">
          {(["STT", "TTS"] as const).map((fn) => (
            <VoiceFunctionSection
              key={fn}
              fn={fn}
              providers={providers.data}
              connections={connections.data}
            />
          ))}
        </div>
      )}
    </SettingsLayout>
  );
}

function VoiceFunctionSection({
  fn,
  providers,
  connections,
}: {
  fn: VoiceFunction;
  providers: VoiceProviderResponse[];
  connections: VoiceConnectionResponse[];
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const problemMessage = useProblemMessage();
  const select = useMutation({
    ...selectChatVoiceProviderMutation(),
    onSuccess: () => invalidateVoice(cache),
  });
  const active = connections.find((connection) => isDefault(connection, fn));
  const title =
    fn === "STT" ? ui("Chuyển giọng nói thành văn bản") : ui("Đọc văn bản thành giọng nói");
  const choose = (provider: VoiceProviderId | null) =>
    select.mutate({ body: { function: fn, provider: provider ?? undefined } });
  return (
    <section aria-label={title} className="flex flex-col gap-3">
      <SectionHeader
        title={title}
        description={
          fn === "STT"
            ? ui("Nhận dạng lời nói khi thành viên dùng micro trong Chat và Tìm kiếm.")
            : ui("Đọc câu trả lời của trợ lý thành tiếng.")
        }
        actions={
          <Button
            size="sm"
            prominence="secondary"
            pending={select.isPending && !select.variables.body.provider}
            disabled={!active || select.isPending}
            onClick={() => choose(null)}
          >
            {fn === "STT" ? ui("Tắt nhận dạng giọng nói") : ui("Tắt đọc thành tiếng")}
          </Button>
        }
      />
      {!active && (
        <Alert role="status">
          <AlertTitle>
            {fn === "STT"
              ? ui("Chưa có nhà cung cấp mặc định, nên micro trong Chat và Tìm kiếm đang tắt.")
              : ui("Chưa có nhà cung cấp mặc định, nên đọc thành tiếng đang tắt.")}
          </AlertTitle>
        </Alert>
      )}
      <ul className="flex flex-col gap-3">
        {/* A speech-to-text-only provider has no read-aloud card. */}
        {providers
          .filter((provider) => fn === "STT" || provider.speech)
          .map((provider) => (
            <VoiceProviderCard
              // Keyed by provider only, so a save keeps the card and returns focus to its trigger.
              key={provider.provider}
              fn={fn}
              provider={provider}
              connection={connections.find((item) => item.provider === provider.provider)}
              autoSelect={!active}
              disabled={select.isPending}
              onSelect={choose}
            />
          ))}
      </ul>
      {select.isError && (
        <p role="alert" className="text-sm text-status-danger-content">
          {problemMessage(voiceProblem(select.error))}
        </p>
      )}
    </section>
  );
}

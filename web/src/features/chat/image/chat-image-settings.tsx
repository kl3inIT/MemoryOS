import { ImageIcon } from "lucide-react";
import { ConnectionStatusBadge } from "@/components/composites/connection-form";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { ProviderCard } from "@/components/provider-logos/provider-card";
import { ProviderLogo } from "@/components/provider-logos/provider-logo";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { ImageConnectionCard, ModelSummary } from "./image-connection-card";
import {
  imageProblem,
  imageProviderMarks,
  imageProviderNames,
  useImageConnections,
  type ImageProvider,
} from "./use-image-connections";

/** The image generation administration: the provider in use and each provider's connection. */
export function ChatImageSettings() {
  const ui = useAppTranslation();
  const problemMessage = useProblemMessage();
  const manager = useApplicationSession().capabilities.includes("MODELS_MANAGE");
  const { providers, connections, select } = useImageConnections(manager);
  if (!manager)
    return (
      <p role="alert" className="p-6">
        {ui("Bạn không có quyền quản lý mô hình.")}
      </p>
    );
  const choose = (provider: ImageProvider | null) =>
    select.mutate({ body: { provider: provider ?? undefined } });
  const catalog = providers.data ?? [];
  const configured = connections.data ?? [];
  const active = configured.find((connection) => connection.active);
  const activeProvider =
    active && catalog.find((provider) => provider.provider === active.provider);
  return (
    <SettingsLayout>
      <PageHeader
        title={ui("Tạo ảnh")}
        icon={<ImageIcon />}
        description={ui("Cài đặt nhà cung cấp Chat dùng để tạo và sửa ảnh.")}
      />
      {providers.isError || connections.isError ? (
        <Alert variant="destructive">
          <AlertTitle>{ui("Không tải được cài đặt tạo ảnh.")}</AlertTitle>
          <div>
            <Button onClick={() => void Promise.all([providers.refetch(), connections.refetch()])}>
              {ui("Tải lại")}
            </Button>
          </div>
        </Alert>
      ) : providers.isPending || connections.isPending ? (
        <p role="status">{ui("Đang tải…")}</p>
      ) : (
        <div className="flex flex-col gap-8">
          <section aria-label={ui("Đang dùng")} className="flex flex-col gap-3">
            <h2 className="font-heading-h3 text-content-primary">{ui("Đang dùng")}</h2>
            {active && activeProvider ? (
              <ProviderCard
                logo={<ProviderLogo mark={imageProviderMarks[active.provider]} />}
                name={imageProviderNames[active.provider]}
                description={<ModelSummary provider={activeProvider} model={active.model} />}
                selected
                actions={
                  <>
                    <ConnectionStatusBadge>{ui("Đang dùng")}</ConnectionStatusBadge>
                    <Button
                      size="sm"
                      prominence="secondary"
                      disabled={select.isPending}
                      onClick={() => choose(null)}
                    >
                      {ui("Tắt tạo ảnh")}
                    </Button>
                  </>
                }
              />
            ) : (
              <Alert role="note">
                <AlertDescription>
                  {ui("Chọn một nhà cung cấp để bật tạo ảnh trong Chat.")}
                </AlertDescription>
              </Alert>
            )}
          </section>
          <section aria-label={ui("Nhà cung cấp")} className="flex flex-col gap-3">
            <h2 className="font-heading-h3 text-content-primary">{ui("Nhà cung cấp")}</h2>
            {catalog.map((provider) => (
              <ImageConnectionCard
                key={`${provider.provider}:${
                  configured.find((c) => c.provider === provider.provider)?.revision ?? "new"
                }`}
                provider={provider}
                connection={configured.find((c) => c.provider === provider.provider)}
                replacements={configured
                  .filter((c) => c.provider !== provider.provider && c.credentialConfigured)
                  .map((c) => c.provider)}
                disabled={select.isPending}
                onSelect={choose}
              />
            ))}
          </section>
        </div>
      )}
      {select.error ? (
        <Alert variant="destructive">
          <AlertTitle>{problemMessage(imageProblem(select.error))}</AlertTitle>
        </Alert>
      ) : null}
    </SettingsLayout>
  );
}

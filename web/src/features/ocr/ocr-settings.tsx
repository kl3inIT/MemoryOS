import { FileText } from "lucide-react";
import { ConnectionStatusBadge } from "@/components/composites/connection-form";
import { SectionHeader } from "@/components/composites/section-header";
import { ProviderCard } from "@/components/provider-logos/provider-card";
import { Alert, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { OcrConnectionCard } from "./ocr-connection-card";
import {
  ocrProblem,
  useOcrConnections,
  useVisionModels,
  type OcrProvider,
} from "./use-ocr-connections";

/** The OCR tab: what reads scans and images, Docling's own OCR, an OCR service or a model that reads images. */
export function OcrSettings() {
  const ui = useAppTranslation();
  const manager = useApplicationSession().capabilities.includes("MODELS_MANAGE");
  const problemMessage = useProblemMessage();
  const { providers, connections, select } = useOcrConnections(manager);
  const vision = useVisionModels(manager);
  if (!manager)
    return (
      <p role="alert" className="p-6">
        {ui("Bạn không có quyền quản lý mô hình.")}
      </p>
    );
  const choose = (provider: OcrProvider | null) =>
    select.mutate({ body: { provider: provider ?? undefined } });
  const builtIn = !connections.data?.some((connection) => connection.active);
  return (
    <>
      {providers.isError || connections.isError ? (
        <Alert variant="destructive">
          <AlertTitle>{ui("Không tải được kết nối OCR.")}</AlertTitle>
          <div>
            <Button onClick={() => void Promise.all([providers.refetch(), connections.refetch()])}>
              {ui("Tải lại")}
            </Button>
          </div>
        </Alert>
      ) : providers.isPending || connections.isPending ? (
        <p role="status">{ui("Đang tải…")}</p>
      ) : (
        <section aria-labelledby="ocr-readers" className="flex flex-col gap-3">
          <SectionHeader id="ocr-readers" title={ui("Đọc bản scan và ảnh")} />
          <ProviderCard
            as="section"
            aria-label={ui("Docling")}
            logo={<FileText />}
            name={ui("Docling")}
            description={ui("Tích hợp sẵn")}
            selected={builtIn}
            actions={
              builtIn ? (
                <ConnectionStatusBadge>{ui("Đang dùng")}</ConnectionStatusBadge>
              ) : (
                <Button
                  size="sm"
                  prominence="secondary"
                  disabled={select.isPending}
                  onClick={() => choose(null)}
                >
                  {ui("Dùng Docling")}
                </Button>
              )
            }
          />
          {providers.data.map((provider) => {
            const connection = connections.data.find((c) => c.provider === provider.provider);
            return (
              <OcrConnectionCard
                key={`${provider.provider}:${connection?.revision ?? "new"}`}
                provider={provider}
                connection={connection}
                models={vision.models}
                disabled={select.isPending}
                onSelect={() => choose(provider.provider)}
              />
            );
          })}
        </section>
      )}
      {select.error ? (
        <Alert variant="destructive">
          <AlertTitle>{problemMessage(ocrProblem(select.error))}</AlertTitle>
        </Alert>
      ) : null}
    </>
  );
}

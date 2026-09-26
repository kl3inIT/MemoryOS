import { useState } from "react";
import { useStore } from "@tanstack/react-form";
import type { WebConnectionResponse } from "@/lib/hey-api/types.gen";
import { useAppForm } from "@/components/form/app-form";
import { useFieldValidity } from "@/components/form/form-context";
import {
  ConnectionCard,
  ConnectionDialog,
  ConnectionForm,
} from "@/components/composites/connection-form";
import { ProviderLogo } from "@/components/provider-logos/provider-logo";
import { Field, FieldDescription, FieldError, FieldLabel } from "@/components/ui/field";
import {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupInput,
} from "@/components/ui/input-group";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useProblemMessage } from "@/lib/use-problem-message";
import {
  useWebConnectionMutations,
  webProblem,
  webProviderNames,
  type WebProvider,
} from "./use-web-connections";

const providerDetails: Record<WebProvider, { description: string; endpoint: string }> = {
  BRAVE: { description: "brave.com", endpoint: "https://api.search.brave.com" },
  TAVILY: { description: "tavily.com", endpoint: "https://api.tavily.com" },
  EXA: { description: "exa.ai", endpoint: "https://api.exa.ai" },
  SERPER: { description: "serper.dev", endpoint: "https://google.serper.dev" },
  GOOGLE_PSE: {
    description: "programmablesearchengine.google.com",
    endpoint: "https://customsearch.googleapis.com",
  },
  SEARXNG: { description: "searxng.org", endpoint: "" },
  NINEROUTER: { description: "9router.com", endpoint: "" },
  FIRECRAWL: { description: "firecrawl.dev", endpoint: "https://api.firecrawl.dev" },
};

/** One Web provider for search (`search`) or page reading: its card and its connection dialog. */
export function WebConnectionCard({
  provider,
  search,
  connection,
  disabled,
  onSelect,
}: {
  provider: WebProvider;
  search: boolean;
  connection?: WebConnectionResponse;
  disabled: boolean;
  onSelect: (search: boolean, provider: WebProvider) => void;
}) {
  const [open, setOpen] = useState(false);
  const active = !!(search ? connection?.searchActive : connection?.contentActive);
  const configured = !!connection && (provider === "SEARXNG" || !!connection.credentialConfigured);
  return (
    <ConnectionCard
      logo={<ProviderLogo mark={provider} />}
      name={webProviderNames[provider]}
      description={providerDetails[provider].description}
      active={active}
      configured={configured}
      disabled={disabled}
      onSelect={() => onSelect(search, provider)}
      onConfigure={() => setOpen(true)}
    >
      {open && (
        <WebConnectionDialog
          provider={provider}
          search={search}
          connection={connection}
          configured={configured}
          disabled={disabled}
          onClose={() => setOpen(false)}
        />
      )}
    </ConnectionCard>
  );
}

function WebConnectionDialog({
  provider,
  search,
  connection,
  configured,
  disabled,
  onClose,
}: {
  provider: WebProvider;
  search: boolean;
  connection?: WebConnectionResponse;
  configured: boolean;
  disabled: boolean;
  onClose: () => void;
}) {
  const ui = useAppTranslation();
  const { save, test, engines } = useWebConnectionMutations();
  const form = useAppForm({
    defaultValues: {
      endpoint: connection?.endpoint ?? "",
      engineId: connection?.engineId ?? "",
      key: "",
    },
    onSubmit: async ({ value }) => {
      test.reset();
      try {
        await save.mutateAsync({
          path: { provider },
          body: {
            endpoint: value.endpoint,
            engineId: value.engineId,
            revision: connection?.revision ?? 0,
            credentialAction: value.key ? "REPLACE" : "KEEP",
            credentialValue: value.key || undefined,
          },
        });
        onClose();
      } catch {
        // The failure stays on the mutation and shows in the form.
      }
    },
  });
  const endpoint = useStore(form.store, (state) => state.values.endpoint);
  const busy = disabled || save.isPending || test.isPending || engines.isPending;
  const failure = save.error ?? test.error;
  const needsEndpoint = provider === "SEARXNG" || provider === "NINEROUTER";
  return (
    <ConnectionDialog open onOpenChange={(next) => !next && onClose()} busy={busy}>
      <ConnectionForm
        title={webProviderNames[provider]}
        description={providerDetails[provider].description}
        onSubmit={() => void form.handleSubmit()}
        saving={save.isPending}
        busy={busy}
        onTest={
          configured
            ? () => {
                save.reset();
                test.mutate({ path: { provider }, body: { search } });
              }
            : undefined
        }
        tested={test.isSuccess}
        error={failure ? webProblem(failure) : undefined}
        onClose={onClose}
      >
        <form.AppField name="endpoint">
          {(field) => (
            <field.TextField
              label={
                provider === "SEARXNG"
                  ? ui("Địa chỉ SearXNG")
                  : provider === "NINEROUTER"
                    ? ui("Địa chỉ 9Router")
                    : ui("Địa chỉ tùy chỉnh (để trống dùng mặc định)")
              }
              required={needsEndpoint}
              maxLength={2048}
              placeholder={
                provider === "NINEROUTER"
                  ? "https://9router.example.com/v1"
                  : providerDetails[provider].endpoint || "https://searx.example.com"
              }
            />
          )}
        </form.AppField>
        {provider === "GOOGLE_PSE" && (
          <form.AppField name="engineId">
            {(field) => (
              <field.TextField label={ui("Mã công cụ tìm kiếm")} required maxLength={200} />
            )}
          </form.AppField>
        )}
        {provider === "NINEROUTER" && (
          <form.AppField name="engineId">
            {() => (
              <NineRouterEngineField
                engines={engines.data?.engines}
                failure={engines.error ? webProblem(engines.error) : undefined}
                listDisabled={busy || !endpoint.trim()}
                onList={() =>
                  engines.mutate(
                    {
                      path: { provider },
                      body: { endpoint, key: form.getFieldValue("key") || null },
                    },
                    {
                      onSuccess: ({ engines: found }) => {
                        const [only, ...others] = found;
                        if (!form.getFieldValue("engineId") && only && others.length === 0)
                          form.setFieldValue("engineId", only);
                      },
                    },
                  )
                }
              />
            )}
          </form.AppField>
        )}
        <form.AppField name="key">
          {(field) => (
            <field.TextField
              label={ui("Khóa API")}
              type="password"
              autoComplete="new-password"
              maxLength={8192}
              placeholder={
                connection?.credentialConfigured ? ui("Đã lưu khóa; để trống để giữ nguyên") : ""
              }
            />
          )}
        </form.AppField>
      </ConnectionForm>
    </ConnectionDialog>
  );
}

/**
 * 9Router lists its connected search engines; the field stays free text for anything it does not report.
 * A custom control bound to the `engineId` field.
 */
function NineRouterEngineField({
  engines,
  failure,
  listDisabled,
  onList,
}: {
  engines?: string[];
  failure?: ReturnType<typeof webProblem>;
  listDisabled: boolean;
  onList: () => void;
}) {
  const ui = useAppTranslation();
  const problemMessage = useProblemMessage();
  const { field, invalid, errors } = useFieldValidity<string>();
  return (
    <Field data-invalid={invalid || undefined}>
      <FieldLabel htmlFor={field.name}>{ui("Engine tìm kiếm")}</FieldLabel>
      <InputGroup>
        <InputGroupInput
          id={field.name}
          name={field.name}
          value={field.state.value}
          required
          maxLength={200}
          list="nine-router-engines"
          aria-invalid={invalid || undefined}
          onBlur={field.handleBlur}
          onChange={(event) => field.handleChange(event.target.value)}
        />
        <InputGroupAddon align="inline-end">
          <InputGroupButton disabled={listDisabled} onClick={onList}>
            {ui("Lấy danh sách")}
          </InputGroupButton>
        </InputGroupAddon>
      </InputGroup>
      <datalist id="nine-router-engines">
        {engines?.map((engine) => (
          <option key={engine} value={engine}>
            {engine}
          </option>
        ))}
      </datalist>
      {engines && (
        <FieldDescription role="status">
          {engines.length > 0
            ? ui("9Router có {{count}} engine: {{names}}", {
                count: engines.length,
                names: engines.slice(0, 6).join(", "),
              })
            : ui(
                "9Router chưa kết nối engine tìm kiếm nào. Thêm provider tìm kiếm trong 9Router rồi thử lại.",
              )}
        </FieldDescription>
      )}
      {failure && <FieldError>{problemMessage(failure)}</FieldError>}
      {invalid ? <FieldError errors={errors} /> : null}
    </Field>
  );
}

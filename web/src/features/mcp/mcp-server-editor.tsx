import { useState } from "react";
import { revalidateLogic, useStore } from "@tanstack/react-form";
import { useQuery } from "@tanstack/react-query";
import { z } from "zod";
import { useAppForm } from "@/components/form/app-form";
import { CatalogDialog } from "@/components/composites/catalog-dialog";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Field,
  FieldDescription,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupInput,
} from "@/components/ui/input-group";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { listMcpGroupOptionsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { McpServerInput, McpServerView } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";

const SLUG = /^[a-z0-9]{1,16}$/;

type AuthType = McpServerInput["authType"];
type Performer = McpServerInput["authPerformer"];
type ProviderMode = NonNullable<McpServerInput["oauthProviderMode"]>;

/** What a server needs before it can be saved; the save stays disabled until the draft passes. */
function serverSchema(editing: boolean) {
  return z
    .object({
      slug: z.string().regex(SLUG),
      name: z.string().trim().min(1),
      description: z.string(),
      url: z.string().trim().min(1),
      authType: z.enum(["OAUTH", "API_TOKEN", "NONE"]),
      performer: z.enum(["PER_USER", "ADMIN"]),
      providerMode: z.enum(["AUTO_DISCOVERY", "KNOWN_PROVIDER"]),
      apiKey: z.string(),
      tenantWide: z.boolean(),
      groupIds: z.array(z.string()),
      acknowledged: z.boolean().refine((value) => value),
    })
    .refine((draft) => draft.tenantWide || draft.groupIds.length > 0, { path: ["groupIds"] })
    .refine(
      // A shared API-key connection needs its key on creation; editing keeps the stored one.
      (draft) =>
        draft.authType !== "API_TOKEN" ||
        draft.performer !== "ADMIN" ||
        editing ||
        draft.apiKey.trim() !== "",
      { path: ["apiKey"] },
    );
}

type ServerDraft = z.input<ReturnType<typeof serverSchema>>;

function serverInput(draft: ServerDraft, server?: McpServerView): McpServerInput {
  const sharedKey = draft.authType === "API_TOKEN" && draft.performer === "ADMIN";
  return {
    slug: draft.slug,
    name: draft.name.trim(),
    description: draft.description.trim() === "" ? null : draft.description.trim(),
    url: draft.url.trim(),
    authType: draft.authType,
    authPerformer: draft.authType === "NONE" ? "ADMIN" : draft.performer,
    oauthProviderMode: draft.authType === "OAUTH" ? draft.providerMode : undefined,
    oauthScopes: server?.oauthScopes ?? [],
    oauthAdditionalParameters: server?.oauthAdditionalParameters ?? {},
    headers: { action: "KEEP" },
    sharedApiKey:
      sharedKey && draft.apiKey.trim() !== ""
        ? { action: "REPLACE", value: draft.apiKey }
        : { action: "KEEP" },
    tenantWide: draft.tenantWide,
    groupIds: draft.tenantWide ? [] : draft.groupIds,
  };
}

function useServerForm(
  server: McpServerView | undefined,
  onSave: (input: McpServerInput, revision?: number) => void,
) {
  const schema = serverSchema(server !== undefined);
  const defaultValues: ServerDraft = {
    slug: server?.slug ?? "",
    name: server?.name ?? "",
    description: server?.description ?? "",
    url: server?.url ?? "",
    authType: server?.authType ?? "OAUTH",
    performer: server?.authPerformer ?? "PER_USER",
    providerMode: server?.oauthProviderMode ?? "AUTO_DISCOVERY",
    apiKey: "",
    tenantWide: server?.tenantWide ?? true,
    groupIds: server?.groupIds ?? [],
    // An existing server was acknowledged when it was added.
    acknowledged: server !== undefined,
  };
  return useAppForm({
    defaultValues,
    validationLogic: revalidateLogic(),
    validators: { onDynamic: schema },
    onSubmit: ({ value }) => onSave(serverInput(value, server), server?.revision),
  });
}

type ServerForm = ReturnType<typeof useServerForm>;

/**
 * Add or edit one MCP server. The slug is immutable because model-facing tool names embed it, and the
 * acknowledgement is required on creation: an MCP server runs third-party code paths against Tenant data.
 */
export function McpServerEditor({
  server,
  saving,
  error,
  onSave,
  onClose,
}: {
  server?: McpServerView;
  saving: boolean;
  error?: string;
  onSave: (input: McpServerInput, revision?: number) => void;
  onClose: () => void;
}) {
  const ui = useAppTranslation();
  const editing = server !== undefined;
  const form = useServerForm(server, onSave);
  const values = useStore(form.store, (state) => state.values);
  const complete = serverSchema(editing).safeParse(values).success;
  const slugInvalid = values.slug !== "" && !SLUG.test(values.slug);

  return (
    <CatalogDialog
      title={ui(editing ? "Sửa máy chủ MCP" : "Thêm máy chủ MCP")}
      description={ui("MemoryOS gọi máy chủ này qua Streamable HTTP.")}
      onClose={onClose}
    >
      <form
        noValidate
        className="flex flex-col gap-5"
        onSubmit={(event) => {
          event.preventDefault();
          if (complete && !saving) void form.handleSubmit();
        }}
      >
        <FieldGroup>
          <div className="grid gap-4 sm:grid-cols-2">
            <form.AppField name="name">
              {(field) => (
                <field.TextField label={ui("Tên")} placeholder={ui("Ví dụ: Google Drive")} />
              )}
            </form.AppField>
            <form.AppField name="slug">
              {(field) => (
                <Field data-invalid={slugInvalid || undefined}>
                  <FieldLabel htmlFor="mcp-slug">{ui("Mã ngắn")}</FieldLabel>
                  <Input
                    id="mcp-slug"
                    value={field.state.value}
                    disabled={editing}
                    placeholder={ui("Ví dụ: drive")}
                    aria-invalid={slugInvalid || undefined}
                    onBlur={field.handleBlur}
                    onChange={(event) => field.handleChange(event.target.value.toLowerCase())}
                  />
                  <FieldDescription>
                    {ui(
                      editing
                        ? "Không đổi được: tên công cụ gửi cho mô hình đã dùng mã này."
                        : "1-16 ký tự a-z hoặc 0-9. Công cụ sẽ có tên mcp_<mã>_<công cụ>.",
                    )}
                  </FieldDescription>
                </Field>
              )}
            </form.AppField>
          </div>
          <form.AppField name="url">
            {(field) => (
              <field.TextField
                label={ui("Địa chỉ máy chủ")}
                placeholder="https://drivemcp.googleapis.com/mcp"
                description={
                  editing && values.url.trim() !== server.url ? (
                    <span className="text-status-warning-content">
                      {ui("Đổi địa chỉ sẽ xoá thông tin đăng nhập đã lưu và danh sách công cụ.")}
                    </span>
                  ) : undefined
                }
              />
            )}
          </form.AppField>
          <form.AppField name="description">
            {(field) => <field.TextField label={ui("Mô tả")} />}
          </form.AppField>
          <AuthFields form={form} server={server} values={values} />
          <AccessFields form={form} tenantWide={values.tenantWide} />
          {!editing ? (
            <form.AppField name="acknowledged">
              {(field) => (
                <Field orientation="horizontal">
                  <Checkbox
                    id="mcp-acknowledged"
                    checked={field.state.value}
                    onCheckedChange={(checked) => field.handleChange(checked === true)}
                  />
                  <FieldLabel htmlFor="mcp-acknowledged">
                    <span className="font-secondary-body text-content-muted">
                      {ui(
                        "Tôi hiểu máy chủ MCP là bên thứ ba: công cụ của nó có thể đọc và thay đổi dữ liệu, và kết quả trả về là dữ liệu không đáng tin.",
                      )}
                    </span>
                  </FieldLabel>
                </Field>
              )}
            </form.AppField>
          ) : null}
        </FieldGroup>

        {error ? (
          <Alert variant="destructive" role="alert">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        ) : null}

        <div className="flex justify-end gap-2">
          <Button prominence="secondary" onClick={onClose}>
            {ui("Huỷ")}
          </Button>
          <Button type="submit" pending={saving} disabled={!complete || saving}>
            {ui(editing ? "Lưu" : "Thêm")}
          </Button>
        </div>
      </form>
    </CatalogDialog>
  );
}

function AuthFields({
  form,
  server,
  values,
}: {
  form: ServerForm;
  server?: McpServerView;
  values: ServerDraft;
}) {
  const ui = useAppTranslation();
  return (
    <FieldSet>
      <FieldLegend className="sr-only">{ui("Cách đăng nhập")}</FieldLegend>
      <form.AppField name="authType">
        {(field) => (
          <Choice<AuthType>
            label={ui("Cách đăng nhập")}
            value={field.state.value}
            onChange={field.handleChange}
            options={[
              { value: "OAUTH", label: ui("OAuth") },
              { value: "API_TOKEN", label: ui("Khoá API") },
              { value: "NONE", label: ui("Không cần") },
            ]}
          />
        )}
      </form.AppField>
      {server && values.authType !== server.authType ? (
        <FieldDescription>
          <span className="text-status-warning-content">
            {ui("Đổi cách đăng nhập sẽ xoá thông tin đăng nhập đã lưu.")}
          </span>
        </FieldDescription>
      ) : null}
      {values.authType !== "NONE" ? (
        <form.AppField name="performer">
          {(field) => (
            <Choice<Performer>
              label={ui("Ai đăng nhập")}
              hint={ui(
                "Mỗi người tự kết nối: công cụ chạy bằng quyền của chính người hỏi. Một kết nối dùng chung: mọi người dùng chung một tài khoản do bạn kết nối.",
              )}
              value={field.state.value}
              onChange={field.handleChange}
              options={[
                { value: "PER_USER", label: ui("Mỗi người tự kết nối") },
                { value: "ADMIN", label: ui("Một kết nối dùng chung") },
              ]}
            />
          )}
        </form.AppField>
      ) : null}
      {values.authType === "OAUTH" ? (
        <>
          <form.AppField name="providerMode">
            {(field) => (
              <Choice<ProviderMode>
                label={ui("Lấy thông tin OAuth thế nào")}
                value={field.state.value}
                onChange={field.handleChange}
                options={[
                  { value: "AUTO_DISCOVERY", label: ui("Tự dò máy chủ OAuth") },
                  { value: "KNOWN_PROVIDER", label: ui("Tự nhập điểm cuối") },
                ]}
              />
            )}
          </form.AppField>
          <RedirectUri />
        </>
      ) : null}
      {values.authType === "API_TOKEN" && values.performer === "ADMIN" ? (
        <form.AppField name="apiKey">
          {(field) => (
            <field.TextField
              label={ui("Khoá API dùng chung")}
              type="password"
              autoComplete="off"
              placeholder={server ? ui("Để trống để giữ khoá đã lưu") : ""}
            />
          )}
        </form.AppField>
      ) : null}
    </FieldSet>
  );
}

function AccessFields({ form, tenantWide }: { form: ServerForm; tenantWide: boolean }) {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const groups = useQuery({
    ...listMcpGroupOptionsOptions({ query: { size: 50 } }),
    enabled: !tenantWide,
  });
  return (
    <FieldSet>
      <FieldLegend className="sr-only">{ui("Ai dùng được")}</FieldLegend>
      <form.AppField name="tenantWide">
        {(field) => (
          <Choice
            label={ui("Ai dùng được")}
            value={field.state.value ? "ALL" : "GROUPS"}
            onChange={(value) => field.handleChange(value === "ALL")}
            options={[
              { value: "ALL", label: ui("Cả tổ chức") },
              { value: "GROUPS", label: ui("Chọn nhóm") },
            ]}
          />
        )}
      </form.AppField>
      {!tenantWide ? (
        <form.AppField name="groupIds">
          {(field) => (
            <div className="flex flex-col gap-2">
              {(groups.data?.items ?? []).map((group) => (
                <Field key={group.id} orientation="horizontal">
                  <Checkbox
                    id={`mcp-group-${group.id}`}
                    checked={field.state.value.includes(group.id)}
                    onCheckedChange={(checked) =>
                      field.handleChange(
                        checked === true
                          ? [...field.state.value, group.id]
                          : field.state.value.filter((id) => id !== group.id),
                      )
                    }
                  />
                  <FieldLabel htmlFor={`mcp-group-${group.id}`}>{group.name}</FieldLabel>
                </Field>
              ))}
              {groups.isError ? (
                <Alert variant="destructive" role="alert">
                  <AlertDescription>
                    {problem(presentProblem(groups.error, "initialLoad").message)}
                  </AlertDescription>
                </Alert>
              ) : null}
              {groups.data && groups.data.items.length === 0 ? (
                <FieldDescription>{ui("Chưa có nhóm nào.")}</FieldDescription>
              ) : null}
            </div>
          )}
        </form.AppField>
      ) : null}
    </FieldSet>
  );
}

/** A labelled set of mutually exclusive options; each option announces whether it is chosen. */
function Choice<T extends string>({
  label,
  hint,
  value,
  onChange,
  options,
}: {
  label: string;
  hint?: string;
  value: T;
  onChange: (value: T) => void;
  options: { value: T; label: string }[];
}) {
  return (
    <FieldSet>
      <FieldLegend variant="label">{label}</FieldLegend>
      {hint ? <FieldDescription>{hint}</FieldDescription> : null}
      <ToggleGroup
        type="single"
        variant="outline"
        size="sm"
        className="flex-wrap"
        value={value}
        // A single toggle group deselects on a second press; a choice always keeps one option.
        onValueChange={(next) => {
          if (next) onChange(next as T);
        }}
      >
        {options.map((option) => (
          <ToggleGroupItem key={option.value} value={option.value}>
            {option.label}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
    </FieldSet>
  );
}

/** Google and other providers reject a callback that is not registered, so the exact URI is copyable here. */
function RedirectUri() {
  const ui = useAppTranslation();
  const [copied, setCopied] = useState(false);
  const uri = `${window.location.origin}/login/oauth2/code/mcp`;
  return (
    <Field>
      <FieldLabel htmlFor="mcp-redirect-uri">{ui("Địa chỉ callback")}</FieldLabel>
      <FieldDescription>
        {ui("Đăng ký đúng địa chỉ này trong ứng dụng OAuth của mỗi tổ chức.")}
      </FieldDescription>
      <InputGroup>
        <InputGroupInput id="mcp-redirect-uri" readOnly value={uri} />
        <InputGroupAddon align="inline-end">
          <InputGroupButton
            onClick={() => {
              void navigator.clipboard?.writeText(uri);
              setCopied(true);
            }}
          >
            {ui(copied ? "Đã chép" : "Chép")}
          </InputGroupButton>
        </InputGroupAddon>
      </InputGroup>
    </Field>
  );
}

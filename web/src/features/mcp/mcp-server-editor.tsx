import { useId, useState, type ReactNode } from "react";
import { revalidateLogic, useStore } from "@tanstack/react-form";
import { useQuery } from "@tanstack/react-query";
import { ChevronDown, Plus, X } from "lucide-react";
import { z } from "zod";
import { useAppForm } from "@/components/form/app-form";
import { CatalogDialog } from "@/components/composites/catalog-dialog";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
  Field,
  FieldContent,
  FieldDescription,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
  FieldTitle,
} from "@/components/ui/field";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupInput,
} from "@/components/ui/input-group";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { listMcpGroupOptionsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { McpServerInput, McpServerView } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { slugFromName } from "./mcp-servers";

const SLUG = /^[a-z0-9]{1,16}$/;
/** Google drops the refresh token without these, so a connection ends when its first access token does. */
const GOOGLE_PARAMETERS = { access_type: "offline", prompt: "consent" };

type AuthType = McpServerInput["authType"];
type Performer = McpServerInput["authPerformer"];
type ProviderMode = NonNullable<McpServerInput["oauthProviderMode"]>;
type Row = { name: string; value: string };

function filledRows(rows: Row[]): Row[] {
  return rows
    .map((row) => ({ name: row.name.trim(), value: row.value.trim() }))
    .filter((row) => row.name !== "" || row.value !== "");
}

function rowsToMap(rows: Row[]): Record<string, string> {
  return Object.fromEntries(filledRows(rows).map((row) => [row.name, row.value]));
}

function rowsComplete(rows: Row[]): boolean {
  return filledRows(rows).every((row) => row.name !== "" && row.value !== "");
}

function scopeList(scopes: string): string[] {
  return [...new Set(scopes.split(/[\s,]+/).filter((scope) => scope !== ""))];
}

function isGoogleApi(url: string): boolean {
  try {
    return new URL(url.trim()).hostname.endsWith(".googleapis.com");
  } catch {
    return false;
  }
}

/** What a server needs before it can be saved; the save stays disabled until the draft passes. */
function serverSchema(editing: boolean) {
  return z
    .object({
      url: z.string().trim().min(1),
      name: z.string().trim().min(1),
      slug: z.string().regex(SLUG),
      slugEdited: z.boolean(),
      description: z.string(),
      authType: z.enum(["OAUTH", "API_TOKEN", "NONE"]),
      performer: z.enum(["PER_USER", "ADMIN"]),
      providerMode: z.enum(["AUTO_DISCOVERY", "KNOWN_PROVIDER"]),
      apiKey: z.string(),
      scopes: z.string(),
      parameters: z.array(z.object({ name: z.string(), value: z.string() })),
      headerMode: z.enum(["KEEP", "REPLACE", "REMOVE"]),
      headers: z.array(z.object({ name: z.string(), value: z.string() })),
      tenantWide: z.boolean(),
      groupIds: z.array(z.string()),
    })
    .refine((draft) => draft.tenantWide || draft.groupIds.length > 0, { path: ["groupIds"] })
    .refine((draft) => rowsComplete(draft.parameters), { path: ["parameters"] })
    .refine((draft) => draft.headerMode !== "REPLACE" || rowsComplete(draft.headers), {
      path: ["headers"],
    })
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

function serverInput(draft: ServerDraft, editing: boolean): McpServerInput {
  const oauth = draft.authType === "OAUTH";
  const sharedKey = draft.authType === "API_TOKEN" && draft.performer === "ADMIN";
  const headers = rowsToMap(draft.headers);
  return {
    slug: draft.slug,
    name: draft.name.trim(),
    description: draft.description.trim() === "" ? null : draft.description.trim(),
    url: draft.url.trim(),
    authType: draft.authType,
    authPerformer: draft.authType === "NONE" ? "ADMIN" : draft.performer,
    oauthProviderMode: oauth ? draft.providerMode : undefined,
    oauthScopes: oauth ? scopeList(draft.scopes) : [],
    oauthAdditionalParameters: oauth ? rowsToMap(draft.parameters) : {},
    headers:
      draft.headerMode === "REMOVE"
        ? { action: "REMOVE" }
        : draft.headerMode === "REPLACE" && Object.keys(headers).length > 0
          ? { action: "REPLACE", values: headers }
          : // Emptied rows remove a saved template; a new server simply has none.
            draft.headerMode === "REPLACE" && editing
            ? { action: "REMOVE" }
            : { action: "KEEP" },
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
    url: server?.url ?? "",
    name: server?.name ?? "",
    slug: server?.slug ?? "",
    slugEdited: server !== undefined,
    description: server?.description ?? "",
    authType: server?.authType ?? "OAUTH",
    performer: server?.authPerformer ?? "PER_USER",
    providerMode: server?.oauthProviderMode ?? "AUTO_DISCOVERY",
    apiKey: "",
    scopes: (server?.oauthScopes ?? []).join(" "),
    parameters: Object.entries(server?.oauthAdditionalParameters ?? {}).map(([name, value]) => ({
      name,
      value,
    })),
    // A new server writes its rows; an existing one keeps its template until replaced.
    headerMode: server === undefined ? "REPLACE" : "KEEP",
    headers: [],
    tenantWide: server?.tenantWide ?? true,
    groupIds: server?.groupIds ?? [],
  };
  return useAppForm({
    defaultValues,
    validationLogic: revalidateLogic(),
    validators: { onDynamic: schema },
    onSubmit: ({ value }) => onSave(serverInput(value, server !== undefined), server?.revision),
  });
}

type ServerForm = ReturnType<typeof useServerForm>;

/** An existing server opens its advanced settings when one of them is set. */
function hasAdvancedValues(server: McpServerView | undefined): boolean {
  return (
    server !== undefined &&
    (server.description !== null ||
      server.oauthScopes.length > 0 ||
      Object.keys(server.oauthAdditionalParameters).length > 0 ||
      server.headerNames.length > 0 ||
      server.oauthProviderMode === "KNOWN_PROVIDER")
  );
}

/**
 * Add or edit one MCP server, in the order an administrator decides: where it is, how it signs in, whose account
 * it uses and who may use it. What only some servers need waits under Advanced. The short code follows the name
 * until edited and is immutable once saved, because model-facing tool names embed it.
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

  return (
    <CatalogDialog title={ui(editing ? "Sửa máy chủ MCP" : "Thêm máy chủ MCP")} onClose={onClose}>
      <form
        noValidate
        className="flex flex-col gap-5"
        onSubmit={(event) => {
          event.preventDefault();
          if (complete && !saving) void form.handleSubmit();
        }}
      >
        <FieldGroup>
          <form.AppField name="url">
            {(field) => (
              <field.TextField
                label={ui("Địa chỉ máy chủ")}
                placeholder="https://mcp.linear.app/mcp"
                autoComplete="off"
                description={
                  editing && values.url.trim() !== server.url ? (
                    <span className="text-status-warning-content">
                      {ui("Đổi địa chỉ sẽ xóa thông tin đăng nhập đã lưu và danh sách công cụ.")}
                    </span>
                  ) : undefined
                }
              />
            )}
          </form.AppField>
          <form.AppField
            name="name"
            listeners={{
              onChange: ({ value }) => {
                if (!form.getFieldValue("slugEdited"))
                  form.setFieldValue("slug", slugFromName(value));
              },
            }}
          >
            {(field) => <field.TextField label={ui("Tên")} placeholder={ui("Ví dụ: Linear")} />}
          </form.AppField>
          <AuthFields form={form} server={server} values={values} />
          <AccessFields form={form} tenantWide={values.tenantWide} />
          <AdvancedFields form={form} server={server} values={values} />
        </FieldGroup>

        {error ? (
          <Alert variant="destructive" role="alert">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        ) : null}

        <div className="flex justify-end gap-2">
          <Button prominence="secondary" onClick={onClose}>
            {ui("Hủy")}
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
    <>
      <form.AppField name="authType">
        {(field) => (
          <Choice<AuthType>
            label={ui("Cách đăng nhập")}
            value={field.state.value}
            onChange={field.handleChange}
            options={[
              { value: "OAUTH", label: ui("OAuth") },
              { value: "API_TOKEN", label: ui("Khóa API") },
              { value: "NONE", label: ui("Không cần") },
            ]}
            warning={
              server && values.authType !== server.authType
                ? ui("Đổi cách đăng nhập sẽ xóa thông tin đăng nhập đã lưu.")
                : undefined
            }
          />
        )}
      </form.AppField>
      {values.authType !== "NONE" ? (
        <form.AppField name="performer">
          {(field) => (
            <Choice<Performer>
              label={ui("Tài khoản nào")}
              value={field.state.value}
              onChange={field.handleChange}
              options={[
                { value: "PER_USER", label: ui("Mỗi người dùng tài khoản của mình") },
                { value: "ADMIN", label: ui("Dùng chung một tài khoản") },
              ]}
            />
          )}
        </form.AppField>
      ) : null}
      {values.authType === "API_TOKEN" && values.performer === "ADMIN" ? (
        <form.AppField name="apiKey">
          {(field) => (
            <field.TextField
              label={ui("Khóa API dùng chung")}
              type="password"
              autoComplete="off"
              placeholder={
                server?.sharedCredentialConfigured ? ui("Để trống để giữ khóa đã lưu") : ""
              }
            />
          )}
        </form.AppField>
      ) : null}
    </>
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
    <>
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
    </>
  );
}

function AdvancedFields({
  form,
  server,
  values,
}: {
  form: ServerForm;
  server?: McpServerView;
  values: ServerDraft;
}) {
  const ui = useAppTranslation();
  const [open, setOpen] = useState(hasAdvancedValues(server));
  const editing = server !== undefined;
  const oauth = values.authType === "OAUTH";
  const slugInvalid = values.slug !== "" && !SLUG.test(values.slug);
  const parameterMap = rowsToMap(values.parameters);
  const googleMissing =
    oauth &&
    isGoogleApi(values.url) &&
    Object.entries(GOOGLE_PARAMETERS).some(([name, value]) => parameterMap[name] !== value);

  return (
    <Collapsible open={open || googleMissing} onOpenChange={setOpen}>
      <CollapsibleTrigger asChild>
        <Button prominence="internal" size="sm" className="-ml-2 self-start">
          {ui("Nâng cao")}
          <ChevronDown
            data-icon="inline-end"
            className={open || googleMissing ? "rotate-180" : ""}
          />
        </Button>
      </CollapsibleTrigger>
      <CollapsibleContent>
        <FieldGroup className="mt-4">
          <form.AppField name="slug">
            {(field) => (
              <Field data-invalid={slugInvalid || undefined}>
                <FieldLabel htmlFor="mcp-slug">{ui("Mã ngắn")}</FieldLabel>
                <Input
                  id="mcp-slug"
                  value={field.state.value}
                  disabled={editing}
                  aria-invalid={slugInvalid || undefined}
                  onBlur={field.handleBlur}
                  onChange={(event) => {
                    form.setFieldValue("slugEdited", true);
                    field.handleChange(event.target.value.toLowerCase());
                  }}
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
          <form.AppField name="description">
            {(field) => <field.TextField label={ui("Mô tả")} optional />}
          </form.AppField>
          {oauth ? (
            <>
              <form.AppField name="providerMode">
                {(field) => (
                  <Choice<ProviderMode>
                    label={ui("Ứng dụng OAuth")}
                    value={field.state.value}
                    onChange={field.handleChange}
                    options={[
                      { value: "AUTO_DISCOVERY", label: ui("Tự dò từ máy chủ") },
                      { value: "KNOWN_PROVIDER", label: ui("Nhập ứng dụng OAuth thủ công") },
                    ]}
                  />
                )}
              </form.AppField>
              <form.AppField name="scopes">
                {(field) => (
                  <field.TextField
                    label={ui("Scopes")}
                    optional
                    autoComplete="off"
                    description={ui("Cách nhau bằng dấu cách.")}
                  />
                )}
              </form.AppField>
              <form.AppField name="parameters">
                {(field) => (
                  <Rows
                    legend={ui("Tham số cấp quyền thêm")}
                    rows={field.state.value}
                    onChange={field.handleChange}
                    namePlaceholder="access_type"
                    valuePlaceholder="offline"
                    footer={
                      googleMissing ? (
                        <Alert>
                          <AlertDescription>
                            <div className="flex flex-wrap items-center justify-between gap-2">
                              {ui(
                                "Google chỉ giữ kết nối quá một giờ khi có access_type=offline và prompt=consent.",
                              )}
                              <Button
                                size="sm"
                                prominence="secondary"
                                onClick={() => {
                                  // The notice goes once the parameters are in; the section stays open.
                                  setOpen(true);
                                  field.handleChange([
                                    ...field.state.value.filter(
                                      (row) => !Object.hasOwn(GOOGLE_PARAMETERS, row.name.trim()),
                                    ),
                                    ...Object.entries(GOOGLE_PARAMETERS).map(([name, value]) => ({
                                      name,
                                      value,
                                    })),
                                  ]);
                                }}
                              >
                                {ui("Thêm tham số Google")}
                              </Button>
                            </div>
                          </AlertDescription>
                        </Alert>
                      ) : null
                    }
                  />
                )}
              </form.AppField>
              <RedirectUri />
            </>
          ) : null}
          <HeaderFields form={form} server={server} values={values} />
        </FieldGroup>
      </CollapsibleContent>
    </Collapsible>
  );
}

/**
 * Header values may hold secrets and are never read back, so an existing template shows only its names and is
 * replaced or removed as a whole.
 */
function HeaderFields({
  form,
  server,
  values,
}: {
  form: ServerForm;
  server?: McpServerView;
  values: ServerDraft;
}) {
  const ui = useAppTranslation();
  const saved = server?.headerNames ?? [];
  const keyHint =
    values.authType === "API_TOKEN"
      ? ui("Đặt {api_key} vào giá trị. Để trống thì gửi Authorization: Bearer {api_key}.")
      : undefined;

  if (values.headerMode !== "REPLACE" && saved.length > 0) {
    return (
      <Field>
        <FieldTitle>{ui("Header")}</FieldTitle>
        <FieldDescription>
          {values.headerMode === "REMOVE" ? ui("Sẽ xóa khi lưu.") : saved.join(", ")}
        </FieldDescription>
        <div className="flex gap-2">
          <Button
            size="sm"
            prominence="secondary"
            onClick={() => {
              form.setFieldValue(
                "headers",
                saved.map((name) => ({ name, value: "" })),
              );
              form.setFieldValue("headerMode", "REPLACE");
            }}
          >
            {ui("Thay header")}
          </Button>
          {values.headerMode === "KEEP" ? (
            <Button
              size="sm"
              prominence="secondary"
              tone="danger"
              onClick={() => form.setFieldValue("headerMode", "REMOVE")}
            >
              {ui("Xóa header")}
            </Button>
          ) : (
            <Button
              size="sm"
              prominence="internal"
              onClick={() => form.setFieldValue("headerMode", "KEEP")}
            >
              {ui("Giữ header")}
            </Button>
          )}
        </div>
      </Field>
    );
  }
  return (
    <form.AppField name="headers">
      {(field) => (
        <Rows
          legend={ui("Header")}
          description={keyHint}
          rows={field.state.value}
          onChange={(rows) => {
            field.handleChange(rows);
            if (values.headerMode !== "REPLACE") form.setFieldValue("headerMode", "REPLACE");
          }}
          namePlaceholder={values.authType === "API_TOKEN" ? "Authorization" : "X-Header"}
          valuePlaceholder={values.authType === "API_TOKEN" ? "Bearer {api_key}" : ""}
          secret
        />
      )}
    </form.AppField>
  );
}

/** Name/value rows; a blank row is ignored, a half-filled one blocks the save. */
function Rows({
  legend,
  description,
  rows,
  onChange,
  namePlaceholder,
  valuePlaceholder,
  secret = false,
  footer,
}: {
  legend: string;
  description?: string;
  rows: Row[];
  onChange: (rows: Row[]) => void;
  namePlaceholder: string;
  valuePlaceholder: string;
  secret?: boolean;
  footer?: ReactNode;
}) {
  const ui = useAppTranslation();
  const id = useId();
  const update = (index: number, change: Partial<Row>) =>
    onChange(rows.map((row, at) => (at === index ? { ...row, ...change } : row)));
  return (
    <FieldSet>
      <FieldLegend variant="label">{legend}</FieldLegend>
      {description ? <FieldDescription>{description}</FieldDescription> : null}
      <div className="flex flex-col gap-2">
        {rows.map((row, index) => (
          <div key={index} className="flex items-center gap-2">
            <Input
              aria-label={ui("Tên")}
              id={`${id}-name-${index}`}
              value={row.name}
              placeholder={namePlaceholder}
              autoComplete="off"
              className="min-w-0 flex-1"
              onChange={(event) => update(index, { name: event.target.value })}
            />
            <Input
              aria-label={ui("Giá trị")}
              value={row.value}
              placeholder={valuePlaceholder}
              type={secret ? "password" : "text"}
              autoComplete="off"
              className="min-w-0 flex-1"
              onChange={(event) => update(index, { value: event.target.value })}
            />
            <IconButton
              size="sm"
              prominence="internal"
              aria-label={ui("Xóa dòng")}
              onClick={() => onChange(rows.filter((_, at) => at !== index))}
            >
              <X />
            </IconButton>
          </div>
        ))}
        <Button
          size="sm"
          prominence="internal"
          className="self-start"
          onClick={() => onChange([...rows, { name: "", value: "" }])}
        >
          <Plus data-icon="inline-start" />
          {ui("Thêm dòng")}
        </Button>
        {footer}
      </div>
    </FieldSet>
  );
}

/** Mutually exclusive options as radios, so the chosen one reads in both themes. */
function Choice<T extends string>({
  label,
  value,
  onChange,
  options,
  warning,
}: {
  label: string;
  value: T;
  onChange: (value: T) => void;
  options: { value: T; label: string }[];
  warning?: string;
}) {
  const id = useId();
  return (
    <FieldSet>
      <FieldLegend variant="label">{label}</FieldLegend>
      <RadioGroup value={value} onValueChange={(next) => onChange(next as T)}>
        <div className="flex flex-wrap gap-2">
          {options.map((option) => (
            <FieldLabel key={option.value} htmlFor={`${id}-${option.value}`} className="w-auto!">
              <Field orientation="horizontal">
                <RadioGroupItem id={`${id}-${option.value}`} value={option.value} />
                <FieldContent>
                  <FieldTitle>{option.label}</FieldTitle>
                </FieldContent>
              </Field>
            </FieldLabel>
          ))}
        </div>
      </RadioGroup>
      {warning ? (
        <FieldDescription>
          <span className="text-status-warning-content">{warning}</span>
        </FieldDescription>
      ) : null}
    </FieldSet>
  );
}

/** Google and other providers reject a callback that is not registered, so the exact URI is copyable here. */
function RedirectUri() {
  const ui = useAppTranslation();
  const [copied, setCopied] = useState(false);
  // The runbook requires MEMORYOS_MCP_REDIRECT_URI to be this origin's callback.
  const uri = `${window.location.origin}/login/oauth2/code/mcp`;
  return (
    <Field>
      <FieldLabel htmlFor="mcp-redirect-uri">{ui("Địa chỉ callback")}</FieldLabel>
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

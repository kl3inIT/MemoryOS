import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { CatalogDialog } from "@/features/models/catalog-dialog";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { listMcpGroupOptions } from "@/lib/hey-api/sdk.gen";
import type { McpServerInput, McpServerView } from "@/lib/hey-api/types.gen";

const SLUG = /^[a-z0-9]{1,16}$/;

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
  const problem = useProblemMessage();
  const editing = server !== undefined;
  const [slug, setSlug] = useState(server?.slug ?? "");
  const [name, setName] = useState(server?.name ?? "");
  const [description, setDescription] = useState(server?.description ?? "");
  const [url, setUrl] = useState(server?.url ?? "");
  const [authType, setAuthType] = useState<McpServerInput["authType"]>(server?.authType ?? "OAUTH");
  const [performer, setPerformer] = useState<McpServerInput["authPerformer"]>(
    server?.authPerformer ?? "PER_USER",
  );
  const [providerMode, setProviderMode] = useState<
    NonNullable<McpServerInput["oauthProviderMode"]>
  >(server?.oauthProviderMode ?? "AUTO_DISCOVERY");
  const [apiKey, setApiKey] = useState("");
  const [tenantWide, setTenantWide] = useState(server?.tenantWide ?? true);
  const [groupIds, setGroupIds] = useState<string[]>(server?.groupIds ?? []);
  const [acknowledged, setAcknowledged] = useState(editing);

  const groups = useQuery({
    queryKey: ["mcp", "group-options"],
    queryFn: async () =>
      (await listMcpGroupOptions({ query: { size: 50 }, throwOnError: true })).data,
    enabled: !tenantWide,
  });

  const slugValid = SLUG.test(slug);
  const complete =
    slugValid &&
    name.trim() !== "" &&
    url.trim() !== "" &&
    acknowledged &&
    (tenantWide || groupIds.length > 0) &&
    // A shared API-key connection needs its key on creation; editing keeps the stored one.
    (authType !== "API_TOKEN" || performer !== "ADMIN" || editing || apiKey.trim() !== "");

  const submit = () => {
    if (!complete || saving) return;
    onSave(
      {
        slug,
        name: name.trim(),
        description: description.trim() === "" ? null : description.trim(),
        url: url.trim(),
        authType,
        authPerformer: authType === "NONE" ? "ADMIN" : performer,
        oauthProviderMode: authType === "OAUTH" ? providerMode : undefined,
        oauthScopes: server?.oauthScopes ?? [],
        oauthAdditionalParameters: server?.oauthAdditionalParameters ?? {},
        headers: { action: "KEEP" },
        sharedApiKey:
          authType === "API_TOKEN" && performer === "ADMIN" && apiKey.trim() !== ""
            ? { action: "REPLACE", value: apiKey }
            : { action: "KEEP" },
        tenantWide,
        groupIds: tenantWide ? [] : groupIds,
      },
      server?.revision,
    );
  };

  return (
    <CatalogDialog
      title={ui(editing ? "Sửa máy chủ MCP" : "Thêm máy chủ MCP")}
      description={ui("MemoryOS gọi máy chủ này qua Streamable HTTP.")}
      onClose={onClose}
    >
      <div className="flex flex-col gap-5">
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="flex flex-col gap-2">
            <Label htmlFor="mcp-name">{ui("Tên")}</Label>
            <Input
              id="mcp-name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder={ui("Ví dụ: Google Drive")}
            />
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="mcp-slug">{ui("Mã ngắn")}</Label>
            <Input
              id="mcp-slug"
              value={slug}
              disabled={editing}
              onChange={(event) => setSlug(event.target.value.toLowerCase())}
              placeholder={ui("Ví dụ: drive")}
              aria-invalid={slug !== "" && !slugValid}
            />
            <p className="font-secondary-body text-content-muted">
              {ui(
                editing
                  ? "Không đổi được: tên công cụ gửi cho mô hình đã dùng mã này."
                  : "1-16 ký tự a-z hoặc 0-9. Công cụ sẽ có tên mcp_<mã>_<công cụ>.",
              )}
            </p>
          </div>
        </div>

        <div className="flex flex-col gap-2">
          <Label htmlFor="mcp-url">{ui("Địa chỉ máy chủ")}</Label>
          <Input
            id="mcp-url"
            value={url}
            onChange={(event) => setUrl(event.target.value)}
            placeholder="https://drivemcp.googleapis.com/mcp"
          />
          {editing && url.trim() !== server.url ? (
            <p className="font-secondary-body text-status-warning-content">
              {ui("Đổi địa chỉ sẽ xoá thông tin đăng nhập đã lưu và danh sách công cụ.")}
            </p>
          ) : null}
        </div>

        <div className="flex flex-col gap-2">
          <Label htmlFor="mcp-description">{ui("Mô tả")}</Label>
          <Input
            id="mcp-description"
            value={description}
            onChange={(event) => setDescription(event.target.value)}
          />
        </div>

        <fieldset className="flex flex-col gap-4">
          <legend className="sr-only">{ui("Cách đăng nhập")}</legend>
          <Choice
            label={ui("Cách đăng nhập")}
            value={authType}
            onChange={setAuthType}
            options={[
              { value: "OAUTH", label: ui("OAuth") },
              { value: "API_TOKEN", label: ui("Khoá API") },
              { value: "NONE", label: ui("Không cần") },
            ]}
          />
          {editing && authType !== server.authType ? (
            <p className="font-secondary-body text-status-warning-content">
              {ui("Đổi cách đăng nhập sẽ xoá thông tin đăng nhập đã lưu.")}
            </p>
          ) : null}

          {authType !== "NONE" ? (
            <Choice
              label={ui("Ai đăng nhập")}
              hint={ui(
                "Mỗi người tự kết nối: công cụ chạy bằng quyền của chính người hỏi. Một kết nối dùng chung: mọi người dùng chung một tài khoản do bạn kết nối.",
              )}
              value={performer}
              onChange={setPerformer}
              options={[
                { value: "PER_USER", label: ui("Mỗi người tự kết nối") },
                { value: "ADMIN", label: ui("Một kết nối dùng chung") },
              ]}
            />
          ) : null}

          {authType === "OAUTH" ? (
            <>
              <Choice
                label={ui("Lấy thông tin OAuth thế nào")}
                value={providerMode}
                onChange={setProviderMode}
                options={[
                  { value: "AUTO_DISCOVERY", label: ui("Tự dò máy chủ OAuth") },
                  { value: "KNOWN_PROVIDER", label: ui("Tự nhập điểm cuối") },
                ]}
              />
              <RedirectUri />
            </>
          ) : null}

          {authType === "API_TOKEN" && performer === "ADMIN" ? (
            <div className="flex flex-col gap-2">
              <Label htmlFor="mcp-key">{ui("Khoá API dùng chung")}</Label>
              <Input
                id="mcp-key"
                type="password"
                autoComplete="off"
                value={apiKey}
                onChange={(event) => setApiKey(event.target.value)}
                placeholder={editing ? ui("Để trống để giữ khoá đã lưu") : ""}
              />
            </div>
          ) : null}
        </fieldset>

        <fieldset className="flex flex-col gap-3">
          <legend className="sr-only">{ui("Ai dùng được")}</legend>
          <Choice
            label={ui("Ai dùng được")}
            value={tenantWide ? "ALL" : "GROUPS"}
            onChange={(value) => setTenantWide(value === "ALL")}
            options={[
              { value: "ALL", label: ui("Cả tổ chức") },
              { value: "GROUPS", label: ui("Chọn nhóm") },
            ]}
          />
          {!tenantWide ? (
            <div className="flex flex-col gap-2">
              {(groups.data?.items ?? []).map((group) => (
                <label key={group.id} className="flex items-center gap-2">
                  <Checkbox
                    checked={groupIds.includes(group.id)}
                    onCheckedChange={(checked) =>
                      setGroupIds((current) =>
                        checked === true
                          ? [...current, group.id]
                          : current.filter((id) => id !== group.id),
                      )
                    }
                  />
                  <span className="font-main-ui-body text-content-primary">{group.name}</span>
                </label>
              ))}
              {groups.isError ? (
                <p role="alert" className="font-secondary-body text-status-danger-content">
                  {problem(presentProblem(groups.error, "initialLoad").message)}
                </p>
              ) : null}
              {groups.data && groups.data.items.length === 0 ? (
                <p className="font-secondary-body text-content-muted">{ui("Chưa có nhóm nào.")}</p>
              ) : null}
            </div>
          ) : null}
        </fieldset>

        {!editing ? (
          <label className="flex items-start gap-2">
            <Checkbox
              checked={acknowledged}
              onCheckedChange={(checked) => setAcknowledged(checked === true)}
            />
            <span className="font-secondary-body text-content-muted">
              {ui(
                "Tôi hiểu máy chủ MCP là bên thứ ba: công cụ của nó có thể đọc và thay đổi dữ liệu, và kết quả trả về là dữ liệu không đáng tin.",
              )}
            </span>
          </label>
        ) : null}

        {error ? (
          <p role="alert" className="font-secondary-body text-status-danger-content">
            {error}
          </p>
        ) : null}

        <div className="flex justify-end gap-2">
          <Button type="button" prominence="secondary" onClick={onClose}>
            {ui("Huỷ")}
          </Button>
          <Button type="button" onClick={submit} disabled={!complete || saving}>
            {ui(editing ? "Lưu" : "Thêm")}
          </Button>
        </div>
      </div>
    </CatalogDialog>
  );
}

/**
 * A labelled set of mutually exclusive options. Plain buttons would announce no selected state, so the group
 * carries radio semantics and each option its own checked state.
 */
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
    <div className="flex flex-col gap-2">
      <p className="font-main-ui-body-strong text-content-primary" id={`mcp-choice-${label}`}>
        {label}
      </p>
      {hint ? <p className="font-secondary-body text-content-muted">{hint}</p> : null}
      <div
        role="radiogroup"
        aria-labelledby={`mcp-choice-${label}`}
        className="flex flex-wrap gap-2"
      >
        {options.map((option) => (
          <Button
            key={option.value}
            type="button"
            role="radio"
            aria-checked={value === option.value}
            prominence={value === option.value ? "primary" : "secondary"}
            size="sm"
            onClick={() => onChange(option.value)}
          >
            {option.label}
          </Button>
        ))}
      </div>
    </div>
  );
}

/** Google and other providers reject a callback that is not registered, so the exact URI is copyable here. */
function RedirectUri() {
  const ui = useAppTranslation();
  const [copied, setCopied] = useState(false);
  const uri = `${window.location.origin}/login/oauth2/code/mcp`;
  return (
    <div className="flex flex-col gap-2">
      <p className="font-main-ui-body-strong text-content-primary">{ui("Địa chỉ callback")}</p>
      <p className="font-secondary-body text-content-muted">
        {ui("Đăng ký đúng địa chỉ này trong ứng dụng OAuth của mỗi tổ chức.")}
      </p>
      <div className="flex items-center gap-2">
        <code className="min-w-0 flex-1 truncate rounded-md bg-surface-sunken px-2 py-1 font-secondary-body text-content-secondary">
          {uri}
        </code>
        <Button
          type="button"
          prominence="secondary"
          size="sm"
          onClick={() => {
            void navigator.clipboard?.writeText(uri);
            setCopied(true);
          }}
        >
          {ui(copied ? "Đã chép" : "Chép")}
        </Button>
      </div>
    </div>
  );
}

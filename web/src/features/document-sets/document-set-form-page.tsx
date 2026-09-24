import { useAppTranslation } from "@/i18n/use-app-translation";
import { useState, type ReactNode } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { ArrowLeft, Library, X } from "lucide-react";
import { PersonAvatar } from "@/components/composites/person-avatar";
import { Button } from "@/components/ui/button";
import { ClampedList } from "@/components/ui/clamped-list";
import { IconButton } from "@/components/ui/icon-button";
import { Checkbox } from "@/components/ui/checkbox";
import { HelpPopover } from "@/components/ui/help-popover";
import { Input } from "@/components/ui/input";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { chatActionError } from "@/features/chat/chat-action-utils";
import {
  documentSetSchema,
  documentSetsKey,
  loadPersonaSources,
  personLabel,
  type AgentPerson,
  type AgentRef,
  type DocumentSet,
} from "@/features/chat/chat-workspace-api";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { AgentPrincipalPicker } from "@/features/agents/agent-principal-picker";
import {
  createDocumentSet,
  getDocumentSet,
  shareDocumentSet,
  updateDocumentSet,
} from "@/lib/hey-api/sdk.gen";
import { DocumentSetSourcePicker } from "./document-set-source-picker";

type Draft = {
  name: string;
  description: string;
  isPublic: boolean;
  sourceIds: string[];
  people: AgentPerson[];
  groups: AgentRef[];
};

const draftOf = (set?: DocumentSet): Draft => ({
  name: set?.name ?? "",
  description: set?.description ?? "",
  isPublic: set?.isPublic ?? false,
  sourceIds: set?.sourceIds ?? [],
  people: set?.userShares ?? [],
  groups: set?.groupShares ?? [],
});

const sameIds = (left: string[], right: string[]) =>
  left.length === right.length && left.every((id) => right.includes(id));

/** Onyx `/admin/documents/sets/new` and `/[id]`: one card with name, description, sharing and Sources. */
/** Rows of chips kept in view before the rest move behind "+N". */
const visibleRows = 4;

export function DocumentSetFormPage({ documentSetId }: { documentSetId?: string }) {
  const ui = useAppTranslation();
  const { actorId, authorizationVersion } = useApplicationSession();
  const existing = useQuery({
    queryKey: [...documentSetsKey, documentSetId, actorId, authorizationVersion],
    enabled: documentSetId !== undefined,
    queryFn: async ({ signal }) =>
      documentSetSchema.parse(
        (
          await getDocumentSet({
            path: { documentSetId: documentSetId! },
            signal,
          })
        ).data,
      ),
  });
  const title = documentSetId ? ui("Sửa bộ tài liệu") : ui("Bộ tài liệu mới");

  return (
    <SettingsLayout className="gap-6">
      <Link
        to="/admin/document-sets"
        className="inline-flex w-fit items-center gap-2 rounded-lg font-secondary-action text-content-secondary outline-none transition-colors hover:text-content-primary focus-visible:ring-3 focus-visible:ring-focus-ring/40"
      >
        <ArrowLeft className="size-4" aria-hidden="true" />
        {ui("Quay lại")}
      </Link>
      <div className="border-b border-border-subtle pb-5">
        <PageHeader title={title} icon={<Library />} iconSize="lg" />
      </div>
      {documentSetId === undefined ? (
        <DocumentSetForm />
      ) : existing.isPending ? (
        <p role="status">{ui("Đang tải bộ tài liệu…")}</p>
      ) : existing.isError ? (
        <p role="alert" className="text-status-danger-content">
          {chatActionError(existing.error)}
        </p>
      ) : existing.data.permissions.edit ? (
        <DocumentSetForm key={existing.data.revision} existing={existing.data} />
      ) : (
        <p role="alert" className="text-status-danger-content">
          {ui("Bạn không có quyền sửa bộ tài liệu này.")}
        </p>
      )}
    </SettingsLayout>
  );
}

function DocumentSetForm({ existing }: { existing?: DocumentSet }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const navigate = useNavigate();
  const notify = useActionNotifications();
  const { actorId, authorizationVersion } = useApplicationSession();
  const [draft, setDraft] = useState(() => draftOf(existing));
  const [submitted, setSubmitted] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const sources = useQuery({
    queryKey: ["chat-persona-sources", actorId, authorizationVersion],
    queryFn: ({ signal }) => loadPersonaSources(signal),
  });
  const canShare = existing === undefined || existing.permissions.share;
  const nameMissing = draft.name.trim() === "";
  const sourcesMissing = draft.sourceIds.length === 0;
  const patch = (next: Partial<Draft>) => setDraft((current) => ({ ...current, ...next }));

  async function submit() {
    setSubmitted(true);
    if (nameMissing || sourcesMissing || saving) return;
    setSaving(true);
    setError(undefined);
    let saved: DocumentSet | undefined;
    try {
      const body = {
        name: draft.name.trim(),
        description: draft.description.trim(),
        sourceIds: draft.sourceIds,
        isPublic: draft.isPublic,
      };
      saved = documentSetSchema.parse(
        existing
          ? (
              await updateDocumentSet({
                path: { documentSetId: existing.id },
                query: { revision: existing.revision },
                body,
              })
            ).data
          : (
              await createDocumentSet({
                body,
              })
            ).data,
      );
      const actorIds = draft.people.map((person) => person.actorId);
      const groupIds = draft.groups.map((group) => group.id);
      const sharesChanged =
        !sameIds(
          actorIds,
          saved.userShares.map((person) => person.actorId),
        ) ||
        !sameIds(
          groupIds,
          saved.groupShares.map((group) => group.id),
        );
      if (canShare && sharesChanged) {
        await shareDocumentSet({
          path: { documentSetId: saved.id },
          query: { revision: saved.revision },
          body: { actorIds, groupIds },
        });
      }
      await cache.invalidateQueries({ queryKey: documentSetsKey });
      notify({
        title: existing ? ui("Đã cập nhật bộ tài liệu") : ui("Đã tạo bộ tài liệu"),
        tone: "success",
        surviveNavigation: true,
      });
      await navigate({ to: "/admin/document-sets" });
    } catch (cause) {
      if (saved) {
        // The set itself was saved but its shares were not; show the saved state and let the user retry sharing.
        await cache.invalidateQueries({ queryKey: documentSetsKey });
        notify({
          title: ui("Đã lưu bộ tài liệu nhưng chưa lưu được chia sẻ"),
          description: chatActionError(cause),
          tone: "error",
          surviveNavigation: true,
        });
        if (!existing)
          await navigate({
            to: "/admin/document-sets/$documentSetId",
            params: { documentSetId: saved.id },
            replace: true,
          });
        return;
      }
      setError(chatActionError(cause));
    } finally {
      setSaving(false);
    }
  }

  return (
    <form
      noValidate
      className="flex flex-col gap-6 rounded-2xl border border-border-subtle bg-surface-raised p-6"
      onSubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
    >
      <div className="flex flex-col gap-4">
        <Field
          label={ui("Tên")}
          error={submitted && nameMissing ? ui("Nhập tên bộ tài liệu.") : undefined}
        >
          <Input
            value={draft.name}
            maxLength={200}
            disabled={saving}
            placeholder={ui("Tên của bộ tài liệu")}
            aria-invalid={(submitted && nameMissing) || undefined}
            onChange={(event) => patch({ name: event.target.value })}
          />
        </Field>
        <Field label={ui("Mô tả")} optional>
          <Input
            value={draft.description}
            maxLength={2000}
            disabled={saving}
            placeholder={ui("Mô tả bộ tài liệu này gồm những gì")}
            onChange={(event) => patch({ description: event.target.value })}
          />
        </Field>
        {/* The help control stays outside the label, so reading it cannot toggle the choice. */}
        <div className="flex items-center gap-2">
          <label className="flex items-center gap-3">
            <Checkbox
              checked={draft.isPublic}
              disabled={saving}
              onCheckedChange={(checked) => patch({ isPublic: checked === true })}
            />
            <span className="font-main-ui-action text-content-primary">
              {ui("Công khai bộ tài liệu này?")}
            </span>
          </label>
          <HelpPopover label={ui("Công khai bộ tài liệu này?")}>
            <p>
              {ui(
                "Bật thì mọi người trong tổ chức đều dùng được bộ tài liệu này. Quyền đọc từng nguồn và tài liệu vẫn được kiểm tra riêng, nên bộ công khai không cấp thêm quyền cho ai.",
              )}
            </p>
          </HelpPopover>
        </div>
        {canShare && (
          <section className="flex flex-col gap-2">
            <div className="flex items-center gap-2">
              <h2 className="font-main-ui-action text-content-primary">
                {ui("Chia sẻ bộ tài liệu")}
              </h2>
              <HelpPopover label={ui("Chia sẻ bộ tài liệu")}>
                <p>
                  {draft.isPublic
                    ? ui(
                        "Bộ tài liệu đang công khai nên ai cũng dùng được. Danh sách dưới đây chỉ có tác dụng khi bạn tắt công khai.",
                      )
                    : ui(
                        "Chỉ bạn, quản trị viên trợ lý và những người hoặc nhóm được chia sẻ mới dùng được bộ tài liệu này. Quyền đọc từng nguồn và tài liệu vẫn được kiểm tra riêng.",
                      )}
                </p>
              </HelpPopover>
            </div>
            <AgentPrincipalPicker
              exclude={
                new Set([
                  ...draft.people.map((person) => person.actorId),
                  ...draft.groups.map((group) => group.id),
                ])
              }
              onPick={(principal) =>
                principal.kind === "person"
                  ? patch({ people: [...draft.people, principal.person] })
                  : patch({ groups: [...draft.groups, principal.group] })
              }
            />
            {(draft.people.length > 0 || draft.groups.length > 0) && (
              <ClampedList
                maxRows={visibleRows}
                label={ui("Đã chia sẻ với")}
                items={[
                  ...draft.people.map((person) => (
                    <ShareChip
                      key={person.actorId}
                      avatar={
                        <PersonAvatar name={personLabel(person)} seed={person.actorId} size="sm" />
                      }
                      label={personLabel(person)}
                      disabled={saving}
                      onRemove={() =>
                        patch({
                          people: draft.people.filter((other) => other.actorId !== person.actorId),
                        })
                      }
                    />
                  )),
                  ...draft.groups.map((group) => (
                    <ShareChip
                      key={group.id}
                      avatar={<PersonAvatar name={group.name} kind="group" size="sm" />}
                      label={group.name}
                      disabled={saving}
                      onRemove={() =>
                        patch({ groups: draft.groups.filter((other) => other.id !== group.id) })
                      }
                    />
                  )),
                ]}
              />
            )}
          </section>
        )}
      </div>

      <div className="border-t border-border-subtle" />

      <section className="flex flex-col gap-2">
        <h2 className="font-main-ui-action text-content-primary">{ui("Chọn nguồn")}</h2>
        <p className="font-secondary-body text-content-muted">
          {ui("Mọi tài liệu đã lập chỉ mục từ các nguồn được chọn sẽ thuộc bộ tài liệu này.")}
        </p>
        {sources.isError ? (
          <p role="alert" className="font-secondary-body text-status-danger-content">
            {ui("Không tải được nguồn.")}{" "}
            <Button
              type="button"
              size="sm"
              prominence="tertiary"
              onClick={() => void sources.refetch()}
            >
              {ui("Tải lại")}
            </Button>
          </p>
        ) : (
          <DocumentSetSourcePicker
            options={sources.data ?? []}
            known={existing?.sources ?? []}
            value={draft.sourceIds}
            disabled={saving || sources.isPending}
            invalid={submitted && sourcesMissing}
            onChange={(sourceIds) => patch({ sourceIds })}
          />
        )}
        {submitted && sourcesMissing && (
          <p className="font-secondary-body text-status-danger-content">
            {ui("Chọn ít nhất một nguồn.")}
          </p>
        )}
      </section>

      {error && (
        <p role="alert" className="text-status-danger-content">
          {error}
        </p>
      )}

      <div className="flex justify-center border-t border-border-subtle pt-5">
        <Button type="submit" className="w-56" disabled={saving} pending={saving}>
          {existing ? ui("Cập nhật bộ tài liệu") : ui("Tạo bộ tài liệu")}
        </Button>
      </div>
    </form>
  );
}

function Field({
  label,
  optional = false,
  error,
  children,
}: {
  label: string;
  optional?: boolean;
  error?: string;
  children: ReactNode;
}) {
  const ui = useAppTranslation();
  return (
    <label className="flex flex-col gap-1.5">
      <span className="font-main-ui-action text-content-primary">
        {label}
        {optional && (
          <span className="ml-1 font-main-ui-body text-content-muted">
            {ui("(không bắt buộc)")}
          </span>
        )}
      </span>
      {children}
      {error && <span className="font-secondary-body text-status-danger-content">{error}</span>}
    </label>
  );
}

function ShareChip({
  avatar,
  label,
  disabled,
  onRemove,
}: {
  avatar: ReactNode;
  label: string;
  disabled: boolean;
  onRemove: () => void;
}) {
  const ui = useAppTranslation();
  return (
    <span className="flex max-w-full items-center gap-1.5 rounded-xl border border-border-subtle bg-surface-raised py-1 pr-1 pl-1.5 font-secondary-body">
      {avatar}
      <span className="truncate" title={label}>
        {label}
      </span>
      <IconButton
        prominence="internal"
        size="sm"
        disabled={disabled}
        aria-label={ui("Bỏ {{v1}}", { v1: label })}
        onClick={onRemove}
      >
        <X />
      </IconButton>
    </span>
  );
}

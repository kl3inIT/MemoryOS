import { useAppTranslation } from "@/i18n/use-app-translation";
import type { ReactNode } from "react";
import { revalidateLogic, useStore } from "@tanstack/react-form";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { ArrowLeft, Library, X } from "lucide-react";
import { z } from "zod";
import { useAppForm, setServerErrors, useProblemErrors } from "@/components/form/app-form";
import { useFieldValidity } from "@/components/form/form-context";
import { PersonAvatar } from "@/components/composites/person-avatar";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Card, CardContent } from "@/components/ui/card";
import { ClampedList } from "@/components/ui/clamped-list";
import { Skeleton } from "@/components/ui/skeleton";
import {
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field";
import { HelpPopover } from "@/components/ui/help-popover";
import { IconButton } from "@/components/ui/icon-button";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Separator } from "@/components/ui/separator";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { actionErrorText } from "@/lib/action-errors";
import {
  documentSetOf,
  invalidateDocumentSets,
  type DocumentSet,
} from "@/features/document-sets/document-sets-api";
import { personaSourcesOptions } from "@/features/chat/chat-personas-api";
import { personLabel, type NamedRef, type Person } from "@/features/identity/principals";
import { PrincipalPicker } from "@/features/identity/principal-picker";
import {
  createDocumentSetMutation,
  getDocumentSetOptions,
  shareDocumentSetMutation,
  updateDocumentSetMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { zInput, zPrincipalGroup, zPrincipalPerson } from "@/lib/hey-api/zod.gen";
import { DocumentSetSourcePicker } from "./document-set-source-picker";
import { Button } from "@/components/ui/button";

const sameIds = (left: string[], right: string[]) =>
  left.length === right.length && left.every((id) => right.includes(id));

/** Rows of chips kept in view before the rest move behind "+N". */
const visibleRows = 4;

/** Onyx `/admin/documents/sets/new` and `/[id]`: one card with name, description, sharing and Sources. */
export function DocumentSetFormPage({ documentSetId }: { documentSetId?: string }) {
  const ui = useAppTranslation();
  const existing = useQuery({
    ...getDocumentSetOptions({ path: { documentSetId: documentSetId ?? "" } }),
    enabled: documentSetId !== undefined,
    select: documentSetOf,
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
        <Skeleton role="status" aria-label={ui("Đang tải bộ tài liệu…")} className="h-96" />
      ) : existing.isError ? (
        <Alert variant="destructive">
          <AlertDescription>{actionErrorText(existing.error)}</AlertDescription>
        </Alert>
      ) : existing.data.permissions.edit ? (
        <DocumentSetForm key={existing.data.revision} existing={existing.data} />
      ) : (
        <Alert variant="destructive">
          <AlertDescription>{ui("Bạn không có quyền sửa bộ tài liệu này.")}</AlertDescription>
        </Alert>
      )}
    </SettingsLayout>
  );
}

function DocumentSetForm({ existing }: { existing?: DocumentSet }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const navigate = useNavigate();
  const notify = useActionNotifications();
  const problemErrors = useProblemErrors();
  const sources = useQuery(personaSourcesOptions());
  const create = useMutation(createDocumentSetMutation());
  const update = useMutation(updateDocumentSetMutation());
  const share = useMutation(shareDocumentSetMutation());
  const canShare = existing === undefined || existing.permissions.share;
  // The generated request schema, with the rules this form adds and the shares the set is sent with.
  const schema = zInput.required().extend({
    name: zInput.shape.name.unwrap().trim().min(1, ui("Nhập tên bộ tài liệu.")),
    sourceIds: zInput.shape.sourceIds.unwrap().min(1, ui("Chọn ít nhất một nguồn.")),
    people: z.array(zPrincipalPerson.partial({ name: true, email: true })),
    groups: z.array(zPrincipalGroup),
  });

  const people: Person[] = existing?.userShares ?? [];
  const groups: NamedRef[] = existing?.groupShares ?? [];
  const form = useAppForm({
    defaultValues: {
      name: existing?.name ?? "",
      description: existing?.description ?? "",
      isPublic: existing?.isPublic ?? false,
      sourceIds: existing?.sourceIds ?? [],
      people,
      groups,
    },
    // Errors appear on submit and then follow each change until the form is valid.
    validationLogic: revalidateLogic(),
    validators: { onDynamic: schema },
    onSubmit: async ({ value, formApi }) => {
      const body = {
        name: value.name.trim(),
        description: value.description.trim(),
        sourceIds: value.sourceIds,
        isPublic: value.isPublic,
      };
      let saved: DocumentSet;
      try {
        saved = documentSetOf(
          existing
            ? await update.mutateAsync({
                path: { documentSetId: existing.id },
                query: { revision: existing.revision },
                body,
              })
            : await create.mutateAsync({ body }),
        );
      } catch (cause) {
        setServerErrors(formApi, problemErrors(cause));
        return;
      }
      const actorIds = value.people.map((person) => person.actorId);
      const groupIds = value.groups.map((group) => group.id);
      const sharesChanged =
        !sameIds(
          actorIds,
          saved.userShares.map((person) => person.actorId),
        ) ||
        !sameIds(
          groupIds,
          saved.groupShares.map((group) => group.id),
        );
      try {
        if (canShare && sharesChanged) {
          await share.mutateAsync({
            path: { documentSetId: saved.id },
            query: { revision: saved.revision },
            body: { actorIds, groupIds },
          });
        }
      } catch (cause) {
        // The set itself was saved but its shares were not; show the saved state and let the user retry sharing.
        await invalidateDocumentSets(cache, saved.id);
        notify({
          title: ui("Đã lưu bộ tài liệu nhưng chưa lưu được chia sẻ"),
          description: actionErrorText(cause),
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
      await invalidateDocumentSets(cache, saved.id);
      notify({
        title: existing ? ui("Đã cập nhật bộ tài liệu") : ui("Đã tạo bộ tài liệu"),
        tone: "success",
        surviveNavigation: true,
      });
      await navigate({ to: "/admin/document-sets" });
    },
  });
  const saving = useStore(form.store, (state) => state.isSubmitting);

  return (
    <Card>
      <CardContent>
        <form
          noValidate
          className="flex flex-col gap-6"
          onSubmit={(event) => {
            event.preventDefault();
            void form.handleSubmit();
          }}
        >
          <FieldGroup>
            <form.AppField name="name">
              {(field) => (
                <field.TextField
                  label={ui("Tên")}
                  maxLength={200}
                  disabled={saving}
                  placeholder={ui("Tên của bộ tài liệu")}
                />
              )}
            </form.AppField>
            <form.AppField name="description">
              {(field) => (
                <field.TextField
                  label={ui("Mô tả")}
                  optional
                  maxLength={2000}
                  disabled={saving}
                  placeholder={ui("Mô tả bộ tài liệu này gồm những gì")}
                />
              )}
            </form.AppField>
            <form.AppField name="isPublic">
              {(field) => (
                <field.CheckboxField label={ui("Công khai bộ tài liệu này?")} disabled={saving}>
                  {/* The help control stays outside the label, so reading it cannot toggle the choice. */}
                  <HelpPopover label={ui("Công khai bộ tài liệu này?")}>
                    <p>
                      {ui(
                        "Bật thì mọi người trong tổ chức đều dùng được bộ tài liệu này. Quyền đọc từng nguồn và tài liệu vẫn được kiểm tra riêng, nên bộ công khai không cấp thêm quyền cho ai.",
                      )}
                    </p>
                  </HelpPopover>
                </field.CheckboxField>
              )}
            </form.AppField>
            {canShare && (
              <section className="flex flex-col gap-2">
                <div className="flex items-center gap-2">
                  <h2 className="font-main-ui-action text-content-primary">
                    {ui("Chia sẻ bộ tài liệu")}
                  </h2>
                  <form.Subscribe selector={(state) => state.values.isPublic}>
                    {(isPublic) => (
                      <HelpPopover label={ui("Chia sẻ bộ tài liệu")}>
                        <p>
                          {isPublic
                            ? ui(
                                "Bộ tài liệu đang công khai nên ai cũng dùng được. Danh sách dưới đây chỉ có tác dụng khi bạn tắt công khai.",
                              )
                            : ui(
                                "Chỉ bạn, quản trị viên trợ lý và những người hoặc nhóm được chia sẻ mới dùng được bộ tài liệu này. Quyền đọc từng nguồn và tài liệu vẫn được kiểm tra riêng.",
                              )}
                        </p>
                      </HelpPopover>
                    )}
                  </form.Subscribe>
                </div>
                <form.Subscribe
                  selector={(state) => ({
                    people: state.values.people,
                    groups: state.values.groups,
                  })}
                >
                  {({ people, groups }) => (
                    <>
                      <PrincipalPicker
                        exclude={
                          new Set([
                            ...people.map((person) => person.actorId),
                            ...groups.map((group) => group.id),
                          ])
                        }
                        onPick={(principal) =>
                          principal.kind === "person"
                            ? form.setFieldValue("people", [...people, principal.person])
                            : form.setFieldValue("groups", [...groups, principal.group])
                        }
                      />
                      {(people.length > 0 || groups.length > 0) && (
                        <ClampedList
                          maxRows={visibleRows}
                          label={ui("Đã chia sẻ với")}
                          items={[
                            ...people.map((person) => (
                              <ShareChip
                                key={person.actorId}
                                avatar={
                                  <PersonAvatar
                                    name={personLabel(person)}
                                    seed={person.actorId}
                                    size="sm"
                                  />
                                }
                                label={personLabel(person)}
                                disabled={saving}
                                onRemove={() =>
                                  form.setFieldValue(
                                    "people",
                                    people.filter((other) => other.actorId !== person.actorId),
                                  )
                                }
                              />
                            )),
                            ...groups.map((group) => (
                              <ShareChip
                                key={group.id}
                                avatar={<PersonAvatar name={group.name} kind="group" size="sm" />}
                                label={group.name}
                                disabled={saving}
                                onRemove={() =>
                                  form.setFieldValue(
                                    "groups",
                                    groups.filter((other) => other.id !== group.id),
                                  )
                                }
                              />
                            )),
                          ]}
                        />
                      )}
                    </>
                  )}
                </form.Subscribe>
              </section>
            )}
          </FieldGroup>

          <Separator />

          <form.AppField name="sourceIds">
            {() => (
              <SourcesField
                options={sources.data ?? []}
                known={existing?.sources ?? []}
                disabled={saving || sources.isPending}
                loadFailed={sources.isError}
                onReload={() => void sources.refetch()}
              />
            )}
          </form.AppField>

          <form.AppForm>
            <form.FormError />
            <div className="flex justify-center border-t border-border-subtle pt-5">
              <form.SubmitButton className="w-56">
                {existing ? ui("Cập nhật bộ tài liệu") : ui("Tạo bộ tài liệu")}
              </form.SubmitButton>
            </div>
          </form.AppForm>
        </form>
      </CardContent>
    </Card>
  );
}

/** The Sources of the set: a custom control bound to the `sourceIds` field. */
function SourcesField({
  options,
  known,
  disabled,
  loadFailed,
  onReload,
}: {
  options: { id: string; name: string; type: string }[];
  known: { id: string; name: string }[];
  disabled: boolean;
  loadFailed: boolean;
  onReload: () => void;
}) {
  const ui = useAppTranslation();
  const { field, invalid, errors } = useFieldValidity<string[]>();
  return (
    <FieldSet data-invalid={invalid || undefined}>
      <FieldLegend variant="label">{ui("Chọn nguồn")}</FieldLegend>
      <FieldDescription>
        {ui("Mọi tài liệu đã lập chỉ mục từ các nguồn được chọn sẽ thuộc bộ tài liệu này.")}
      </FieldDescription>
      {loadFailed ? (
        <FieldError>
          {ui("Không tải được nguồn.")}{" "}
          <Button type="button" size="sm" prominence="tertiary" onClick={onReload}>
            {ui("Tải lại")}
          </Button>
        </FieldError>
      ) : (
        <DocumentSetSourcePicker
          options={options}
          known={known}
          value={field.state.value}
          disabled={disabled}
          invalid={invalid}
          onChange={(sourceIds) => field.handleChange(sourceIds)}
        />
      )}
      {invalid ? <FieldError errors={errors} /> : null}
    </FieldSet>
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

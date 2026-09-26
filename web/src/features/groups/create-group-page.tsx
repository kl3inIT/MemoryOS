import { useAppTranslation } from "@/i18n/use-app-translation";
import { revalidateLogic, useStore } from "@tanstack/react-form";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link, useBlocker, useNavigate } from "@tanstack/react-router";
import { ArrowLeft, Users } from "lucide-react";
import { useEffect, useRef } from "react";
import { z } from "zod";
import { useAppForm, setServerErrors } from "@/components/form/app-form";
import { PageHeader } from "@/components/composites/settings-layout";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { createGroupMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import { zCreateGroupRequest } from "@/lib/hey-api/zod.gen";
import { groupMutationError } from "./group-errors";

const formId = "create-group-form";

export function CreateGroupPage() {
  const ui = useAppTranslation();

  const navigate = useNavigate({ from: "/admin/groups/new" });
  const queryClient = useQueryClient();
  const createGroup = useMutation(createGroupMutation());
  const form = useAppForm({
    defaultValues: { name: "" },
    validationLogic: revalidateLogic(),
    validators: {
      onDynamic: z.object({
        name: zCreateGroupRequest.shape.name.trim().min(1, ui("Enter a group name.")),
      }),
    },
    onSubmit: async ({ value, formApi }) => {
      let groupId: string;
      try {
        groupId = (await createGroup.mutateAsync({ body: { name: value.name.trim() } })).id;
      } catch (cause) {
        setServerErrors(formApi, { form: ui(groupMutationError(cause, "create")), fields: {} });
        return;
      }
      await queryClient.invalidateQueries();
      leaving.current = true;
      await navigate({ to: "/admin/groups/$groupId", params: { groupId }, replace: true });
    },
  });
  const dirty = useStore(form.store, (state) => state.values.name.length > 0);
  // Set once the page leaves on purpose (created or discarded), so that navigation is not held.
  const leaving = useRef(false);
  const blocker = useBlocker({
    shouldBlockFn: () => dirty && !leaving.current,
    enableBeforeUnload: () => dirty && !leaving.current,
    withResolver: true,
  });
  // A create in flight finishes by opening the new group, so navigation waits for it instead of offering a discard.
  const creating = createGroup.isPending;
  useEffect(() => {
    if (creating && blocker.status === "blocked") blocker.reset();
  }, [creating, blocker]);

  async function cancel() {
    leaving.current = true;
    await navigate({ to: "/admin/groups", search: { page: 0, size: 20 }, replace: true });
  }

  return (
    <section className="mx-auto w-full max-w-[var(--page-width-narrow)] px-5 py-8 sm:px-8 sm:py-10">
      <Link
        to="/admin/groups"
        search={{ page: 0, size: 20 }}
        className="inline-flex items-center gap-2 rounded-lg font-secondary-action text-content-secondary outline-none transition-colors hover:text-content-primary focus-visible:ring-3 focus-visible:ring-focus-ring/40"
      >
        <ArrowLeft className="size-4" aria-hidden="true" />
        {ui("Groups")}
      </Link>

      <div className="mt-6">
        <PageHeader
          icon={<Users />}
          title={ui("Create group")}
          description={ui("Start with a unique group name.")}
          actions={
            <>
              {dirty ? (
                <ConfirmDialog
                  trigger={
                    <Button prominence="secondary" disabled={createGroup.isPending}>
                      {ui("Cancel")}
                    </Button>
                  }
                  title={ui("Discard this group?")}
                  description={ui("The unsaved group name will be lost.")}
                  confirmLabel={ui("Discard")}
                  pendingLabel={ui("Discarding…")}
                  onConfirm={cancel}
                />
              ) : (
                <Button asChild prominence="secondary" disabled={createGroup.isPending}>
                  <Link to="/admin/groups" search={{ page: 0, size: 20 }}>
                    {ui("Cancel")}
                  </Link>
                </Button>
              )}
              <form.AppForm>
                <form.SubmitButton form={formId}>
                  {createGroup.isPending ? ui("Creating…") : ui("Create group")}
                </form.SubmitButton>
              </form.AppForm>
            </>
          }
        />
      </div>

      <Card className="mt-6">
        <CardContent>
          <form
            id={formId}
            noValidate
            className="flex flex-col gap-4"
            onSubmit={(event) => {
              event.preventDefault();
              void form.handleSubmit();
            }}
          >
            <form.AppField name="name">
              {(field) => (
                <field.TextField
                  label={ui("Group name")}
                  maxLength={120}
                  disabled={createGroup.isPending}
                  placeholder={ui("e.g. Research")}
                  description={ui(
                    "You can add members, managers, capabilities, and Source associations after creation.",
                  )}
                />
              )}
            </form.AppField>
            <form.AppForm>
              <form.FormError />
            </form.AppForm>
          </form>
        </CardContent>
      </Card>
      <ConfirmDialog
        open={blocker.status === "blocked" && !creating}
        onOpenChange={(open) => {
          if (!open && blocker.status === "blocked") blocker.reset();
        }}
        title={ui("Discard this group?")}
        description={ui("The unsaved group name will be lost.")}
        confirmLabel={ui("Discard")}
        pendingLabel={ui("Discarding…")}
        onConfirm={async () => blocker.proceed?.()}
      />
    </section>
  );
}

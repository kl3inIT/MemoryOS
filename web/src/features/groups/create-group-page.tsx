import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link, useBlocker, useNavigate } from "@tanstack/react-router";
import { ArrowLeft, Users } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { PageHeader } from "@/components/ui/settings-layout";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Input } from "@/components/ui/input";
import { sameOriginMutationHeaders } from "@/lib/api";
import { createGroupMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import { groupMutationError } from "./group-errors";

export function CreateGroupPage() {
  const ui = useAppTranslation();

  const navigate = useNavigate({ from: "/admin/groups/new" });
  const queryClient = useQueryClient();
  const createGroup = useMutation(createGroupMutation());
  const [name, setName] = useState("");
  const [error, setError] = useState<AppCopy | null>(null);
  const dirty = name.length > 0;
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

  async function submit() {
    const normalizedName = name.trim();
    if (!normalizedName || createGroup.isPending) return;
    setError(null);
    try {
      const group = await createGroup.mutateAsync({
        headers: sameOriginMutationHeaders,
        body: { name: normalizedName },
      });
      await queryClient.invalidateQueries();
      leaving.current = true;
      await navigate({
        to: "/admin/groups/$groupId",
        params: { groupId: group.id },
        replace: true,
      });
    } catch (cause) {
      setError(groupMutationError(cause, "create"));
    }
  }

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
              <Button
                pending={createGroup.isPending}
                disabled={!name.trim()}
                onClick={() => void submit()}
              >
                {createGroup.isPending ? ui("Creating…") : ui("Create group")}
              </Button>
            </>
          }
        />
      </div>

      <form
        className="mt-6 rounded-xl border border-border-subtle bg-surface-raised p-5 sm:p-6"
        onSubmit={(event) => {
          event.preventDefault();
          void submit();
        }}
      >
        <label htmlFor="new-group-name" className="font-secondary-action text-content-primary">
          {ui("Group name")}
        </label>
        <Input
          id="new-group-name"
          autoFocus
          value={name}
          maxLength={120}
          disabled={createGroup.isPending}
          placeholder={ui("e.g. Research")}
          className="mt-2"
          onChange={(event) => setName(event.target.value)}
        />
        <p className="mt-2 font-secondary-body text-content-muted">
          {ui(
            "You can add members, managers, capabilities, and Source associations after creation.",
          )}
        </p>
        {error ? (
          <p
            role="alert"
            className="mt-4 rounded-lg bg-status-danger-surface px-4 py-3 font-secondary-body text-status-danger-content"
          >
            {ui(error)}
          </p>
        ) : null}
        <button type="submit" className="sr-only" tabIndex={-1} aria-hidden="true" />
      </form>
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

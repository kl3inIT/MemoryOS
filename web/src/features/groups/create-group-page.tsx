import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { ArrowLeft, ShieldPlus } from "lucide-react";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Input } from "@/components/ui/input";
import { sameOriginMutationHeaders } from "@/lib/api";
import { createGroupMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import { groupMutationError } from "./group-errors";

export function CreateGroupPage() {
  const navigate = useNavigate({ from: "/admin/groups/new" });
  const queryClient = useQueryClient();
  const createGroup = useMutation(createGroupMutation());
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const dirty = name.length > 0;

  useEffect(() => {
    if (!dirty) return;
    const warnBeforeUnload = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener("beforeunload", warnBeforeUnload);
    return () => window.removeEventListener("beforeunload", warnBeforeUnload);
  }, [dirty]);

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
        Groups
      </Link>

      <header className="mt-6 flex flex-col gap-4 border-b border-border-subtle pb-5 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <span className="grid size-10 place-items-center rounded-xl border border-border-subtle bg-surface-subtle text-content-secondary">
            <ShieldPlus className="size-5" aria-hidden="true" />
          </span>
          <div>
            <h1 className="font-heading-h2 text-content-primary">Create group</h1>
            <p className="mt-1 font-main-ui-body text-content-muted">
              Start with a unique group name.
            </p>
          </div>
        </div>
        <div className="flex gap-2">
          {dirty ? (
            <ConfirmDialog
              trigger={
                <Button prominence="secondary" disabled={createGroup.isPending}>
                  Cancel
                </Button>
              }
              title="Discard this group?"
              description="The unsaved group name will be lost."
              confirmLabel="Discard"
              pendingLabel="Discarding…"
              onConfirm={cancel}
            />
          ) : (
            <Button asChild prominence="secondary" disabled={createGroup.isPending}>
              <Link to="/admin/groups" search={{ page: 0, size: 20 }}>
                Cancel
              </Link>
            </Button>
          )}
          <Button
            pending={createGroup.isPending}
            disabled={!name.trim()}
            onClick={() => void submit()}
          >
            {createGroup.isPending ? "Creating…" : "Create group"}
          </Button>
        </div>
      </header>

      <form
        className="mt-6 rounded-xl border border-border-subtle bg-surface-raised p-5 sm:p-6"
        onSubmit={(event) => {
          event.preventDefault();
          void submit();
        }}
      >
        <label htmlFor="new-group-name" className="font-secondary-action text-content-primary">
          Group name
        </label>
        <Input
          id="new-group-name"
          autoFocus
          value={name}
          maxLength={120}
          disabled={createGroup.isPending}
          placeholder="e.g. Research"
          className="mt-2"
          onChange={(event) => setName(event.target.value)}
        />
        <p className="mt-2 font-secondary-body text-content-muted">
          You can add members, managers, capabilities, and Source associations after creation.
        </p>
        {error ? (
          <p
            role="alert"
            className="mt-4 rounded-lg bg-status-danger-surface px-4 py-3 font-secondary-body text-status-danger-content"
          >
            {error}
          </p>
        ) : null}
        <button type="submit" className="sr-only" tabIndex={-1} aria-hidden="true" />
      </form>
    </section>
  );
}

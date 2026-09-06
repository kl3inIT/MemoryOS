import { useMutation } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  activateUserMutation,
  deactivateUserMutation,
  revokeInvitationMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { createInvitation, rotateInvitation } from "@/lib/hey-api/sdk.gen";
import type { IssuedInvitation, UserListItem } from "@/lib/hey-api/types.gen";
import { invitationError, membershipActionError } from "./user-action-errors";

export type UserPendingAction = "activate" | "deactivate" | "rotate" | "revoke";

export function userActionPendingLabel(action: UserPendingAction) {
  if (action === "activate") return "Activating…";
  if (action === "deactivate") return "Deactivating…";
  if (action === "rotate") return "Rotating link…";
  return "Revoking…";
}

type UseUserActionsOptions = {
  onUsersChanged: () => void;
  onInvitationIssued: (invitation: IssuedInvitation) => void;
};

type InvitationIssue = { email: string } | { invitationId: string };

export function useUserActions({ onUsersChanged, onInvitationIssued }: UseUserActionsOptions) {
  const activateUser = useMutation(activateUserMutation());
  const deactivateUser = useMutation(deactivateUserMutation());
  const invitationIssue = useMutation({
    mutationFn: async (input: InvitationIssue) => {
      const { data } =
        "email" in input
          ? await createInvitation({
              body: input,
              headers: sameOriginMutationHeaders,
              throwOnError: true,
            })
          : await rotateInvitation({
              path: input,
              headers: sameOriginMutationHeaders,
              throwOnError: true,
            });
      onInvitationIssued(data);
    },
  });
  const revokeInvitation = useMutation(revokeInvitationMutation());
  const activeRows = useRef(new Set<string>());
  const issuanceInFlight = useRef(false);
  const [pendingActions, setPendingActions] = useState<Partial<Record<string, UserPendingAction>>>(
    {},
  );
  const [rowErrors, setRowErrors] = useState<Partial<Record<string, string>>>({});

  async function runAction(
    entry: UserListItem,
    action: UserPendingAction,
    request: () => Promise<void>,
  ) {
    const key = entry.actorId ? `actor:${entry.actorId}` : `invitation:${entry.invitationId}`;
    if (activeRows.current.has(key)) return;

    activeRows.current.add(key);
    setPendingActions((current) => ({ ...current, [key]: action }));
    setRowErrors((current) => {
      const next = { ...current };
      delete next[key];
      return next;
    });

    try {
      await request();
      onUsersChanged();
    } catch (error) {
      const message =
        action === "rotate" || action === "revoke"
          ? invitationError(error)
          : membershipActionError(error);
      setRowErrors((current) => ({ ...current, [key]: message }));
      throw error;
    } finally {
      activeRows.current.delete(key);
      setPendingActions((current) => {
        const next = { ...current };
        delete next[key];
        return next;
      });
    }
  }

  async function activate(entry: UserListItem) {
    const actorId = entry.actorId;
    if (!actorId) return;
    await runAction(entry, "activate", () =>
      activateUser.mutateAsync({
        path: { actorId },
        headers: sameOriginMutationHeaders,
      }),
    );
  }

  async function deactivate(entry: UserListItem) {
    const actorId = entry.actorId;
    if (!actorId) return;
    await runAction(entry, "deactivate", () =>
      deactivateUser.mutateAsync({
        path: { actorId },
        headers: sameOriginMutationHeaders,
      }),
    );
  }

  async function create(email: string) {
    if (issuanceInFlight.current) return;
    issuanceInFlight.current = true;
    try {
      await invitationIssue.mutateAsync({ email });
      onUsersChanged();
    } finally {
      issuanceInFlight.current = false;
    }
  }

  async function rotate(entry: UserListItem) {
    const invitationId = entry.invitationId;
    if (!invitationId || issuanceInFlight.current) return;
    issuanceInFlight.current = true;
    try {
      await runAction(entry, "rotate", () => invitationIssue.mutateAsync({ invitationId }));
    } finally {
      issuanceInFlight.current = false;
    }
  }

  async function revoke(entry: UserListItem) {
    const invitationId = entry.invitationId;
    if (!invitationId) return;
    await runAction(entry, "revoke", () =>
      revokeInvitation.mutateAsync({
        path: { invitationId },
        headers: sameOriginMutationHeaders,
      }),
    );
  }

  return {
    pendingActions,
    rowErrors,
    invitationPending: invitationIssue.isPending,
    create,
    activate,
    deactivate,
    rotate,
    revoke,
  };
}

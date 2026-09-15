import { useMutation } from "@tanstack/react-query";
import { TriangleAlert } from "lucide-react";
import { type RefObject, useId, useRef, useState } from "react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  renameSourceMutation,
  updateSourceAccessMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import { SourceAccessChoice } from "./source-access-choice";
import { sourceMutationError } from "./source-errors";

export type SourceMetadataField = "name" | "access";

const fileAccessModes = ["PUBLIC", "PRIVATE"] as const;
const googleDriveAccessModes = ["SYNC", "PRIVATE", "PUBLIC"] as const;

/** Renames a Source or changes who can read it. Mounted only while open. */
export function SourceMetadataDialog({
  source,
  field,
  disabled,
  restoreFocusRef,
  onClose,
  onSaved,
}: {
  source: SourceSummary;
  field: SourceMetadataField;
  disabled: boolean;
  restoreFocusRef: RefObject<HTMLElement | null>;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const ui = useAppTranslation();
  const accessLabelId = useId();
  const rename = useMutation(renameSourceMutation());
  const updateAccess = useMutation(updateSourceAccessMutation());
  const [name, setName] = useState(source.name);
  const [access, setAccess] = useState(source.access);
  const [error, setError] = useState<AppCopy | null>(null);
  const saved = useRef(false);
  const pending = rename.isPending || updateAccess.isPending;
  const unchanged = field === "name" ? name.trim() === source.name : access === source.access;

  async function save() {
    if (disabled || pending || unchanged || (field === "name" && !name.trim())) return;
    setError(null);
    try {
      if (field === "name") {
        await rename.mutateAsync({
          path: { sourceId: source.id },
          headers: sameOriginMutationHeaders,
          body: { name: name.trim() },
        });
      } else {
        await updateAccess.mutateAsync({
          path: { sourceId: source.id },
          headers: sameOriginMutationHeaders,
          body: { access },
        });
      }
      saved.current = true;
      onClose();
      await onSaved();
    } catch (cause) {
      setError(sourceMutationError(cause, "metadata"));
    }
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open && !pending) onClose();
      }}
    >
      <DialogContent
        className="sm:max-w-lg"
        {...(field === "name" ? { "aria-describedby": undefined } : {})}
        onCloseAutoFocus={(event) => {
          // A saved change moves focus itself, since it can remove the menu with the permission.
          event.preventDefault();
          if (!saved.current) restoreFocusRef.current?.focus();
        }}
      >
        <form
          className="grid gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            void save();
          }}
        >
          <DialogHeader>
            <DialogTitle>
              {field === "name" ? ui("Rename source") : ui("Change visibility")}
            </DialogTitle>
            {field === "access" ? (
              <DialogDescription>
                {source.type === "GOOGLE_DRIVE"
                  ? ui(
                      "Public documents can be read by everyone in this Tenant and Private documents by members of an associated group. Auto Sync documents can be read by people who can open the file in Google Drive, matched by their verified login email.",
                    )
                  : ui(
                      "Public files can be searched and read by everyone in this Tenant. Private files require membership in an associated group.",
                    )}
              </DialogDescription>
            ) : null}
          </DialogHeader>
          {field === "name" ? (
            <label className="grid gap-2">
              <span className="font-secondary-action text-content-primary">
                {ui("Source name")}
              </span>
              <Input
                value={name}
                maxLength={120}
                required
                disabled={disabled || pending}
                onChange={(event) => setName(event.target.value)}
              />
            </label>
          ) : (
            <div className="grid gap-2">
              <span id={accessLabelId} className="font-secondary-action text-content-primary">
                {ui("Visibility")}
              </span>
              <SourceAccessChoice
                id={`${accessLabelId}-choice`}
                labelledBy={accessLabelId}
                modes={source.type === "GOOGLE_DRIVE" ? googleDriveAccessModes : fileAccessModes}
                value={access}
                disabled={disabled || pending}
                onValueChange={setAccess}
              />
            </div>
          )}
          {error ? (
            <Alert variant="destructive">
              <TriangleAlert aria-hidden="true" />
              <AlertDescription>{ui(error)}</AlertDescription>
            </Alert>
          ) : null}
          <DialogFooter>
            <Button prominence="secondary" disabled={pending} onClick={onClose}>
              {ui("Cancel")}
            </Button>
            <Button
              type="submit"
              pending={pending}
              disabled={disabled || unchanged || (field === "name" && !name.trim())}
            >
              {field === "name" ? ui("Save name") : ui("Save visibility")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

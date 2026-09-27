import { useId, type ReactNode } from "react";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { PersonAvatar } from "@/components/composites/person-avatar";
import { ClampedList } from "@/components/ui/clamped-list";
import { IconButton } from "@/components/ui/icon-button";
import { Field, FieldTitle } from "@/components/ui/field";
import { X } from "lucide-react";
import { PrincipalPicker } from "@/features/identity/principal-picker";
import { personLabel } from "@/features/identity/principals";
import type { MeetingAudience } from "./meetings-api";

/**
 * Picks the members and Groups a meeting is shared with, reusing the invite field the Agent and Document Set
 * editors use so one picker behaves the same everywhere.
 */
export function MeetingShareField({
  value,
  disabled = false,
  label,
  onChange,
}: {
  value: MeetingAudience;
  disabled?: boolean;
  label: string;
  onChange: (audience: MeetingAudience) => void;
}) {
  const ui = useAppTranslation();
  const titleId = useId();
  const chosen = new Set([
    ...value.people.map((person) => person.actorId),
    ...value.groups.map((group) => group.id),
  ]);
  return (
    <Field aria-labelledby={titleId}>
      <FieldTitle id={titleId}>{label}</FieldTitle>
      <PrincipalPicker
        exclude={chosen}
        onPick={(principal) =>
          principal.kind === "person"
            ? onChange({ ...value, people: [...value.people, principal.person] })
            : onChange({ ...value, groups: [...value.groups, principal.group] })
        }
      />
      {(value.people.length > 0 || value.groups.length > 0) && (
        <ClampedList
          maxRows={3}
          label={ui("Đã chia sẻ với")}
          items={[
            ...value.people.map((person) => (
              <Chip
                key={person.actorId}
                avatar={<PersonAvatar name={personLabel(person)} seed={person.actorId} size="sm" />}
                label={personLabel(person)}
                disabled={disabled}
                onRemove={() =>
                  onChange({
                    ...value,
                    people: value.people.filter((other) => other.actorId !== person.actorId),
                  })
                }
              />
            )),
            ...value.groups.map((group) => (
              <Chip
                key={group.id}
                avatar={<PersonAvatar name={group.name} kind="group" size="sm" />}
                label={group.name}
                disabled={disabled}
                onRemove={() =>
                  onChange({
                    ...value,
                    groups: value.groups.filter((other) => other.id !== group.id),
                  })
                }
              />
            )),
          ]}
        />
      )}
    </Field>
  );
}

function Chip({
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
        <X aria-hidden="true" />
      </IconButton>
    </span>
  );
}

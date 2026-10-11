import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQuery } from "@tanstack/react-query";
import { RefreshCw } from "lucide-react";
import { useId } from "react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Field,
  FieldDescription,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { listLarkSpacesOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { LarkRootResponse } from "@/lib/hey-api/types.gen";
import { sourceMutationError } from "@/features/sources/shared/source-errors";
import { syncIntervalUnitName, syncIntervalUnits } from "@/features/sources/shared/sync-interval";
import { type LarkScopeDraft } from "./lark-scope";

/**
 * What a Lark Source reads: the Wiki spaces its app's bot is a member of, read from Lark now, and
 * the Drive folders shared with the bot, pasted as links.
 */
export function LarkScopeFields({
  credentialId,
  botName,
  savedRoots = [],
  draft,
  disabled,
  onChange,
}: {
  credentialId: string;
  botName: string | undefined;
  /** The saved spaces, so one the bot has since left stays visible and can be removed. */
  savedRoots?: readonly LarkRootResponse[];
  draft: LarkScopeDraft;
  disabled: boolean;
  onChange: (draft: LarkScopeDraft) => void;
}) {
  const ui = useAppTranslation();
  const id = useId();
  const spaces = useQuery({ ...listLarkSpacesOptions({ path: { credentialId } }), retry: false });
  const listed = spaces.data ?? [];
  const unlisted = savedRoots
    .filter((root) => root.kind === "WIKI_SPACE")
    .filter((root) => !listed.some((space) => space.spaceId === root.token))
    .map((root) => ({ spaceId: root.token, name: root.displayName ?? root.token }));
  const options = [...listed, ...unlisted];

  function toggle(spaceId: string, checked: boolean) {
    const rest = draft.spaceIds.filter((value) => value !== spaceId);
    onChange({ ...draft, spaceIds: checked ? [...rest, spaceId] : rest });
  }

  return (
    <FieldGroup>
      <FieldSet>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <FieldLegend>{ui("Wiki spaces")}</FieldLegend>
          <Button
            size="sm"
            prominence="tertiary"
            disabled={disabled}
            pending={spaces.isFetching}
            onClick={() => void spaces.refetch()}
          >
            <RefreshCw data-icon="inline-start" aria-hidden="true" />
            {ui("Refresh")}
          </Button>
        </div>
        {spaces.isPending ? (
          <p role="status" className="text-sm text-content-secondary">
            {ui("Loading Wiki spaces…")}
          </p>
        ) : spaces.isError ? (
          <Alert variant="destructive">
            <AlertDescription>{ui(sourceMutationError(spaces.error, "lark"))}</AlertDescription>
          </Alert>
        ) : null}
        {options.length > 0 ? (
          <div className="flex flex-col gap-2">
            {options.map((space) => (
              <Field key={space.spaceId} orientation="horizontal">
                <Checkbox
                  id={`${id}-${space.spaceId}`}
                  checked={draft.spaceIds.includes(space.spaceId)}
                  disabled={disabled}
                  onCheckedChange={(checked) => toggle(space.spaceId, checked === true)}
                />
                <FieldLabel htmlFor={`${id}-${space.spaceId}`}>{space.name}</FieldLabel>
              </Field>
            ))}
          </div>
        ) : spaces.isSuccess ? (
          <p className="text-sm text-content-secondary">
            {botName
              ? ui("{{v1}} is not a member of any Wiki space yet.", { v1: botName })
              : ui("The app's bot is not a member of any Wiki space yet.")}
          </p>
        ) : null}
      </FieldSet>
      <Field>
        <FieldLabel htmlFor={`${id}-folders`}>{ui("Drive folders")}</FieldLabel>
        <Textarea
          id={`${id}-folders`}
          rows={4}
          value={draft.folderLinksText}
          disabled={disabled}
          placeholder="https://example.larksuite.com/drive/folder/…"
          spellCheck={false}
          onChange={(event) => onChange({ ...draft, folderLinksText: event.target.value })}
        />
        <FieldDescription>{ui("One folder link per line.")}</FieldDescription>
      </Field>
      <LarkIntervalField
        interval={draft.interval}
        disabled={disabled}
        onChange={(interval) => onChange({ ...draft, interval })}
      />
    </FieldGroup>
  );
}

/** How often the Source reads its scope again: a whole number of minutes, hours, days or weeks. */
export function LarkIntervalField({
  interval,
  disabled,
  onChange,
}: {
  interval: LarkScopeDraft["interval"];
  disabled: boolean;
  onChange: (interval: LarkScopeDraft["interval"]) => void;
}) {
  const ui = useAppTranslation();
  const id = useId();
  return (
    <Field>
      <FieldLabel htmlFor={`${id}-value`}>{ui("Sync every")}</FieldLabel>
      <div className="flex items-center gap-2">
        <Input
          id={`${id}-value`}
          type="number"
          inputMode="numeric"
          min={1}
          step={1}
          required
          value={interval.value}
          disabled={disabled}
          className="w-24"
          onChange={(event) => onChange({ ...interval, value: event.target.value })}
        />
        <Select
          value={interval.unit}
          disabled={disabled}
          onValueChange={(next) => {
            const unit = syncIntervalUnits.find((entry) => entry === next);
            if (unit) onChange({ ...interval, unit });
          }}
        >
          <SelectTrigger aria-label={ui("Interval unit")} className="min-w-28">
            <SelectValue />
          </SelectTrigger>
          <SelectContent position="popper">
            <SelectGroup>
              {syncIntervalUnits.map((unit) => (
                <SelectItem key={unit} value={unit}>
                  {syncIntervalUnitName(unit, Number(interval.value) || 0)}
                </SelectItem>
              ))}
            </SelectGroup>
          </SelectContent>
        </Select>
      </div>
    </Field>
  );
}

import { Globe, ShieldCheck } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { ManagedProvider } from "./model-catalog";

export type DataBoundary = ManagedProvider["dataBoundary"];

/** Where a provider sits relative to the organization's data, as its administrator stated it. */
export function DataBoundaryTag({ boundary }: { boundary: DataBoundary }) {
  const ui = useAppTranslation();
  return boundary === "INTERNAL" ? (
    <Badge variant="secondary">
      <ShieldCheck aria-hidden="true" />
      {ui("Internal")}
    </Badge>
  ) : (
    <Badge variant="outline">
      <Globe aria-hidden="true" />
      {ui("External")}
    </Badge>
  );
}

/** Bordered radio choice shared by the provider editor's option groups. */
export const radioCard =
  "flex cursor-pointer items-start gap-3 rounded-xl border border-border-default px-3 py-2.5 has-[[data-state=checked]]:border-border-strong has-[[data-state=checked]]:bg-surface-sunken";

export function DataBoundaryField({
  value,
  onChange,
}: {
  value: DataBoundary;
  onChange: (value: DataBoundary) => void;
}) {
  const ui = useAppTranslation();
  const [confirming, setConfirming] = useState(false);
  const options = [
    {
      value: "EXTERNAL",
      title: ui("External"),
      description: ui(
        "Data leaves the organization's infrastructure, for example a standard OpenAI or Anthropic API.",
      ),
    },
    {
      value: "INTERNAL",
      title: ui("Internal"),
      description: ui(
        "A self-hosted server, or an enterprise agreement that commits to no retention and no training.",
      ),
    },
  ] as const;
  return (
    <fieldset className="space-y-2">
      <legend className="font-main-ui-action">{ui("Data boundary")}</legend>
      <RadioGroup
        value={value}
        onValueChange={(next) => {
          if (next === "INTERNAL" && value !== "INTERNAL") setConfirming(true);
          else onChange(next as DataBoundary);
        }}
      >
        {options.map((option) => (
          <label key={option.value} className={radioCard}>
            <RadioGroupItem value={option.value} className="mt-0.5" />
            <span className="min-w-0 flex-1">
              <span className="block font-main-ui-body font-medium">{option.title}</span>
              <span className="block font-secondary-body text-content-muted">
                {option.description}
              </span>
            </span>
          </label>
        ))}
      </RadioGroup>
      <p className="font-secondary-body text-content-muted">
        {ui("This label is recorded and shown only; it does not block any request yet.")}
      </p>
      {confirming && (
        <ConfirmDialog
          open
          onOpenChange={(open) => {
            if (!open) setConfirming(false);
          }}
          title={ui("Mark this provider as Internal?")}
          description={ui(
            "Confirm that it is self-hosted, or that its agreement forbids retaining your data and training on it. Internal documents may later be sent to it without asking users.",
          )}
          confirmLabel={ui("Mark as Internal")}
          pendingLabel={ui("Mark as Internal")}
          confirmTone="default"
          onConfirm={async () => {
            onChange("INTERNAL");
            setConfirming(false);
          }}
        />
      )}
    </fieldset>
  );
}

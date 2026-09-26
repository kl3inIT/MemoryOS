import { Globe, ShieldCheck } from "lucide-react";
import { useId, useState, type ReactNode } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
  Field,
  FieldContent,
  FieldDescription,
  FieldLabel,
  FieldLegend,
  FieldSet,
  FieldTitle,
} from "@/components/ui/field";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { StatusBadge } from "@/components/ui/status-badge";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { ManagedProvider } from "./model-catalog";

export type DataBoundary = ManagedProvider["dataBoundary"];

/** Where a provider sits relative to the organization's data, as its administrator stated it. */
export function DataBoundaryTag({ boundary }: { boundary: DataBoundary }) {
  const ui = useAppTranslation();
  // Where data goes is state, so it takes the status tones, always with its icon and label.
  return boundary === "INTERNAL" ? (
    <StatusBadge tone="success">
      <ShieldCheck aria-hidden="true" />
      {ui("Internal")}
    </StatusBadge>
  ) : (
    <StatusBadge tone="info">
      <Globe aria-hidden="true" />
      {ui("External")}
    </StatusBadge>
  );
}

/** One bordered choice of a radio group: the whole card is the radio's label. */
export function RadioCard({
  id,
  value,
  title,
  description,
}: {
  id: string;
  value: string;
  title: ReactNode;
  description?: ReactNode;
}) {
  return (
    <FieldLabel htmlFor={id}>
      <Field orientation="horizontal">
        <RadioGroupItem id={id} value={value} />
        <FieldContent>
          <FieldTitle>{title}</FieldTitle>
          {description ? <FieldDescription>{description}</FieldDescription> : null}
        </FieldContent>
      </Field>
    </FieldLabel>
  );
}

export function DataBoundaryField({
  value,
  onChange,
}: {
  value: DataBoundary;
  onChange: (value: DataBoundary) => void;
}) {
  const ui = useAppTranslation();
  const [confirming, setConfirming] = useState(false);
  const id = useId();
  return (
    <FieldSet>
      <FieldLegend variant="label">{ui("Data boundary")}</FieldLegend>
      <RadioGroup
        value={value}
        onValueChange={(next) => {
          if (next === "INTERNAL" && value !== "INTERNAL") setConfirming(true);
          else onChange(next as DataBoundary);
        }}
      >
        <RadioCard
          id={`${id}-external`}
          value="EXTERNAL"
          title={ui("External")}
          description={ui(
            "Data leaves the organization's infrastructure, for example a standard OpenAI or Anthropic API.",
          )}
        />
        <RadioCard
          id={`${id}-internal`}
          value="INTERNAL"
          title={ui("Internal")}
          description={ui(
            "A self-hosted server, or an enterprise agreement that commits to no retention and no training.",
          )}
        />
      </RadioGroup>
      <FieldDescription>
        {ui("This label is recorded and shown only; it does not block any request yet.")}
      </FieldDescription>
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
    </FieldSet>
  );
}

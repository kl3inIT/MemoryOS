import { useAppTranslation } from "@/i18n/use-app-translation";
import { useId, useState } from "react";
import { ChevronDown, Users, X } from "lucide-react";
import { PersonAvatar } from "@/components/composites/person-avatar";
import { Button } from "@/components/ui/button";
import { ButtonGroup } from "@/components/ui/button-group";
import { Checkbox } from "@/components/ui/checkbox";
import { Field, FieldLabel } from "@/components/ui/field";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";

/** Filters the catalog by who made each agent; several owners can be chosen at once. */
export function AgentCreatorFilter({
  owners,
  value,
  onChange,
}: {
  owners: string[];
  value: string[];
  onChange: (value: string[]) => void;
}) {
  const ui = useAppTranslation();
  const id = useId();
  const [search, setSearch] = useState("");
  const shown = owners.filter((owner) =>
    owner.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()),
  );
  const label =
    value.length > 1
      ? ui("{{v1}} người tạo", { v1: value.length })
      : (value[0] ?? ui("Mọi người tạo"));
  return (
    <ButtonGroup>
      <Popover>
        <PopoverTrigger asChild>
          <Button prominence="secondary" className="flex-1 justify-start sm:flex-none">
            <Users data-icon="inline-start" aria-hidden="true" />
            {label}
            <ChevronDown data-icon="inline-end" aria-hidden="true" />
          </Button>
        </PopoverTrigger>
        <PopoverContent align="end" className="w-72 p-1">
          <Input
            size="sm"
            value={search}
            placeholder={ui("Tìm người tạo…")}
            onChange={(event) => setSearch(event.target.value)}
            className="mb-1"
          />
          <div className="flex max-h-64 flex-col overflow-y-auto">
            {shown.map((owner, index) => (
              <div key={owner} className="rounded-lg px-2 py-1.5 hover:bg-surface-subtle">
                <Field orientation="horizontal">
                  <Checkbox
                    id={`${id}-${index}`}
                    checked={value.includes(owner)}
                    onCheckedChange={(checked) =>
                      onChange(
                        checked === true
                          ? [...value, owner]
                          : value.filter((item) => item !== owner),
                      )
                    }
                  />
                  <FieldLabel htmlFor={`${id}-${index}`} className="min-w-0 flex-1 cursor-pointer">
                    <PersonAvatar name={owner} size="sm" />
                    <span className="min-w-0 flex-1 truncate">{owner}</span>
                  </FieldLabel>
                </Field>
              </div>
            ))}
            {shown.length === 0 && (
              <p className="px-2 py-3 font-secondary-body text-content-muted">
                {ui("Không tìm thấy kết quả")}
              </p>
            )}
          </div>
        </PopoverContent>
      </Popover>
      {value.length > 0 && (
        <IconButton
          prominence="secondary"
          aria-label={ui("Xoá bộ lọc người tạo")}
          onClick={() => onChange([])}
        >
          <X />
        </IconButton>
      )}
    </ButtonGroup>
  );
}

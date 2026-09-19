import { Monitor, Moon, Sun } from "lucide-react";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { useTheme, type ThemePreference } from "@/features/theme/theme-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";

const options: { value: ThemePreference; label: string; icon: typeof Sun }[] = [
  { value: "system", label: "Auto", icon: Monitor },
  { value: "light", label: "Light", icon: Sun },
  { value: "dark", label: "Dark", icon: Moon },
];

/** Onyx "Color Mode" with Langdock-style preview cards; the choice stays in this browser. */
export function AppearanceSection() {
  const ui = useAppTranslation();
  const { preference, setTheme } = useTheme();
  return (
    <section aria-labelledby="appearance-heading" className="flex max-w-2xl flex-col gap-3">
      <div>
        <h2 id="appearance-heading" className="font-heading-h3 text-content-primary">
          {ui("Color Mode")}
        </h2>
        <p className="text-content-muted">{ui("Select your preferred color mode for the UI.")}</p>
      </div>
      <RadioGroup
        value={preference}
        onValueChange={(value) => setTheme(value as ThemePreference)}
        aria-labelledby="appearance-heading"
        className="grid grid-cols-3 gap-3"
      >
        {options.map(({ value, label, icon: Icon }) => (
          <label
            key={value}
            className={cn(
              "flex cursor-pointer flex-col gap-2 rounded-xl border border-border-subtle bg-surface-base p-2 transition-colors",
              "has-[[data-state=checked]]:border-border-strong has-[[data-state=checked]]:ring-2 has-[[data-state=checked]]:ring-ring/40",
            )}
          >
            <span
              aria-hidden="true"
              className={cn(
                "grid h-14 grid-cols-[30%_1fr] overflow-hidden rounded-lg border border-border-subtle",
                value === "light" && "bg-white [&>span:first-child]:bg-neutral-100",
                value === "dark" && "bg-neutral-900 [&>span:first-child]:bg-neutral-950",
                value === "system" &&
                  "bg-[linear-gradient(90deg,white_50%,var(--color-neutral-900)_50%)] [&>span:first-child]:bg-neutral-100",
              )}
            >
              <span />
              <span />
            </span>
            <span className="flex items-center gap-2 font-main-ui-body text-content-primary">
              <RadioGroupItem value={value} className="sr-only" />
              <Icon className="size-4 text-content-muted" aria-hidden="true" />
              {ui(label)}
            </span>
          </label>
        ))}
      </RadioGroup>
    </section>
  );
}

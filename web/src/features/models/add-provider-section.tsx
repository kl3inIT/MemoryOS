import { Plug, Route } from "lucide-react";
import type { ReactNode } from "react";
import { SectionHeader } from "@/components/composites/section-header";
import { ProviderCard } from "@/components/provider-logos/provider-card";
import { ProviderLogo } from "@/components/provider-logos/provider-logo";
import { Button } from "@/components/ui/button";
import { appText, type AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";

/** A new connection prefilled for one vendor, gateway or server; every preset speaks the OpenAI protocol. */
export type ProviderPreset = { name: string; baseUrl?: string };

type PresetCard = ProviderPreset & { subtitle: AppCopy; logo: ReactNode };

const vendors: PresetCard[] = [
  {
    name: "GPT",
    subtitle: "GPT models from OpenAI.",
    baseUrl: "https://api.openai.com/v1",
    logo: <ProviderLogo mark="OPENAI" />,
  },
  {
    name: "Claude",
    subtitle: "Claude models from Anthropic.",
    baseUrl: "https://api.anthropic.com/v1",
    logo: <ProviderLogo mark="ANTHROPIC" />,
  },
];

const gateways: PresetCard[] = [
  {
    name: "9Router",
    subtitle: "One key routed to several vendors.",
    logo: <ProviderLogo mark="NINEROUTER" />,
  },
  {
    name: "OpenRouter",
    subtitle: "Hosted marketplace of models from many vendors.",
    baseUrl: "https://openrouter.ai/api/v1",
    logo: <ProviderLogo mark="OPENROUTER" />,
  },
  {
    name: "LiteLLM Proxy",
    subtitle: "Self-hosted proxy in front of your own provider keys.",
    baseUrl: "http://localhost:4000/v1",
    logo: <Route />,
  },
];

const selfHosted: PresetCard[] = [
  {
    name: "Ollama",
    subtitle: "Open-weight models running on your own machine or server.",
    baseUrl: "http://localhost:11434/v1",
    logo: <ProviderLogo mark="OLLAMA" />,
  },
  {
    name: "LM Studio",
    subtitle: "Local models served by the LM Studio desktop app or its headless server.",
    baseUrl: "http://localhost:1234/v1",
    logo: <ProviderLogo mark="LM_STUDIO" />,
  },
  {
    name: "OpenAI-Compatible",
    subtitle: "Any endpoint that speaks the OpenAI API, such as vLLM or a gateway.",
    logo: <Plug />,
  },
];

function PresetGrid({
  presets,
  disabled,
  onConnect,
}: {
  presets: PresetCard[];
  disabled: boolean;
  onConnect: (preset: ProviderPreset) => void;
}) {
  const ui = useAppTranslation();
  return (
    <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
      {presets.map((preset) => (
        <ProviderCard
          key={preset.name}
          logo={preset.logo}
          name={preset.name}
          description={ui(preset.subtitle)}
          actions={
            <Button
              size="sm"
              prominence="secondary"
              disabled={disabled}
              aria-label={ui(appText("Connect {{name}}", { name: preset.name }))}
              onClick={() => onConnect({ name: preset.name, baseUrl: preset.baseUrl })}
            >
              {ui("Connect")}
            </Button>
          }
        />
      ))}
    </div>
  );
}

/** Onyx's provider grid: popular vendors, then gateways, then self-hosted and custom endpoints. */
export function AddProviderSection({
  disabled,
  onConnect,
}: {
  disabled: boolean;
  onConnect: (preset: ProviderPreset) => void;
}) {
  const ui = useAppTranslation();
  return (
    <section aria-labelledby="add-connection" className="flex flex-col gap-6">
      <SectionHeader
        id="add-connection"
        title={ui("Add Provider")}
        description={ui("MemoryOS supports both popular providers and self-hosted models.")}
      />
      <PresetGrid presets={vendors} disabled={disabled} onConnect={onConnect} />
      <div className="flex flex-col gap-2">
        <h3 className="font-main-ui-action text-content-secondary">{ui("Gateways & Routers")}</h3>
        <PresetGrid presets={gateways} disabled={disabled} onConnect={onConnect} />
      </div>
      <div className="flex flex-col gap-2">
        <h3 className="font-main-ui-action text-content-secondary">{ui("Self-hosted & Custom")}</h3>
        <PresetGrid presets={selfHosted} disabled={disabled} onConnect={onConnect} />
      </div>
    </section>
  );
}

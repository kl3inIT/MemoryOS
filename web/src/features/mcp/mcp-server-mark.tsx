import type { CSSProperties } from "react";
import { Icon, type IconifyIcon } from "@iconify/react";
import airtableLogo from "@iconify-icons/logos/airtable";
import asanaLogo from "@iconify-icons/logos/asana-icon";
import atlassianLogo from "@iconify-icons/logos/atlassian";
import clickupLogo from "@iconify-icons/logos/clickup-icon";
import confluenceLogo from "@iconify-icons/logos/confluence";
import datadogLogo from "@iconify-icons/logos/datadog-icon";
import dropboxLogo from "@iconify-icons/logos/dropbox";
import figmaLogo from "@iconify-icons/logos/figma";
import githubLogo from "@iconify-icons/logos/github-icon";
import gitlabLogo from "@iconify-icons/logos/gitlab-icon";
import gmailLogo from "@iconify-icons/logos/google-gmail";
import googleCalendarLogo from "@iconify-icons/logos/google-calendar";
import googleDriveLogo from "@iconify-icons/logos/google-drive";
import intercomLogo from "@iconify-icons/logos/intercom-icon";
import jiraLogo from "@iconify-icons/logos/jira";
import linearLogo from "@iconify-icons/logos/linear-icon";
import miroLogo from "@iconify-icons/logos/miro-icon";
import notionLogo from "@iconify-icons/logos/notion-icon";
import sentryLogo from "@iconify-icons/logos/sentry-icon";
import slackLogo from "@iconify-icons/logos/slack-icon";
import supabaseLogo from "@iconify-icons/logos/supabase-icon";
import trelloLogo from "@iconify-icons/logos/trello";
import zapierLogo from "@iconify-icons/logos/zapier-icon";
import zendeskLogo from "@iconify-icons/logos/zendesk-icon";
import boxIcon from "@iconify-icons/simple-icons/box";
import canvaIcon from "@iconify-icons/simple-icons/canva";
import hubspotIcon from "@iconify-icons/simple-icons/hubspot";
import sharepointIcon from "@iconify-icons/simple-icons/microsoftsharepoint";
import stripeIcon from "@iconify-icons/simple-icons/stripe";
import { cn } from "@/lib/utils";

/**
 * A service's own full-colour logo on a white tile, as ChatGPT shows its plugins; `tile` is used where the colour
 * logo set has only a wordmark or nothing, drawing the one-colour mark white on the brand's colour.
 */
type Brand = { logo: IconifyIcon } | { tile: IconifyIcon; color: string };

/**
 * Known services by a word of their MCP address or name, first match wins, so the more specific entries come
 * first. The marks are bundled; fetching a server's favicon would tell that host the Tenant's server list.
 */
const brands: [string, Brand][] = [
  ["googledrive", { logo: googleDriveLogo }],
  ["drivemcp", { logo: googleDriveLogo }],
  ["google drive", { logo: googleDriveLogo }],
  ["gmail", { logo: gmailLogo }],
  ["calendar", { logo: googleCalendarLogo }],
  ["sharepoint", { tile: sharepointIcon, color: "#0B828C" }],
  ["confluence", { logo: confluenceLogo }],
  ["jira", { logo: jiraLogo }],
  ["atlassian", { logo: atlassianLogo }],
  ["linear", { logo: linearLogo }],
  ["notion", { logo: notionLogo }],
  ["github", { logo: githubLogo }],
  ["gitlab", { logo: gitlabLogo }],
  ["slack", { logo: slackLogo }],
  ["figma", { logo: figmaLogo }],
  ["hubspot", { tile: hubspotIcon, color: "#FF7A59" }],
  ["asana", { logo: asanaLogo }],
  ["stripe", { tile: stripeIcon, color: "#635BFF" }],
  ["supabase", { logo: supabaseLogo }],
  ["sentry", { logo: sentryLogo }],
  ["miro", { logo: miroLogo }],
  ["canva", { tile: canvaIcon, color: "#00C4CC" }],
  ["intercom", { logo: intercomLogo }],
  ["datadog", { logo: datadogLogo }],
  ["dropbox", { logo: dropboxLogo }],
  ["box.com", { tile: boxIcon, color: "#0061D5" }],
  ["airtable", { logo: airtableLogo }],
  ["clickup", { logo: clickupLogo }],
  ["trello", { logo: trelloLogo }],
  ["zapier", { logo: zapierLogo }],
  ["zendesk", { logo: zendeskLogo }],
];

function brandOf(url: string, name: string): Brand | undefined {
  const haystack = `${url} ${name}`.toLowerCase();
  return brands.find(([word]) => haystack.includes(word))?.[1];
}

const tileClass = "inline-flex size-10 shrink-0 items-center justify-center rounded-xl";

/**
 * Identifies one MCP server in a list: the service's logo when the address or name names a known service,
 * otherwise a monogram from the slug the administrator chose.
 */
export function McpServerMark({
  slug,
  url = "",
  name = "",
  className,
}: {
  slug: string;
  url?: string;
  name?: string;
  className?: string;
}) {
  const brand = brandOf(url, name);
  if (brand && "logo" in brand) {
    return (
      <span
        aria-hidden="true"
        className={cn(tileClass, "bg-white ring-1 ring-black/10", className)}
      >
        <Icon icon={brand.logo} className="size-6!" />
      </span>
    );
  }
  if (brand) {
    return (
      <span
        aria-hidden="true"
        className={cn(tileClass, "bg-(--mcp-brand) text-white", className)}
        style={{ "--mcp-brand": brand.color } as CSSProperties}
      >
        <Icon icon={brand.tile} className="size-5!" />
      </span>
    );
  }
  const monogram =
    slug
      .replaceAll(/[^a-z0-9]/gi, "")
      .slice(0, 2)
      .toUpperCase() || "M";
  return (
    <span
      aria-hidden="true"
      className={cn(
        tileClass,
        "bg-surface-sunken font-main-ui-action text-content-secondary uppercase",
        className,
      )}
    >
      {monogram}
    </span>
  );
}

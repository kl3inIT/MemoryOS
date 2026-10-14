import type { CSSProperties } from "react";
import { Icon, type IconifyIcon } from "@iconify/react";
import airtableIcon from "@iconify-icons/simple-icons/airtable";
import asanaIcon from "@iconify-icons/simple-icons/asana";
import atlassianIcon from "@iconify-icons/simple-icons/atlassian";
import boxIcon from "@iconify-icons/simple-icons/box";
import canvaIcon from "@iconify-icons/simple-icons/canva";
import clickupIcon from "@iconify-icons/simple-icons/clickup";
import confluenceIcon from "@iconify-icons/simple-icons/confluence";
import datadogIcon from "@iconify-icons/simple-icons/datadog";
import dropboxIcon from "@iconify-icons/simple-icons/dropbox";
import figmaIcon from "@iconify-icons/simple-icons/figma";
import githubIcon from "@iconify-icons/simple-icons/github";
import gitlabIcon from "@iconify-icons/simple-icons/gitlab";
import gmailIcon from "@iconify-icons/simple-icons/gmail";
import googlecalendarIcon from "@iconify-icons/simple-icons/googlecalendar";
import googledriveIcon from "@iconify-icons/simple-icons/googledrive";
import hubspotIcon from "@iconify-icons/simple-icons/hubspot";
import intercomIcon from "@iconify-icons/simple-icons/intercom";
import jiraIcon from "@iconify-icons/simple-icons/jira";
import linearIcon from "@iconify-icons/simple-icons/linear";
import sharepointIcon from "@iconify-icons/simple-icons/microsoftsharepoint";
import miroIcon from "@iconify-icons/simple-icons/miro";
import notionIcon from "@iconify-icons/simple-icons/notion";
import sentryIcon from "@iconify-icons/simple-icons/sentry";
import slackIcon from "@iconify-icons/simple-icons/slack";
import stripeIcon from "@iconify-icons/simple-icons/stripe";
import supabaseIcon from "@iconify-icons/simple-icons/supabase";
import trelloIcon from "@iconify-icons/simple-icons/trello";
import zapierIcon from "@iconify-icons/simple-icons/zapier";
import zendeskIcon from "@iconify-icons/simple-icons/zendesk";
import { cn } from "@/lib/utils";

/** `color: null` marks a black logo, drawn dark on a white tile as the service shows it. */
type Brand = { icon: IconifyIcon; color: string | null };

/**
 * Known services by a word of their MCP address or name, first match wins, so the more specific entries come
 * first. The marks are bundled; fetching a server's favicon would tell that host the Tenant's server list.
 * Colours are the services' own brand colours, drawn as the tile behind a white mark.
 */
const brands: [string, Brand][] = [
  ["googledrive", { icon: googledriveIcon, color: "#1A73E8" }],
  ["drivemcp", { icon: googledriveIcon, color: "#1A73E8" }],
  ["google drive", { icon: googledriveIcon, color: "#1A73E8" }],
  ["gmail", { icon: gmailIcon, color: "#EA4335" }],
  ["calendar", { icon: googlecalendarIcon, color: "#4285F4" }],
  ["sharepoint", { icon: sharepointIcon, color: "#0B828C" }],
  ["confluence", { icon: confluenceIcon, color: "#172B4D" }],
  ["jira", { icon: jiraIcon, color: "#0052CC" }],
  ["atlassian", { icon: atlassianIcon, color: "#0052CC" }],
  ["linear", { icon: linearIcon, color: "#5E6AD2" }],
  ["notion", { icon: notionIcon, color: null }],
  ["github", { icon: githubIcon, color: null }],
  ["gitlab", { icon: gitlabIcon, color: "#FC6D26" }],
  ["slack", { icon: slackIcon, color: "#4A154B" }],
  ["figma", { icon: figmaIcon, color: null }],
  ["hubspot", { icon: hubspotIcon, color: "#FF7A59" }],
  ["asana", { icon: asanaIcon, color: "#F06A6A" }],
  ["stripe", { icon: stripeIcon, color: "#635BFF" }],
  ["supabase", { icon: supabaseIcon, color: null }],
  ["sentry", { icon: sentryIcon, color: "#362D59" }],
  ["miro", { icon: miroIcon, color: null }],
  ["canva", { icon: canvaIcon, color: "#00C4CC" }],
  ["intercom", { icon: intercomIcon, color: "#1F8DED" }],
  ["datadog", { icon: datadogIcon, color: "#632CA6" }],
  ["dropbox", { icon: dropboxIcon, color: "#0061FF" }],
  ["box.com", { icon: boxIcon, color: "#0061D5" }],
  ["airtable", { icon: airtableIcon, color: "#18BFFF" }],
  ["clickup", { icon: clickupIcon, color: "#7B68EE" }],
  ["trello", { icon: trelloIcon, color: "#0052CC" }],
  ["zapier", { icon: zapierIcon, color: "#FF4F00" }],
  ["zendesk", { icon: zendeskIcon, color: "#03363D" }],
];

function brandOf(url: string, name: string): Brand | undefined {
  const haystack = `${url} ${name}`.toLowerCase();
  return brands.find(([word]) => haystack.includes(word))?.[1];
}

/**
 * Identifies one MCP server in a list: the service's mark on its brand colour when the address or name names a
 * known service, otherwise a monogram from the slug the administrator chose.
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
  if (brand) {
    return (
      <span
        aria-hidden="true"
        className={cn(
          "inline-flex size-10 shrink-0 items-center justify-center rounded-xl",
          brand.color === null
            ? "bg-white text-black ring-1 ring-black/10"
            : "bg-(--mcp-brand) text-white",
          className,
        )}
        style={brand.color === null ? undefined : ({ "--mcp-brand": brand.color } as CSSProperties)}
      >
        <Icon icon={brand.icon} className="size-5" />
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
        "inline-flex size-10 shrink-0 items-center justify-center rounded-xl",
        "bg-surface-sunken font-main-ui-action text-content-secondary uppercase",
        className,
      )}
    >
      {monogram}
    </span>
  );
}

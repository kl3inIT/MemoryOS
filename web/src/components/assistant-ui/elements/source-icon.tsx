"use client";

// SourceIcon from assistant-ui Sources (MIT), registry retrieved 2026-09-13.
// Adapted to MemoryOS tokens, with bundled brand marks in place of a favicon service: a favicon request
// would leave the content security policy and tell a third party which sites a conversation cited.
import { Icon, type IconifyIcon } from "@iconify/react";
import amazonIcon from "@iconify-icons/simple-icons/amazon";
import awsIcon from "@iconify-icons/simple-icons/amazonwebservices";
import anthropicIcon from "@iconify-icons/simple-icons/anthropic";
import appleIcon from "@iconify-icons/simple-icons/apple";
import atlassianIcon from "@iconify-icons/simple-icons/atlassian";
import facebookIcon from "@iconify-icons/simple-icons/facebook";
import githubIcon from "@iconify-icons/simple-icons/github";
import googleIcon from "@iconify-icons/simple-icons/google";
import linkedinIcon from "@iconify-icons/simple-icons/linkedin";
import mdnIcon from "@iconify-icons/simple-icons/mdnwebdocs";
import mediumIcon from "@iconify-icons/simple-icons/medium";
import microsoftIcon from "@iconify-icons/simple-icons/microsoft";
import officeIcon from "@iconify-icons/simple-icons/microsoftoffice";
import sharepointIcon from "@iconify-icons/simple-icons/microsoftsharepoint";
import mozillaIcon from "@iconify-icons/simple-icons/mozilla";
import notionIcon from "@iconify-icons/simple-icons/notion";
import npmIcon from "@iconify-icons/simple-icons/npm";
import openaiIcon from "@iconify-icons/simple-icons/openai";
import pythonIcon from "@iconify-icons/simple-icons/python";
import reactIcon from "@iconify-icons/simple-icons/react";
import redditIcon from "@iconify-icons/simple-icons/reddit";
import slackIcon from "@iconify-icons/simple-icons/slack";
import stackoverflowIcon from "@iconify-icons/simple-icons/stackoverflow";
import vercelIcon from "@iconify-icons/simple-icons/vercel";
import wikipediaIcon from "@iconify-icons/simple-icons/wikipedia";
import xIcon from "@iconify-icons/simple-icons/x";
import youtubeIcon from "@iconify-icons/simple-icons/youtube";
import { Globe } from "lucide-react";
import { cn } from "@/lib/utils";

/** Well-known sites by registered domain; a subdomain takes its domain's mark unless it has its own. */
const brands: Record<string, IconifyIcon> = {
  "amazon.com": amazonIcon,
  "aws.amazon.com": awsIcon,
  "anthropic.com": anthropicIcon,
  "claude.ai": anthropicIcon,
  "apple.com": appleIcon,
  "atlassian.com": atlassianIcon,
  "atlassian.net": atlassianIcon,
  "facebook.com": facebookIcon,
  "github.com": githubIcon,
  "github.io": githubIcon,
  "githubusercontent.com": githubIcon,
  "google.com": googleIcon,
  "linkedin.com": linkedinIcon,
  "developer.mozilla.org": mdnIcon,
  "medium.com": mediumIcon,
  "microsoft.com": microsoftIcon,
  "office.com": officeIcon,
  "microsoft365.com": officeIcon,
  "sharepoint.com": sharepointIcon,
  "mozilla.org": mozillaIcon,
  "notion.so": notionIcon,
  "notion.site": notionIcon,
  "npmjs.com": npmIcon,
  "openai.com": openaiIcon,
  "chatgpt.com": openaiIcon,
  "python.org": pythonIcon,
  "react.dev": reactIcon,
  "reactjs.org": reactIcon,
  "reddit.com": redditIcon,
  "slack.com": slackIcon,
  "stackoverflow.com": stackoverflowIcon,
  "vercel.com": vercelIcon,
  "vercel.app": vercelIcon,
  "wikipedia.org": wikipediaIcon,
  "x.com": xIcon,
  "twitter.com": xIcon,
  "youtube.com": youtubeIcon,
  "youtu.be": youtubeIcon,
};

/** The mark of the most specific known domain the host belongs to. */
function brandOf(domain: string) {
  const labels = domain.toLowerCase().replace(/\.$/, "").split(".");
  for (let start = 0; start < labels.length - 1; start++) {
    const brand = brands[labels.slice(start).join(".")];
    if (brand) return brand;
  }
  return undefined;
}

export function SourceIcon({
  domain,
  className,
  brand = true,
  fallback = "globe",
}: {
  domain: string;
  className?: string;
  /** False skips the brand mark, for a name that is not a web host. */
  brand?: boolean;
  /** Shown for a site without a known mark; next to a citation number chips pass `none`. */
  fallback?: "globe" | "none";
}) {
  const icon = brand ? brandOf(domain) : undefined;
  if (icon)
    return (
      <Icon
        icon={icon}
        ssr
        data-slot="source-icon"
        aria-hidden="true"
        className={cn("size-3.5 shrink-0 text-content-secondary", className)}
      />
    );
  if (fallback === "none") return null;
  return (
    <Globe
      data-slot="source-icon-fallback"
      aria-hidden="true"
      className={cn("size-3.5 shrink-0 text-content-muted", className)}
    />
  );
}

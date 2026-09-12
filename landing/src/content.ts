import type { LucideIcon } from "lucide-react";
import {
  Bot,
  Building2,
  Cable,
  ChartColumn,
  Cloud,
  FileText,
  KeyRound,
  Layers,
  Plug,
  Quote,
  Search,
  ShieldCheck,
} from "lucide-react";
import genaiFundLogo from "@/assets/logos/genai-fund.png";
import tascoLogo from "@/assets/logos/tasco.png";

type Link = {
  label: string;
  href: string;
};

type FooterLink = Link & {
  external?: boolean;
};

type SectionIntro = {
  title: string;
  description: string;
};

type Entry = {
  title: string;
  description: string;
};

type IconEntry = Entry & {
  icon: LucideIcon;
};

// Systems on the left feed MemoryOS in the middle, which produces what is on the right.
type Flow = {
  inputs: readonly string[];
  label: string;
  outputs: readonly string[];
};

type Highlight = Entry & {
  points: readonly string[];
  flow: Flow;
};

type CapabilitySize = "wide" | "standard" | "full";

type Capability = IconEntry & {
  size: CapabilitySize;
};

const contact = {
  label: "Contact us",
  email: "info@vadan.app",
  href: "mailto:info@vadan.app",
} as const;

const navigation: readonly Link[] = [
  { label: "Product", href: "#product" },
  { label: "AI assets", href: "#assets" },
  { label: "How it works", href: "#how-it-works" },
  { label: "Deployment", href: "#deployment" },
  { label: "FAQ", href: "#faq" },
];

const hero = {
  title: "One governed memory for your people and AI agents",
  description:
    "MemoryOS connects every system your company works in, answers with citations, and turns proven AI work into governed assets that people and agents reuse, all under the access rules you already have. Run it in your cloud or your own data center.",
  secondaryAction: { label: "See how it works", href: "#how-it-works" },
} as const;

const productPreview = {
  label: "Example of a cited answer in MemoryOS",
  question: "What is the approval flow for a new supplier contract?",
  assistantName: "MemoryOS",
  answer: [
    {
      text: "Contracts above your department's spending limit need Legal review before signature.",
      citation: 1,
    },
    {
      text: "After approval, Procurement registers the supplier and attaches the signed contract.",
      citation: 2,
    },
  ],
  sourcesLabel: "Sources",
  citations: [
    { index: 1, title: "Procurement policy 2026.pdf", location: "SharePoint" },
    { index: 2, title: "Supplier onboarding checklist.docx", location: "Google Drive" },
  ],
  accessNote: "Answered only from documents you can access",
} as const;

// Single-colour alpha masks of the product owner's logo files; the page tints them per theme.
type TrustSignal = Entry & {
  logo: {
    src: string;
    label: string;
  };
};

const trustSignals: readonly TrustSignal[] = [
  {
    title: "Trusted by Tasco",
    description: "Our first enterprise design partner brings MemoryOS to its leaders and managers.",
    logo: { src: tascoLogo, label: "Tasco" },
  },
  {
    title: "Backed by GenAI Fund",
    description: "Vanda builds MemoryOS with backing from GenAI Fund.",
    logo: { src: genaiFundLogo, label: "GenAI Fund" },
  },
];

const product = {
  title: "Knowledge people can trust, and AI your company can govern",
  description:
    "MemoryOS is the governed context layer for your AI agents and one trusted place for your people to search and ask. Both work from approved company data, see only what they may access, and can check every source.",
  search: {
    title: "Ask once instead of searching ten systems",
    description:
      "Policies live in SharePoint, specifications in Drive, decisions in Slack and numbers in business systems. MemoryOS connects them all, understands every document, and gives everyone one place to search and ask. Every answer cites the passages it used.",
    points: [
      "Keyword and semantic search across every connected source",
      "Answers that cite the original document and passage",
      "Calculations, tables, charts and reports from company data",
    ],
    flow: {
      inputs: [
        "Google Workspace",
        "Microsoft 365",
        "Slack and Teams",
        "Confluence and Jira",
        "Salesforce and HubSpot",
        "Files, databases and APIs",
      ],
      label: "MemoryOS",
      outputs: ["Search", "Cited answers", "Analysis and reports"],
    },
  },
  identity: {
    title: "Enterprise identity and access, built in",
    description:
      "People sign in with the identity providers your company already runs, and users and groups stay in sync on their own. MemoryOS checks access before it retrieves anything, so search, agents and MCP clients only ever see what that person may read.",
    points: [
      "Multiple SSO providers over SAML 2.0 and OpenID Connect",
      "SCIM provisioning for users and groups",
      "Roles, groups and source permissions enforced on every request",
    ],
    flow: {
      inputs: [
        "Microsoft Entra ID",
        "Okta",
        "Google Workspace",
        "Keycloak",
        "Any SAML or OIDC provider",
      ],
      label: "MemoryOS access",
      outputs: ["Single sign-on", "SCIM users and groups", "Roles and permissions"],
    },
  },
} as const;

const capabilities: SectionIntro & { items: readonly Capability[] } = {
  title: "Everything an enterprise knowledge layer needs",
  description:
    "Each capability works under the same permission model, from the first connected source to the last agent.",
  items: [
    {
      title: "Connect every system",
      description:
        "Google Workspace, Microsoft 365, SharePoint, Teams, Slack, Confluence, Notion, Jira, Salesforce, HubSpot, Zendesk, GitHub, Box, Dropbox, Amazon S3, email, databases and REST APIs. Content and permissions stay in sync as sources change.",
      icon: Cable,
      size: "wide",
    },
    {
      title: "Index any file",
      description:
        "PDFs, Word, Excel, PowerPoint, Google Docs and Sheets, emails, web pages, images and scanned pages. MemoryOS reads the text, tables and charts inside them, in Vietnamese and English.",
      icon: FileText,
      size: "wide",
    },
    {
      title: "Enterprise search",
      description:
        "Keyword and semantic search across everything a person is allowed to see, ranked for relevance.",
      icon: Search,
      size: "standard",
    },
    {
      title: "Cited answers",
      description:
        "Answers link each claim to the document and passage it came from, so people can check before they act.",
      icon: Quote,
      size: "standard",
    },
    {
      title: "Analysis and reports",
      description: "Calculates, builds tables and charts, and produces reports from company data.",
      icon: ChartColumn,
      size: "standard",
    },
    {
      title: "Enterprise identity",
      description:
        "Multiple SSO providers over SAML and OIDC, SCIM user and group provisioning, and role-based access.",
      icon: KeyRound,
      size: "standard",
    },
    {
      title: "Custom agents",
      description:
        "Agents for each department or workflow, with their own knowledge, instructions and tools.",
      icon: Bot,
      size: "standard",
    },
    {
      title: "MCP server",
      description:
        "Approved knowledge and AI assets for your other agents through the Model Context Protocol.",
      icon: Plug,
      size: "standard",
    },
    {
      title: "Permission-aware by design",
      description:
        "Access is checked before retrieval for search, answers, agents and MCP alike. Only permitted context ever reaches the model.",
      icon: ShieldCheck,
      size: "full",
    },
  ],
};

const assets = {
  title: "Organizational AI Memory",
  description:
    "MemoryOS is the system of record for reusable AI capability inside your company. It brings the knowledge, instructions, prompts, packages, ownership and permissions behind successful AI-assisted work into one governed lifecycle, so people and agents can discover, use and improve what the organization already knows.",
  problem: {
    title: "The problem",
    description:
      "AI is moving from individual assistance to repeatable human-agent workflows. Yet the parts that make those workflows reliable, from source knowledge and instructions to prompts, quality standards and approvals, stay scattered across personal tools and team silos. When they are not owned and versioned together, teams duplicate work, proven methods drift, handovers lose context, and agents act without a record of what was approved or why.",
  },
  solution: {
    title: "The solution",
    description:
      "MemoryOS treats reusable AI-assisted work as governed assets. Each exact release keeps its accountable owner, permissions, provenance, dependencies and usage history, and authorized employees and agents receive the same approved capability wherever they work.",
  },
  pillars: [
    {
      title: "Turn what works into assets",
      description:
        "AI work that succeeds once becomes an organizational asset the whole company can find and use.",
    },
    {
      title: "Govern every release",
      description:
        "Each asset has an accountable owner, exact versions, permissions and a record of where it came from.",
    },
    {
      title: "Reuse with trusted context",
      description:
        "People and agents use the same approved asset, together with the knowledge it depends on.",
    },
    {
      title: "Improve and hand over",
      description:
        "Usage and feedback show what works, and ownership moves between people without losing context.",
    },
  ],
  surfaces: {
    title: "Delivered where work happens",
    items: ["Web app", "Assistant", "REST API", "CLI", "MCP"],
  },
  asset: {
    name: "Supplier contract review",
    status: "Released",
    fields: [
      { term: "Owner", detail: "Procurement team" },
      { term: "Release", detail: "1.2.0" },
      { term: "Knowledge", detail: "Procurement policies" },
      { term: "Available to", detail: "Procurement and Legal" },
      { term: "Used by", detail: "People and agents" },
    ],
  },
} as const;

const howItWorks: SectionIntro & {
  steps: readonly Entry[];
  request: { title: string; steps: readonly Entry[] };
} = {
  title: "From connected sources to answers you can verify",
  description: "The same path serves every search, answer, agent and MCP request.",
  steps: [
    {
      title: "Connect",
      description:
        "Administrators connect the systems they approve. Each source keeps its own access rules.",
    },
    {
      title: "Index",
      description:
        "MemoryOS reads every document, including scans, splits it into passages and keeps the search index current as sources change.",
    },
    {
      title: "Ask",
      description:
        "People search and ask in plain language, work with custom agents, or use the same knowledge from MCP clients.",
    },
    {
      title: "Verify",
      description:
        "Every answer cites its documents and passages, so people can check the source before they act.",
    },
  ],
  request: {
    title: "On every request",
    steps: [
      {
        title: "Sign in with SSO",
        description:
          "Identity comes from your identity provider; users and groups stay in sync through SCIM.",
      },
      {
        title: "Check access first",
        description: "MemoryOS applies access rules before any retrieval runs.",
      },
      {
        title: "Send only permitted context",
        description: "The model receives only passages the person is allowed to read.",
      },
    ],
  },
};

const deployment: SectionIntro & { options: readonly IconEntry[] } = {
  title: "Deploy in your cloud or your data center",
  description:
    "MemoryOS runs inside infrastructure you control, so documents, search indexes and conversations stay in your environment. Use a managed model endpoint in your cloud or open-weight models on your own GPUs.",
  options: [
    {
      title: "Amazon Web Services",
      description:
        "In your own AWS account on Amazon EC2 in a private VPC, with Amazon S3 backups, AWS KMS encryption and Amazon CloudWatch monitoring.",
      icon: Cloud,
    },
    {
      title: "Other clouds",
      description: "The same containers on the public or private cloud your IT team already runs.",
      icon: Layers,
    },
    {
      title: "On-premise",
      description:
        "Inside your own data center, with self-hosted models and no data leaving your network.",
      icon: Building2,
    },
  ],
};

const faq: SectionIntro & { items: readonly { question: string; answer: string }[] } = {
  title: "Frequently asked questions",
  description: "What IT, security and business teams ask before they deploy.",
  items: [
    {
      question: "What is MemoryOS?",
      answer:
        "MemoryOS, built by Vanda, is the governed context layer between your company's systems and the people and AI agents who use them. It connects those systems, answers questions with citations, and turns proven AI work into governed assets under one permission model.",
    },
    {
      question: "Where does our data stay?",
      answer:
        "In infrastructure you control: your AWS account, another cloud or your own data center. Documents, search indexes and conversations never leave that environment.",
    },
    {
      question: "Which AI model answers questions?",
      answer:
        "Your choice: a managed model endpoint in your cloud or open-weight models on your own GPUs. MemoryOS sends a model only the context the signed-in person may read.",
    },
    {
      question: "Which identity providers do you support?",
      answer:
        "Any SAML 2.0 or OpenID Connect provider, including Microsoft Entra ID, Okta, Google Workspace and Keycloak, several at once if you need them. SCIM keeps users and groups in sync.",
    },
    {
      question: "How are permissions enforced?",
      answer:
        "MemoryOS applies source permissions and its own roles and groups before retrieval, so search, answers, custom agents and MCP clients return only what that person may see.",
    },
    {
      question: "Which sources can we connect?",
      answer:
        "Google Workspace, Microsoft 365, Slack, Confluence, Notion, Jira, Salesforce, HubSpot, GitHub, cloud storage, email, databases and REST APIs, plus file uploads. MemoryOS indexes every common document format, including scanned files.",
    },
    {
      question: "Who uses MemoryOS today?",
      answer: "Tasco is our first enterprise design partner. Vanda is backed by GenAI Fund.",
    },
    {
      question: "How do we start?",
      answer:
        "Email info@vadan.app. We scope a deployment around your systems, identity provider and first use cases.",
    },
  ],
};

const footer: SectionIntro & {
  action: string;
  organization: string;
  links: readonly FooterLink[];
} = {
  title: "Bring MemoryOS to your company",
  description:
    "Tell us about your systems, identity provider and first use cases, and we will scope a deployment together.",
  action: "Email info@vadan.app",
  organization: "Vanda",
  links: [],
};

export {
  assets,
  capabilities,
  contact,
  deployment,
  faq,
  footer,
  hero,
  howItWorks,
  navigation,
  product,
  productPreview,
  trustSignals,
  type Capability,
  type CapabilitySize,
  type Flow,
  type Highlight,
};

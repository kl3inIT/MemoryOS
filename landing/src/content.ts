import genaiFundLogo from "@/assets/logos/genai-fund.png";
import tascoLogo from "@/assets/logos/tasco.png";

/*
 * Every string a reader or a screen reader meets on the page. Illustrations hidden from assistive
 * technology (the line drawings, the ingestion stage and the capability mocks) keep their sample
 * data beside them. The page names standards buyers look for, never the internal stack
 * (src/App.test.tsx).
 */

type Link = {
  label: string;
  href: string;
};

type SectionIntro = {
  title: string;
  description: string;
};

type Entry = {
  title: string;
  description: string;
};

type Highlight = Entry & {
  points: readonly string[];
};

type CapabilityMock =
  | "connectors"
  | "files"
  | "search"
  | "citations"
  | "analysis"
  | "sso"
  | "agents"
  | "mcp"
  | "permissions";

type Capability = Entry & {
  mock: CapabilityMock;
};

type GatePassage = {
  title: string;
  allowed: boolean;
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
    description: "Vadan builds MemoryOS with backing from GenAI Fund.",
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
      mock: "connectors",
    },
    {
      title: "Index any file",
      description:
        "PDFs, Word, Excel, PowerPoint, Google Docs and Sheets, emails, web pages, images and scanned pages. MemoryOS reads the text, tables and charts inside them, in Vietnamese and English.",
      mock: "files",
    },
    {
      title: "Enterprise search",
      description:
        "Keyword and semantic search across everything a person is allowed to see, ranked for relevance.",
      mock: "search",
    },
    {
      title: "Cited answers",
      description:
        "Answers link each claim to the document and passage it came from, so people can check before they act.",
      mock: "citations",
    },
    {
      title: "Analysis and reports",
      description: "Calculates, builds tables and charts, and produces reports from company data.",
      mock: "analysis",
    },
    {
      title: "Enterprise identity",
      description:
        "Multiple SSO providers over SAML and OIDC, SCIM user and group provisioning, and role-based access.",
      mock: "sso",
    },
    {
      title: "Custom agents",
      description:
        "Agents for each department or workflow, with their own knowledge, instructions and tools.",
      mock: "agents",
    },
    {
      title: "MCP server",
      description:
        "Approved knowledge and AI assets for your other agents through the Model Context Protocol.",
      mock: "mcp",
    },
    {
      title: "Permission-aware by design",
      description:
        "Access is checked before retrieval for search, answers, agents and MCP alike. Only permitted context ever reaches the model.",
      mock: "permissions",
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
  stages: readonly Entry[];
  gate: {
    title: string;
    summary: string;
    person: Entry;
    check: string;
    model: Entry;
    blockedLabel: string;
    blockedNote: string;
    allowedLabel: string;
    passages: readonly GatePassage[];
  };
} = {
  title: "From connected sources to answers you can verify",
  description: "The same path serves every search, answer, agent and MCP request.",
  stages: [
    {
      title: "Pull",
      description: "Connectors pull from every system you approve, with its access rules.",
    },
    {
      title: "Extract",
      description: "MemoryOS reads pages, tables, charts and scans into structured text.",
    },
    {
      title: "Chunk",
      description: "Text splits into passages that keep their source and access rules.",
    },
    {
      title: "Embed",
      description: "Each passage becomes a vector that captures its meaning.",
    },
    {
      title: "Index",
      description: "Keyword and semantic indexes stay current as sources change.",
    },
    {
      title: "Answer",
      description: "A question finds the closest passages, and the answer cites them.",
    },
  ],
  gate: {
    title: "On every request",
    summary: "The model sees only passages the person may read.",
    person: { title: "Procurement analyst", description: "Signed in with SSO" },
    check: "Access check",
    model: { title: "Model", description: "Receives permitted passages only" },
    blockedLabel: "Blocked at the access check",
    blockedNote: "No access",
    allowedLabel: "Sent to the model",
    passages: [
      { title: "Procurement policy, section 4", allowed: true },
      { title: "Salary bands 2026", allowed: false },
      { title: "Supplier onboarding checklist", allowed: true },
      { title: "Board meeting minutes", allowed: false },
    ],
  },
};

const deployment: SectionIntro & { options: readonly Entry[] } = {
  title: "Deploy in your cloud or your data center",
  description:
    "MemoryOS runs inside infrastructure you control, so documents, search indexes and conversations stay in your environment. Use a managed model endpoint in your cloud or open-weight models on your own GPUs.",
  options: [
    {
      title: "Amazon Web Services",
      description:
        "In your own AWS account on Amazon EC2 in a private VPC, with Amazon S3 backups, AWS KMS encryption and Amazon CloudWatch monitoring.",
    },
    {
      title: "Other clouds",
      description: "The same containers on the public or private cloud your IT team already runs.",
    },
    {
      title: "On-premise",
      description:
        "Inside your own data center, with self-hosted models and no data leaving your network.",
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
        "MemoryOS, built by Vadan, is the governed context layer between your company's systems and the people and AI agents who use them. It connects those systems, answers questions with citations, and turns proven AI work into governed assets under one permission model.",
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
      answer: "Tasco is our first enterprise design partner. Vadan is backed by GenAI Fund.",
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
} = {
  title: "Bring MemoryOS to your company",
  description:
    "Tell us about your systems, identity provider and first use cases, and we will scope a deployment together.",
  action: "Email info@vadan.app",
  organization: "Vadan",
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
  type CapabilityMock,
  type GatePassage,
  type Highlight,
};

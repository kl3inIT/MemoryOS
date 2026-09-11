import genaiFundLogo from "@/assets/logos/genai-fund.png";
import tascoLogo from "@/assets/logos/tasco.png";

/*
 * Every string a reader or a screen reader meets on the page. Illustrations hidden from assistive
 * technology (the ingestion stage and the capability mocks) keep their sample data beside them.
 * Short copy stays at one sentence of at most 12 words (src/content.test.ts).
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
  | "search"
  | "citations"
  | "analysis"
  | "sso"
  | "agents"
  | "governance"
  | "mcp"
  | "permissions";

type Capability = Entry & {
  mock: CapabilityMock;
};

type GatePassage = {
  title: string;
  allowed: boolean;
};

type DeploymentHost = {
  title: string;
  platform: string;
  services: readonly string[];
};

type Milestone = Entry & {
  period: string;
  dateTime: string;
  deliverable: string;
};

const contact = {
  label: "Contact us",
  email: "aws@vadan.app",
  href: "mailto:aws@vadan.app",
} as const;

const navigation: readonly Link[] = [
  { label: "Product", href: "#product" },
  { label: "How it works", href: "#how-it-works" },
  { label: "Deployment", href: "#deployment" },
  { label: "Roadmap", href: "#roadmap" },
  { label: "FAQ", href: "#faq" },
];

const hero = {
  title: "One governed memory for your people and AI agents",
  statement: "Cited answers from approved sources, under your access rules, in your AWS.",
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
    { index: 1, title: "Procurement policy 2026.pdf", location: "Google Drive" },
    { index: 2, title: "Supplier onboarding checklist.docx", location: "Uploaded file" },
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
    title: "Partnering with Tasco",
    description: "Vadan and Tasco run a MemoryOS Production PoC from September to December 2026.",
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
  description: "A separate AI and knowledge layer over the systems you already use.",
  search: {
    title: "Ask once instead of searching five systems",
    description: "MemoryOS indexes approved sources and gives everyone one place to ask.",
    points: [
      "Full-text and semantic search across approved sources",
      "Answers that cite the original document and passage",
      "Built-in Python that turns data into tables, charts and reports",
    ],
  },
  governance: {
    title: "Enterprise AI that follows your access rules",
    description: "People sign in with SSO; access is checked before anything is retrieved.",
    points: [
      "SSO/OIDC with your existing identity provider",
      "Access checked before every retrieval",
      "Owners, versions and approvals for agents, instructions and tools",
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
  description: "One path for every search, answer, agent and MCP request.",
  stages: [
    {
      title: "Pull",
      description: "Connectors pull from Drive, files, APIs and business systems.",
    },
    {
      title: "Extract",
      description: "Docling and OCR turn pages, tables and scans into structured text.",
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
      description: "Full-text and vector indexes stay current as sources change.",
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

const capabilities: SectionIntro & { items: readonly Capability[] } = {
  title: "Everything a company knowledge layer needs",
  description: "One permission model, from the first source to the last agent.",
  items: [
    {
      title: "Data connectors",
      description: "Drive, files, APIs and approved systems, kept in sync.",
      mock: "connectors",
    },
    {
      title: "Enterprise search",
      description: "One search across everything you are allowed to see.",
      mock: "search",
    },
    {
      title: "Cited answers",
      description: "Every claim links to the passage it came from.",
      mock: "citations",
    },
    {
      title: "Analysis and reports",
      description: "Built-in Python turns retrieved data into tables and charts.",
      mock: "analysis",
    },
    {
      title: "Enterprise SSO",
      description: "Sign in with the identity provider you already run.",
      mock: "sso",
    },
    {
      title: "Custom agents",
      description: "Agents per team, with their own knowledge and tools.",
      mock: "agents",
    },
    {
      title: "AI asset governance",
      description: "Owners, versions and approvals for every agent and tool.",
      mock: "governance",
    },
    {
      title: "MCP server",
      description: "Approved knowledge for Claude Desktop, Codex and other MCP clients.",
      mock: "mcp",
    },
    {
      title: "Permission-aware by design",
      description: "Access is checked before retrieval, on every surface.",
      mock: "permissions",
    },
  ],
};

const deployment: SectionIntro & {
  caption: string;
  people: Entry;
  entry: string;
  account: string;
  network: string;
  application: DeploymentHost;
  internalLink: string;
  data: DeploymentHost;
  modelLink: string;
  model: DeploymentHost;
  sharedServices: readonly Entry[];
} = {
  title: "Runs inside your AWS environment",
  description: "Two EC2 hosts in a private VPC; only web traffic enters.",
  caption: "MemoryOS Production PoC architecture on AWS",
  people: { title: "Employees and MCP clients", description: "SSO sign-in" },
  entry: "HTTPS",
  account: "Your AWS account",
  network: "Private VPC",
  application: {
    title: "Application and processing",
    platform: "Amazon EC2",
    services: [
      "Web and API",
      "Connectors and workers",
      "Docling and OCR",
      "Reverse proxy and monitoring",
    ],
  },
  internalLink: "Private network",
  data: {
    title: "Data, search and storage",
    platform: "Amazon EC2 with Amazon EBS",
    services: [
      "PostgreSQL: metadata and access rules",
      "Redis: queues and cache",
      "OpenSearch: full-text and vector",
      "MinIO: files and citations",
    ],
  },
  modelLink: "Permitted context only",
  model: {
    title: "Managed model endpoint",
    platform: "On AWS",
    services: ["No GPU servers to run"],
  },
  sharedServices: [
    { title: "Amazon S3 and EBS snapshots", description: "Independent backups" },
    { title: "Amazon CloudWatch", description: "Monitoring and alerts" },
    { title: "AWS KMS and Secrets Manager", description: "Keys and secrets" },
    { title: "AWS IAM", description: "Least-privilege access" },
    { title: "Amazon ECR", description: "Container images" },
  ],
};

const roadmap: SectionIntro & {
  milestones: readonly Milestone[];
  deliverableLabel: string;
  next: { title: string; items: readonly string[] };
} = {
  title: "From Production PoC to company-wide memory",
  description: "The PoC with our partner Tasco ships one deliverable each month.",
  milestones: [
    {
      period: "September 2026",
      dateTime: "2026-09",
      title: "Foundation",
      description: "Private network, SSO, web and API running on AWS.",
      deliverable: "Secure platform",
    },
    {
      period: "October 2026",
      dateTime: "2026-10",
      title: "Knowledge",
      description: "Sources indexed; search and cited answers respect access.",
      deliverable: "Cited search",
    },
    {
      period: "November 2026",
      dateTime: "2026-11",
      title: "Agents",
      description: "Custom agents, asset governance and the MCP server.",
      deliverable: "Agents and MCP",
    },
    {
      period: "December 2026",
      dateTime: "2026-12",
      title: "Acceptance",
      description: "User testing, quality measured, next-phase proposal.",
      deliverable: "PoC report",
    },
  ],
  deliverableLabel: "Deliverable:",
  next: {
    title: "After the PoC",
    items: [
      "Company-wide rollout",
      "ERP, CRM and HR connectors",
      "Web search and deep research",
      "Multi-AZ high availability",
    ],
  },
};

const faq: SectionIntro & { items: readonly { question: string; answer: string }[] } = {
  title: "Frequently asked questions",
  description: "What IT, security and business teams ask before a pilot.",
  items: [
    {
      question: "What is MemoryOS?",
      answer:
        "MemoryOS is an AI and knowledge layer built by Vadan. It connects approved company sources, answers questions with citations, and gives employees, custom agents and MCP clients one permission model.",
    },
    {
      question: "Where does our data stay?",
      answer:
        "In your own cloud environment. MemoryOS runs on Amazon EC2 in a private VPC, stores backups on Amazon S3 and EBS snapshots, and keeps keys and secrets in AWS KMS and Secrets Manager.",
    },
    {
      question: "What is exposed to the internet?",
      answer:
        "Only the HTTPS entry point for the web app and API. The application and data hosts stay on private networking, and model calls go to a protected endpoint on AWS.",
    },
    {
      question: "Which AI model answers questions?",
      answer:
        "A managed large language model endpoint on AWS. MemoryOS sends it only the context the signed-in person may read, and you do not operate GPU servers.",
    },
    {
      question: "How are permissions enforced?",
      answer:
        "People sign in through SSO/OIDC. MemoryOS checks access rules before retrieval, so search, answers, custom agents and MCP clients return only what that person may see.",
    },
    {
      question: "Which sources can we connect?",
      answer:
        "Google Drive, uploaded files, OpenAPI and REST APIs, and other business systems your company approves. Docling and OCR extract text, tables and layout from documents, including scanned files.",
    },
    {
      question: "How does search find the right passages?",
      answer:
        "Documents are split into passages that keep their source and access rules. Hybrid search combines full-text and vector matching across the passages a person may read, then ranks them for relevance.",
    },
    {
      question: "Who does Vadan work with?",
      answer:
        "Vadan partners with Tasco on a MemoryOS Production PoC from September to December 2026, and is backed by GenAI Fund.",
    },
    {
      question: "How do we start?",
      answer:
        "Email aws@vadan.app. We scope a pilot around your sources, identity provider and first use cases.",
    },
  ],
};

const footer: SectionIntro & {
  action: string;
  organization: string;
} = {
  title: "Bring MemoryOS to your company",
  description: "Tell us your sources and first use cases; we'll scope a pilot.",
  action: "Email aws@vadan.app",
  organization: "Vadan",
};

export {
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
  roadmap,
  trustSignals,
  type Capability,
  type CapabilityMock,
  type DeploymentHost,
  type GatePassage,
  type Highlight,
};

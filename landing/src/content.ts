import type { LucideIcon } from "lucide-react";
import {
  BadgeCheck,
  Bot,
  Cable,
  ChartColumn,
  KeyRound,
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

type Highlight = Entry & {
  points: readonly string[];
};

type CapabilitySize = "wide" | "standard" | "full";

type Capability = Entry & {
  icon: LucideIcon;
  size: CapabilitySize;
};

type DeploymentHost = {
  title: string;
  platform: string;
  services: readonly string[];
};

type Milestone = Entry & {
  period: string;
  dateTime: string;
};

const contact = {
  label: "Contact us",
  email: "aws@vanda.app",
  href: "mailto:aws@vanda.app",
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
  description:
    "MemoryOS connects your company's documents and business systems, answers questions with citations, and applies the same access rules to search, custom agents and MCP clients. It runs in your own AWS environment.",
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
    title: "Deploying with Tasco",
    description: "Tasco runs MemoryOS in a Production PoC from September to December 2026.",
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
    "MemoryOS is a separate AI and knowledge layer over the systems you already use. Employees and AI agents work from approved company data, with sources they can check.",
  search: {
    title: "Ask once instead of searching five systems",
    description:
      "Policies live in Drive, specifications in shared files, and numbers in business systems. MemoryOS indexes the sources your company approves and gives everyone one place to search and ask. Every answer cites the passages it used.",
    points: [
      "Full-text and semantic search across approved sources",
      "Answers that cite the original document and passage",
      "Built-in Python that turns data into tables, charts and reports",
    ],
    sources: [
      "Google Drive",
      "Uploaded files",
      "OpenAPI and REST APIs",
      "Approved business systems",
    ],
    indexLabel: "MemoryOS index",
    outputs: ["Search", "Cited answers", "Analysis and reports"],
  },
  governance: {
    title: "Enterprise AI that follows your access rules",
    description:
      "People sign in with your SSO. MemoryOS checks access before it retrieves anything, so the model only sees what that person may read. Search, custom agents and MCP clients share one permission model, and every AI asset has an owner, a version and an approval.",
    points: [
      "SSO/OIDC with your existing identity provider",
      "Access checked before every retrieval",
      "Owners, versions and approvals for agents, instructions and tools",
    ],
    asset: {
      name: "Supplier review agent",
      status: "Approved",
      fields: [
        { term: "Owner", detail: "Procurement team" },
        { term: "Version", detail: "3" },
        { term: "Knowledge", detail: "Procurement policies" },
        { term: "Tools", detail: "Contract lookup" },
        { term: "Available to", detail: "Procurement and Legal" },
      ],
    },
  },
} as const;

const capabilities: SectionIntro & { items: readonly Capability[] } = {
  title: "Everything a company knowledge layer needs",
  description:
    "Each capability works under the same permission model, from the first connected source to the last agent.",
  items: [
    {
      title: "Data connectors",
      description:
        "Connect Google Drive, uploaded files, OpenAPI and REST APIs, and other business systems you approve. Docling and OCR extract text from documents and scans, and the index stays current as sources change.",
      icon: Cable,
      size: "wide",
    },
    {
      title: "Enterprise search",
      description:
        "Hybrid full-text and vector search across everything a person is allowed to see, ranked for relevance.",
      icon: Search,
      size: "wide",
    },
    {
      title: "Cited answers",
      description:
        "Retrieval-augmented answers link each claim to the document and passage it came from.",
      icon: Quote,
      size: "standard",
    },
    {
      title: "Analysis and reports",
      description:
        "Built-in Python calculates, builds tables and charts, and produces reports from retrieved data.",
      icon: ChartColumn,
      size: "standard",
    },
    {
      title: "Enterprise SSO",
      description:
        "Sign in through SSO/OIDC and the identity and access management you already run.",
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
      title: "AI asset governance",
      description:
        "Versions, owners, approvals and permissions for agents, instructions and tools, so teams reuse what is approved.",
      icon: BadgeCheck,
      size: "standard",
    },
    {
      title: "MCP server",
      description:
        "Approved knowledge and tools for Codex, Claude Desktop and other agents through the Model Context Protocol.",
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

const howItWorks: SectionIntro & {
  steps: readonly Entry[];
  request: { title: string; steps: readonly Entry[] };
} = {
  title: "From connected sources to answers you can verify",
  description: "The same path serves every search, answer, agent and MCP request.",
  steps: [
    {
      title: "Connect",
      description: "Administrators connect approved sources. Each source keeps its access rules.",
    },
    {
      title: "Index",
      description:
        "Workers extract text with Docling and OCR, split it into passages, create embeddings and keep the full-text and vector index current.",
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
        description: "Identity comes from your SSO/OIDC provider and existing IAM.",
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

const deployment: SectionIntro & {
  caption: string;
  people: Entry;
  account: string;
  network: string;
  application: DeploymentHost;
  data: DeploymentHost;
  model: DeploymentHost;
  sharedServices: readonly Entry[];
} = {
  title: "Runs inside your AWS environment",
  description:
    "MemoryOS ships as containers on two Amazon EC2 instances in a private VPC. Only the web and API entry point is public; application, data and model traffic stays on private networking or protected endpoints.",
  caption: "MemoryOS Production PoC architecture on AWS",
  people: { title: "Employees and MCP clients", description: "HTTPS with SSO sign-in" },
  account: "Your AWS account",
  network: "Private VPC",
  application: {
    title: "Application and processing",
    platform: "Amazon EC2",
    services: [
      "MemoryOS web and API",
      "Connectors and workers",
      "Docling and OCR",
      "Reverse proxy and monitoring",
    ],
  },
  data: {
    title: "Data, search and storage",
    platform: "Amazon EC2 with Amazon EBS",
    services: [
      "PostgreSQL for metadata, users and access rules",
      "Redis for queues and cache",
      "OpenSearch for full-text and vector search",
      "MinIO for original files and citations",
    ],
  },
  model: {
    title: "Managed model endpoint",
    platform: "On AWS",
    services: ["Receives only permitted context", "No GPU servers for you to run"],
  },
  sharedServices: [
    { title: "Amazon S3 and EBS snapshots", description: "Independent backups" },
    { title: "Amazon CloudWatch", description: "Monitoring and alerts" },
    { title: "AWS KMS and Secrets Manager", description: "Encryption keys and secrets" },
    { title: "AWS IAM", description: "Least-privilege access" },
    { title: "Amazon ECR", description: "Container images" },
  ],
};

const roadmap: SectionIntro & {
  milestones: readonly Milestone[];
  next: { title: string; items: readonly Entry[] };
} = {
  title: "From Production PoC to company-wide memory",
  description:
    "The Tasco Production PoC runs from September to December 2026, and each month ends with a working deliverable.",
  milestones: [
    {
      period: "September 2026",
      dateTime: "2026-09",
      title: "Foundation",
      description:
        "Infrastructure, private network, SSO and sample data confirmed. Web, API, security and monitoring deployed.",
    },
    {
      period: "October 2026",
      dateTime: "2026-10",
      title: "Knowledge",
      description:
        "Sources connected, ingested and indexed. Enterprise search and cited answers respect access rules.",
    },
    {
      period: "November 2026",
      dateTime: "2026-11",
      title: "Agents",
      description:
        "Model integration and benchmark. Custom agents, AI asset governance and the MCP server working end to end.",
    },
    {
      period: "December 2026",
      dateTime: "2026-12",
      title: "Acceptance",
      description:
        "User testing. Answer quality, citations, latency and performance measured. PoC report and next-phase proposal delivered.",
    },
  ],
  next: {
    title: "After the PoC",
    items: [
      {
        title: "Company-wide rollout",
        description: "Every department on the same governed memory.",
      },
      {
        title: "More business systems",
        description: "Connectors for the ERP, CRM and HR systems teams depend on.",
      },
      {
        title: "Web search and deep research",
        description: "Answers that combine internal knowledge with vetted external sources.",
      },
      {
        title: "High availability",
        description: "Multi-AZ deployment for production scale.",
      },
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
        "MemoryOS is an AI and knowledge layer built by Vanda. It connects approved company sources, answers questions with citations, and gives employees, custom agents and MCP clients one permission model.",
    },
    {
      question: "Where does our data stay?",
      answer:
        "In your own cloud environment. MemoryOS runs on Amazon EC2 in a private VPC, stores backups on Amazon S3 and EBS snapshots, and keeps keys and secrets in AWS KMS and Secrets Manager.",
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
        "Google Drive, uploaded files, OpenAPI and REST APIs, and other business systems your company approves. Docling and OCR process documents, including scanned files.",
    },
    {
      question: "Who uses MemoryOS today?",
      answer:
        "Tasco is deploying MemoryOS in a Production PoC from September to December 2026. Vanda is backed by GenAI Fund.",
    },
    {
      question: "How do we start?",
      answer:
        "Email aws@vanda.app. We scope a pilot around your sources, identity provider and first use cases.",
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
    "Tell us about your sources, identity provider and first use cases, and we will scope a pilot together.",
  action: "Email aws@vanda.app",
  organization: "Vanda",
  links: [
    {
      label: "Source code on GitHub",
      href: "https://github.com/kl3inIT/MemoryOS",
      external: true,
    },
    { label: "Third-party notices", href: "/THIRD_PARTY_NOTICES.txt" },
  ],
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
  type CapabilitySize,
  type DeploymentHost,
  type Highlight,
};

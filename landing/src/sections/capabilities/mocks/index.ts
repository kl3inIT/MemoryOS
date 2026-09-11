import type { ComponentType } from "react";
import type { CapabilityMock } from "@/content";
import { AgentsMock } from "@/sections/capabilities/mocks/agents";
import { AnalysisMock } from "@/sections/capabilities/mocks/analysis";
import { CitationsMock } from "@/sections/capabilities/mocks/citations";
import { ConnectorsMock } from "@/sections/capabilities/mocks/connectors";
import { GovernanceMock } from "@/sections/capabilities/mocks/governance";
import { McpMock } from "@/sections/capabilities/mocks/mcp";
import { PermissionsMock } from "@/sections/capabilities/mocks/permissions";
import { SearchMock } from "@/sections/capabilities/mocks/search";
import { SsoMock } from "@/sections/capabilities/mocks/sso";

/*
 * Each capability's mock: markup in its final state, hidden from assistive technology, whose
 * elements declare their entrances (see animate-mock.ts). Without motion no timeline is built and
 * the mock stays as marked up.
 */
const mocks: Record<CapabilityMock, ComponentType> = {
  connectors: ConnectorsMock,
  search: SearchMock,
  citations: CitationsMock,
  analysis: AnalysisMock,
  sso: SsoMock,
  agents: AgentsMock,
  governance: GovernanceMock,
  mcp: McpMock,
  permissions: PermissionsMock,
};

export { mocks };

import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import {
  ApplicationSessionContext,
  type ApplicationSession,
} from "@/features/identity/application-session-context";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import { SourceSummaryCard } from "./source-summary-card";

const session: ApplicationSession = {
  actorId: "actor",
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Team", role: "OWNER" },
  capabilities: ["SOURCES_READ", "SOURCES_MANAGE"],
  scopedCapabilities: [],
};
const drive: SourceSummary = {
  id: "source",
  name: "Drive knowledge",
  type: "GOOGLE_DRIVE",
  access: "SYNC",
  status: "ACTIVE",
  documentCount: 12,
  pendingWork: false,
  lastSucceededAt: null,
  errorCode: null,
  permissions: {
    edit: true,
    delete: true,
    publish: true,
    manageConfiguration: true,
    removeItems: true,
  },
};
const groupsOnly =
  "Document access follows this Source's MemoryOS groups, not Google Drive file permissions.";
const perFile = /Readers need access to each file in Google Drive/;

function renderCard(source: SourceSummary) {
  render(
    <ApplicationSessionContext.Provider value={session}>
      <SourceSummaryCard source={source} />
    </ApplicationSessionContext.Provider>,
  );
}

afterEach(cleanup);

describe("SourceSummaryCard", () => {
  it("explains that Auto Sync readers need Google Drive file access", () => {
    renderCard(drive);

    expect(screen.getByText(perFile)).toBeTruthy();
    expect(screen.queryByText(groupsOnly)).toBeNull();
  });

  it("explains that a Private Drive Source follows its groups", () => {
    renderCard({ ...drive, access: "PRIVATE" });

    expect(screen.getByText(groupsOnly)).toBeTruthy();
    expect(screen.queryByText(perFile)).toBeNull();
  });

  it("adds no access explanation to a Public Drive Source or a FILE Source", () => {
    renderCard({ ...drive, access: "PUBLIC" });
    renderCard({ ...drive, id: "file", type: "FILE", access: "PRIVATE" });

    expect(screen.queryByText(groupsOnly)).toBeNull();
    expect(screen.queryByText(perFile)).toBeNull();
  });
});

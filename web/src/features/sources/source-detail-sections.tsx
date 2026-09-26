import type { ReactNode } from "react";
import { Card, CardContent } from "@/components/ui/card";
import { TabsContent } from "@/components/ui/tabs";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import { GoogleDrivePanel } from "@/features/sources/google-drive/google-drive-panel";
import { SourceItemHistory } from "@/features/sources/history/source-item-history";
import { SourceRunHistory } from "@/features/sources/history/source-run-history";
import { SharePointPanel } from "@/features/sources/sharepoint/sharepoint-panel";
import { SourceFilesPanel } from "./source-files-panel";
import { SourceGroupsSection } from "./source-groups-section";
import { SourceManagerSection } from "./source-manager-section";
import { SourceSectionTabs } from "./source-section-tabs";
import { googleDriveSections, type SourceSection } from "./source-sections";
import { SourceUploadForm } from "./source-upload-form";
import type { SourceDetail } from "./use-source-detail";

/** The tab panels of a Source, laid out for its provider. */
export function SourceDetailSections({
  source,
  detail,
  section,
  sections,
  showGroups,
  isAdministrator,
  onAuthorityChanged,
}: {
  source: SourceSummary;
  detail: SourceDetail;
  section: string;
  /** Sections of a file or SharePoint Source; Google Drive lays out its own. */
  sections: readonly SourceSection[];
  showGroups: boolean;
  isAdministrator: boolean;
  onAuthorityChanged: () => Promise<void>;
}) {
  const { permissions, itemActions } = detail;
  const panelDisabled = detail.managementBusy || source.status === "DELETING";
  const filesPanel = (
    <SourceFilesPanel
      source={source}
      files={detail.files}
      canUpload={permissions.upload}
      disabled={detail.itemBusy}
      working={itemActions.working}
      onReindex={permissions.reindex ? (item) => void itemActions.reindex(item) : undefined}
      onRemove={permissions.removeItems ? itemActions.remove : undefined}
    />
  );
  const settings = (
    <>
      {showGroups ? (
        <SourceGroupsSection
          sourceId={source.id}
          editable={permissions.edit}
          onAuthorityChanged={onAuthorityChanged}
        />
      ) : null}
      {isAdministrator ? (
        <SourceManagerSection source={source} onAssigned={onAuthorityChanged} />
      ) : null}
    </>
  );

  if (source.type === "GOOGLE_DRIVE")
    return (
      <>
        <GoogleDrivePanel
          source={source}
          sourceStale={detail.sourceQuery.isError}
          disabled={panelDisabled}
          onBusyChange={detail.setProviderBusy}
          activeSection={section}
          content={filesPanel}
          settings={settings}
          navigation={<SourceSectionTabs sections={googleDriveSections} />}
        />
        <TabsContent value="history">
          <HistoryCard>
            <SourceRunHistory key={source.id} sourceId={source.id} />
          </HistoryCard>
        </TabsContent>
      </>
    );

  return (
    <>
      <SourceSectionTabs sections={sections} />
      <TabsContent value="content">
        {source.type === "SHAREPOINT" ? (
          <div className="flex flex-col gap-6">
            <SharePointPanel
              source={source}
              sourceStale={detail.sourceQuery.isError}
              disabled={panelDisabled}
              onBusyChange={detail.setProviderBusy}
            />
            {filesPanel}
          </div>
        ) : (
          <>
            {permissions.upload ? (
              <SourceUploadForm
                upload={detail.upload}
                fileInput={detail.fileInput}
                disabled={detail.busy || source.status === "DELETING"}
              />
            ) : null}
            {filesPanel}
          </>
        )}
      </TabsContent>
      <TabsContent value="history">
        <HistoryCard>
          {source.type === "SHAREPOINT" ? (
            <SourceRunHistory key={`${source.id}-runs`} sourceId={source.id} kinds />
          ) : null}
          <SourceItemHistory key={source.id} sourceId={source.id} />
        </HistoryCard>
      </TabsContent>
      <TabsContent value="settings">
        <div className="mt-5">{settings}</div>
      </TabsContent>
    </>
  );
}

function HistoryCard({ children }: { children: ReactNode }) {
  return (
    <Card size="sm" className="mt-5">
      <CardContent>
        <div className="flex flex-col gap-6">{children}</div>
      </CardContent>
    </Card>
  );
}

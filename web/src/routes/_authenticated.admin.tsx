import { createFileRoute, Outlet } from "@tanstack/react-router";
import { SourceUploadRecoveryProvider } from "@/features/sources/upload/source-upload-recovery-provider";

/** Administration; the shell draws its sidebar from the page table and refuses a page the person may not open. */
export const Route = createFileRoute("/_authenticated/admin")({
  component: function AdministrationLayout() {
    return (
      <SourceUploadRecoveryProvider>
        <Outlet />
      </SourceUploadRecoveryProvider>
    );
  },
});

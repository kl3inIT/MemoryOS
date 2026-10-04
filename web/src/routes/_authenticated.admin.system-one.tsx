import { createFileRoute } from "@tanstack/react-router";
import { SystemOnePage } from "@/features/system-one/system-one-page";

export const Route = createFileRoute("/_authenticated/admin/system-one")({
  component: SystemOnePage,
});

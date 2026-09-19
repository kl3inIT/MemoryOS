import { createFileRoute } from "@tanstack/react-router";
import { MyUsagePage } from "@/features/usage/my-usage-page";

export const Route = createFileRoute("/_authenticated/settings/usage")({
  component: MyUsagePage,
});

import { createFileRoute } from "@tanstack/react-router";
import { SearchPage } from "@/features/search/search-page";

export const Route = createFileRoute("/_authenticated/")({
  component: SearchPage,
});

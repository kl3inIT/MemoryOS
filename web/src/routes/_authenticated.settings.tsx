import { createFileRoute } from "@tanstack/react-router";

/** Personal settings with Onyx's tabs in the administration-style sidebar (MEM-145); the shell draws the tabs. */
export const Route = createFileRoute("/_authenticated/settings")({});

import { useEffect, useState } from "react";
import { useNavigate, useSearch } from "@tanstack/react-router";
import { z } from "zod";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { foldText } from "@/lib/fold-text";
import type { Project } from "./chat-projects-api";

/** The Projects page's address: `q` narrows the list by name and description, so a reload or Back keeps it. */
export const projectsSearchSchema = z.object({
  q: z.string().max(200).optional().catch(undefined),
});

/**
 * The filter of the Projects page. The box holds what is typed and narrows the list at once; the address follows
 * once typing settles, replacing its entry, and an address changed by Back sets the box instead.
 */
export function useProjectsFilter() {
  const shown = useSearch({ from: "/_authenticated/projects/" }).q ?? "";
  const navigate = useNavigate({ from: "/projects/" });
  const [text, setText] = useState(shown);
  const [shownText, setShownText] = useState(shown);
  if (shownText !== shown) {
    setShownText(shown);
    if (text !== shown) setText(shown);
  }
  const settled = useDebouncedValue(text.trim(), 250);
  useEffect(() => {
    if (settled !== shown)
      void navigate({ search: { q: settled || undefined }, replace: true, resetScroll: false });
    // Only a settled change of the box writes the address; a change of the address sets the box above.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settled]);
  return { text, setText, active: text.trim().length > 0 };
}

/** The Projects whose name or description holds the typed words, with or without their marks, in list order. */
export function matchingProjects(projects: Project[], text: string) {
  const folded = foldText(text.trim());
  if (!folded) return projects;
  return projects.filter((project) =>
    foldText(`${project.name}\n${project.description}`).includes(folded),
  );
}

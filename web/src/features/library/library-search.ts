import { z } from "zod";
import { LIBRARY_CATEGORIES, LIBRARY_PAGE_SIZE, LIBRARY_PAGE_SIZES } from "./library";
import type { LibraryView } from "./library-views";

const LIBRARY_SOURCES = ["UPLOAD", "GENERATED", "IMAGE"] as const;
const LIBRARY_VIEWS = [
  "recent",
  "ready",
  "shared",
  "sources",
  "documents",
  "starred",
  "pending",
  "trash",
] as const satisfies readonly LibraryView[];
/** The kinds a chip of an entry view narrows to; the person's own files are one choice, whatever made them. */
const ENTRY_KIND_CHIPS = ["OWNED", "MEETING", "AGENT_FILE", "DOCUMENT"] as const;
export type EntryKindChip = (typeof ENTRY_KIND_CHIPS)[number];

/** One value or several: a link names one category, the filters may hold more. */
const some = <const T extends readonly [string, ...string[]]>(values: T) =>
  z
    .union([z.enum(values), z.array(z.enum(values)).max(values.length)])
    .transform((value) => (Array.isArray(value) ? value : [value]))
    .default([])
    .catch([]);

/**
 * What the library shows, in its address: the view, the search, the filters, the order and the page. The
 * defaults below are stripped from links, so `?category=IMAGE` opens the library narrowed to images. `starred`
 * narrows Tệp của tôi; `kind` and `sourceId` narrow the views of what reaches the person otherwise; `sourceStatus`,
 * `provider` and `access` narrow the Sources view.
 */
export const librarySearchSchema = z.object({
  view: z.enum(LIBRARY_VIEWS).default("ready").catch("ready"),
  q: z.string().max(500).default("").catch(""),
  mode: z.enum(["name", "content"]).default("name").catch("name"),
  category: some(LIBRARY_CATEGORIES),
  source: some(LIBRARY_SOURCES),
  sort: z
    .enum(["NEWEST", "OLDEST", "LARGEST", "SMALLEST", "NAME", "DELETED"])
    .default("NEWEST")
    .catch("NEWEST"),
  starred: z.boolean().default(false).catch(false),
  kind: z.enum(ENTRY_KIND_CHIPS).optional().catch(undefined),
  sourceId: z.string().uuid().optional().catch(undefined),
  sourceStatus: z
    .enum(["NOT_STARTED", "INDEXING", "ACTIVE", "PAUSED", "FAILED"])
    .optional()
    .catch(undefined),
  provider: z.enum(["FILE", "GOOGLE_DRIVE", "SHAREPOINT"]).optional().catch(undefined),
  access: z.enum(["PUBLIC", "PRIVATE", "SYNC"]).optional().catch(undefined),
  page: z.coerce.number().int().min(0).default(0).catch(0),
  size: z.coerce
    .number()
    .refine((size) => (LIBRARY_PAGE_SIZES as readonly number[]).includes(size))
    .default(LIBRARY_PAGE_SIZE)
    .catch(LIBRARY_PAGE_SIZE),
});

export type LibrarySearch = z.output<typeof librarySearchSchema>;

export const librarySearchDefaults = {
  view: "ready",
  q: "",
  mode: "name",
  category: [],
  source: [],
  sort: "NEWEST",
  starred: false,
  page: 0,
  size: LIBRARY_PAGE_SIZE,
} as const satisfies LibrarySearch;

/**
 * The filters one view keeps that mean nothing on another. Moving to or from a view other than the person's own
 * files clears them; the search and the page size follow the person everywhere.
 */
export const viewFilterDefaults = {
  mode: "name",
  category: [],
  source: [],
  sort: "NEWEST",
  starred: false,
  kind: undefined,
  sourceId: undefined,
  sourceStatus: undefined,
  provider: undefined,
  access: undefined,
} as const satisfies Partial<LibrarySearch>;

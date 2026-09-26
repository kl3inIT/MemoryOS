import { z } from "zod";
import { LIBRARY_CATEGORIES, LIBRARY_PAGE_SIZE, LIBRARY_PAGE_SIZES } from "./library";

const LIBRARY_SOURCES = ["UPLOAD", "GENERATED", "IMAGE"] as const;

/** One value or several: a link names one category, the filters may hold more. */
const some = <const T extends readonly [string, ...string[]]>(values: T) =>
  z
    .union([z.enum(values), z.array(z.enum(values)).max(values.length)])
    .transform((value) => (Array.isArray(value) ? value : [value]))
    .default([])
    .catch([]);

/**
 * What the file library shows, in its address: the slice, the search, the filters, the order and the page. The
 * defaults below are stripped from links, so `?category=IMAGE` opens the library narrowed to images.
 */
export const librarySearchSchema = z.object({
  view: z.enum(["ready", "favorite", "pending", "trash"]).default("ready").catch("ready"),
  q: z.string().max(500).default("").catch(""),
  mode: z.enum(["name", "content"]).default("name").catch("name"),
  category: some(LIBRARY_CATEGORIES),
  source: some(LIBRARY_SOURCES),
  sort: z
    .enum(["NEWEST", "OLDEST", "LARGEST", "SMALLEST", "NAME", "DELETED"])
    .default("NEWEST")
    .catch("NEWEST"),
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
  page: 0,
  size: LIBRARY_PAGE_SIZE,
} as const satisfies LibrarySearch;

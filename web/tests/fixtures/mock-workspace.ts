import type { IncomingMessage, ServerResponse } from "node:http";
import type {
  ChatLibraryEntry,
  ChatLibraryFile,
  ChatLibrarySource,
} from "../../src/lib/hey-api/types.gen.ts";

/**
 * Sample Projects and Library files for browsing the app by hand on `pnpm mock-api`. Only
 * `scripts/start-mock-api.ts` composes this module; the browser tests never see it, so their counts of
 * Projects and files stay what each spec creates or routes itself.
 */
const hour = 3_600_000;
const ago = (hours: number) => new Date(Date.now() - hours * hour).toISOString();
const fileId = (index: number) =>
  `71000000-0000-4000-8000-0000000000${String(index).padStart(2, "0")}`;
const reportProject = { kind: "PROJECT", id: "72000000-0000-4000-8000-000000000001" } as const;

const file = (
  index: number,
  overrides: Partial<ChatLibraryFile> & Pick<ChatLibraryFile, "filename" | "mediaType">,
): ChatLibraryFile => ({
  source: "UPLOAD",
  id: fileId(index),
  sizeBytes: 250_000,
  createdAt: ago(1),
  category: "DOCUMENT",
  sessionId: null,
  sessionTitle: null,
  messageId: null,
  favorite: false,
  status: "READY",
  errorCode: null,
  deletedAt: null,
  purgeAfter: null,
  usedBy: [],
  deletable: true,
  ...overrides,
});

const files: ChatLibraryFile[] = [
  file(1, {
    filename: "bao-cao-tai-chinh-quy-3.xlsx",
    mediaType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    category: "SPREADSHEET",
    sizeBytes: 1_840_000,
    createdAt: ago(2),
    favorite: true,
    usedBy: [{ ...reportProject, name: "Báo cáo quý 3" }],
    deletable: false,
  }),
  file(2, {
    filename: "hop-dong-thue-van-phong.pdf",
    mediaType: "application/pdf",
    sizeBytes: 3_200_000,
    createdAt: ago(5),
    usedBy: [{ ...reportProject, name: "Báo cáo quý 3" }],
    deletable: false,
  }),
  file(3, {
    filename: "ghi-am-giao-ban-tuan-38.mp3",
    mediaType: "audio/mpeg",
    category: "OTHER",
    sizeBytes: 18_500_000,
    createdAt: ago(27),
  }),
  file(4, {
    source: "IMAGE",
    filename: "poster-hoi-nghi-khach-hang.png",
    mediaType: "image/png",
    category: "IMAGE",
    sizeBytes: 920_000,
    createdAt: ago(30),
    sessionTitle: "Thiết kế poster hội nghị",
  }),
  file(5, {
    source: "GENERATED",
    filename: "ke-hoach-marketing-thang-10.docx",
    mediaType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    sizeBytes: 74_000,
    createdAt: ago(75),
    favorite: true,
    sessionTitle: "Lập kế hoạch marketing",
  }),
  file(6, {
    source: "GENERATED",
    filename: "bao-cao-doanh-thu.pptx",
    mediaType: "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    category: "PRESENTATION",
    sizeBytes: 5_600_000,
    createdAt: ago(24 * 12),
    sessionTitle: "Tổng hợp doanh thu theo vùng",
  }),
  file(7, {
    filename: "quy-che-nhan-su-2026.docx",
    mediaType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    sizeBytes: 410_000,
    createdAt: ago(24 * 40),
  }),
  file(8, {
    source: "IMAGE",
    filename: "so-do-to-chuc.png",
    mediaType: "image/png",
    category: "IMAGE",
    sizeBytes: 640_000,
    createdAt: ago(24 * 41),
  }),
  file(9, {
    filename: "ban-nhap-de-xuat-cu.pdf",
    mediaType: "application/pdf",
    sizeBytes: 150_000,
    createdAt: ago(24 * 20),
    deletedAt: ago(24 * 2),
    purgeAfter: ago(-24 * 28),
  }),
];

/** Projects with their conversations, newest first; the last one is empty. */
export const mockProjects = [
  {
    name: "Báo cáo quý 3",
    description: "Tổng hợp số liệu và soạn báo cáo tài chính quý 3.",
    instructions: "Trả lời bằng tiếng Việt, số liệu theo đơn vị triệu đồng.",
    iconName: "finance",
    fileIds: [fileId(1), fileId(2)],
    sessions: ["Đối chiếu doanh thu theo vùng", "Soạn phần nhận xét tổng quan"],
  },
  {
    name: "Tuyển dụng 2026",
    description: "Mô tả công việc, câu hỏi phỏng vấn và đánh giá ứng viên.",
    instructions: "",
    iconName: "people",
    fileIds: [],
    sessions: ["Mô tả công việc Kế toán trưởng", "Bộ câu hỏi phỏng vấn Backend"],
  },
  {
    name: "Ý tưởng sản phẩm",
    description: "",
    instructions: "",
    iconName: "idea",
    fileIds: [],
    sessions: [] as string[],
  },
];

const entryOf = (value: ChatLibraryFile): ChatLibraryEntry => ({
  kind: value.source,
  id: value.id,
  name: value.filename,
  mediaType: value.mediaType,
  sizeBytes: value.sizeBytes,
  category: value.category,
  at: value.createdAt,
  owned: true,
  starred: value.favorite,
  openedAt: null,
  ownerName: null,
  reason: { kind: "OWNER", names: [] },
  sessionId: value.sessionId,
  sessionTitle: value.sessionTitle,
  messageId: value.messageId,
  meeting: null,
  agents: [],
  document: null,
});

const sourceId = "73000000-0000-4000-8000-000000000001";
const meeting: ChatLibraryEntry = {
  ...entryOf(files[2]!),
  kind: "MEETING",
  id: "74000000-0000-4000-8000-000000000001",
  name: "Giao ban tuần 38",
  mediaType: null,
  sizeBytes: null,
  at: ago(26),
  owned: false,
  starred: false,
  ownerName: "Trần Thu Hà",
  reason: { kind: "GROUP_SHARE", names: ["Kế toán"] },
  meeting: { status: "ENDED", minutesReady: true, hasTranscript: true, durationMs: 42 * 60_000 },
};
const document: ChatLibraryEntry = {
  ...entryOf(files[1]!),
  kind: "DOCUMENT",
  id: "75000000-0000-4000-8000-000000000001",
  name: "quy-trinh-mua-sam.pdf",
  at: ago(24 * 6),
  owned: false,
  starred: false,
  reason: { kind: "PUBLIC_SOURCE", names: [] },
  document: {
    generation: "1",
    title: "Quy trình mua sắm",
    sourceId,
    sourceName: "Nhân sự",
    sourceType: "FILE",
    providerUrl: null,
  },
};
const sources: ChatLibrarySource[] = [
  {
    id: sourceId,
    name: "Nhân sự",
    type: "FILE",
    access: "PUBLIC",
    status: "ACTIVE",
    readableDocuments: 1,
    lastSucceededAt: ago(24),
    groups: [],
    managerName: "Nguyễn Văn An",
  },
];

function send(response: ServerResponse, data: unknown, status = 200) {
  if (status === 204) response.writeHead(204);
  else response.writeHead(status, { "content-type": "application/json" });
  response.end(status === 204 ? undefined : JSON.stringify(data));
}

function listed(params: URLSearchParams) {
  const query = (params.get("query") ?? "").toLocaleLowerCase();
  const sourceFilter = params.getAll("sources");
  const categories = params.getAll("categories");
  const status = params.get("status") ?? "READY";
  const newest = (a: ChatLibraryFile, b: ChatLibraryFile) => b.createdAt.localeCompare(a.createdAt);
  const order: Record<string, (a: ChatLibraryFile, b: ChatLibraryFile) => number> = {
    OLDEST: (a, b) => a.createdAt.localeCompare(b.createdAt),
    LARGEST: (a, b) => b.sizeBytes - a.sizeBytes,
    SMALLEST: (a, b) => a.sizeBytes - b.sizeBytes,
    NAME: (a, b) => a.filename.localeCompare(b.filename),
    DELETED: (a, b) => (b.deletedAt ?? "").localeCompare(a.deletedAt ?? ""),
  };
  return files
    .filter((value) => (status === "TRASH" ? value.deletedAt !== null : value.deletedAt === null))
    .filter((value) => status === "TRASH" || (status === "READY") === (value.status === "READY"))
    .filter((value) => !query || value.filename.toLocaleLowerCase().includes(query))
    .filter((value) => !sourceFilter.length || sourceFilter.includes(value.source))
    .filter((value) => !categories.length || categories.includes(value.category))
    .filter((value) => params.get("favorite") !== "true" || value.favorite)
    .sort(order[params.get("sort") ?? "NEWEST"] ?? newest);
}

function page<T>(items: T[], params: URLSearchParams) {
  const offset = Number(params.get("offset") ?? 0);
  const limit = Number(params.get("limit") ?? 50);
  return { items: items.slice(offset, offset + limit), hasMore: items.length > offset + limit };
}

/**
 * The Library routes of `pnpm mock-api`, shaped as the generated `/api/chat/library*` types. Stars are kept in
 * memory and other writes are acknowledged without effect; an unknown read answers 404. Returns false for a
 * path outside the family.
 */
export function handleMockLibrary(request: IncomingMessage, response: ServerResponse): boolean {
  const url = new URL(request.url!, "http://localhost");
  if (!url.pathname.startsWith("/api/chat/library")) return false;
  const path = url.pathname.slice("/api/chat/library".length);
  const params = url.searchParams;
  const star = /^\/entries\/[A-Z_]+\/([0-9a-f-]+)\/star$/.exec(path);
  if (star) {
    const target = files.find((value) => value.id === star[1]);
    if (target) target.favorite = request.method === "PUT";
    send(response, null, 204);
  } else if (request.method !== "GET") send(response, null, 204);
  else if (path === "") {
    const all = listed(params);
    send(response, {
      ...page(all, params),
      totalCount: all.length,
      totalBytes: all.reduce((sum, value) => sum + value.sizeBytes, 0),
    });
  } else if (path === "/usage") {
    const owned = files.filter((value) => value.deletedAt === null);
    send(response, {
      usedBytes: owned.reduce((sum, value) => sum + value.sizeBytes, 0),
      fileCount: owned.length,
      limitBytes: 5_000_000_000,
      byCategory: [...new Set(owned.map((value) => value.category))].map((category) => ({
        category,
        usedBytes: owned
          .filter((value) => value.category === category)
          .reduce((sum, value) => sum + value.sizeBytes, 0),
      })),
    });
  } else if (path === "/trash") send(response, { days: 30 });
  else if (path === "/recent")
    send(response, [
      ...files
        .filter((value) => value.deletedAt === null)
        .slice(0, 4)
        .map(entryOf),
      meeting,
    ]);
  else if (path === "/starred") {
    const starred = files.filter((value) => value.favorite && value.deletedAt === null);
    send(response, { ...page(starred.map(entryOf), params), totalCount: starred.length });
  } else if (path === "/shared")
    send(response, { items: [meeting], totalCount: 1, hasMore: false });
  else if (path === "/sources") send(response, sources);
  else if (path === "/documents") send(response, { items: [document], nextCursor: null });
  else if (path === "/documents/sources")
    send(response, [{ id: sourceId, name: "Nhân sự", type: "FILE" }]);
  else if (path === "/search" || path === "/archives") send(response, []);
  else send(response, {}, 404);
  return true;
}

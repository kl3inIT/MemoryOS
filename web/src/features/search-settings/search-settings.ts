import type { QueryClient } from "@tanstack/react-query";
import { appText, type AppCopy, type AppText } from "@/i18n/app-text";
import { ApiError } from "@/lib/api";
import type {
  EmbeddingModelPresetResponse,
  SearchGenerationRequest,
  SearchGenerationResponse,
  SearchRebuildProgressResponse,
  SearchSettingsResponse,
} from "@/lib/hey-api/types.gen";

export type Generation = SearchGenerationResponse;
export type RebuildProgress = SearchRebuildProgressResponse;
export type SearchSettings = SearchSettingsResponse;
export type ModelPreset = EmbeddingModelPresetResponse;

/** Every read on this page changes together: providers carry `inUse`, generations carry provider names. */
export async function refreshSearchSettings(client: QueryClient) {
  await Promise.all(
    ["getSearchSettings", "listEmbeddingProviders"].map((id) =>
      client.invalidateQueries({ queryKey: [{ _id: id }] }, { throwOnError: true }),
    ),
  );
}

/** How often the page rereads the settings while a future generation is being built. */
export const rebuildPollMillis = 5_000;

/** The change-model form keeps numbers as typed text so a half-typed value is never coerced. */
export type GenerationDraft = {
  providerId: string;
  model: string;
  dimensions: string;
  queryPrefix: string;
  documentPrefix: string;
  minimumSemanticScore: string;
};

/** The form opens on the present generation, so an administrator edits from what is running. */
export function draftFrom(present: Generation): GenerationDraft {
  return {
    providerId: present.providerId,
    model: present.model,
    dimensions: String(present.dimensions),
    queryPrefix: present.queryPrefix,
    documentPrefix: present.documentPrefix,
    minimumSemanticScore: String(present.minimumSemanticScore),
  };
}

/** A preset fills what decides the vectors; the provider and the score threshold stay as chosen. */
export function applyPreset(draft: GenerationDraft, preset: ModelPreset): GenerationDraft {
  return {
    ...draft,
    model: preset.model,
    dimensions: String(preset.dimensions),
    queryPrefix: preset.queryPrefix,
    documentPrefix: preset.documentPrefix,
  };
}

/** The preset the draft still matches exactly, or none once any of its prefilled fields was edited. */
export function matchingPreset(draft: GenerationDraft, presets: readonly ModelPreset[]) {
  return presets.find(
    (preset) =>
      preset.model === draft.model &&
      String(preset.dimensions) === draft.dimensions.trim() &&
      preset.queryPrefix === draft.queryPrefix &&
      preset.documentPrefix === draft.documentPrefix,
  );
}

export type DraftProblem = "provider" | "model" | "dimensions" | "score";

/** Prefixes are sent verbatim: a trailing space or newline is part of what the model was trained on. */
export function generationRequest(
  draft: GenerationDraft,
):
  | { request: SearchGenerationRequest; problems: [] }
  | { request: null; problems: DraftProblem[] } {
  const problems: DraftProblem[] = [];
  const dimensions = Number(draft.dimensions.trim());
  const score = Number(draft.minimumSemanticScore.trim());
  if (!draft.providerId) problems.push("provider");
  if (!draft.model.trim()) problems.push("model");
  if (!/^\d+$/.test(draft.dimensions.trim()) || dimensions < 1 || dimensions > 65_536)
    problems.push("dimensions");
  if (!draft.minimumSemanticScore.trim() || !Number.isFinite(score) || score < 0 || score > 1)
    problems.push("score");
  if (problems.length > 0) return { request: null, problems };
  return {
    request: {
      providerId: draft.providerId,
      model: draft.model.trim(),
      dimensions,
      queryPrefix: draft.queryPrefix,
      documentPrefix: draft.documentPrefix,
      minimumSemanticScore: score,
    },
    problems: [],
  };
}

/** Whole percent, never 100 before every document is ready. */
export function rebuildPercent(progress: RebuildProgress) {
  if (progress.total <= 0) return progress.switchable ? 100 : 0;
  const ratio = Math.min(1, progress.ready / progress.total);
  return progress.ready >= progress.total ? 100 : Math.min(99, Math.floor(ratio * 100));
}

/** Remaining time in plain steps; seconds are noise for a rebuild that takes minutes. */
export function remainingTime(seconds: number | null): AppText {
  if (seconds == null || !Number.isFinite(seconds) || seconds < 0) return appText("Đang ước lượng");
  if (seconds < 60) return appText("Dưới 1 phút");
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return appText("Khoảng {{minutes}} phút", { minutes });
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest === 0
    ? appText("Khoảng {{hours}} giờ", { hours })
    : appText("Khoảng {{hours}} giờ {{minutes}} phút", { hours, minutes: rest });
}

/** A PAST generation can still be restored until its retention ends. */
export function retained(generation: Generation, now = Date.now()) {
  return generation.retainedUntil != null && new Date(generation.retainedUntil).getTime() > now;
}

export type SearchActions = {
  /** A new model can be chosen only while nothing is being rebuilt. */
  changeModel: boolean;
  /** The switch button exists only for a rebuild a person started; an automatic one switches itself. */
  showSwitch: boolean;
  switchEnabled: boolean;
  cancel: boolean;
  restore: (generation: Generation) => boolean;
};

export function searchActions(settings: SearchSettings, now = Date.now()): SearchActions {
  const future = settings.future;
  return {
    changeModel: future == null,
    showSwitch: future != null && !future.automatic,
    switchEnabled: future != null && !future.automatic && Boolean(settings.rebuild?.switchable),
    cancel: future != null,
    restore: (generation) =>
      future == null && generation.status === "PAST" && retained(generation, now),
  };
}

export type SearchOperation =
  | "load"
  | "create"
  | "cancel"
  | "switch"
  | "restore"
  | "saveProvider"
  | "deleteProvider"
  | "test";

/** Branches on the status only, as the API conventions require; the design gives each 409 one meaning. */
export function searchSettingsError(cause: unknown, operation: SearchOperation): string {
  if (!(cause instanceof ApiError)) return "Không gửi được yêu cầu. Kiểm tra kết nối rồi thử lại.";
  switch (cause.status) {
    case 400:
      return "Cấu hình không hợp lệ. Kiểm tra endpoint, model và số chiều.";
    case 401:
      return "Phiên đăng nhập đã hết. Đăng nhập lại.";
    case 403:
      return "Chỉ quản trị model của Tenant vận hành được đổi cấu hình tìm kiếm.";
    case 404:
      return "Mục này không còn. Tải lại trang.";
    case 409:
      switch (operation) {
        case "create":
          return "Đang có một index được dựng lại.";
        case "switch":
          return "Index mới chưa dựng xong.";
        case "restore":
          return "Index này đã hết hạn giữ, hoặc đang có index được dựng lại.";
        case "saveProvider":
          return "Provider vừa được sửa ở nơi khác. Tải lại rồi thử lại.";
        case "deleteProvider":
          return "Provider đang được một index dùng.";
        default:
          return "Cấu hình vừa thay đổi. Tải lại rồi thử lại.";
      }
    case 503:
      return "Provider embedding hoặc OpenSearch chưa sẵn sàng.";
    default:
      return "Yêu cầu thất bại. Tải lại rồi thử lại.";
  }
}

/** The server's own explanation, shown after our copy and never used as a translation key. */
export function problemDetail(cause: unknown): string | undefined {
  if (!(cause instanceof ApiError)) return undefined;
  const problem = cause.cause;
  if (!problem || typeof problem !== "object" || !("detail" in problem)) return undefined;
  const detail = problem.detail;
  return typeof detail === "string" && detail.trim() ? detail.trim().slice(0, 300) : undefined;
}

/** Copy for a failed action, with the server's detail appended for a conflict. */
export function searchSettingsProblem(cause: unknown, operation: SearchOperation): AppCopy {
  const message = searchSettingsError(cause, operation);
  const detail =
    cause instanceof ApiError && cause.status === 409 ? problemDetail(cause) : undefined;
  return detail
    ? appText("{{message}} {{detail}}", { message: appText(message), detail })
    : message;
}

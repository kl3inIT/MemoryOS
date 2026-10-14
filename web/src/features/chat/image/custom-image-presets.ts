import type { ImageTemplate, ImageTemplateStep } from "@/lib/hey-api/types.gen";

/** A starting point for a custom HTTP image connection; product names are proper nouns. */
export type CustomImagePreset = { id: string; label: string; template: ImageTemplate };

const openAiCompatible: ImageTemplate = {
  authHeader: "Authorization",
  authPrefix: "Bearer ",
  generate: {
    path: "/images/generations",
    body: '{"model":"{{model}}","prompt":"{{prompt}}","n":1,"response_format":"b64_json"}',
    imagePointer: "/data/0/b64_json",
    imageFormat: "BASE64",
  },
};

export const customImagePresets: CustomImagePreset[] = [
  { id: "openai-compatible", label: "OpenAI-compatible", template: openAiCompatible },
  {
    id: "together",
    label: "Together AI",
    template: {
      ...openAiCompatible,
      generate: {
        ...openAiCompatible.generate,
        body: '{"model":"{{model}}","prompt":"{{prompt}}","n":1,"response_format":"base64","aspect_ratio":"{{aspect_ratio}}"}',
      },
    },
  },
  {
    id: "fal",
    label: "fal.ai",
    template: {
      authHeader: "Authorization",
      authPrefix: "Key ",
      generate: {
        path: "/fal-ai/flux/schnell",
        body: '{"prompt":"{{prompt}}","sync_mode":true}',
        imagePointer: "/images/0/url",
        imageFormat: "DATA_URI",
      },
    },
  },
  {
    id: "hugging-face",
    label: "Hugging Face Inference",
    template: {
      authHeader: "Authorization",
      authPrefix: "Bearer ",
      generate: {
        path: "/models/black-forest-labs/FLUX.1-schnell",
        body: '{"inputs":"{{prompt}}"}',
        imagePointer: "",
        imageFormat: "RAW",
      },
    },
  },
];

export type ImageFormat = ImageTemplateStep["imageFormat"];

/** The custom HTTP template as the form edits it: both steps are always present, `editing` keeps the edit step. */
export type TemplateDraft = {
  authHeader: string;
  authPrefix: string;
  generate: ImageTemplateStep;
  edit: ImageTemplateStep;
  editing: boolean;
};

const emptyStep: ImageTemplateStep = {
  path: "",
  body: "",
  imagePointer: "",
  imageFormat: "BASE64",
};

export function templateDraft(template?: ImageTemplate | null): TemplateDraft {
  return {
    authHeader: template?.authHeader ?? "Authorization",
    authPrefix: template?.authPrefix ?? "Bearer ",
    generate: { ...(template?.generate ?? emptyStep) },
    edit: { ...(template?.edit ?? emptyStep) },
    editing: !!template?.edit,
  };
}

const stepOf = (step: ImageTemplateStep): ImageTemplateStep => ({
  path: step.path.trim(),
  body: step.body,
  imagePointer: step.imageFormat === "RAW" ? "" : step.imagePointer.trim(),
  imageFormat: step.imageFormat,
});

export function templateOf(draft: TemplateDraft): ImageTemplate {
  return {
    authHeader: draft.authHeader.trim(),
    authPrefix: draft.authPrefix,
    generate: stepOf(draft.generate),
    edit: draft.editing ? stepOf(draft.edit) : undefined,
  };
}

/** Whether the text is a JSON object, the only request body a template sends. */
export function isJsonObject(text: string) {
  try {
    const parsed: unknown = JSON.parse(text);
    return typeof parsed === "object" && parsed !== null && !Array.isArray(parsed);
  } catch {
    return false;
  }
}

const stepComplete = (step: ImageTemplateStep) =>
  step.path.startsWith("/") &&
  isJsonObject(step.body) &&
  (step.imageFormat === "RAW" || step.imagePointer.trim().startsWith("/"));

/** Whether the template is complete enough to test. */
export function templateComplete(draft: TemplateDraft) {
  return (
    draft.authHeader.trim().length > 0 &&
    stepComplete(draft.generate) &&
    (!draft.editing || stepComplete(draft.edit))
  );
}

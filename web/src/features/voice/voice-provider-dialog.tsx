import { useId, useState, type FormEvent } from "react";
import { CheckCircle2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select } from "@/components/ui/select";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  saveChatVoiceConnection,
  selectChatVoiceProvider,
  testChatVoiceConnection,
} from "@/lib/hey-api/sdk.gen";
import type { VoiceConnectionResponse, VoiceProviderResponse } from "@/lib/hey-api/types.gen";
import type { ErrorMessage } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { canServe, voiceProblem, type VoiceFunction } from "./voice-providers";

/**
 * Connect or edit one provider for one function. The server verifies the credential before it stores anything; the
 * other function's model and voice are sent back unchanged.
 */
export function VoiceProviderDialog({
  open,
  onOpenChange,
  fn,
  name,
  provider,
  connection,
  autoSelect,
  onSaved,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  fn: VoiceFunction;
  name: string;
  provider: VoiceProviderResponse;
  connection?: VoiceConnectionResponse;
  autoSelect: boolean;
  onSaved: () => Promise<void>;
}) {
  const ui = useAppTranslation();
  const problemMessage = useProblemMessage();
  const id = useId();
  const initial = {
    endpoint: connection?.endpoint ?? "",
    model:
      fn === "STT"
        ? connection?.sttModel || provider.sttModels[0] || ""
        : connection?.ttsModel || provider.ttsModels[0] || "",
    voice: connection?.ttsVoice || provider.voices[0] || "",
  };
  const [endpoint, setEndpoint] = useState(initial.endpoint);
  const [key, setKey] = useState("");
  const [removeKey, setRemoveKey] = useState(false);
  const [model, setModel] = useState(initial.model);
  const [voice, setVoice] = useState(initial.voice);
  const [pending, setPending] = useState<"save" | "test">();
  const [error, setError] = useState<ErrorMessage>();
  const [tested, setTested] = useState(false);

  function changeOpen(next: boolean) {
    if (pending) return;
    if (!next) {
      setEndpoint(initial.endpoint);
      setKey("");
      setRemoveKey(false);
      setModel(initial.model);
      setVoice(initial.voice);
      setError(undefined);
      setTested(false);
    }
    onOpenChange(next);
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const body = {
      endpoint: endpoint.trim(),
      sttModel: fn === "STT" ? model.trim() : (connection?.sttModel ?? ""),
      ttsModel: fn === "TTS" ? model.trim() : (connection?.ttsModel ?? ""),
      ttsVoice: fn === "TTS" ? voice.trim() : (connection?.ttsVoice ?? ""),
    };
    const becomesDefault =
      autoSelect &&
      canServe(provider, fn, {
        ...body,
        credential: key !== "" || (!!connection?.credentialConfigured && !removeKey),
      });
    setPending("save");
    setError(undefined);
    setTested(false);
    try {
      await saveChatVoiceConnection({
        path: { provider: provider.provider },
        body: {
          ...body,
          credentialAction: key ? "REPLACE" : removeKey ? "REMOVE" : "KEEP",
          credentialValue: key || undefined,
          activate: becomesDefault && !connection ? fn : undefined,
          revision: connection?.revision ?? 0,
        },
        headers: sameOriginMutationHeaders,
        throwOnError: true,
      });
      // The server activates only newly created rows; an existing row is selected explicitly.
      if (becomesDefault && connection)
        await selectChatVoiceProvider({
          body: { function: fn, provider: provider.provider },
          headers: sameOriginMutationHeaders,
          throwOnError: true,
        });
      setKey("");
      await onSaved();
      onOpenChange(false);
    } catch (failed) {
      setError(voiceProblem(failed));
    } finally {
      setPending(undefined);
    }
  }

  async function test() {
    setPending("test");
    setError(undefined);
    setTested(false);
    try {
      await testChatVoiceConnection({
        path: { provider: provider.provider },
        headers: sameOriginMutationHeaders,
        throwOnError: true,
      });
      setTested(true);
    } catch (failed) {
      setError(voiceProblem(failed));
    } finally {
      setPending(undefined);
    }
  }

  const azure = provider.provider === "AZURE";
  return (
    <Dialog open={open} onOpenChange={changeOpen}>
      <DialogContent
        className="sm:max-w-2xl"
        onEscapeKeyDown={(event) => {
          if (pending) event.preventDefault();
        }}
        onInteractOutside={(event) => {
          if (pending) event.preventDefault();
        }}
      >
        <form onSubmit={(event) => void save(event)} className="grid gap-5">
          <DialogHeader>
            <DialogTitle>
              {connection ? ui("Cấu hình {{name}}", { name }) : ui("Kết nối {{name}}", { name })}
            </DialogTitle>
            <DialogDescription>
              {fn === "STT"
                ? ui("Dùng để nhận dạng giọng nói trong Chat và Tìm kiếm.")
                : ui("Dùng để đọc câu trả lời thành tiếng.")}
            </DialogDescription>
          </DialogHeader>
          <fieldset disabled={!!pending} className="grid gap-4">
            <section className="grid gap-4 rounded-xl border border-border-subtle bg-surface-base p-4">
              <div>
                <h3 className="font-main-ui-action text-content-primary">
                  {ui("Thông tin kết nối")}
                </h3>
                <p className="mt-0.5 font-secondary-body text-content-muted">
                  {ui("MemoryOS xác minh nhà cung cấp trước khi lưu cấu hình và mã hóa khóa API.")}
                </p>
              </div>
              <div className="grid gap-1.5">
                <Label htmlFor={`${id}-endpoint`}>
                  {azure
                    ? ui("Địa chỉ tài nguyên Speech")
                    : provider.requiresEndpoint
                      ? ui("Địa chỉ máy chủ")
                      : ui("Địa chỉ API")}
                </Label>
                <Input
                  id={`${id}-endpoint`}
                  value={endpoint}
                  required={provider.requiresEndpoint}
                  maxLength={2048}
                  inputMode="url"
                  placeholder={
                    provider.defaultEndpoint ||
                    (azure
                      ? "https://your-resource.cognitiveservices.azure.com"
                      : "http://speaches.internal:8000/v1")
                  }
                  aria-describedby={`${id}-endpoint-hint`}
                  onChange={(event) => setEndpoint(event.target.value)}
                />
                <p id={`${id}-endpoint-hint`} className="text-xs text-content-muted">
                  {azure
                    ? ui("Endpoint của tài nguyên Azure AI Speech, trong mục Keys and Endpoint.")
                    : provider.requiresEndpoint
                      ? ui("Địa chỉ gốc của API tương thích OpenAI, thường kết thúc bằng /v1.")
                      : ui("Để trống để dùng địa chỉ mặc định của nhà cung cấp.")}
                </p>
              </div>
              <div className="grid gap-1.5">
                <Label htmlFor={`${id}-key`}>
                  {provider.requiresKey ? ui("Khóa API") : ui("Khóa API (không bắt buộc)")}
                </Label>
                <Input
                  id={`${id}-key`}
                  type="password"
                  autoComplete="new-password"
                  value={key}
                  maxLength={8192}
                  required={provider.requiresKey && !connection?.credentialConfigured}
                  disabled={removeKey}
                  placeholder={
                    connection?.credentialConfigured
                      ? ui("Đã lưu khóa; để trống để giữ nguyên")
                      : ""
                  }
                  aria-describedby={`${id}-key-hint`}
                  onChange={(event) => setKey(event.target.value)}
                />
                <p id={`${id}-key-hint`} className="text-xs text-content-muted">
                  {ui("Khóa được mã hóa khi lưu và không bao giờ được gửi lại trình duyệt.")}
                </p>
                {!provider.requiresKey && connection?.credentialConfigured && (
                  <div className="flex items-center gap-2 pt-1">
                    <Checkbox
                      id={`${id}-remove-key`}
                      checked={removeKey}
                      onCheckedChange={(checked) => {
                        setRemoveKey(checked === true);
                        if (checked === true) setKey("");
                      }}
                    />
                    <Label htmlFor={`${id}-remove-key`} className="font-normal">
                      {ui("Xóa khóa đã lưu")}
                    </Label>
                  </div>
                )}
              </div>
            </section>
            <section className="grid gap-4 rounded-xl border border-border-subtle bg-surface-base p-4">
              <div>
                <h3 className="font-main-ui-action text-content-primary">
                  {ui("Mô hình và giọng")}
                </h3>
                <p className="mt-0.5 font-secondary-body text-content-muted">
                  {fn === "STT"
                    ? ui("Chọn mô hình dùng cho bản ghi cuối cùng và đường dự phòng.")
                    : ui("Chọn mô hình và giọng mặc định cho câu trả lời được đọc thành tiếng.")}
                </p>
              </div>
              <ChoiceField
                id={`${id}-model`}
                label={fn === "STT" ? ui("Mô hình nhận dạng") : ui("Mô hình giọng nói")}
                value={model}
                options={fn === "STT" ? provider.sttModels : provider.ttsModels}
                hint={ui("Nhập đúng tên mô hình mà máy chủ cung cấp.")}
                onChange={setModel}
              />
              {fn === "TTS" && (
                <ChoiceField
                  id={`${id}-voice`}
                  label={ui("Giọng đọc")}
                  value={voice}
                  options={provider.voices}
                  hint={
                    provider.provider === "ELEVENLABS"
                      ? ui("Voice ID trong thư viện giọng của ElevenLabs.")
                      : ui("Nhập đúng tên giọng mà máy chủ cung cấp.")
                  }
                  onChange={setVoice}
                />
              )}
            </section>
          </fieldset>
          {tested && (
            <p
              role="status"
              className="flex items-center gap-1.5 text-sm text-status-success-content"
            >
              <CheckCircle2 className="size-4" aria-hidden="true" />
              {ui("Nhà cung cấp đã chấp nhận cấu hình đã lưu.")}
            </p>
          )}
          {error && (
            <p
              role="alert"
              className="rounded-lg bg-status-danger-surface px-4 py-3 text-sm text-status-danger-content"
            >
              {problemMessage(error)}
            </p>
          )}
          <DialogFooter>
            {connection && (
              <Button
                prominence="internal"
                className="sm:mr-auto"
                pending={pending === "test"}
                disabled={!!pending}
                onClick={() => void test()}
              >
                {ui("Kiểm tra kết nối")}
              </Button>
            )}
            <DialogClose asChild>
              <Button prominence="secondary" disabled={!!pending}>
                {ui("Hủy")}
              </Button>
            </DialogClose>
            <Button type="submit" pending={pending === "save"} disabled={!!pending}>
              {pending === "save"
                ? ui("Đang kiểm tra khóa…")
                : connection
                  ? ui("Lưu")
                  : ui("Kết nối")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/** Known identifiers as a list; OpenAI-compatible models and ElevenLabs voice IDs are typed. */
function ChoiceField({
  id,
  label,
  value,
  options,
  hint,
  onChange,
}: {
  id: string;
  label: string;
  value: string;
  options: string[];
  hint: string;
  onChange: (value: string) => void;
}) {
  const choices =
    options.length > 0 && value && !options.includes(value) ? [value, ...options] : options;
  return (
    <div className="grid gap-1.5">
      <Label htmlFor={id}>{label}</Label>
      {choices.length > 0 ? (
        <Select id={id} value={value} required onChange={(event) => onChange(event.target.value)}>
          {choices.map((choice) => (
            <option key={choice} value={choice}>
              {choice}
            </option>
          ))}
        </Select>
      ) : (
        <>
          <Input
            id={id}
            value={value}
            required
            maxLength={200}
            aria-describedby={`${id}-hint`}
            onChange={(event) => onChange(event.target.value)}
          />
          <p id={`${id}-hint`} className="text-xs text-content-muted">
            {hint}
          </p>
        </>
      )}
    </div>
  );
}

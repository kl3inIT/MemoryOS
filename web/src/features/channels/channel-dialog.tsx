import {
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type RefObject,
} from "react";
import { useQueryClient } from "@tanstack/react-query";
import { revalidateLogic, useStore } from "@tanstack/react-form";
import { BotMessageSquare, Eye, EyeOff } from "lucide-react";
import { z } from "zod";
import { useAppForm } from "@/components/form/app-form";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Field,
  FieldContent,
  FieldDescription,
  FieldGroup,
  FieldLabel,
  FieldTitle,
} from "@/components/ui/field";
import {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupInput,
} from "@/components/ui/input-group";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { isUnauthenticated } from "@/lib/api";
import { getCurrentIdentityQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import { createLarkChannel, createZaloChannel, replaceChannelSecret } from "@/lib/hey-api/sdk.gen";
import type { ChannelResponse } from "@/lib/hey-api/types.gen";
import { zCreateChannelRequest } from "@/lib/hey-api/zod.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { ChannelMark } from "./channel-actions";
import { channelError } from "./channel-errors";

const APP_ID = /^cli_[0-9a-z]{8,40}$/;
const MAX_SECRET_LENGTH = 256;
const REGIONS = ["LARK", "FEISHU"] as const;
const LANGUAGES = ["vi", "en"] as const;
const PROVIDERS = ["LARK", "ZALO_BOT"] as const;
const TRANSPORTS = ["WEBHOOK", "POLLING"] as const;
const MAX_TOKEN_LENGTH = 256;

type Provider = ChannelResponse["provider"];

type ChannelDialogProps = {
  open: boolean;
  /** Counts openings, so every opening starts from an empty form. */
  session: number;
  /** The bot whose App Secret is replaced, or null to add a bot. */
  replacing: ChannelResponse | null;
  triggerRef: RefObject<HTMLElement | null>;
  onOpenChange: (open: boolean) => void;
  onSaved: () => Promise<void>;
};

/** Adds a Lark or Zalo bot, or replaces its secret, once the platform accepts it. */
export function ChannelDialog(props: ChannelDialogProps) {
  const ui = useAppTranslation();
  const [busy, setBusy] = useState(false);
  const { open, replacing, triggerRef, onOpenChange } = props;
  const zalo = replacing?.provider === "ZALO_BOT";
  return (
    <Dialog open={open} onOpenChange={(next) => !busy && onOpenChange(next)}>
      <DialogContent
        className="sm:max-w-4xl"
        onCloseAutoFocus={(event) => {
          event.preventDefault();
          triggerRef.current?.focus();
        }}
      >
        <DialogHeader>
          <DialogTitle>
            {replacing
              ? zalo
                ? ui("Thay token của bot")
                : ui("Thay App Secret của bot")
              : ui("Thêm bot")}
          </DialogTitle>
          <DialogDescription className="sr-only">
            {ui("Nền tảng kiểm tra bot trước khi lưu; bot bị từ chối sẽ không được lưu.")}
          </DialogDescription>
        </DialogHeader>
        <ProviderForms key={props.session} {...props} onBusyChange={setBusy} />
      </DialogContent>
    </Dialog>
  );
}

type FormProps = ChannelDialogProps & { onBusyChange: (busy: boolean) => void };

/** A new bot starts with its platform; a bot whose secret is replaced keeps its own. */
function ProviderForms(props: FormProps) {
  const ui = useAppTranslation();
  const id = useId();
  const [provider, setProvider] = useState<Provider>(props.replacing?.provider ?? "LARK");
  const [busy, setBusy] = useState(false);
  const { onBusyChange } = props;
  const busyChange = useCallback(
    (next: boolean) => {
      setBusy(next);
      onBusyChange(next);
    },
    [onBusyChange],
  );
  return (
    <div className="flex flex-col gap-5">
      {props.replacing ? null : (
        <Field>
          <FieldTitle>{ui("Nền tảng")}</FieldTitle>
          <RadioGroup
            value={provider}
            disabled={busy}
            onValueChange={(value) => setProvider(value === "ZALO_BOT" ? "ZALO_BOT" : "LARK")}
          >
            <div className="grid grid-cols-2 gap-3">
              {PROVIDERS.map((value) => (
                <FieldLabel key={value} htmlFor={`${id}-${value}`}>
                  <Field orientation="horizontal" className="items-center">
                    <RadioGroupItem id={`${id}-${value}`} value={value} />
                    <ChannelMark provider={value} />
                    <FieldTitle>{value === "ZALO_BOT" ? ui("Zalo") : ui("Lark")}</FieldTitle>
                  </Field>
                </FieldLabel>
              ))}
            </div>
          </RadioGroup>
        </Field>
      )}
      {provider === "ZALO_BOT" ? (
        <ZaloForm {...props} onBusyChange={busyChange} />
      ) : (
        <ChannelForm {...props} onBusyChange={busyChange} />
      )}
    </div>
  );
}

function ChannelForm({ replacing, onSaved, onBusyChange }: FormProps) {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const id = useId();
  const queryClient = useQueryClient();
  const notify = useActionNotifications();
  // The App Secret lives only in a ref: it never enters React Query, state or browser storage.
  const secretInput = useRef<HTMLInputElement>(null);
  const secret = useRef("");
  const active = useRef(true);
  const [secretReady, setSecretReady] = useState(false);
  const [revealSecret, setRevealSecret] = useState(false);
  const [region, setRegion] = useState<(typeof REGIONS)[number]>(replacing?.region ?? "LARK");
  const [language, setLanguage] = useState<(typeof LANGUAGES)[number]>("vi");
  const [error, setError] = useState<string | null>(null);
  const form = useAppForm({
    defaultValues: { botName: replacing?.name ?? "", appId: replacing?.appId ?? "" },
    validationLogic: revalidateLogic(),
    validators: {
      onDynamic: z.object({
        botName: zCreateChannelRequest.shape.name,
        appId: z.string().trim().regex(APP_ID, ui("Nhập App ID của app Lark, bắt đầu bằng cli_.")),
      }),
    },
    onSubmit: ({ value }) => save(value),
  });
  const saving = useStore(form.store, (state) => state.isSubmitting);

  useLayoutEffect(() => {
    active.current = true;
    const input = secretInput.current;
    return () => {
      active.current = false;
      secret.current = "";
      if (input) input.value = "";
    };
  }, []);

  useEffect(() => {
    onBusyChange(saving);
    return () => onBusyChange(false);
  }, [saving, onBusyChange]);

  function takeSecret() {
    const value = secret.current.trim();
    secret.current = "";
    if (secretInput.current) secretInput.current.value = "";
    setSecretReady(false);
    return value;
  }

  async function save(value: { botName: string; appId: string }) {
    // A direct call, not a mutation: the App Secret is read here and never enters React Query.
    const appSecret = takeSecret();
    if (!appSecret) return;
    setError(null);
    try {
      if (replacing) {
        await replaceChannelSecret({
          path: { channelId: replacing.id },
          headers: { "If-Match": `"${replacing.revision}"` },
          body: { appSecret },
        });
      } else {
        await createLarkChannel({
          body: {
            name: value.botName.trim(),
            region,
            appId: value.appId.trim(),
            appSecret,
            noticeLanguage: language,
          },
        });
      }
      if (!active.current) return;
      notify({
        title: replacing ? "Đã thay App Secret" : "Đã thêm bot",
        description: replacing
          ? appText("Lark đã chấp nhận App Secret mới của {{v1}}.", { v1: replacing.name })
          : appText("{{v1}} đang tắt. Bật bot để bắt đầu trả lời.", { v1: value.botName.trim() }),
        tone: "success",
      });
      await onSaved();
    } catch (cause) {
      if (!active.current) return;
      if (isUnauthenticated(cause))
        void queryClient.resetQueries({ queryKey: getCurrentIdentityQueryKey(), exact: true });
      setError(channelError(cause) ?? problem(presentProblem(cause, "mutation").message));
    }
  }

  return (
    <form
      className="flex flex-col gap-5"
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        void form.handleSubmit();
      }}
    >
      <div className="grid min-w-0 gap-5 lg:grid-cols-5">
        <aside className="h-fit rounded-xl border border-border-subtle bg-surface-base p-4 lg:col-span-2">
          <div className="mb-3 flex items-center gap-2">
            <span className="grid size-8 place-items-center text-content-muted">
              <BotMessageSquare className="size-4" aria-hidden="true" />
            </span>
            <h3 className="font-main-ui-action text-content-primary">
              {ui("Chuẩn bị app trên Lark")}
            </h3>
          </div>
          <ChannelAppGuide />
        </aside>
        <FieldGroup className="min-w-0 lg:col-span-3">
          <form.AppField name="botName">
            {(field) => (
              <field.TextField
                label={ui("Tên bot")}
                maxLength={100}
                disabled={saving || Boolean(replacing)}
                readOnly={Boolean(replacing)}
                placeholder={ui("ví dụ: Trợ lý MemoryOS")}
                autoComplete="off"
              />
            )}
          </form.AppField>
          <Field>
            <FieldTitle>{ui("Khu vực")}</FieldTitle>
            <RadioGroup
              value={region}
              disabled={saving || Boolean(replacing)}
              onValueChange={(value) => setRegion(value === "FEISHU" ? "FEISHU" : "LARK")}
            >
              <div className="flex flex-wrap gap-3">
                {REGIONS.map((value) => (
                  <FieldLabel key={value} htmlFor={`${id}-${value}`} className="min-w-40 flex-1">
                    <Field orientation="horizontal">
                      <RadioGroupItem id={`${id}-${value}`} value={value} />
                      <FieldContent>
                        <FieldTitle>{value === "LARK" ? ui("Lark") : ui("Feishu")}</FieldTitle>
                        <FieldDescription>
                          {value === "LARK" ? ui("larksuite.com") : ui("feishu.cn")}
                        </FieldDescription>
                      </FieldContent>
                    </Field>
                  </FieldLabel>
                ))}
              </div>
            </RadioGroup>
          </Field>
          <form.AppField name="appId">
            {(field) => (
              <field.TextField
                label={ui("App ID")}
                disabled={saving || Boolean(replacing)}
                readOnly={Boolean(replacing)}
                placeholder={ui("cli_a1b2c3d4e5f6a7b8")}
                autoComplete="off"
                spellCheck={false}
              />
            )}
          </form.AppField>
          <Field>
            <FieldLabel htmlFor={`${id}-secret`}>{ui("App Secret")}</FieldLabel>
            <InputGroup>
              <InputGroupInput
                ref={secretInput}
                id={`${id}-secret`}
                type={revealSecret ? "text" : "password"}
                disabled={saving}
                maxLength={MAX_SECRET_LENGTH}
                autoComplete="off"
                autoCapitalize="off"
                spellCheck={false}
                onChange={(event) => {
                  secret.current = event.target.value;
                  setSecretReady(Boolean(event.target.value.trim()));
                }}
              />
              <InputGroupAddon align="inline-end">
                <InputGroupButton
                  size="icon-xs"
                  aria-label={revealSecret ? ui("Ẩn App Secret") : ui("Hiện App Secret")}
                  disabled={saving}
                  onClick={() => setRevealSecret(!revealSecret)}
                >
                  {revealSecret ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}
                </InputGroupButton>
              </InputGroupAddon>
            </InputGroup>
          </Field>
          {replacing ? null : (
            <Field>
              <FieldTitle>{ui("Ngôn ngữ thông báo của bot")}</FieldTitle>
              <RadioGroup
                value={language}
                disabled={saving}
                onValueChange={(value) => setLanguage(value === "en" ? "en" : "vi")}
              >
                <div className="flex flex-wrap gap-3">
                  {LANGUAGES.map((value) => (
                    <FieldLabel key={value} htmlFor={`${id}-${value}`} className="min-w-40 flex-1">
                      <Field orientation="horizontal">
                        <RadioGroupItem id={`${id}-${value}`} value={value} />
                        <FieldTitle>{value === "vi" ? ui("Tiếng Việt") : ui("English")}</FieldTitle>
                      </Field>
                    </FieldLabel>
                  ))}
                </div>
              </RadioGroup>
            </Field>
          )}
        </FieldGroup>
      </div>
      {error ? (
        <Alert variant="destructive">
          <AlertDescription>{ui(error)}</AlertDescription>
        </Alert>
      ) : null}
      <DialogFooter>
        <DialogClose asChild>
          <Button prominence="secondary" disabled={saving}>
            {ui("Hủy")}
          </Button>
        </DialogClose>
        <Button type="submit" pending={saving} disabled={saving || !secretReady}>
          {ui("Kiểm tra và lưu")}
        </Button>
      </DialogFooter>
    </form>
  );
}

/** Adds a Zalo bot by its token, or replaces the token, once Zalo accepts it. */
function ZaloForm({ replacing, onSaved, onBusyChange }: FormProps) {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const id = useId();
  const queryClient = useQueryClient();
  const notify = useActionNotifications();
  // The token lives only in a ref: it never enters React Query, state or browser storage.
  const tokenInput = useRef<HTMLInputElement>(null);
  const token = useRef("");
  const active = useRef(true);
  const [tokenReady, setTokenReady] = useState(false);
  const [revealToken, setRevealToken] = useState(false);
  const [transport, setTransport] = useState<(typeof TRANSPORTS)[number]>("WEBHOOK");
  const [language, setLanguage] = useState<(typeof LANGUAGES)[number]>("vi");
  const [error, setError] = useState<string | null>(null);
  const form = useAppForm({
    defaultValues: { botName: replacing?.name ?? "" },
    validationLogic: revalidateLogic(),
    validators: {
      onDynamic: z.object({ botName: zCreateChannelRequest.shape.name }),
    },
    onSubmit: ({ value }) => save(value),
  });
  const saving = useStore(form.store, (state) => state.isSubmitting);

  useLayoutEffect(() => {
    active.current = true;
    const input = tokenInput.current;
    return () => {
      active.current = false;
      token.current = "";
      if (input) input.value = "";
    };
  }, []);

  useEffect(() => {
    onBusyChange(saving);
    return () => onBusyChange(false);
  }, [saving, onBusyChange]);

  function takeToken() {
    const value = token.current.trim();
    token.current = "";
    if (tokenInput.current) tokenInput.current.value = "";
    setTokenReady(false);
    return value;
  }

  async function save(value: { botName: string }) {
    // A direct call, not a mutation: the token is read here and never enters React Query.
    const botToken = takeToken();
    if (!botToken) return;
    setError(null);
    try {
      if (replacing) {
        await replaceChannelSecret({
          path: { channelId: replacing.id },
          headers: { "If-Match": `"${replacing.revision}"` },
          body: { appSecret: botToken },
        });
      } else {
        await createZaloChannel({
          body: {
            name: value.botName.trim(),
            token: botToken,
            noticeLanguage: language,
            transport,
          },
        });
      }
      if (!active.current) return;
      notify({
        title: replacing ? "Đã thay token" : "Đã thêm bot",
        description: replacing
          ? appText("Zalo đã chấp nhận token mới của {{v1}}.", { v1: replacing.name })
          : appText("{{v1}} đang tắt. Bật bot để bắt đầu trả lời.", { v1: value.botName.trim() }),
        tone: "success",
      });
      await onSaved();
    } catch (cause) {
      if (!active.current) return;
      if (isUnauthenticated(cause))
        void queryClient.resetQueries({ queryKey: getCurrentIdentityQueryKey(), exact: true });
      setError(
        channelError(cause, "ZALO_BOT") ?? problem(presentProblem(cause, "mutation").message),
      );
    }
  }

  return (
    <form
      className="flex flex-col gap-5"
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        void form.handleSubmit();
      }}
    >
      <div className="grid min-w-0 gap-5 lg:grid-cols-5">
        <aside className="h-fit rounded-xl border border-border-subtle bg-surface-base p-4 lg:col-span-2">
          <div className="mb-3 flex items-center gap-2">
            <span className="grid size-8 place-items-center text-content-muted">
              <BotMessageSquare className="size-4" aria-hidden="true" />
            </span>
            <h3 className="font-main-ui-action text-content-primary">
              {ui("Chuẩn bị bot trên Zalo")}
            </h3>
          </div>
          <ZaloBotGuide />
        </aside>
        <FieldGroup className="min-w-0 lg:col-span-3">
          <form.AppField name="botName">
            {(field) => (
              <field.TextField
                label={ui("Tên bot")}
                maxLength={100}
                disabled={saving || Boolean(replacing)}
                readOnly={Boolean(replacing)}
                placeholder={ui("ví dụ: Trợ lý MemoryOS")}
                autoComplete="off"
              />
            )}
          </form.AppField>
          <Field>
            <FieldLabel htmlFor={`${id}-token`}>{ui("Token")}</FieldLabel>
            <InputGroup>
              <InputGroupInput
                ref={tokenInput}
                id={`${id}-token`}
                type={revealToken ? "text" : "password"}
                disabled={saving}
                maxLength={MAX_TOKEN_LENGTH}
                placeholder={ui("1234567890:abc-xyz")}
                autoComplete="off"
                autoCapitalize="off"
                spellCheck={false}
                onChange={(event) => {
                  token.current = event.target.value;
                  setTokenReady(Boolean(event.target.value.trim()));
                }}
              />
              <InputGroupAddon align="inline-end">
                <InputGroupButton
                  size="icon-xs"
                  aria-label={revealToken ? ui("Ẩn token") : ui("Hiện token")}
                  disabled={saving}
                  onClick={() => setRevealToken(!revealToken)}
                >
                  {revealToken ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}
                </InputGroupButton>
              </InputGroupAddon>
            </InputGroup>
          </Field>
          {replacing ? null : (
            <>
              <Field>
                <FieldTitle>{ui("Cách nhận tin")}</FieldTitle>
                <RadioGroup
                  value={transport}
                  disabled={saving}
                  onValueChange={(value) =>
                    setTransport(value === "POLLING" ? "POLLING" : "WEBHOOK")
                  }
                >
                  <div className="flex flex-wrap gap-3">
                    {TRANSPORTS.map((value) => (
                      <FieldLabel
                        key={value}
                        htmlFor={`${id}-${value}`}
                        className="min-w-40 flex-1"
                      >
                        <Field orientation="horizontal">
                          <RadioGroupItem id={`${id}-${value}`} value={value} />
                          <FieldTitle>
                            {value === "WEBHOOK" ? ui("Webhook") : ui("Polling")}
                          </FieldTitle>
                        </Field>
                      </FieldLabel>
                    ))}
                  </div>
                </RadioGroup>
              </Field>
              <Field>
                <FieldTitle>{ui("Ngôn ngữ thông báo của bot")}</FieldTitle>
                <RadioGroup
                  value={language}
                  disabled={saving}
                  onValueChange={(value) => setLanguage(value === "en" ? "en" : "vi")}
                >
                  <div className="flex flex-wrap gap-3">
                    {LANGUAGES.map((value) => (
                      <FieldLabel
                        key={value}
                        htmlFor={`${id}-${value}`}
                        className="min-w-40 flex-1"
                      >
                        <Field orientation="horizontal">
                          <RadioGroupItem id={`${id}-${value}`} value={value} />
                          <FieldTitle>
                            {value === "vi" ? ui("Tiếng Việt") : ui("English")}
                          </FieldTitle>
                        </Field>
                      </FieldLabel>
                    ))}
                  </div>
                </RadioGroup>
              </Field>
            </>
          )}
        </FieldGroup>
      </div>
      {error ? (
        <Alert variant="destructive">
          <AlertDescription>{ui(error)}</AlertDescription>
        </Alert>
      ) : null}
      <DialogFooter>
        <DialogClose asChild>
          <Button prominence="secondary" disabled={saving}>
            {ui("Hủy")}
          </Button>
        </DialogClose>
        <Button type="submit" pending={saving} disabled={saving || !tokenReady}>
          {ui("Kiểm tra và lưu")}
        </Button>
      </DialogFooter>
    </form>
  );
}

/** Where a Zalo bot and its token come from, in the order the Zalo app shows them. */
function ZaloBotGuide() {
  const ui = useAppTranslation();
  return (
    <ol className="flex list-decimal flex-col gap-2 pl-5 font-secondary-body text-content-secondary">
      <li>{ui("Trong app Zalo, tìm Zalo Bot Manager và mở Zalo Bot Creator.")}</li>
      <li>{ui("Tạo bot với tên bắt đầu bằng Bot.")}</li>
      <li>{ui("Sao chép token mà Zalo gửi trong tin nhắn.")}</li>
    </ol>
  );
}

const EVENT = "im.message.receive_v1";

const SCOPES = [
  "im:message.p2p_msg:readonly",
  "im:message.group_at_msg:readonly",
  "im:message:send_as_bot",
  "im:message.reactions:write_only",
  "cardkit:card:write",
  "contact:user.email:readonly",
  "im:chat:readonly",
] as const;

/** The console steps a bot's app needs, in the order Lark's console shows them. */
function ChannelAppGuide() {
  const ui = useAppTranslation();
  return (
    <ol className="flex list-decimal flex-col gap-2 pl-5 font-secondary-body text-content-secondary">
      <li>{ui("Tạo custom app trong Lark Developer Console và bật tính năng Bot.")}</li>
      <li>
        {ui("Thêm các quyền:")}
        <ul className="mt-1 flex flex-col gap-0.5">
          {SCOPES.map((scope) => (
            <li key={scope}>
              <code className="font-mono text-xs break-all text-content-primary">{scope}</code>
            </li>
          ))}
        </ul>
      </li>
      <li>
        {ui("Trong Events & callbacks, chọn nhận sự kiện qua long connection và thêm sự kiện")}{" "}
        <code className="font-mono text-xs text-content-primary">{EVENT}</code>.
      </li>
      <li>{ui("Tạo phiên bản và phát hành app, rồi sao chép App ID và App Secret.")}</li>
    </ol>
  );
}

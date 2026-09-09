import { CheckCircle2, CircleAlert, Info, X } from "lucide-react";
import { Toast } from "radix-ui";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { IconButton } from "@/components/ui/icon-button";

interface ActionNotification {
  title: string;
  description?: string;
  tone: "success" | "info" | "error";
  surviveNavigation?: boolean;
}

const NotificationContext = createContext<((notice: ActionNotification) => void) | null>(null);

export function useActionNotifications() {
  const notify = useContext(NotificationContext);
  if (!notify) throw new Error("Action notifications require a provider");
  return notify;
}

export function ActionNotifications({ children, scope }: { children: ReactNode; scope?: string }) {
  const nextId = useRef(0);
  const previousScope = useRef(scope);
  const [notices, setNotices] = useState<(ActionNotification & { id: number })[]>([]);
  const notify = useCallback((notice: ActionNotification) => {
    const id = nextId.current++;
    setNotices((current) => [...current, { ...notice, id }]);
  }, []);
  const dismiss = useCallback((id: number) => {
    setNotices((current) => current.filter((notice) => notice.id !== id));
  }, []);

  useLayoutEffect(() => {
    if (previousScope.current === scope) return;
    previousScope.current = scope;
    setNotices((current) => current.filter((notice) => notice.surviveNavigation));
  }, [scope]);

  return (
    <NotificationContext.Provider value={notify}>
      <Toast.Provider duration={Infinity} swipeDirection="right">
        {children}
        {notices.map((notice) => (
          <ActionNotificationToast key={notice.id} notice={notice} dismiss={dismiss} />
        ))}
        <Toast.Viewport
          label="Action notifications ({hotkey})"
          className="pointer-events-none fixed right-0 top-0 z-50 flex max-h-[100dvh] w-full max-w-sm flex-col gap-2 overflow-y-auto p-4 outline-none"
        />
      </Toast.Provider>
    </NotificationContext.Provider>
  );
}

function ActionNotificationToast({
  notice,
  dismiss,
}: {
  notice: ActionNotification & { id: number };
  dismiss: (id: number) => void;
}) {
  useEffect(() => {
    // Expiry must continue when Orca's visible browser pane loses focus.
    const timer = window.setTimeout(() => dismiss(notice.id), 5_000);
    return () => window.clearTimeout(timer);
  }, [dismiss, notice.id]);

  const Icon =
    notice.tone === "success" ? CheckCircle2 : notice.tone === "error" ? CircleAlert : Info;
  const color =
    notice.tone === "success"
      ? "text-status-success-content"
      : notice.tone === "error"
        ? "text-status-danger-content"
        : "text-status-info-content";

  return (
    <Toast.Root
      aria-label={notice.title}
      onOpenChange={(open) => {
        if (!open) dismiss(notice.id);
      }}
      className="pointer-events-auto flex items-start gap-3 rounded-lg border border-border-default bg-surface-overlay p-4 shadow-lg"
    >
      <Icon className={`mt-0.5 size-5 shrink-0 ${color}`} aria-hidden="true" />
      <div className="min-w-0 flex-1 space-y-1">
        <Toast.Title className="text-sm font-medium text-content-primary">
          {notice.title}
        </Toast.Title>
        {notice.description ? (
          <Toast.Description className="break-words text-sm text-content-secondary">
            {notice.description}
          </Toast.Description>
        ) : null}
      </div>
      <Toast.Close asChild>
        <IconButton
          aria-label={`Dismiss ${notice.title}`}
          size="sm"
          className="shrink-0 text-content-muted"
        >
          <X aria-hidden="true" />
        </IconButton>
      </Toast.Close>
    </Toast.Root>
  );
}

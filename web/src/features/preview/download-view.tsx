import { Button } from "@/components/ui/button";
import { useAppTranslation } from "@/i18n/use-app-translation";

/**
 * The fallback for a file this app does not render inline: the reason, and the download that does hold the
 * whole file. The caller supplies the authorized download route, which differs per owner.
 */
export function DownloadView({
  href,
  filename,
  message,
}: {
  href: string;
  filename: string;
  message: string;
}) {
  const ui = useAppTranslation();
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-4 p-6 text-center">
      <p className="text-sm text-content-secondary">{message}</p>
      <Button asChild size="sm" prominence="secondary">
        <a href={href} download={filename}>
          {ui("Tải xuống")}
        </a>
      </Button>
    </div>
  );
}

import { useEffect, useState } from "react";

/**
 * An object URL whose lifetime is the blob's; the viewer never keeps one past the blob it was made for.
 * `useFileSrc` in `@/hooks/use-attachment-src` does the same for a `File` but is vendored from
 * assistant-ui, so it is not widened to `Blob` here.
 */
export function useObjectUrl(blob: Blob | undefined): string | undefined {
  const [entry, setEntry] = useState<{ blob: Blob; url: string }>();
  useEffect(() => {
    if (!blob) return undefined;
    const url = URL.createObjectURL(blob);
    // The object URL is a browser resource whose lifetime is the effect's.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setEntry({ blob, url });
    return () => URL.revokeObjectURL(url);
  }, [blob]);
  return entry && entry.blob === blob ? entry.url : undefined;
}

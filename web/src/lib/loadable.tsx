import { useEffect, useState, type ComponentType, type ReactNode } from "react";

/**
 * A component whose code is loaded on demand and shown the moment that code is in memory.
 *
 * `React.lazy` suspends once even when its chunk has already arrived, and React then holds the content back
 * until 300 ms after the fallback appeared. Two readers nested that way kept a document waiting more than half
 * a second with all of its code loaded. This loads the same chunk but never suspends: it renders at once when
 * `preload` has finished, and `fallback` until it has. A chunk that cannot be loaded is thrown to the error
 * boundary, as a lazy component's is.
 */
export function loadable<Props extends object>(load: () => Promise<ComponentType<Props>>) {
  let loaded: ComponentType<Props> | undefined;
  let loading: Promise<ComponentType<Props>> | undefined;

  /** Starts loading the code, once; a failed load is tried again by the next call. */
  const preload = () =>
    (loading ??= load().then(
      (component) => (loaded = component),
      (failure: unknown) => {
        loading = undefined;
        throw failure;
      },
    ));

  function Loadable({ fallback, ...props }: Props & { fallback: ReactNode }) {
    const [state, setState] = useState<{ Component?: ComponentType<Props>; failure?: unknown }>(
      () => ({ Component: loaded }),
    );
    const waiting = !state.Component && state.failure === undefined;
    useEffect(() => {
      if (!waiting) return undefined;
      let mounted = true;
      preload().then(
        (Component) => {
          if (mounted) setState({ Component });
        },
        (failure: unknown) => {
          if (mounted) setState({ failure });
        },
      );
      return () => {
        mounted = false;
      };
    }, [waiting]);
    if (state.failure !== undefined) throw state.failure;
    return state.Component ? <state.Component {...(props as unknown as Props)} /> : fallback;
  }

  return { Loadable, preload };
}

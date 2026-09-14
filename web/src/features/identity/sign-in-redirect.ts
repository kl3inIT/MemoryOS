// A signed-out browser goes straight to the backend OAuth2 flow. The attempt time is kept for the
// tab so that a completed login which still cannot establish a session (for example a rejected
// session cookie) shows the manual sign-in gate instead of bouncing through the provider forever.

export const SIGN_IN_PATH = "/oauth2/authorization/memoryos";

const ATTEMPT_KEY = "memoryos.signInRedirectAt";
const RETRY_WINDOW_MS = 30_000;

export function hasRecentSignInRedirect() {
  try {
    const attemptedAt = Number(window.sessionStorage.getItem(ATTEMPT_KEY));
    return attemptedAt > 0 && Date.now() - attemptedAt < RETRY_WINDOW_MS;
  } catch {
    return false;
  }
}

export function redirectToSignIn() {
  try {
    window.sessionStorage.setItem(ATTEMPT_KEY, String(Date.now()));
  } catch {
    // Storage unavailable: redirect without the loop guard.
  }
  window.location.assign(SIGN_IN_PATH);
}

export function clearSignInRedirect() {
  try {
    window.sessionStorage.removeItem(ATTEMPT_KEY);
  } catch {
    // Storage unavailable: nothing was recorded.
  }
}

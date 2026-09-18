// A signed-out browser goes straight to the backend OAuth2 flow. The provider session usually
// still exists, so a transient identity failure simply bounces back into the application.

export const SIGN_IN_PATH = "/oauth2/authorization/memoryos";

export function redirectToSignIn() {
  window.location.assign(SIGN_IN_PATH);
}

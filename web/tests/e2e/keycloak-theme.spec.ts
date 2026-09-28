import { readFileSync } from "node:fs";
import { expect, test } from "@playwright/test";

/**
 * The login theme against Keycloak's own markup: a centred card under the MemoryOS wordmark, with
 * the Tenant's identity provider offered below the form.
 *
 * The page is rendered from the stylesheet this repository ships and the markup keycloak.v2
 * produces, so the test exercises the theme rather than a running server. What it holds is what
 * the theme promises — the card is centred, the header carries the MemoryOS wordmark, and the
 * Tenant's provider button carries the Tenant's own. How the card looks inside is the designer's, and is not frozen here. The page
 * does scroll a little on a 900-pixel viewport, because the centring rule adds its padding to a
 * full-viewport minimum height; that is the design's to decide, so no test holds it either way.
 */
const themeCss = readFileSync(
  new URL(
    "../../../infrastructure/keycloak/themes/memoryos/login/resources/css/memoryos.css",
    import.meta.url,
  ),
  "utf8",
);

function keycloakMarkup(options: { pageId?: string; title?: string } = {}) {
  const pageId = options.pageId ?? "login-login";
  const title = options.title ?? "Continue to MemoryOS";

  return `<!doctype html>
    <html class="login-pf" lang="en">
      <head><meta name="viewport" content="width=device-width, initial-scale=1"><style>${themeCss}</style></head>
      <body id="keycloak-bg" data-page-id="${pageId}">
        <div class="pf-v5-c-login">
          <div class="pf-v5-c-login__container">
            <header id="kc-header" class="pf-v5-c-login__header">
              <div id="kc-header-wrapper" class="pf-v5-c-brand">MemoryOS</div>
            </header>
            <main class="pf-v5-c-login__main">
              <div class="pf-v5-c-login__main-header">
                <h1 class="pf-v5-c-title pf-m-3xl" id="kc-page-title">${title}</h1>
              </div>
              <div class="pf-v5-c-login__main-body">
                <div id="kc-form"><div id="kc-form-wrapper">
                  <form id="kc-form-login" class="pf-v5-c-form">
                    <div class="pf-v5-c-form__group">
                      <div class="pf-v5-c-form__group-label pf-v5-u-pb-xs">
                        <label class="pf-v5-c-form__label" for="username">
                          <span class="pf-v5-c-form__label-text">Email address</span>
                        </label>
                      </div>
                      <span class="pf-v5-c-form-control">
                        <input id="username" name="username" autocomplete="username">
                      </span>
                    </div>
                    <div class="pf-v5-c-form__group">
                      <div class="pf-v5-c-form__group-label pf-v5-u-pb-xs">
                        <label class="pf-v5-c-form__label" for="password">
                          <span class="pf-v5-c-form__label-text">Password</span>
                        </label>
                      </div>
                      <span class="pf-v5-c-form-control">
                        <input id="password" name="password" type="password" autocomplete="current-password">
                      </span>
                    </div>
                    <div class="pf-v5-c-form__group">
                      <input class="pf-v5-c-button pf-m-primary pf-m-block" type="submit" value="Sign in">
                    </div>
                  </form>
                </div></div>
                <div id="kc-social-providers" class="pf-v5-c-login__main-footer-band">
                  <hr>
                  <h2>Or continue with</h2>
                  <ul class="pf-v5-c-login__main-footer-links">
                    <li><a id="social-tasco" class="pf-v5-c-button pf-m-control" href="#">
                      <svg></svg><span class="kc-social-provider-name">Đăng nhập với</span>
                    </a></li>
                  </ul>
                </div>
              </div>
            </main>
          </div>
        </div>
      </body>
    </html>`;
}

test("centres the card under the MemoryOS wordmark", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.setContent(keycloakMarkup());

  await expect(page.getByRole("heading", { name: "Continue to MemoryOS" })).toBeVisible();

  const layout = await page.evaluate(() => {
    const card = document.querySelector(".pf-v5-c-login__container")!.getBoundingClientRect();
    const header = getComputedStyle(document.querySelector("#kc-header-wrapper")!);
    return {
      leftInset: Math.round(card.left),
      rightInset: Math.round(innerWidth - card.right),
      headerImage: header.backgroundImage,
    };
  });

  expect(Math.abs(layout.leftInset - layout.rightInset)).toBeLessThanOrEqual(1);
  expect(layout.headerImage).toContain("memoryos-wordmark.svg");
});

test("keeps the identity provider button on one line with its wordmark", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.setContent(keycloakMarkup());

  const button = page.locator("#social-tasco");
  await expect(button).toBeVisible();
  // The provider's own icon is replaced by the wordmark that follows the button's text.
  const marks = await page.evaluate(() => {
    const element = document.querySelector("#social-tasco")!;
    const svg = getComputedStyle(element.querySelector("svg")!);
    const after = getComputedStyle(element.querySelector("span")!, "::after");
    return { svgDisplay: svg.display, afterMask: after.maskImage || after.webkitMaskImage };
  });
  expect(marks.svgDisplay).toBe("none");
  expect(marks.afterMask).toContain("tasco-logo.png");
});

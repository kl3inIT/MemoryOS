import { readFileSync } from "node:fs";
import { expect, test } from "@playwright/test";

const themeCss = readFileSync(
  new URL(
    "../../../infrastructure/keycloak/themes/memoryos/login/resources/css/memoryos.css",
    import.meta.url,
  ),
  "utf8",
);

function keycloakMarkup(options: { pageId?: string; formId?: string; title?: string } = {}) {
  const pageId = options.pageId ?? "login-login";
  const formId = options.formId ?? "kc-form-login";
  const title = options.title ?? "Continue to your memory.";

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
                  <form id="${formId}" class="pf-v5-c-form">
                    <div class="pf-v5-c-form__group">
                      <div class="pf-v5-c-form__group-label pf-v5-u-pb-xs">
                        <label class="pf-v5-c-form__label" for="username">
                          <span class="pf-v5-c-form__label-text">Email address</span>
                        </label>
                      </div>
                      <span class="pf-v5-c-form-control">
                        <input id="username" name="username" autocomplete="username">
                      </span>
                      <div id="input-error-container-username"></div>
                    </div>
                    <div class="pf-v5-c-form__group">
                      <div class="pf-v5-c-form__group-label pf-v5-u-pb-xs">
                        <label class="pf-v5-c-form__label" for="password">
                          <span class="pf-v5-c-form__label-text">Password</span>
                        </label>
                      </div>
                      <div class="pf-v5-c-input-group">
                        <div class="pf-v5-c-input-group__item pf-m-fill">
                          <span class="pf-v5-c-form-control">
                            <input id="password" name="password" type="password" autocomplete="current-password">
                          </span>
                        </div>
                        <div class="pf-v5-c-input-group__item">
                          <button class="pf-v5-c-button pf-m-control" type="button" data-password-toggle aria-label="Show password">
                            <i class="fa-eye fas"></i>
                          </button>
                        </div>
                      </div>
                      <div class="pf-v5-c-form__helper-text">
                        <div class="pf-v5-c-helper-text pf-v5-u-display-flex pf-v5-u-justify-content-space-between">
                          <div class="pf-v5-c-helper-text__item">
                            <span class="pf-v5-c-helper-text__item-text"><a href="#reset">Forgot password?</a></span>
                          </div>
                        </div>
                      </div>
                      <div id="input-error-container-password"></div>
                    </div>
                    <div class="pf-v5-c-form__group">
                      <div class="pf-v5-c-form__actions pf-v5-u-pt-sm">
                        <button class="pf-v5-c-button pf-m-primary pf-m-block" id="kc-login" type="submit">Sign in</button>
                      </div>
                    </div>
                  </form>
                </div></div>
              </div>
              <div class="pf-v5-c-login__main-footer"></div>
            </main>
          </div>
        </div>
      </body>
    </html>`;
}

test("renders the approved compact Keycloak login without document scrolling", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.setContent(keycloakMarkup());

  await expect(page.getByRole("heading", { name: "Continue to your memory." })).toBeVisible();
  await expect(page.getByText("MemoryOS", { exact: true })).toBeVisible();
  await expect(page.getByText("auth.kl3in.tech")).toHaveCount(0);
  await expect(page.getByText("Private by design")).toHaveCount(0);

  const layout = await page.evaluate(() => {
    const card = document.querySelector("main")!.getBoundingClientRect();
    const identity = document.querySelector("#kc-header")!.getBoundingClientRect();
    return {
      scrollHeight: document.documentElement.scrollHeight,
      clientHeight: document.documentElement.clientHeight,
      cardHeight: Math.round(card.height),
      cardRightInset: Math.round(innerWidth - card.right),
      identityLeftInset: Math.round(identity.left),
    };
  });
  expect(layout.scrollHeight).toBe(layout.clientHeight);
  expect(layout.cardHeight).toBeLessThanOrEqual(610);
  expect(layout.cardRightInset).toBeGreaterThanOrEqual(180);
  expect(layout.identityLeftInset - layout.cardRightInset).toBeGreaterThanOrEqual(40);
  expect(layout.identityLeftInset - layout.cardRightInset).toBeLessThanOrEqual(72);

  await page.getByLabel("Email address").focus();
  const focusStyle = await page.getByLabel("Email address").evaluate((element) => ({
    borderColor: getComputedStyle(element.closest(".pf-v5-c-form-control")!).borderColor,
    boxShadow: getComputedStyle(element.closest(".pf-v5-c-form-control")!).boxShadow,
  }));
  expect(focusStyle.borderColor).toBe("rgb(102, 114, 229)");
  expect(focusStyle.boxShadow).not.toBe("none");
});

test("keeps the Keycloak form inside one mobile viewport", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.setContent(keycloakMarkup());

  const layout = await page.evaluate(() => {
    const card = document.querySelector("main")!.getBoundingClientRect();
    return {
      documentHeight: document.documentElement.scrollHeight,
      viewportHeight: innerHeight,
      documentWidth: document.documentElement.scrollWidth,
      viewportWidth: innerWidth,
      cardTop: Math.round(card.top),
      cardBottom: Math.round(card.bottom),
    };
  });
  expect(layout.documentHeight).toBe(layout.viewportHeight);
  expect(layout.documentWidth).toBe(layout.viewportWidth);
  expect(layout.cardTop).toBeGreaterThan(50);
  expect(layout.cardBottom).toBeLessThanOrEqual(layout.viewportHeight - 8);
});

test("identifies inherited required-action and terminal screens", async ({ page }) => {
  await page.setContent(
    keycloakMarkup({
      pageId: "login-login-update-password",
      formId: "kc-passwd-update-form",
      title: "Create your password.",
    }),
  );
  const updateLabel = await page
    .locator("#kc-page-title")
    .evaluate((element) => getComputedStyle(element, "::before").content);
  expect(updateLabel).toContain("INVITATION");

  await page.setContent(
    keycloakMarkup({
      pageId: "login-login-verify-email",
      formId: "kc-verify-email-form",
      title: "Check your inbox.",
    }),
  );
  const verifyEmailLabel = await page
    .locator("#kc-page-title")
    .evaluate((element) => getComputedStyle(element, "::before").content);
  expect(verifyEmailLabel).toContain("VERIFY YOUR EMAIL");

  await page.setContent(
    keycloakMarkup({
      pageId: "login-info",
      formId: "kc-info",
      title: "Email verified.",
    }),
  );
  const infoLabel = await page
    .locator("#kc-page-title")
    .evaluate((element) => getComputedStyle(element, "::before").content);
  expect(infoLabel).toContain("EMAIL VERIFIED");

  await page.setContent(
    keycloakMarkup({
      pageId: "login-error",
      formId: "kc-error",
      title: "We couldn't continue.",
    }),
  );
  const errorLabel = await page
    .locator("#kc-page-title")
    .evaluate((element) => getComputedStyle(element, "::before").content);
  expect(errorLabel).toContain("ACTION UNAVAILABLE");
});

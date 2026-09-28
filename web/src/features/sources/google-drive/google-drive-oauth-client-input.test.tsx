import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useRef, useState } from "react";
import { afterEach, describe, expect, it } from "vitest";
import {
  GoogleDriveOAuthClientInput,
  type GoogleDriveOAuthClientInputHandle,
} from "./google-drive-oauth-client-input";

const validJson = JSON.stringify({
  web: { client_id: "test.apps.googleusercontent.com", client_secret: "synthetic-secret" },
});

afterEach(cleanup);

function CredentialForm() {
  const input = useRef<GoogleDriveOAuthClientInputHandle>(null);
  const [ready, setReady] = useState(false);
  return (
    <>
      <GoogleDriveOAuthClientInput ref={input} onReadyChange={setReady} />
      <button disabled={!ready} onClick={() => input.current?.takeJson()}>
        Continue
      </button>
    </>
  );
}

describe("Google OAuth client input", () => {
  it("rejects malformed and non-Web credentials without echoing their contents", async () => {
    const user = userEvent.setup();
    render(<CredentialForm />);
    const input = screen.getByRole("textbox", { name: "Upload or paste OAuth app JSON" });
    await user.click(input);
    await user.paste('{"client_secret":"synthetic-secret"');
    expect(screen.getByRole("button", { name: "Continue" })).toBeDisabled();
    expect(screen.getByRole("alert")).not.toHaveTextContent("synthetic-secret");
    await user.clear(input);
    await user.paste(
      JSON.stringify({ installed: { client_id: "desktop", client_secret: "synthetic-secret" } }),
    );
    expect(screen.getByRole("button", { name: "Continue" })).toBeDisabled();
    expect(screen.getByRole("alert")).not.toHaveTextContent("synthetic-secret");
    await user.clear(input);
    await user.paste(validJson);
    expect(screen.getByRole("button", { name: "Continue" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "Continue" }));
    expect(input).toHaveValue("");
    expect(screen.getByRole("button", { name: "Continue" })).toBeDisabled();
  });

  it("enforces the 16 KiB limit for uploads and multibyte pasted JSON", async () => {
    const user = userEvent.setup();
    render(<CredentialForm />);
    await user.upload(
      screen.getByLabelText("Upload OAuth client JSON"),
      new File([" ".repeat(16 * 1024 + 1)], "large.json", { type: "application/json" }),
    );
    expect(screen.getByRole("button", { name: "Continue" })).toBeDisabled();
    expect(screen.getByRole("alert")).toBeVisible();
    const input = screen.getByRole("textbox", { name: "Upload or paste OAuth app JSON" });
    await user.click(input);
    await user.paste(
      JSON.stringify({ web: { client_id: "test", client_secret: "é".repeat(8192) } }),
    );
    expect(screen.getByRole("button", { name: "Continue" })).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent("16 KiB");
  });

  it("does not restore an earlier upload after the owner edits or clears the credential", async () => {
    const user = userEvent.setup();
    render(<CredentialForm />);
    let finish!: (text: string) => void;
    const file = new File([validJson], "client.json", { type: "application/json" });
    Object.defineProperty(file, "text", {
      value: () =>
        new Promise<string>((resolve) => {
          finish = resolve;
        }),
    });
    await user.upload(screen.getByLabelText("Upload OAuth client JSON"), file);
    await waitFor(() => expect(finish).toBeTypeOf("function"));
    const input = screen.getByRole("textbox", { name: "Upload or paste OAuth app JSON" });
    await user.click(input);
    await user.paste(validJson);
    await user.click(screen.getByRole("button", { name: "Clear client JSON" }));
    await act(async () => {
      finish(validJson);
    });
    expect(input).toHaveValue("");
    expect(screen.getByRole("button", { name: "Continue" })).toBeDisabled();
  });

  it("clears plaintext DOM values when unmounted", async () => {
    const user = userEvent.setup();
    const view = render(<CredentialForm />);
    const input = screen.getByRole("textbox", { name: "Upload or paste OAuth app JSON" });
    await user.click(input);
    await user.paste(validJson);
    view.unmount();
    expect(input).toHaveValue("");
  });
});

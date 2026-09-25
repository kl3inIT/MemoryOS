import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useRef, useState } from "react";
import { afterEach, describe, expect, it } from "vitest";
import {
  SharePointCredentialInput,
  type SharePointAuthMethod,
  type SharePointCredentialInputHandle,
} from "./sharepoint-credential-input";

afterEach(cleanup);

function CredentialForm({ method: initial = "CLIENT_SECRET" as SharePointAuthMethod }) {
  const input = useRef<SharePointCredentialInputHandle>(null);
  const [method, setMethod] = useState<SharePointAuthMethod>(initial);
  const [ready, setReady] = useState(false);
  const [taken, setTaken] = useState<string>("");
  return (
    <>
      <SharePointCredentialInput
        ref={input}
        method={method}
        onMethodChange={setMethod}
        onReadyChange={setReady}
      />
      <button
        disabled={!ready}
        onClick={() => setTaken(JSON.stringify(input.current?.take() ?? null))}
      >
        Continue
      </button>
      <output data-testid="taken">{taken}</output>
    </>
  );
}

describe("SharePoint credential input", () => {
  it("returns the secret once via take() and clears it", async () => {
    const user = userEvent.setup();
    render(<CredentialForm />);
    const secret = screen.getByLabelText("Client secret Value");
    await user.click(secret);
    await user.paste("synthetic-secret-value");
    expect(screen.getByRole("button", { name: "Continue" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "Continue" }));
    expect(screen.getByTestId("taken")).toHaveTextContent("synthetic-secret-value");
    expect(secret).toHaveValue("");
    expect(screen.getByRole("button", { name: "Continue" })).toBeDisabled();
    // A second take returns nothing: the draft was consumed.
    await user.click(screen.getByRole("button", { name: "Continue" }));
  });

  it("rejects a secret longer than 256 characters without echoing it", async () => {
    const user = userEvent.setup();
    render(<CredentialForm />);
    const secret = screen.getByLabelText("Client secret Value");
    await user.click(secret);
    await user.paste("x".repeat(300));
    expect(screen.getByRole("button", { name: "Continue" })).toBeDisabled();
    expect(screen.getByRole("alert")).not.toHaveTextContent("xxx");
  });

  it("rejects a keystore over 16 KiB and accepts a small .pfx", async () => {
    const user = userEvent.setup();
    render(<CredentialForm method="CERTIFICATE" />);
    const upload = document.querySelector<HTMLInputElement>('input[type="file"]')!;
    const tooBig = new File([new Uint8Array(16 * 1024 + 1)], "big.pfx", {
      type: "application/x-pkcs12",
    });
    await user.upload(upload, tooBig);
    expect(screen.getByRole("alert")).toHaveTextContent("16 KiB");
    expect(screen.getByRole("button", { name: "Continue" })).toBeDisabled();
    const small = new File([new Uint8Array([1, 2, 3, 4])], "app.pfx", {
      type: "application/x-pkcs12",
    });
    await user.upload(upload, small);
    await waitFor(() => expect(screen.getByRole("button", { name: "Continue" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "Continue" }));
    // base64 of bytes 1,2,3,4 is AQIDBA==
    expect(screen.getByTestId("taken")).toHaveTextContent("AQIDBA==");
  });

  it("clears plaintext DOM values when unmounted", async () => {
    const user = userEvent.setup();
    const { unmount } = render(<CredentialForm />);
    const secret = screen.getByLabelText("Client secret Value");
    await user.click(secret);
    await user.paste("synthetic-secret-value");
    unmount();
    expect(secret).toHaveValue("");
  });
});

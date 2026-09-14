import { useState } from "react";
import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";
import { useTranslation } from "react-i18next";
import { FeedbackDialog } from "@/components/assistant-ui/elements/feedback-dialog";
import { i18n } from "@/i18n";
import { ChatDialog } from "./chat-dialog";

it("keeps feedback IDs and the draft when a failed submission is retranslated, without replaying it", async () => {
  const submit = vi.fn().mockRejectedValue({ status: 500, detail: "private diagnostic" });
  function Feedback() {
    const { t } = useTranslation("feedback");
    const [reason, setReason] = useState("");
    const [note, setNote] = useState("");
    return (
      <ChatDialog
        open
        title={t("negativeTitle")}
        description={t("description")}
        submitLabel={t("send")}
        onSubmit={() => submit({ reason, note })}
      >
        <FeedbackDialog
          reasons={[{ id: "incorrect", label: t("incorrect") }]}
          selected={reason}
          note={note}
          onNoteChange={setNote}
          onToggleReason={setReason}
        />
      </ChatDialog>
    );
  }
  render(<Feedback />);
  const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "Not factual" }));
  const field = screen.getByRole("textbox", { name: "Comment" });
  await user.type(field, "The figures do not match.");
  await user.click(screen.getByRole("button", { name: "Send feedback" }));
  expect(await screen.findByRole("alert")).not.toHaveTextContent("private diagnostic");
  const error = screen.getByRole("alert").textContent;
  await act(async () => {
    await i18n.changeLanguage("vi");
  });
  expect(screen.getByRole("alert").textContent).not.toBe(error);
  expect(field).toHaveValue("The figures do not match.");
  expect(screen.getByRole("button", { name: i18n.t("feedback:incorrect") })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  expect(submit).toHaveBeenCalledExactlyOnceWith({
    reason: "incorrect",
    note: "The figures do not match.",
  });
});

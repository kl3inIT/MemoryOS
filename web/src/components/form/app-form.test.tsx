import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { revalidateLogic } from "@tanstack/react-form";
import { describe, expect, it, vi } from "vitest";
import { z } from "zod";
import { useAppForm, setServerErrors, useProblemErrors } from "@/components/form/app-form";
import { ApiError } from "@/lib/api";
import type { ApiProblem } from "@/lib/hey-api/types.gen";

const rejected: ApiProblem = {
  title: "Bad Request",
  status: 400,
  detail: "Invalid name",
  instance: "/api/example",
  errors: [{ field: "name", message: "size", code: "SIZE", params: { min: 1, max: 20 } }],
};

function NameForm({ save }: { save: (name: string) => Promise<void> }) {
  const problemErrors = useProblemErrors();
  const form = useAppForm({
    defaultValues: { name: "" },
    validationLogic: revalidateLogic(),
    validators: { onDynamic: z.object({ name: z.string().min(1, "Enter a name.") }) },
    onSubmit: async ({ value, formApi }) => {
      try {
        await save(value.name);
      } catch (cause) {
        setServerErrors(formApi, problemErrors(cause));
      }
    },
  });
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        void form.handleSubmit();
      }}
    >
      <form.AppField name="name">{(field) => <field.TextField label="Name" />}</form.AppField>
      <form.AppForm>
        <form.FormError />
        <form.SubmitButton>Save</form.SubmitButton>
      </form.AppForm>
    </form>
  );
}

describe("useAppForm", () => {
  it("submits again after the server refused the previous attempt", async () => {
    const save = vi
      .fn<(name: string) => Promise<void>>()
      .mockRejectedValueOnce(new ApiError(400, rejected))
      .mockResolvedValueOnce(undefined);
    const user = userEvent.setup();
    render(<NameForm save={save} />);

    const name = screen.getByRole("textbox", { name: "Name" });
    await user.type(name, "Finance");
    await user.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByText("Check the highlighted fields and try again.")).toBeVisible();
    expect(name).toHaveAttribute("aria-invalid", "true");

    await user.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(save).toHaveBeenCalledTimes(2));
    expect(screen.queryByText("Check the highlighted fields and try again.")).toBeNull();
    expect(name).not.toHaveAttribute("aria-invalid");
  });

  it("clears the server's errors once the person edits the form", async () => {
    const save = vi
      .fn<(name: string) => Promise<void>>()
      .mockRejectedValueOnce(new ApiError(400, rejected));
    const user = userEvent.setup();
    render(<NameForm save={save} />);

    const name = screen.getByRole("textbox", { name: "Name" });
    await user.type(name, "Finance");
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("Check the highlighted fields and try again.")).toBeVisible();

    await user.type(name, "s");

    expect(screen.queryByText("Check the highlighted fields and try again.")).toBeNull();
    expect(name).not.toHaveAttribute("aria-invalid");
  });
});

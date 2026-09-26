import { createFormHook } from "@tanstack/react-form";
import { fieldContext, formContext } from "@/components/form/form-context";
import { CheckboxField, FormError, SubmitButton, TextField } from "@/components/form/form-fields";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";

/**
 * The application's TanStack Form hook: `form.AppField` binds the shared shadcn `Field` controls
 * (`field.TextField`, `field.CheckboxField`) and `form.AppForm` the form-level parts
 * (`form.SubmitButton`, `form.FormError`).
 */
export const { useAppForm } = createFormHook({
  fieldContext,
  formContext,
  fieldComponents: { TextField, CheckboxField },
  formComponents: { SubmitButton, FormError },
});

/**
 * Turns a failed mutation into the error map `form.setErrorMap({ onSubmit: … })` takes: the
 * `ApiProblem` field violations under their field names and the problem itself as the form error.
 */
export function useProblemErrors() {
  const problemMessage = useProblemMessage();
  return (error: unknown) => {
    const problem = presentProblem(error, "mutation");
    return {
      form: problemMessage(problem.message),
      fields: Object.fromEntries(
        Object.entries(problem.fields).map(([name, message]) => [
          name,
          { message: problemMessage(message) },
        ]),
      ),
    };
  };
}

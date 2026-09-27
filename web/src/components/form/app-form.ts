import {
  createFormHook,
  defaultValidationLogic,
  type AnyFormApi,
  type ValidationLogicFn,
} from "@tanstack/react-form";
import { fieldContext, formContext } from "@/components/form/form-context";
import { CheckboxField, FormError, SubmitButton, TextField } from "@/components/form/form-fields";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";

const appForm = createFormHook({
  fieldContext,
  formContext,
  fieldComponents: { TextField, CheckboxField },
  formComponents: { SubmitButton, FormError },
});

/**
 * Runs the form's validation logic and, on submit, also re-evaluates each field's `onServer` entry.
 * Submitting validates the fields without the form's validators, so a field with no validator of
 * its own would keep the violation the server placed on it and block every later submit.
 */
function clearingServerErrors(
  logic: ValidationLogicFn = defaultValidationLogic,
): ValidationLogicFn {
  return (props) =>
    logic({
      ...props,
      runValidation: ({ validators, form }) =>
        props.runValidation({
          validators:
            props.event.type === "submit" &&
            !props.event.async &&
            !validators.some((validator) => validator?.cause === "server")
              ? [...validators, { fn: undefined, cause: "server" }]
              : validators,
          form,
        }),
    });
}

/**
 * The application's TanStack Form hook: `form.AppField` binds the shared shadcn `Field` controls
 * (`field.TextField`, `field.CheckboxField`) and `form.AppForm` the form-level parts
 * (`form.SubmitButton`, `form.FormError`). A failed submission's server errors, set with
 * `useProblemErrors`, clear when the person edits the form or submits again.
 */
export const useAppForm: typeof appForm.useAppForm = (options) =>
  appForm.useAppForm({
    ...options,
    validationLogic: clearingServerErrors(options.validationLogic),
  });

/**
 * Declares a component that renders part of a form built elsewhere with {@link useAppForm}, typed
 * by that form's `defaultValues`; the component receives the form as its `form` prop.
 */
export const { withForm } = appForm;

/** A failed submission's errors: the message under the form and the messages on its fields. */
export type ServerErrors = {
  form?: string;
  fields: Record<string, { message: string } | undefined>;
};

/**
 * Shows a failed submission's errors on the form. They go under the `onServer` key, never
 * `onSubmit`: TanStack Form re-evaluates a key only through a validator of that key, and it runs a
 * built-in `onServer` validator on every change, blur and submit (with {@link useAppForm} also for
 * each field on submit), so the errors clear once the person edits the form or submits again. An
 * `onSubmit` entry without an `onSubmit` validator is never cleared, and the next submit would stop
 * at validation without running `onSubmit`.
 */
export function setServerErrors(formApi: AnyFormApi, errors: ServerErrors) {
  formApi.setErrorMap({ onServer: errors });
}

/**
 * Turns a failed mutation into the errors {@link setServerErrors} shows: the `ApiProblem` field
 * violations under their field names and the problem itself as the form error.
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

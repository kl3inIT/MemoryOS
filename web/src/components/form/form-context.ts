import { createFormHookContexts } from "@tanstack/react-form";

/** The contexts the shared field and form components read; `useAppForm` provides them. */
const contexts = createFormHookContexts();
export const { fieldContext, formContext, useFormContext } = contexts;
const { useFieldContext } = contexts;

/** Validation errors as `FieldError` lists them: zod issues and server messages alike. */
function messagesOf(errors: unknown[]) {
  return errors.map((error) =>
    typeof error === "string"
      ? { message: error }
      : error &&
          typeof error === "object" &&
          "message" in error &&
          typeof error.message === "string"
        ? { message: error.message }
        : undefined,
  );
}

/**
 * The bound field with whether it shows as invalid and its messages for `FieldError`. A field shows
 * its errors once touched, which submitting does for every field. Custom controls inside
 * `form.AppField` use it to set `data-invalid` on `Field` and `aria-invalid` on the control.
 */
export function useFieldValidity<TValue>() {
  const field = useFieldContext<TValue>();
  const invalid = field.state.meta.isTouched && !field.state.meta.isValid;
  return { field, invalid, errors: messagesOf(field.state.meta.errors) };
}

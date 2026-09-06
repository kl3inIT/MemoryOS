import { cva, type VariantProps } from "class-variance-authority";

const actionStateClasses =
  "border bg-[var(--action-surface)] text-[var(--action-content)] border-[var(--action-border)] transition-[color,background-color,border-color,box-shadow,transform] duration-150 outline-none hover:bg-[var(--action-surface-hover)] hover:text-[var(--action-content-hover)] hover:border-[var(--action-border-hover)] active:bg-[var(--action-surface-active)] active:text-[var(--action-content-active)] active:border-[var(--action-border-active)] focus-visible:ring-3 focus-visible:ring-focus-ring/40 focus-visible:ring-offset-2 focus-visible:ring-offset-surface-base disabled:pointer-events-none disabled:bg-[var(--action-disabled-surface)] disabled:text-[var(--action-disabled-content)] disabled:border-[var(--action-disabled-border)] aria-disabled:pointer-events-none aria-disabled:bg-[var(--action-disabled-surface)] aria-disabled:text-[var(--action-disabled-content)] aria-disabled:border-[var(--action-disabled-border)]";

const defaultDisabledVariables =
  "[--action-disabled-surface:var(--action-default-disabled-surface)] [--action-disabled-content:var(--action-default-disabled-content)] [--action-disabled-border:var(--action-default-disabled-border)]";
const dangerDisabledVariables =
  "[--action-disabled-surface:var(--action-danger-disabled-surface)] [--action-disabled-content:var(--action-danger-disabled-content)] [--action-disabled-border:var(--action-danger-disabled-border)]";

const actionVariants = cva(actionStateClasses, {
  variants: {
    tone: {
      default: defaultDisabledVariables,
      danger: dangerDisabledVariables,
    },
    prominence: {
      primary: "",
      secondary: "",
      tertiary: "",
      internal: "",
    },
  },
  compoundVariants: [
    {
      tone: "default",
      prominence: "primary",
      class:
        "[--action-surface:var(--action-default-primary-surface)] [--action-surface-hover:var(--action-default-primary-surface-hover)] [--action-surface-active:var(--action-default-primary-surface-active)] [--action-content:var(--action-default-primary-content)] [--action-content-hover:var(--action-default-primary-content-hover)] [--action-content-active:var(--action-default-primary-content-active)] [--action-border:var(--action-default-primary-border)] [--action-border-hover:var(--action-default-primary-border-hover)] [--action-border-active:var(--action-default-primary-border-active)]",
    },
    {
      tone: "default",
      prominence: "secondary",
      class:
        "[--action-surface:var(--action-default-secondary-surface)] [--action-surface-hover:var(--action-default-secondary-surface-hover)] [--action-surface-active:var(--action-default-secondary-surface-active)] [--action-content:var(--action-default-secondary-content)] [--action-content-hover:var(--action-default-secondary-content-hover)] [--action-content-active:var(--action-default-secondary-content-active)] [--action-border:var(--action-default-secondary-border)] [--action-border-hover:var(--action-default-secondary-border-hover)] [--action-border-active:var(--action-default-secondary-border-active)]",
    },
    {
      tone: "default",
      prominence: "tertiary",
      class:
        "[--action-surface:var(--action-default-tertiary-surface)] [--action-surface-hover:var(--action-default-tertiary-surface-hover)] [--action-surface-active:var(--action-default-tertiary-surface-active)] [--action-content:var(--action-default-tertiary-content)] [--action-content-hover:var(--action-default-tertiary-content-hover)] [--action-content-active:var(--action-default-tertiary-content-active)] [--action-border:var(--action-default-tertiary-border)] [--action-border-hover:var(--action-default-tertiary-border-hover)] [--action-border-active:var(--action-default-tertiary-border-active)]",
    },
    {
      tone: "default",
      prominence: "internal",
      class:
        "[--action-surface:var(--action-default-internal-surface)] [--action-surface-hover:var(--action-default-internal-surface-hover)] [--action-surface-active:var(--action-default-internal-surface-active)] [--action-content:var(--action-default-internal-content)] [--action-content-hover:var(--action-default-internal-content-hover)] [--action-content-active:var(--action-default-internal-content-active)] [--action-border:var(--action-default-internal-border)] [--action-border-hover:var(--action-default-internal-border-hover)] [--action-border-active:var(--action-default-internal-border-active)]",
    },
    {
      tone: "danger",
      prominence: "primary",
      class:
        "[--action-surface:var(--action-danger-primary-surface)] [--action-surface-hover:var(--action-danger-primary-surface-hover)] [--action-surface-active:var(--action-danger-primary-surface-active)] [--action-content:var(--action-danger-primary-content)] [--action-content-hover:var(--action-danger-primary-content-hover)] [--action-content-active:var(--action-danger-primary-content-active)] [--action-border:var(--action-danger-primary-border)] [--action-border-hover:var(--action-danger-primary-border-hover)] [--action-border-active:var(--action-danger-primary-border-active)]",
    },
    {
      tone: "danger",
      prominence: "secondary",
      class:
        "[--action-surface:var(--action-danger-secondary-surface)] [--action-surface-hover:var(--action-danger-secondary-surface-hover)] [--action-surface-active:var(--action-danger-secondary-surface-active)] [--action-content:var(--action-danger-secondary-content)] [--action-content-hover:var(--action-danger-secondary-content-hover)] [--action-content-active:var(--action-danger-secondary-content-active)] [--action-border:var(--action-danger-secondary-border)] [--action-border-hover:var(--action-danger-secondary-border-hover)] [--action-border-active:var(--action-danger-secondary-border-active)]",
    },
    {
      tone: "danger",
      prominence: "tertiary",
      class:
        "[--action-surface:var(--action-danger-tertiary-surface)] [--action-surface-hover:var(--action-danger-tertiary-surface-hover)] [--action-surface-active:var(--action-danger-tertiary-surface-active)] [--action-content:var(--action-danger-tertiary-content)] [--action-content-hover:var(--action-danger-tertiary-content-hover)] [--action-content-active:var(--action-danger-tertiary-content-active)] [--action-border:var(--action-danger-tertiary-border)] [--action-border-hover:var(--action-danger-tertiary-border-hover)] [--action-border-active:var(--action-danger-tertiary-border-active)]",
    },
    {
      tone: "danger",
      prominence: "internal",
      class:
        "[--action-surface:var(--action-danger-internal-surface)] [--action-surface-hover:var(--action-danger-internal-surface-hover)] [--action-surface-active:var(--action-danger-internal-surface-active)] [--action-content:var(--action-danger-internal-content)] [--action-content-hover:var(--action-danger-internal-content-hover)] [--action-content-active:var(--action-danger-internal-content-active)] [--action-border:var(--action-danger-internal-border)] [--action-border-hover:var(--action-danger-internal-border-hover)] [--action-border-active:var(--action-danger-internal-border-active)]",
    },
  ],
  defaultVariants: {
    tone: "default",
    prominence: "primary",
  },
});

type ActionVariantProps = VariantProps<typeof actionVariants>;
type ActionTone = NonNullable<ActionVariantProps["tone"]>;
type ActionProminence = NonNullable<ActionVariantProps["prominence"]>;

export { actionVariants, type ActionProminence, type ActionTone };

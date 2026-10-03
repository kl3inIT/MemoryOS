import { cva, type VariantProps } from "class-variance-authority";

const actionStateClasses =
  "border bg-[var(--action-surface)] text-[var(--action-content)] border-[var(--action-border)] transition-[color,background-color,border-color,box-shadow,transform] duration-150 outline-none hover:bg-[var(--action-surface-hover)] hover:text-[var(--action-content-hover)] hover:border-[var(--action-border-hover)] active:bg-[var(--action-surface-active)] active:text-[var(--action-content-active)] active:border-[var(--action-border-active)] focus-visible:ring-3 focus-visible:ring-focus-ring/40 focus-visible:ring-offset-2 focus-visible:ring-offset-surface-base disabled:pointer-events-none disabled:bg-[var(--action-disabled-surface)] disabled:text-[var(--action-disabled-content)] disabled:border-[var(--action-disabled-border)] aria-disabled:pointer-events-none aria-disabled:bg-[var(--action-disabled-surface)] aria-disabled:text-[var(--action-disabled-content)] aria-disabled:border-[var(--action-disabled-border)]";

const defaultDisabledVariables =
  "[--action-disabled-surface:var(--action-default-disabled-surface)] [--action-disabled-content:var(--action-default-disabled-content)] [--action-disabled-border:var(--action-default-disabled-border)]";
const dangerDisabledVariables =
  "[--action-disabled-surface:var(--action-danger-disabled-surface)] [--action-disabled-content:var(--action-danger-disabled-content)] [--action-disabled-border:var(--action-danger-disabled-border)]";
const successVariables =
  "[--action-border-active:var(--status-success-emphasis-border)] [--action-border-hover:var(--status-success-emphasis-border)] [--action-border:var(--status-success-emphasis-border)] [--action-content-active:var(--content-on-emphasis)] [--action-content-hover:var(--content-on-emphasis)] [--action-content:var(--content-on-emphasis)] [--action-surface-active:var(--status-success-emphasis)] [--action-surface-hover:var(--status-success-emphasis)] [--action-surface:var(--status-success-emphasis)] [--action-disabled-surface:var(--status-success-emphasis)] [--action-disabled-content:var(--content-on-emphasis)] [--action-disabled-border:var(--status-success-emphasis-border)]";

const actionVariants = cva(actionStateClasses, {
  variants: {
    tone: {
      default: defaultDisabledVariables,
      danger: dangerDisabledVariables,
      success: successVariables,
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
        "[--action-border-active:var(--action-default-primary-border-active)] [--action-border-hover:var(--action-default-primary-border-hover)] [--action-border:var(--action-default-primary-border)] [--action-content-active:var(--action-default-primary-content-active)] [--action-content-hover:var(--action-default-primary-content-hover)] [--action-content:var(--action-default-primary-content)] [--action-surface-active:var(--action-default-primary-surface-active)] [--action-surface-hover:var(--action-default-primary-surface-hover)] [--action-surface:var(--action-default-primary-surface)]",
    },
    {
      tone: "default",
      prominence: "secondary",
      class:
        "[--action-border-active:var(--action-default-secondary-border-active)] [--action-border-hover:var(--action-default-secondary-border-hover)] [--action-border:var(--action-default-secondary-border)] [--action-content-active:var(--action-default-secondary-content-active)] [--action-content-hover:var(--action-default-secondary-content-hover)] [--action-content:var(--action-default-secondary-content)] [--action-surface-active:var(--action-default-secondary-surface-active)] [--action-surface-hover:var(--action-default-secondary-surface-hover)] [--action-surface:var(--action-default-secondary-surface)]",
    },
    {
      tone: "default",
      prominence: "tertiary",
      class:
        "[--action-border-active:var(--action-default-tertiary-border-active)] [--action-border-hover:var(--action-default-tertiary-border-hover)] [--action-border:var(--action-default-tertiary-border)] [--action-content-active:var(--action-default-tertiary-content-active)] [--action-content-hover:var(--action-default-tertiary-content-hover)] [--action-content:var(--action-default-tertiary-content)] [--action-surface-active:var(--action-default-tertiary-surface-active)] [--action-surface-hover:var(--action-default-tertiary-surface-hover)] [--action-surface:var(--action-default-tertiary-surface)]",
    },
    {
      tone: "default",
      prominence: "internal",
      class:
        "[--action-border-active:var(--action-default-internal-border-active)] [--action-border-hover:var(--action-default-internal-border-hover)] [--action-border:var(--action-default-internal-border)] [--action-content-active:var(--action-default-internal-content-active)] [--action-content-hover:var(--action-default-internal-content-hover)] [--action-content:var(--action-default-internal-content)] [--action-surface-active:var(--action-default-internal-surface-active)] [--action-surface-hover:var(--action-default-internal-surface-hover)] [--action-surface:var(--action-default-internal-surface)]",
    },
    {
      tone: "danger",
      prominence: "primary",
      class:
        "[--action-border-active:var(--action-danger-primary-border-active)] [--action-border-hover:var(--action-danger-primary-border-hover)] [--action-border:var(--action-danger-primary-border)] [--action-content-active:var(--action-danger-primary-content-active)] [--action-content-hover:var(--action-danger-primary-content-hover)] [--action-content:var(--action-danger-primary-content)] [--action-surface-active:var(--action-danger-primary-surface-active)] [--action-surface-hover:var(--action-danger-primary-surface-hover)] [--action-surface:var(--action-danger-primary-surface)]",
    },
    {
      tone: "danger",
      prominence: "secondary",
      class:
        "[--action-border-active:var(--action-danger-secondary-border-active)] [--action-border-hover:var(--action-danger-secondary-border-hover)] [--action-border:var(--action-danger-secondary-border)] [--action-content-active:var(--action-danger-secondary-content-active)] [--action-content-hover:var(--action-danger-secondary-content-hover)] [--action-content:var(--action-danger-secondary-content)] [--action-surface-active:var(--action-danger-secondary-surface-active)] [--action-surface-hover:var(--action-danger-secondary-surface-hover)] [--action-surface:var(--action-danger-secondary-surface)]",
    },
    {
      tone: "danger",
      prominence: "tertiary",
      class:
        "[--action-border-active:var(--action-danger-tertiary-border-active)] [--action-border-hover:var(--action-danger-tertiary-border-hover)] [--action-border:var(--action-danger-tertiary-border)] [--action-content-active:var(--action-danger-tertiary-content-active)] [--action-content-hover:var(--action-danger-tertiary-content-hover)] [--action-content:var(--action-danger-tertiary-content)] [--action-surface-active:var(--action-danger-tertiary-surface-active)] [--action-surface-hover:var(--action-danger-tertiary-surface-hover)] [--action-surface:var(--action-danger-tertiary-surface)]",
    },
    {
      tone: "danger",
      prominence: "internal",
      class:
        "[--action-border-active:var(--action-danger-internal-border-active)] [--action-border-hover:var(--action-danger-internal-border-hover)] [--action-border:var(--action-danger-internal-border)] [--action-content-active:var(--action-danger-internal-content-active)] [--action-content-hover:var(--action-danger-internal-content-hover)] [--action-content:var(--action-danger-internal-content)] [--action-surface-active:var(--action-danger-internal-surface-active)] [--action-surface-hover:var(--action-danger-internal-surface-hover)] [--action-surface:var(--action-danger-internal-surface)]",
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

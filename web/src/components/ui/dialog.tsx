import * as React from "react";
import { Dialog as DialogPrimitive } from "radix-ui";
import { XIcon } from "lucide-react";
import { cva, type VariantProps } from "class-variance-authority";

import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";

function Dialog({ ...props }: React.ComponentProps<typeof DialogPrimitive.Root>) {
  return <DialogPrimitive.Root data-slot="dialog" {...props} />;
}

function DialogTrigger({ ...props }: React.ComponentProps<typeof DialogPrimitive.Trigger>) {
  return <DialogPrimitive.Trigger data-slot="dialog-trigger" {...props} />;
}

function DialogPortal({ ...props }: React.ComponentProps<typeof DialogPrimitive.Portal>) {
  return <DialogPrimitive.Portal data-slot="dialog-portal" {...props} />;
}

function DialogClose({ ...props }: React.ComponentProps<typeof DialogPrimitive.Close>) {
  return <DialogPrimitive.Close data-slot="dialog-close" {...props} />;
}

function DialogOverlay({
  className,
  ...props
}: React.ComponentProps<typeof DialogPrimitive.Overlay>) {
  return (
    <DialogPrimitive.Overlay
      data-slot="dialog-overlay"
      className={cn(
        "fixed inset-0 isolate z-50 bg-surface-scrim duration-100 supports-backdrop-filter:backdrop-blur-[2px] data-open:animate-in data-open:fade-in-0 data-closed:animate-out data-closed:fade-out-0 motion-reduce:animate-none",
        className,
      )}
      {...props}
    />
  );
}

const dialogContentVariants = cva(
  "fixed top-1/2 left-1/2 z-50 grid max-h-[calc(100dvh-2rem)] w-full max-w-[calc(100%-2rem)] -translate-x-1/2 -translate-y-1/2 gap-5 overflow-y-auto rounded-2xl border border-border-subtle bg-surface-overlay p-6 font-main-ui-body text-content-primary shadow-md duration-100 outline-none sm:max-w-lg data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95 motion-reduce:animate-none",
  {
    variants: {
      /**
       * `flush` leaves the edges to the content, as a viewer or a split editor needs: no padding or gap, a column
       * that clips its regions, a `DialogHeader` with its own padding and rule, and a `DialogFooter` flush with it.
       */
      layout: {
        padded: "",
        flush: "flex flex-col gap-0 overflow-hidden p-0",
      },
      /** The windowed size from the `sm` breakpoint: a viewer grows to a large, a tall or a full window, or the screen. */
      size: {
        default: "",
        large:
          "sm:h-[min(48rem,calc(100dvh-3rem))] sm:w-[min(64rem,calc(100vw-3rem))] sm:max-w-[min(64rem,calc(100vw-3rem))]",
        tall: "sm:h-[calc(100dvh-3rem)] sm:w-[min(64rem,calc(100vw-3rem))] sm:max-w-[min(64rem,calc(100vw-3rem))]",
        full: "sm:h-[calc(100dvh-3rem)] sm:w-[calc(100vw-3rem)] sm:max-w-[min(96rem,calc(100vw-3rem))]",
        screen:
          "sm:top-0 sm:left-0 sm:h-dvh sm:max-h-dvh sm:w-screen sm:max-w-none sm:translate-x-0 sm:translate-y-0 sm:rounded-none",
      },
      /** Below `sm`: centered like any dialog, filling the screen, or rising from the bottom as a sheet. */
      phone: {
        centered: "",
        screen:
          "inset-0 max-h-none w-auto max-w-none translate-x-0 translate-y-0 sm:inset-auto sm:top-1/2 sm:left-1/2 sm:-translate-x-1/2 sm:-translate-y-1/2",
        sheet:
          "inset-x-0 top-auto bottom-0 left-0 max-h-[calc(100dvh-0.5rem)] min-h-viewer-sheet w-auto max-w-none translate-x-0 translate-y-0 sm:top-1/2 sm:bottom-auto sm:left-1/2 sm:min-h-0 sm:-translate-x-1/2 sm:-translate-y-1/2",
      },
    },
    compoundVariants: [
      // The screen size stays pinned to the corner however the dialog opens on a phone.
      {
        size: "screen",
        phone: "screen",
        className: "sm:top-0 sm:left-0 sm:translate-x-0 sm:translate-y-0",
      },
      {
        size: "screen",
        phone: "sheet",
        className: "sm:top-0 sm:left-0 sm:translate-x-0 sm:translate-y-0",
      },
    ],
    defaultVariants: { layout: "padded", size: "default", phone: "centered" },
  },
);

function DialogContent({
  className,
  children,
  showCloseButton = true,
  layout,
  size,
  phone,
  ...props
}: React.ComponentProps<typeof DialogPrimitive.Content> &
  VariantProps<typeof dialogContentVariants> & {
    showCloseButton?: boolean;
  }) {
  const ui = useAppTranslation();

  return (
    <DialogPortal>
      <DialogOverlay />
      <DialogPrimitive.Content
        data-slot="dialog-content"
        data-layout={layout ?? "padded"}
        data-size={size ?? "default"}
        className={cn(dialogContentVariants({ layout, size, phone }), className)}
        {...props}
      >
        {children}
        {showCloseButton && (
          <DialogPrimitive.Close data-slot="dialog-close" asChild>
            <IconButton aria-label={ui("Đóng")} size="sm" className="absolute top-4 right-4">
              <XIcon />
            </IconButton>
          </DialogPrimitive.Close>
        )}
      </DialogPrimitive.Content>
    </DialogPortal>
  );
}

function DialogHeader({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="dialog-header"
      className={cn(
        "flex flex-col gap-2 pr-8 in-data-[layout=flush]:border-b in-data-[layout=flush]:border-border-subtle in-data-[layout=flush]:px-6 in-data-[layout=flush]:py-4",
        className,
      )}
      {...props}
    />
  );
}

function DialogFooter({
  className,
  showCloseButton = false,
  children,
  ...props
}: React.ComponentProps<"div"> & {
  showCloseButton?: boolean;
}) {
  const ui = useAppTranslation();

  return (
    <div
      data-slot="dialog-footer"
      className={cn(
        "-mx-6 -mb-6 flex flex-col-reverse gap-2 rounded-b-2xl border-t border-border-subtle bg-surface-base px-6 py-4 in-data-[layout=flush]:m-0 sm:flex-row sm:items-center sm:justify-end",
        className,
      )}
      {...props}
    >
      {children}
      {showCloseButton && (
        <DialogPrimitive.Close asChild>
          <Button prominence="secondary">{ui("Đóng")}</Button>
        </DialogPrimitive.Close>
      )}
    </div>
  );
}

function DialogTitle({ className, ...props }: React.ComponentProps<typeof DialogPrimitive.Title>) {
  return (
    <DialogPrimitive.Title
      data-slot="dialog-title"
      className={cn("font-heading-h3 text-content-primary", className)}
      {...props}
    />
  );
}

function DialogDescription({
  className,
  ...props
}: React.ComponentProps<typeof DialogPrimitive.Description>) {
  return (
    <DialogPrimitive.Description
      data-slot="dialog-description"
      className={cn(
        "font-main-ui-body text-content-secondary *:[a]:underline *:[a]:underline-offset-3 *:[a]:hover:text-content-primary",
        className,
      )}
      {...props}
    />
  );
}

export {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogOverlay,
  DialogPortal,
  DialogTitle,
  DialogTrigger,
};

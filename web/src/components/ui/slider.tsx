import * as React from "react";
import { Slider as SliderPrimitive } from "radix-ui";

import { cn } from "@/lib/utils";

function Slider({
  className,
  defaultValue,
  value,
  min = 0,
  max = 100,
  "aria-label": ariaLabel,
  ...props
}: React.ComponentProps<typeof SliderPrimitive.Root>) {
  const _values = React.useMemo(
    () => (Array.isArray(value) ? value : Array.isArray(defaultValue) ? defaultValue : [min, max]),
    [value, defaultValue, min, max],
  );

  return (
    <SliderPrimitive.Root
      data-slot="slider"
      defaultValue={defaultValue}
      value={value}
      min={min}
      max={max}
      className={cn(
        "group/slider relative flex w-full touch-none items-center select-none data-disabled:cursor-not-allowed data-vertical:h-full data-vertical:min-h-40 data-vertical:w-auto data-vertical:flex-col",
        className,
      )}
      {...props}
    >
      <SliderPrimitive.Track
        data-slot="slider-track"
        className="relative grow overflow-hidden rounded-full bg-surface-subtle data-horizontal:h-1 data-horizontal:w-full data-vertical:h-full data-vertical:w-1"
      >
        <SliderPrimitive.Range
          data-slot="slider-range"
          className="absolute bg-content-primary select-none group-data-disabled/slider:bg-content-disabled data-horizontal:h-full data-vertical:w-full"
        />
      </SliderPrimitive.Track>
      {Array.from({ length: _values.length }, (_, index) => (
        <SliderPrimitive.Thumb
          data-slot="slider-thumb"
          key={index}
          // Radix names the slider on its thumb, which is the element with role="slider".
          aria-label={ariaLabel}
          className="relative block size-4 shrink-0 rounded-full border border-border-strong bg-surface-raised shadow-sm transition-[color,box-shadow] outline-none select-none after:absolute after:-inset-2 hover:ring-3 hover:ring-focus-ring/20 focus-visible:ring-3 focus-visible:ring-focus-ring/30 active:ring-3 active:ring-focus-ring/30 group-data-disabled/slider:pointer-events-none group-data-disabled/slider:border-border-subtle group-data-disabled/slider:bg-surface-sunken"
        />
      ))}
    </SliderPrimitive.Root>
  );
}

export { Slider };

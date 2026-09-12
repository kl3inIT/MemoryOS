import { useRef } from "react";
import { howItWorks } from "@/content";
import { gsap, useMotion } from "@/motion/motion";
import { revealText } from "@/motion/text";
import { runTrack } from "@/motion/track";

/*
 * The six stages as type alone: each stage's number, a large title and one sentence. Below lg, and
 * whenever motion is off, the stages stack down the page. From lg the list pins and becomes a
 * horizontal track, after the one on gsap.com: scrolling down slides the stages in from the right,
 * one at a time, while a counter and a hairline follow along (src/motion/track.ts). Each stage's
 * text arrives with it: the title's letters rise and the sentence lights word by word
 * (src/motion/text.ts).
 */

const twoDigits = (value: number) => String(value).padStart(2, "0");

function IngestionStory() {
  const scope = useRef<HTMLDivElement>(null);
  const { stages } = howItWorks;

  useMotion(scope, ({ wide }) => {
    const stage = scope.current;
    const track = stage?.querySelector<HTMLElement>("ol");
    if (!stage || !track) {
      return undefined;
    }
    const stations = gsap.utils.toArray<HTMLElement>("[data-station]", track);
    if (wide) {
      return runTrack(stage, track, stations, revealText);
    }
    for (const station of stations) {
      revealText(station, { trigger: station, start: "top 85%", end: "top 40%" });
    }
    return undefined;
  });

  return (
    <div
      ref={scope}
      className="data-track:flex data-track:h-svh data-track:flex-col data-track:justify-center data-track:pt-16"
    >
      <div
        aria-hidden="true"
        className="mb-16 hidden items-center gap-6 font-main-ui-action tabular-nums track:flex"
      >
        <span className="text-content-muted">
          <span data-track-current="" className="text-content-primary">
            01
          </span>{" "}
          / {twoDigits(stages.length)}
        </span>
        <span className="relative h-px flex-1 bg-border-default">
          <span data-track-progress="" className="absolute inset-0 origin-left bg-accent" />
        </span>
      </div>
      <ol className="grid gap-y-14 sm:gap-y-20 track:flex track:gap-x-32">
        {stages.map((stage, index) => (
          <li
            key={stage.title}
            data-station=""
            className="border-t border-border-subtle pt-6 track:w-[min(58vw,50rem)] track:shrink-0 track:border-t-0 track:pt-0"
          >
            <p aria-hidden="true" className="font-main-ui-action text-accent tabular-nums">
              {twoDigits(index + 1)}
            </p>
            <h3 data-reveal-title="" className="mt-4 font-stage-title text-content-primary">
              {stage.title}
            </h3>
            <p
              data-reveal-words=""
              className="mt-6 max-w-[24ch] font-statement text-content-primary"
            >
              {stage.description}
            </p>
          </li>
        ))}
      </ol>
    </div>
  );
}

export { IngestionStory };

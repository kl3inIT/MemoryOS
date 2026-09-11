import { gsap } from "@/motion/motion";

type Effect = (element: HTMLElement) => { from: gsap.TweenVars; to?: gsap.TweenVars };

const secondsPerTypedCharacter = 0.035;

/*
 * Entrances a mock can declare on its elements. Elements rest in their final state in the markup,
 * so every effect animates from a start state back to it; `to` is only needed where the final
 * state cannot be read back from the computed style.
 */
const effects = {
  fade: () => ({ from: { opacity: 0 } }),
  rise: () => ({ from: { opacity: 0, y: 10 } }),
  slide: () => ({ from: { opacity: 0, x: -10 } }),
  arrive: () => ({ from: { opacity: 0, x: 16 } }),
  drop: () => ({ from: { opacity: 0, y: -14, ease: "back.out(2)" } }),
  pop: () => ({ from: { scale: 0, duration: 0.3, ease: "back.out(2.5)" } }),
  stamp: () => ({ from: { opacity: 0, scale: 1.8, duration: 0.35, ease: "back.out(3)" } }),
  growX: () => ({ from: { scaleX: 0, duration: 0.5 } }),
  growY: () => ({ from: { scaleY: 0, duration: 0.6, ease: "power3.out" } }),
  type: (element) => {
    const characters = element.textContent?.length || 1;
    return {
      from: { clipPath: "inset(0% 100% 0% 0%)" },
      to: {
        clipPath: "inset(0% 0% 0% 0%)",
        duration: characters * secondsPerTypedCharacter,
        ease: `steps(${characters})`,
      },
    };
  },
  mark: () => ({
    from: { backgroundSize: "0% 100%" },
    to: { backgroundSize: "100% 100%", duration: 0.7, ease: "power1.inOut" },
  }),
} satisfies Record<string, Effect>;

type MockEffect = keyof typeof effects;

// Data attributes that give a mock element an entrance; without `at` it starts 0.12 s after the
// element before it.
function enter(effect: MockEffect, at?: number, duration?: number) {
  return { "data-enter": effect, "data-at": at, "data-duration": duration };
}

function isEffect(name: string | undefined): name is MockEffect {
  return name !== undefined && Object.hasOwn(effects, name);
}

// A paused timeline that plays every declared entrance inside `root` in document order.
function animateMock(root: HTMLElement) {
  const timeline = gsap.timeline({ paused: true, defaults: { duration: 0.4, ease: "power2.out" } });
  for (const element of root.querySelectorAll<HTMLElement>("[data-enter]")) {
    const { enter: name, at, duration } = element.dataset;
    if (!isEffect(name)) {
      continue;
    }
    const effect: Effect = effects[name];
    const { from, to } = effect(element);
    const position = at === undefined ? "<0.12" : Number(at);
    const timing = duration === undefined ? {} : { duration: Number(duration) };
    if (to) {
      timeline.fromTo(element, from, { ...to, ...timing }, position);
    } else {
      timeline.from(element, { ...from, ...timing }, position);
    }
  }
  return timeline;
}

export { animateMock, enter };

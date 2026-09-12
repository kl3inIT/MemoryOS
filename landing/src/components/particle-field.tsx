import { useEffect, useRef } from "react";
import { cn } from "@/lib/utils";

type Particle = {
  x: number;
  y: number;
  vx: number;
  vy: number;
  age: number;
  lifetime: number;
  radius: number;
};

type ParticleFieldProps = {
  className?: string;
};

const reducedMotionQuery = "(prefers-reduced-motion: reduce)";
const maxPixelRatio = 2;
const maxParticles = 150;
const areaPerParticle = 6500;
// Forces are tuned per 60 Hz frame and scaled by the real frame time.
const frameMs = 1000 / 60;
const pull = 0.012;
const swirl = 0.65;
const damping = 0.985;
const pointerRadius = 110;
const pointerPush = 0.35;
const arrivalRadius = 26;
const fadeInFrames = 60;
const fadeOutRadius = 170;
const baseAlpha = 0.6;

function random(min: number, max: number) {
  return min + Math.random() * (max - min);
}

/*
 * Particles drift in curved paths toward the field's focal point and fade as they arrive: company
 * knowledge gathering into one memory. The pointer pushes them aside. The focal point comes from the
 * `--field-x`/`--field-y` custom properties (0–1 of the box) and the colour from `--particle`, so
 * breakpoints and themes stay in CSS. With reduced motion the field draws a single still frame.
 */
function ParticleField({ className }: ParticleFieldProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    const context = canvas?.getContext("2d");
    if (!canvas || !context) {
      return;
    }

    const still = window.matchMedia(reducedMotionQuery).matches;
    const pointer = { x: Number.NEGATIVE_INFINITY, y: Number.NEGATIVE_INFINITY };
    const focus = { x: 0, y: 0 };
    let particles: Particle[] = [];
    let width = 0;
    let height = 0;
    let color = "";
    let frame = 0;
    let lastTime = 0;
    let visible = false;

    const readStyle = () => {
      const style = getComputedStyle(canvas);
      focus.x = (Number.parseFloat(style.getPropertyValue("--field-x")) || 0.5) * width;
      focus.y = (Number.parseFloat(style.getPropertyValue("--field-y")) || 0.5) * height;
      color = style.getPropertyValue("--particle").trim();
    };

    // New particles enter from the edges; the first ones fill the whole box.
    const spawn = (particle: Particle, anywhere: boolean) => {
      if (anywhere) {
        particle.x = random(0, width);
        particle.y = random(0, height);
      } else if (Math.random() < width / (width + height)) {
        particle.x = random(0, width);
        particle.y = Math.random() < 0.5 ? 0 : height;
      } else {
        particle.x = Math.random() < 0.5 ? 0 : width;
        particle.y = random(0, height);
      }
      particle.vx = random(-0.3, 0.3);
      particle.vy = random(-0.3, 0.3);
      particle.age = anywhere ? random(0, fadeInFrames) : 0;
      particle.lifetime = random(600, 1400);
      particle.radius = random(0.6, 1.6);
      return particle;
    };

    const resize = () => {
      const ratio = Math.min(window.devicePixelRatio || 1, maxPixelRatio);
      width = canvas.clientWidth;
      height = canvas.clientHeight;
      canvas.width = Math.round(width * ratio);
      canvas.height = Math.round(height * ratio);
      context.setTransform(ratio, 0, 0, ratio, 0, 0);
      readStyle();

      const count = Math.min(maxParticles, Math.round((width * height) / areaPerParticle));
      particles = particles.slice(0, count);
      while (particles.length < count) {
        particles.push(spawn({} as Particle, true));
      }
    };

    const step = (particle: Particle, frames: number) => {
      const dx = focus.x - particle.x;
      const dy = focus.y - particle.y;
      const distance = Math.hypot(dx, dy) || 1;
      // Pull toward the focus with a sideways component, so paths curve in.
      particle.vx += ((dx - dy * swirl) / distance) * pull * frames;
      particle.vy += ((dy + dx * swirl) / distance) * pull * frames;

      const px = particle.x - pointer.x;
      const py = particle.y - pointer.y;
      const pointerDistance = Math.hypot(px, py);
      if (pointerDistance > 0 && pointerDistance < pointerRadius) {
        const push = (1 - pointerDistance / pointerRadius) * pointerPush * frames;
        particle.vx += (px / pointerDistance) * push;
        particle.vy += (py / pointerDistance) * push;
      }

      const drag = damping ** frames;
      particle.vx *= drag;
      particle.vy *= drag;
      particle.x += particle.vx * frames;
      particle.y += particle.vy * frames;
      particle.age += frames;

      if (distance < arrivalRadius || particle.age > particle.lifetime) {
        spawn(particle, false);
      }
    };

    const draw = () => {
      context.clearRect(0, 0, width, height);
      context.fillStyle = color;
      for (const particle of particles) {
        const distance = Math.hypot(focus.x - particle.x, focus.y - particle.y);
        context.globalAlpha =
          baseAlpha *
          Math.min(1, particle.age / fadeInFrames) *
          Math.min(1, distance / fadeOutRadius);
        context.beginPath();
        context.arc(particle.x, particle.y, particle.radius, 0, Math.PI * 2);
        context.fill();
      }
      context.globalAlpha = 1;
    };

    const tick = (time: number) => {
      const frames = lastTime ? Math.min((time - lastTime) / frameMs, 3) : 1;
      lastTime = time;
      for (const particle of particles) {
        step(particle, frames);
      }
      draw();
      frame = requestAnimationFrame(tick);
    };

    const start = () => {
      if (!still && visible && !document.hidden && !frame) {
        lastTime = 0;
        frame = requestAnimationFrame(tick);
      }
    };

    const stop = () => {
      cancelAnimationFrame(frame);
      frame = 0;
    };

    const onPointerMove = (event: PointerEvent) => {
      const bounds = canvas.getBoundingClientRect();
      pointer.x = event.clientX - bounds.left;
      pointer.y = event.clientY - bounds.top;
    };

    const onVisibilityChange = () => (document.hidden ? stop() : start());

    const resizeObserver = new ResizeObserver(() => {
      resize();
      draw();
    });
    resizeObserver.observe(canvas);

    const visibilityObserver = new IntersectionObserver(([entry]) => {
      visible = entry?.isIntersecting ?? false;
      if (visible) {
        start();
      } else {
        stop();
      }
    });
    visibilityObserver.observe(canvas);

    // A theme switch changes --particle.
    const themeObserver = new MutationObserver(() => {
      readStyle();
      draw();
    });
    themeObserver.observe(document.documentElement, { attributeFilter: ["class"] });

    window.addEventListener("pointermove", onPointerMove, { passive: true });
    document.addEventListener("visibilitychange", onVisibilityChange);

    return () => {
      stop();
      resizeObserver.disconnect();
      visibilityObserver.disconnect();
      themeObserver.disconnect();
      window.removeEventListener("pointermove", onPointerMove);
      document.removeEventListener("visibilitychange", onVisibilityChange);
    };
  }, []);

  return (
    <canvas
      ref={canvasRef}
      aria-hidden="true"
      className={cn("pointer-events-none absolute inset-0 -z-10 size-full", className)}
    />
  );
}

export { ParticleField };

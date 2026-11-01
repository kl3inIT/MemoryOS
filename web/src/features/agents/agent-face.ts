import { Avatar, Style } from "@dicebear/core";
import thumbs from "@dicebear/styles/thumbs.json";

/** The DiceBear `thumbs` style (CC0), validated once; every agent face is drawn from it (MEM-273). */
const style = new Style(thumbs);
const faces = new Map<string, string>();

/** The face for a seed as an SVG data URI; the same seed always draws the same face. */
export function agentFace(seed: string) {
  let face = faces.get(seed);
  if (face === undefined) {
    face = new Avatar(style, { seed }).toDataUri();
    faces.set(seed, face);
  }
  return face;
}

/** A new random seed, in the characters the server accepts (`[A-Za-z0-9_-]`, at most 64). */
export function randomAvatarSeed() {
  return crypto.randomUUID();
}

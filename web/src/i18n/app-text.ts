/** Static application copy with inert values; never use user content as a translation key. */
export type AppText = {
  app: string;
  values?: Record<string, string | number | AppText>;
};
export type AppCopy = string | AppText;
export function appText(app: AppCopy, values?: AppText["values"]): AppText {
  return typeof app === "string" ? { app, values } : app;
}

import { createCn } from "cn/engine";
import tables from "./cn-tables";

/**
 * shadcn's class merge (clsx and tailwind-merge semantics) running on tables `cn build` compiles from the
 * classes this app uses, the theme in src/index.css and cn.config.mjs. The Vite plugin regenerates them.
 */
export const cn = createCn(tables);

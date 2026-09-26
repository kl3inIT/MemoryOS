import { createCn } from "cn/config";

/**
 * shadcn's class merge (clsx and tailwind-merge semantics in one compiled engine), taught the theme's
 * typography utilities: each sets the font size and line height, so a later `text-*` size replaces it
 * and it replaces an earlier one.
 */
export const cn = createCn({
  extend: {
    classGroups: {
      "font-size": [
        {
          font: [
            "heading-h1",
            "heading-h2",
            "heading-h3",
            "main-content-body",
            "main-ui-body",
            "main-ui-action",
            "secondary-body",
            "secondary-action",
            "figure-small-label",
          ],
        },
      ],
    },
  },
});

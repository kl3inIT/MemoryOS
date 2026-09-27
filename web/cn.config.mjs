// Merge rules the stylesheet cannot express: the theme typography utilities (`@utility font-*` in
// src/styles/theme.css) each set the font size and line height, so they share the font-size group.
export default {
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
};

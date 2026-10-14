package vn.edu.swd392.vpmcp.tools;

/** JSON schemas for the design-QA tools (optional params and JSON repair arrays). */
final class DesignQaSchemas {
  private DesignQaSchemas() {}

  private static final String DIAGRAM_NAME =
      "\"diagramName\": {\"type\": \"string\", \"description\":"
          + " \"Exact unique name of the target Visual Paradigm diagram\"}";

  private static final String TOLERANCES =
      "\"alignmentTolerance\": {\"type\": \"integer\", \"description\":"
          + " \"Max px drift for row/column alignment (default 3)\"},"
          + " \"spacingTolerance\": {\"type\": \"integer\", \"description\":"
          + " \"Max px deviation from a consistent sibling gap (default 4)\"},"
          + " \"minEdgeMargin\": {\"type\": \"integer\", \"description\":"
          + " \"Minimum px margin from the canvas origin/extent (default 12)\"},"
          + " \"fontTolerance\": {\"type\": \"integer\", \"description\":"
          + " \"Max point-size variance inside one semantic group (default 1)\"}";

  static final String INSPECT =
      "{\"type\":\"object\",\"properties\":{"
          + DIAGRAM_NAME
          + "},\"required\":[\"diagramName\"],\"additionalProperties\":false}";

  static final String VALIDATE =
      "{\"type\":\"object\",\"properties\":{"
          + DIAGRAM_NAME
          + ","
          + TOLERANCES
          + "},\"required\":[\"diagramName\"],\"additionalProperties\":false}";

  static final String RENDER =
      "{\"type\":\"object\",\"properties\":{"
          + DIAGRAM_NAME
          + ",\"outputPath\": {\"type\": \"string\", \"description\":"
          + " \"Absolute .png path for the rendered preview\"},"
          + "\"scalePercent\": {\"type\": \"integer\", \"description\":"
          + " \"Export scale 25-400 (default 100)\"}"
          + "},\"required\":[\"diagramName\",\"outputPath\"],\"additionalProperties\":false}";

  static final String REVIEW =
      "{\"type\":\"object\",\"properties\":{"
          + DIAGRAM_NAME
          + ",\"outputPath\": {\"type\": \"string\", \"description\":"
          + " \"Absolute .png path for the render pass; temp file when omitted\"},"
          + "\"scalePercent\": {\"type\": \"integer\", \"description\":"
          + " \"Export scale 25-400 (default 100)\"},"
          + TOLERANCES
          + "},\"required\":[\"diagramName\"],\"additionalProperties\":false}";

  static final String REPAIR =
      "{\"type\":\"object\",\"properties\":{"
          + DIAGRAM_NAME
          + ",\"repairs\": {\"type\": \"array\", \"description\":"
          + " \"Ordered repair steps: {elementId, action: MOVE|RESIZE|SET_BOUNDS,"
          + " RESET_CAPTION|FIT_SIZE, x, y, width, height}\"},"
          + "\"skipRevalidate\": {\"type\": \"boolean\", \"description\":"
          + " \"Skip the automatic post-repair geometry check (default false)\"}"
          + "},\"required\":[\"diagramName\",\"repairs\"],\"additionalProperties\":false}";

  static final String VERIFY =
      "{\"type\":\"object\",\"properties\":{"
          + DIAGRAM_NAME
          + ",\"outputPath\": {\"type\": \"string\", \"description\":"
          + " \"Absolute .png path to keep the verification render; temp file when omitted\"},"
          + "\"scalePercent\": {\"type\": \"integer\", \"description\":"
          + " \"Export scale 25-400 (default 100)\"},"
          + "\"blockMedium\": {\"type\": \"boolean\", \"description\":"
          + " \"Treat MEDIUM issues as blocking (default false)\"},"
          + TOLERANCES
          + "},\"required\":[\"diagramName\"],\"additionalProperties\":false}";

  static final String FINALIZE =
      "{\"type\":\"object\",\"properties\":{"
          + DIAGRAM_NAME
          + "},\"required\":[\"diagramName\"],\"additionalProperties\":false}";

  static final String STATUS =
      "{\"type\":\"object\",\"properties\":{"
          + DIAGRAM_NAME
          + "},\"required\":[\"diagramName\"],\"additionalProperties\":false}";
}

package vn.edu.swd392.vpmcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.vp.plugin.ExportDiagramAsImageOption;
import com.vp.plugin.ExportDiagramImageMargin;
import com.vp.plugin.diagram.ICaptionUIModel;
import com.vp.plugin.diagram.IConnectorUIModel;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.diagram.IShapeUIModel;
import com.vp.plugin.model.IModelElement;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import vn.edu.swd392.vpmcp.bridge.BridgeException;
import vn.edu.swd392.vpmcp.bridge.DesignQaRegistry;
import vn.edu.swd392.vpmcp.bridge.Tool;

/**
 * Closed-loop layout QA for every diagram type. Enforces
 * "no design output without post-generation verification":
 * mutations mark a diagram dirty, verification must re-read real geometry,
 * render the rasterized output, and only a clean verification epoch may be
 * finalized. See DesignQaRegistry for the state machine.
 */
public final class DesignQaTools extends VpAccess {
  private static final int DEFAULT_ALIGNMENT_TOLERANCE = 3;
  private static final int DEFAULT_SPACING_TOLERANCE = 4;
  private static final int DEFAULT_FONT_TOLERANCE = 1;
  private static final int DEFAULT_EDGE_MARGIN = 12;
  /** Minimum text-fit budget px subtracted from a shape before its text must fit. */
  private static final int TEXT_INSET = 6;
  /** Shape-shape overlap area ratio (of the smaller shape) treated as blocking. */
  private static final double OVERLAP_HIGH_RATIO = 0.25;
  private static final FontRenderContext FONT_CONTEXT = new FontRenderContext(null, true, true);

  /** Shape types whose geometry the diagram engine manages; never layout defects. */
  private static final Set<String> ENGINE_MANAGED =
      Set.of("Activation", "InteractionOperand", "InteractionLifeLineLink",
          "LostFoundMessageEnd");

  /** Sequence containers that legitimately overlay lifelines. */
  private static final Set<String> SEQUENCE_CONTAINERS =
      Set.of("CombinedFragment", "InteractionOperand");

  /** Lifeline-like shapes that sit under sequence containers. */
  private static final Set<String> SEQUENCE_LIFELINES =
      Set.of("InteractionLifeLine", "InteractionActor", "Actor", "CommunicationLifeLine");

  /** Shapes a connector may legitimately cross (lifelines, fragments,
   *  activations, system boundaries, package containers). */
  private static final Set<String> SEQUENCE_CROSSABLE =
      Set.of("InteractionLifeLine", "InteractionActor", "Actor",
          "CombinedFragment", "InteractionOperand", "Activation",
          "System", "SystemBoundary", "Subject", "Package", "Subsystem",
          "Component", "Node");

  private static boolean engineManaged(ElementInfo element) {
    return ENGINE_MANAGED.contains(element.shapeType);
  }

  /** Container-over-lifeline pairs are canonical sequence-diagram structure. */
  private static boolean sequenceOverlayPair(ElementInfo first, ElementInfo second) {
    return SEQUENCE_CONTAINERS.contains(first.shapeType)
            && SEQUENCE_LIFELINES.contains(second.shapeType)
        || SEQUENCE_CONTAINERS.contains(second.shapeType)
            && SEQUENCE_LIFELINES.contains(first.shapeType);
  }

  private static boolean sequenceCrossable(ElementInfo element) {
    return SEQUENCE_CROSSABLE.contains(element.shapeType);
  }

  // ------------------------------------------------------------------ inspect

  @Tool(
      description =
          "Read back the ACTUAL geometry of every element on a diagram: id, parent, bounds,"
              + " connectors with endpoints and captions, font size, model type and name."
              + " Never trust coordinates you requested earlier - this is the real post-layout"
              + " state. Run this before validateDesignGeometry and after every repair.",
      inputSchema = DesignQaSchemas.INSPECT,
      readOnly = true)
  public Map<String, Object> inspectDesignGeometry(String diagramName) throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          List<ElementInfo> elements = collect(diagram);
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("diagramName", diagram.getName());
          result.put("diagramType", diagram.getType());
          result.put("canvasWidth", diagram.getWidth());
          result.put("canvasHeight", diagram.getHeight());
          result.put("elementCount", elements.size());
          List<Map<String, Object>> items = new ArrayList<>();
          for (ElementInfo element : elements) {
            items.add(element.toJson());
          }
          result.put("elements", items);
          return result;
        });
  }

  // ------------------------------------------------------------------ validate

  @Tool(
      description =
          "Geometry checks over ACTUAL diagram state: shape/caption overlap, connector-through-shape,"
              + " dangling connectors, containment and canvas overflow, edge margin, row/column"
              + " alignment drift, sibling spacing consistency, same-type size/font consistency, and"
              + " text overflow measured with real font metrics. Returns structured issues with"
              + " severity and suggestedFix. Counts as one QA pass.",
      inputSchema = DesignQaSchemas.VALIDATE,
      readOnly = true)
  public Map<String, Object> validateDesignGeometry(
      String diagramName,
      int alignmentTolerance,
      int spacingTolerance,
      int minEdgeMargin,
      int fontTolerance)
      throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          List<ElementInfo> elements = collect(diagram);
          Tolerances tolerance =
              new Tolerances(alignmentTolerance, spacingTolerance, minEdgeMargin, fontTolerance);
          List<Issue> issues = runGeometryChecks(diagram, elements, tolerance);
          notePass(diagramName, issues, false);
          return validateResult(issues, false);
        });
  }

  // ------------------------------------------------------------------ render

  @Tool(
      description =
          "Render a diagram to PNG and analyze the rasterized output: ink bounding box, blank"
              + " ratio, ink at image edge. This is the render verification step - geometry alone"
              + " is not proof. Prefer verifyDesign or reviewDesign which include this.",
      inputSchema = DesignQaSchemas.RENDER,
      readOnly = true)
  public Map<String, Object> renderDesign(String diagramName, String outputPath, int scalePercent)
      throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          Path target = resolveRenderPath(diagramName, outputPath);
          int scale = normalizeScale(scalePercent);
          exportPng(diagram, target.toFile(), scale);
          RasterAnalysis raster = analyzeRaster(target.toFile());
          DesignQaRegistry.get().stateFor(diagramName).noteRenderOnly();
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("diagramName", diagram.getName());
          result.put("outputPath", target.toString());
          result.putAll(raster.toJson());
          return result;
        });
  }

  // ------------------------------------------------------------------ review

  @Tool(
      description =
          "Full independent review pass: geometry checks + PNG render + raster analysis, merged"
              + " into one structured issue list. This is the REVIEWER step - run it on the real"
              + " diagram state after generation and after repairs.",
      inputSchema = DesignQaSchemas.REVIEW,
      readOnly = true)
  public Map<String, Object> reviewDesign(
      String diagramName,
      String outputPath,
      int scalePercent,
      int alignmentTolerance,
      int spacingTolerance,
      int minEdgeMargin,
      int fontTolerance)
      throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          List<ElementInfo> elements = collect(diagram);
          Tolerances tolerance =
              new Tolerances(alignmentTolerance, spacingTolerance, minEdgeMargin, fontTolerance);
          List<Issue> issues = runGeometryChecks(diagram, elements, tolerance);
          Path target = resolveRenderPath(diagramName, outputPath);
          int scale = normalizeScale(scalePercent);
          exportPng(diagram, target.toFile(), scale);
          RasterAnalysis raster = analyzeRaster(target.toFile());
          raster.addIssues(issues);
          notePass(diagramName, issues, true);
          Map<String, Object> result = validateResult(issues, true);
          result.put("outputPath", target.toString());
          result.putAll(raster.toJson());
          return result;
        });
  }

  // ------------------------------------------------------------------ repair

  @Tool(
      description =
          "Apply an ordered repair plan: [{elementId, action: MOVE|RESIZE|SET_BOUNDS|FIT_SIZE|"
              + "RESET_CAPTION, x, y, width, height}]. Only repairs you computed from real issues"
              + " are allowed - no random nudging. After applying, the affected checks re-run"
              + " automatically (skipRevalidate to disable) so a fix that creates new overlap or"
              + " overflow is caught immediately. Repeated ineffective repairs are reported as"
              + " stalled and must switch strategy (resize -> layout change, wrap, typography).",
      inputSchema = DesignQaSchemas.REPAIR)
  public Map<String, Object> repairDesign(
      String diagramName, JsonNode repairs, boolean skipRevalidate) throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          if (repairs == null || !repairs.isArray() || repairs.isEmpty()) {
            throw BridgeException.invalidArgument("$.repairs", "repairs must be a non-empty array");
          }
          List<Map<String, Object>> applied = new ArrayList<>();
          List<String> failures = new ArrayList<>();
          for (JsonNode step : repairs) {
            try {
              applied.add(applyRepair(diagram, step));
            } catch (RuntimeException exception) {
              failures.add(
                  "step " + applied.size() + failures.size() + ": " + exception.getMessage());
            }
          }
          DesignQaRegistry.get().stateFor(diagramName).markMutated();
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("applied", applied);
          result.put("failed", failures);
          if (!skipRevalidate && failures.isEmpty()) {
            List<ElementInfo> elements = collect(diagram);
            List<Issue> issues =
                runGeometryChecks(diagram, elements, Tolerances.defaults());
            DesignQaRegistry.PassOutcome outcome = notePass(diagramName, issues, false);
            result.put("postCheck", validateResult(issues, false));
            result.put("repairEffect", repairEffect(outcome, issues.size()));
          } else {
            result.put(
                "postCheck",
                "skipped - run validateDesignGeometry or verifyDesign before finalizeDesign");
          }
          return result;
        });
  }

  // ------------------------------------------------------------------ verify / finalize

  @Tool(
      description =
          "The quality gate. Runs one full iteration: inspect actual geometry -> all geometry"
              + " checks -> render PNG -> raster analysis -> merge issues -> evaluate gate."
              + " Returns VERIFIED only when no CRITICAL/HIGH issue remains (MEDIUM also blocked"
              + " with blockMedium=true) and the minimum review budget is satisfied. Refuses a"
              + " pass budget beyond MAX_ITERATIONS so the loop cannot spin forever; a stalled"
              + " issue signature is reported so you switch repair strategy. Call finalizeDesign"
              + " only after this returns VERIFIED.",
      inputSchema = DesignQaSchemas.VERIFY,
      readOnly = true)
  public Map<String, Object> verifyDesign(
      String diagramName,
      String outputPath,
      int scalePercent,
      boolean blockMedium,
      int alignmentTolerance,
      int spacingTolerance,
      int minEdgeMargin,
      int fontTolerance)
      throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          DesignQaRegistry.QaState state = DesignQaRegistry.get().stateFor(diagramName);
          List<ElementInfo> elements = collect(diagram);
          Tolerances tolerance =
              new Tolerances(alignmentTolerance, spacingTolerance, minEdgeMargin, fontTolerance);
          List<Issue> issues = runGeometryChecks(diagram, elements, tolerance);
          Path target = resolveRenderPath(diagramName, outputPath);
          int scale = normalizeScale(scalePercent);
          exportPng(diagram, target.toFile(), scale);
          RasterAnalysis raster = analyzeRaster(target.toFile());
          raster.addIssues(issues);
          DesignQaRegistry.PassOutcome outcome = notePass(diagramName, issues, true);
          Map<String, Object> result = evaluateGate(state, issues, outcome, blockMedium);
          result.put("outputPath", target.toString());
          result.put("geometryCheck", issues.isEmpty() ? "PASS" : "WARN");
          result.putAll(raster.toJson());
          return result;
        });
  }

  @Tool(
      description =
          "Final gatekeeper: marks a design DONE only if verifyDesign returned VERIFIED on the"
              + " current geometry (any mutation since then blocks). GENERATED -> DONE without"
              + " verification is not possible; a blocked finalize returns status BLOCKED with the"
              + " exact remaining requirements instead of silently passing.",
      inputSchema = DesignQaSchemas.FINALIZE,
      readOnly = true)
  public Map<String, Object> finalizeDesign(String diagramName) throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          DesignQaRegistry.QaState state = DesignQaRegistry.get().stateFor(diagramName);
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("diagramName", diagram.getName());
          result.put("passes", state.passes());
          result.put("renderPasses", state.renderPasses());
          if (!state.isVerified()) {
            result.put("status", "BLOCKED");
            result.put(
                "blocker",
                "No successful verifyDesign on current geometry"
                    + (state.passes() == 0
                        ? " (no QA pass recorded after last mutation)"
                        : " (design mutated after last verification, or issues still open)"));
            result.put(
                "next",
                state.passes() == 0
                    ? "call verifyDesign"
                    : "fix remaining issues then verifyDesign again");
            return result;
          }
          result.put("status", "DONE");
          return result;
        });
  }

  @Tool(
      description =
          "Report the QA state machine position for one diagram: epoch, passes, render passes,"
              + " verified, dirty. Useful to confirm whether edits since the last verifyDesign"
              + " invalidated verification.",
      inputSchema = DesignQaSchemas.STATUS,
      readOnly = true)
  public Map<String, Object> designQaStatus(String diagramName) throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          DesignQaRegistry.QaState state = DesignQaRegistry.get().stateFor(diagramName);
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("diagramName", diagram.getName());
          result.put("epoch", state.epoch());
          result.put("passes", state.passes());
          result.put("renderPasses", state.renderPasses());
          result.put("verified", state.isVerified());
          result.put("minReviewPasses", DesignQaRegistry.MIN_REVIEW_PASSES);
          result.put("maxIterations", DesignQaRegistry.MAX_ITERATIONS);
          return result;
        });
  }

  // ------------------------------------------------------------------ geometry

  private List<ElementInfo> collect(IDiagramUIModel diagram) {
    List<ElementInfo> result = new ArrayList<>();
    IDiagramElement[] items = diagram.toDiagramElementArray();
    if (items == null) {
      return result;
    }
    for (IDiagramElement item : items) {
      if (item != null) {
        collectElement(diagram, item, null, result);
      }
    }
    return result;
  }

  private void collectElement(
      IDiagramUIModel diagram, IDiagramElement element, String parentId,
      List<ElementInfo> result) {
    result.add(new ElementInfo(diagram, element, parentId));
    String id = element.getId();
    IShapeUIModel[] children = element.toChildArray();
    if (children == null) {
      return;
    }
    for (IShapeUIModel child : children) {
      if (child != null) {
        collectElement(diagram, child, id, result);
      }
    }
  }

  // ------------------------------------------------------------------ checks

  private List<Issue> runGeometryChecks(
      IDiagramUIModel diagram, List<ElementInfo> elements, Tolerances tolerance) {
    List<Issue> issues = new ArrayList<>();
    String[] checks = {
      "overlap", "dangling", "throughShape", "containment",
      "canvasMargin", "alignment", "spacing", "consistency", "textOverflow"
    };
    Runnable[] steps = {
      () -> checkOverlap(elements, issues),
      () -> checkDanglingConnectors(elements, issues),
      () -> checkConnectorThroughShape(elements, issues),
      () -> checkContainmentOverflow(elements, issues),
      () -> checkCanvasAndEdgeMargin(diagram, elements, tolerance, issues),
      () -> checkAlignment(elements, tolerance, issues),
      () -> checkSpacing(elements, tolerance, issues),
      () -> checkConsistency(elements, tolerance, issues),
      () -> checkTextOverflow(elements, issues)
    };
    for (int index = 0; index < steps.length; index++) {
      try {
        steps[index].run();
      } catch (RuntimeException exception) {
        throw new IllegalStateException(
            "Design QA check '" + checks[index] + "' failed: " + exception, exception);
      }
    }
    return issues;
  }

  private static void checkOverlap(List<ElementInfo> elements, List<Issue> issues) {
    List<ElementInfo> shapes = new ArrayList<>();
    List<ElementInfo> captions = new ArrayList<>();
    for (ElementInfo element : elements) {
      if (element.connector) {
        if (element.captionBounds != null) {
          captions.add(element);
        }
      } else {
        shapes.add(element);
      }
    }
    for (int a = 0; a < shapes.size(); a++) {
      ElementInfo first = shapes.get(a);
      if (engineManaged(first)) {
        continue;
      }
      for (int b = a + 1; b < shapes.size(); b++) {
        ElementInfo second = shapes.get(b);
        if (engineManaged(second)) {
          continue;
        }
        // Parent/child containment is intentional nesting, not an overlap defect.
        if (first.id.equals(second.parentId) || second.id.equals(first.parentId)
            || sequenceOverlayPair(first, second)) {
          continue;
        }
        Rectangle hit = first.bounds.intersection(second.bounds);
        if (hit.isEmpty()) {
          continue;
        }
        double smaller = Math.min(area(first.bounds), area(second.bounds));
        double ratio = smaller <= 0 ? 1.0 : (hit.width * (double) hit.height) / smaller;
        // One shape fully covering the other is almost always an intentional
        // background/container, not an accidental overlap.
        if (first.bounds.contains(second.bounds) || second.bounds.contains(first.bounds)) {
          continue;
        }
        issues.add(
            Issue.of(
                ratio >= OVERLAP_HIGH_RATIO ? "HIGH" : "MEDIUM",
                "SHAPE_OVERLAP",
                first.id,
                "Overlaps " + second.name + " (" + (int) (ratio * 100) + "% of smaller shape)",
                List.of(first.id, second.id),
                Map.of(
                    "action", "MOVE",
                    "hint", "separate shapes horizontally or vertically"),
                List.of("overlapRatio", ratio)));
      }
    }
    for (ElementInfo caption : captions) {
      for (ElementInfo shape : shapes) {
        if (!caption.captionBounds.intersects(shape.bounds)) {
          continue;
        }
        // A caption attached to the connector's own endpoint vicinity is expected;
        // flag only labels that sit on an unrelated shape.
        if (caption.fromId != null && caption.fromId.equals(shape.id)
            || caption.toId != null && caption.toId.equals(shape.id)
            || sequenceCrossable(shape) || engineManaged(shape)) {
          continue;
        }
        issues.add(
            Issue.of(
                "MEDIUM",
                "CAPTION_OVERLAP",
                caption.id,
                "Connector label '" + caption.name + "' overlaps shape " + shape.name,
                List.of(caption.id, shape.id),
                Map.of("action", "MOVE", "hint", "reroute or move caption off the shape"),
                List.of()));
      }
      for (ElementInfo other : captions) {
        if (caption.id.compareTo(other.id) >= 0
            || !caption.captionBounds.intersects(other.captionBounds)) {
          continue;
        }
        issues.add(
            Issue.of(
                "MEDIUM",
                "CAPTION_OVERLAP",
                caption.id,
                "Connector labels '" + caption.name + "' and '" + other.name + "' overlap",
                List.of(caption.id, other.id),
                Map.of("action", "MOVE", "hint", "separate the two connector captions"),
                List.of()));
      }
    }
  }

  private static void checkDanglingConnectors(
      List<ElementInfo> elements, List<Issue> issues) {
    for (ElementInfo element : elements) {
      if (!element.connector) {
        continue;
      }
      if (element.fromId == null || element.toId == null) {
        issues.add(
            Issue.of(
                "HIGH",
                "DANGLING_CONNECTOR",
                element.id,
                "Connector '" + element.name + "' is missing an endpoint",
                List.of(element.id),
                Map.of("action", "REATTACH", "hint", "connect both ends to shapes"),
                List.of()));
      }
    }
  }

  private static void checkConnectorThroughShape(
      List<ElementInfo> elements, List<Issue> issues) {
    List<ElementInfo> shapes = new ArrayList<>();
    for (ElementInfo element : elements) {
      if (!element.connector) {
        shapes.add(element);
      }
    }
    for (ElementInfo connector : elements) {
      if (!connector.connector || connector.points.length < 2) {
        continue;
      }
      for (ElementInfo shape : shapes) {
        if (shape.id.equals(connector.fromId)
            || shape.id.equals(connector.toId)
            || shape.id.equals(connector.parentId)
            || sequenceCrossable(shape)
            || engineManaged(shape)) {
          continue;
        }
        Rectangle interior = shrink(shape.bounds, 4);
        if (interior.isEmpty()) {
          continue;
        }
        for (int segment = 0; segment + 1 < connector.points.length; segment++) {
          if (interior.intersectsLine(
              connector.points[segment].getX(),
              connector.points[segment].getY(),
              connector.points[segment + 1].getX(),
              connector.points[segment + 1].getY())) {
            issues.add(
                Issue.of(
                    "MEDIUM",
                    "CONNECTOR_THROUGH_SHAPE",
                    connector.id,
                    "Connector '" + connector.name + "' passes through " + shape.name,
                    List.of(connector.id, shape.id),
                    Map.of("action", "REROUTE", "hint", "route the connector around the shape"),
                    List.of()));
            break;
          }
        }
      }
    }
  }

  private static void checkContainmentOverflow(
      List<ElementInfo> elements, List<Issue> issues) {
    Map<String, ElementInfo> byId = new HashMap<>();
    for (ElementInfo element : elements) {
      byId.put(element.id, element);
    }
    for (ElementInfo element : elements) {
      if (element.parentId == null) {
        continue;
      }
      ElementInfo parent = byId.get(element.parentId);
      if (parent == null || parent.bounds.contains(element.bounds)) {
        continue;
      }
      issues.add(
          Issue.of(
              "HIGH",
              "CONTAINMENT_OVERFLOW",
              element.id,
              "'" + element.name + "' escapes parent '" + parent.name + "'",
              List.of(element.id, parent.id),
              Map.of("action", "RESIZE", "hint", "grow the parent or move the child inside"),
              List.of()));
    }
  }

  private void checkCanvasAndEdgeMargin(
      IDiagramUIModel diagram,
      List<ElementInfo> elements,
      Tolerances tolerance,
      List<Issue> issues) {
    int canvasWidth = diagram.getWidth();
    int canvasHeight = diagram.getHeight();
    for (ElementInfo element : elements) {
      if (element.connector) {
        continue;
      }
      Rectangle bounds = element.bounds;
      if (bounds.x < 0 || bounds.y < 0) {
        issues.add(
            Issue.of(
                "HIGH",
                "CANVAS_OVERFLOW",
                element.id,
                "'" + element.name + "' has negative position " + bounds.x + "," + bounds.y,
                List.of(element.id),
                Map.of("action", "MOVE", "hint", "move inside the canvas"),
                List.of()));
      } else if (bounds.x < tolerance.edgeMargin || bounds.y < tolerance.edgeMargin) {
        issues.add(
            Issue.of(
                "MEDIUM",
                "EDGE_MARGIN",
                element.id,
                "'" + element.name + "' is " + Math.min(bounds.x, bounds.y)
                    + "px from the canvas origin (min " + tolerance.edgeMargin + ")",
                List.of(element.id),
                Map.of("action", "MOVE", "hint", "keep at least " + tolerance.edgeMargin + "px margin"),
                List.of("margin", Math.min(bounds.x, bounds.y))));
      }
      if (canvasWidth > 0 && bounds.x + bounds.width > canvasWidth
          || canvasHeight > 0 && bounds.y + bounds.height > canvasHeight) {
        issues.add(
            Issue.of(
                "HIGH",
                "CANVAS_OVERFLOW",
                element.id,
                "'" + element.name + "' extends past canvas extent " + canvasWidth + "x" + canvasHeight,
                List.of(element.id),
                Map.of("action", "RESIZE", "hint", "shrink or move inside the canvas"),
                List.of()));
      }
      if (bounds.width <= 0 || bounds.height <= 0) {
        issues.add(
            Issue.of(
                "MEDIUM",
                "DEGENERATE_SIZE",
                element.id,
                "'" + element.name + "' has non-positive size " + bounds.width + "x" + bounds.height,
                List.of(element.id),
                Map.of("action", "FIT_SIZE", "hint", "use FIT_SIZE or explicit bounds"),
                List.of()));
      }
    }
  }

  private static void checkAlignment(
      List<ElementInfo> elements, Tolerances tolerance, List<Issue> issues) {
    for (List<ElementInfo> group : peerGroups(elements)) {
      // Row peers (overlapping y-range): flag pairs whose top edges drift.
      for (int a = 0; a < group.size(); a++) {
        for (int b = a + 1; b < group.size(); b++) {
          ElementInfo first = group.get(a);
          ElementInfo second = group.get(b);
          boolean sameRow = first.bounds.y < second.bounds.y + second.bounds.height
              && second.bounds.y < first.bounds.y + first.bounds.height;
          if (sameRow && Math.abs(first.bounds.y - second.bounds.y) > tolerance.alignment) {
            issues.add(
                Issue.of(
                    "MEDIUM",
                    "ALIGNMENT_DRIFT",
                    second.id,
                    "'" + second.name + "' top edge drifts "
                        + Math.abs(first.bounds.y - second.bounds.y) + "px from row peer '"
                        + first.name + "'",
                    List.of(first.id, second.id),
                    Map.of("action", "MOVE", "y", first.bounds.y),
                    List.of("drift", Math.abs(first.bounds.y - second.bounds.y))));
          }
          boolean sameColumn = first.bounds.x < second.bounds.x + second.bounds.width
              && second.bounds.x < first.bounds.x + first.bounds.width;
          if (sameColumn && Math.abs(first.bounds.x - second.bounds.x) > tolerance.alignment) {
            issues.add(
                Issue.of(
                    "MEDIUM",
                    "ALIGNMENT_DRIFT",
                    second.id,
                    "'" + second.name + "' left edge drifts "
                        + Math.abs(first.bounds.x - second.bounds.x) + "px from column peer '"
                        + first.name + "'",
                    List.of(first.id, second.id),
                    Map.of("action", "MOVE", "x", first.bounds.x),
                    List.of("drift", Math.abs(first.bounds.x - second.bounds.x))));
          }
        }
      }
    }
  }

  private static void checkSpacing(
      List<ElementInfo> elements, Tolerances tolerance, List<Issue> issues) {
    for (List<ElementInfo> group : peerGroups(elements)) {
      // Row groups sorted by x: horizontal gaps must be consistent.
      List<ElementInfo> row = new ArrayList<>(group);
      row.sort(Comparator.comparingInt(item -> item.bounds.x));
      flagGapDrift(row, tolerance, true, issues);
      List<ElementInfo> column = new ArrayList<>(group);
      column.sort(Comparator.comparingInt(item -> item.bounds.y));
      flagGapDrift(column, tolerance, false, issues);
    }
  }

  private static void flagGapDrift(
      List<ElementInfo> ordered,
      Tolerances tolerance,
      boolean horizontal,
      List<Issue> issues) {
    List<Integer> gaps = new ArrayList<>();
    List<ElementInfo> gapOwners = new ArrayList<>();
    for (int index = 0; index + 1 < ordered.size(); index++) {
      ElementInfo left = ordered.get(index);
      ElementInfo right = ordered.get(index + 1);
      int gap =
          horizontal
              ? right.bounds.x - (left.bounds.x + left.bounds.width)
              : right.bounds.y - (left.bounds.y + left.bounds.height);
      if (gap < 0) {
        continue; // overlap is reported by the overlap check
      }
      gaps.add(gap);
      gapOwners.add(right);
    }
    if (gaps.size() < 2) {
      return;
    }
    int median = median(gaps);
    for (int index = 0; index < gaps.size(); index++) {
      int gap = gaps.get(index);
      if (Math.abs(gap - median) <= tolerance.spacing) {
        continue;
      }
      ElementInfo owner = gapOwners.get(index);
      issues.add(
          Issue.of(
              "MEDIUM",
              "SPACING_INCONSISTENCY",
              owner.id,
              "'" + owner.name + "' " + (horizontal ? "horizontal" : "vertical") + " gap is "
                  + gap + "px; group median is " + median + "px",
              List.of(owner.id),
              Map.of(
                  "action", "MOVE",
                  horizontal ? "x" : "y",
                  previousRightEdge(ordered, owner, horizontal) + median),
              List.of("actual", gap, "expected", median)));
    }
  }

  private static int previousRightEdge(
      List<ElementInfo> ordered, ElementInfo owner, boolean horizontal) {
    int index = ordered.indexOf(owner);
    if (index <= 0) {
      return 0;
    }
    ElementInfo previous = ordered.get(index - 1);
    return horizontal ? previous.bounds.x + previous.bounds.width
        : previous.bounds.y + previous.bounds.height;
  }

  private static void checkConsistency(
      List<ElementInfo> elements, Tolerances tolerance, List<Issue> issues) {
    for (List<ElementInfo> group : peerGroups(elements)) {
      if (group.size() < 2) {
        continue;
      }
      List<Integer> widths = new ArrayList<>();
      List<Integer> heights = new ArrayList<>();
      List<Integer> fonts = new ArrayList<>();
      for (ElementInfo element : group) {
        widths.add(element.bounds.width);
        heights.add(element.bounds.height);
        if (element.fontSize > 0) {
          fonts.add(element.fontSize);
        }
      }
      int medianWidth = median(widths);
      int medianHeight = median(heights);
      for (ElementInfo element : group) {
        if (Math.abs(element.bounds.width - medianWidth) > tolerance.alignment
            || Math.abs(element.bounds.height - medianHeight) > tolerance.alignment) {
          issues.add(
              Issue.of(
                  "MEDIUM",
                  "SIZE_INCONSISTENCY",
                  element.id,
                  "'" + element.name + "' is " + element.bounds.width + "x"
                      + element.bounds.height + "; group median is " + medianWidth + "x"
                      + medianHeight,
                  List.of(element.id),
                  Map.of("action", "RESIZE", "width", medianWidth, "height", medianHeight),
                  List.of()));
        }
      }
      if (fonts.size() >= 2) {
        int medianFont = median(fonts);
        for (ElementInfo element : group) {
          if (element.fontSize > 0 && Math.abs(element.fontSize - medianFont) > tolerance.font) {
            issues.add(
                Issue.of(
                    "MEDIUM",
                    "FONT_INCONSISTENCY",
                    element.id,
                    "'" + element.name + "' font " + element.fontSize + "pt; group median is "
                        + medianFont + "pt",
                    List.of(element.id),
                    Map.of("action", "SET_FONT", "size", medianFont),
                    List.of()));
          }
        }
      }
    }
  }

  private void checkTextOverflow(List<ElementInfo> elements, List<Issue> issues) {
    for (ElementInfo element : elements) {
      if (element.name.isEmpty() || element.fontSize <= 0) {
        continue;
      }
      if (element.connector) {
        // Connector caption text vs its own caption bounds.
        if (element.captionBounds != null && element.textWidth > 0
            && element.textWidth > element.captionBounds.width + 2) {
          issues.add(
              Issue.of(
                  "CRITICAL",
                  "TEXT_CLIPPED",
                  element.id,
                  "Connector label '" + element.name + "' needs ~" + element.textWidth
                      + "px but caption is " + element.captionBounds.width + "px wide",
                  List.of(element.id),
                  Map.of("action", "RESET_CAPTION", "hint", "resetCaptionSize refits the label"),
                  List.of("textWidth", element.textWidth)));
        }
        continue;
      }
      int available = element.bounds.width - TEXT_INSET * 2;
      if (available > 0 && element.textWidth > available) {
        issues.add(
            Issue.of(
                "CRITICAL",
                "TEXT_CLIPPED",
                element.id,
                "'" + element.name + "' text needs ~" + element.textWidth + "px but shape is "
                    + element.bounds.width + "px wide",
                List.of(element.id),
                Map.of("action", "FIT_SIZE", "hint", "fitSize or widen the shape"),
                List.of("textWidth", element.textWidth)));
      }
      if (element.name.contains("\n")) {
        int lines = element.name.split("\n").length;
        int needed = lines * element.textHeight + TEXT_INSET;
        if (needed > element.bounds.height) {
          issues.add(
              Issue.of(
                  "HIGH",
                  "TEXT_OVERFLOW",
                  element.id,
                  "'" + element.name + "' needs ~" + needed + "px height for " + lines
                      + " lines but shape is " + element.bounds.height + "px tall",
                  List.of(element.id),
                  Map.of("action", "FIT_SIZE", "hint", "fitSize or grow the shape"),
                  List.of()));
        }
      }
    }
  }

  // ------------------------------------------------------------------ helpers

  /** Groups top-level non-connector elements by model type - the closest VP has to "same role". */
  private static List<List<ElementInfo>> peerGroups(List<ElementInfo> elements) {
    Map<String, List<ElementInfo>> groups = new LinkedHashMap<>();
    for (ElementInfo element : elements) {
      if (element.connector || element.parentId != null || engineManaged(element)) {
        continue;
      }
      groups.computeIfAbsent(element.modelType, key -> new ArrayList<>()).add(element);
    }
    return new ArrayList<>(groups.values());
  }

  private static int median(List<Integer> values) {
    if (values.isEmpty()) {
      return 0;
    }
    List<Integer> sorted = new ArrayList<>(values);
    sorted.sort(Integer::compareTo);
    int middle = sorted.size() / 2;
    return sorted.size() % 2 == 1
        ? sorted.get(middle)
        : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
  }

  private static double area(Rectangle bounds) {
    return bounds.width * (double) bounds.height;
  }

  private static boolean contains(Rectangle outer, Rectangle inner) {
    return outer.contains(inner);
  }

  private static Rectangle shrink(Rectangle bounds, int inset) {
    return new Rectangle(
        bounds.x + inset,
        bounds.y + inset,
        Math.max(0, bounds.width - inset * 2),
        Math.max(0, bounds.height - inset * 2));
  }

  private DesignQaRegistry.PassOutcome notePass(
      String diagramName, List<Issue> issues, boolean rendered) {
    List<String> keys = new ArrayList<>();
    StringBuilder signature = new StringBuilder();
    for (Issue issue : issues) {
      keys.add(issue.signature());
      signature.append(issue.signature()).append(';');
    }
    return DesignQaRegistry.get()
        .stateFor(diagramName)
        .notePass(keys, signature.toString(), rendered);
  }

  private Map<String, Object> validateResult(List<Issue> issues, boolean rendered) {
    Map<String, Object> result = new LinkedHashMap<>();
    List<Map<String, Object>> serialized = new ArrayList<>();
    int blocking = 0;
    for (Issue issue : issues) {
      serialized.add(issue.toJson());
      if ("CRITICAL".equals(issue.severity) || "HIGH".equals(issue.severity)) {
        blocking++;
      }
    }
    result.put("status", blocking > 0 ? "FAIL" : issues.isEmpty() ? "PASS" : "WARN");
    result.put("issueCount", issues.size());
    result.put("blockingCount", blocking);
    result.put("issues", serialized);
    result.put("rendered", rendered);
    return result;
  }

  private Map<String, Object> evaluateGate(
      DesignQaRegistry.QaState state,
      List<Issue> issues,
      DesignQaRegistry.PassOutcome outcome,
      boolean blockMedium) {
    List<Map<String, Object>> serialized = new ArrayList<>();
    int blocking = 0;
    for (Issue issue : issues) {
      serialized.add(issue.toJson());
      if ("CRITICAL".equals(issue.severity)
          || "HIGH".equals(issue.severity)
          || (blockMedium && "MEDIUM".equals(issue.severity))) {
        blocking++;
      }
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("iterations", state.passes());
    result.put("renderPasses", state.renderPasses());
    result.put("issues", serialized);
    result.put("issueCount", issues.size());
    if (state.passes() > DesignQaRegistry.MAX_ITERATIONS) {
      result.put("status", "DESIGN_VERIFICATION_FAILED");
      result.put("reason", "MAX_ITERATIONS exceeded; issues still open");
      return result;
    }
    if (blocking > 0) {
      result.put("status", "NEEDS_FIX");
      result.put("blockingCount", blocking);
      if (outcome.stalled) {
        result.put(
            "stalled",
            "Same issue signature survived " + DesignQaRegistry.STALL_THRESHOLD
                + "+ passes - switch repair strategy (layout change, wrap, typography,"
                + " parent resize) instead of repeating the same fix");
      }
      if (outcome.regressed) {
        result.put("regression", "Issue count increased after last repair");
      }
      return result;
    }
    boolean independentPass = state.passes() >= DesignQaRegistry.MIN_REVIEW_PASSES;
    if (!independentPass) {
      result.put("status", "NEEDS_FIX");
      result.put(
          "blocker",
          "MIN_REVIEW_PASSES not met: need one independent validateDesignGeometry/reviewDesign"
              + " pass before verifyDesign can close (passes=" + state.passes() + ")");
      return result;
    }
    state.verify();
    result.put("status", "VERIFIED");
    result.put("geometryCheck", "PASS");
    result.put("visualCheck", "PASS");
    result.put("remainingIssues", serialized);
    return result;
  }

  private static Map<String, Object> repairEffect(
      DesignQaRegistry.PassOutcome outcome, int issueCount) {
    Map<String, Object> effect = new LinkedHashMap<>();
    effect.put("remainingIssues", issueCount);
    effect.put("regressed", outcome.regressed);
    effect.put("ineffectiveRepairs", outcome.ineffectiveRepairs);
    if (outcome.stalled) {
      effect.put(
          "stalled",
          "Same issue signature persists - do not repeat this repair; choose an"
              + " alternative strategy");
    }
    return effect;
  }

  // ------------------------------------------------------------------ repairs

  private Map<String, Object> applyRepair(IDiagramUIModel diagram, JsonNode step) {
    if (!step.isObject()) {
      throw BridgeException.invalidArgument("$.repairs[]", "each repair must be an object");
    }
    String elementId = step.path("elementId").asText("");
    requireText(elementId, "repairs[].elementId");
    String action = step.path("action").asText("");
    requireText(action, "repairs[].action");
    IDiagramElement element = findDiagramElementById(diagram, elementId);
    if (element == null) {
      throw new IllegalArgumentException("No diagram element with id: " + elementId);
    }
    Map<String, Object> applied = new LinkedHashMap<>();
    applied.put("elementId", elementId);
    applied.put("action", action);
    switch (action.toUpperCase(Locale.ROOT)) {
      case "MOVE":
        element.setLocation(step.path("x").asInt(element.getX()),
            step.path("y").asInt(element.getY()));
        break;
      case "RESIZE":
        element.setSize(
            step.path("width").asInt(element.getWidth()),
            step.path("height").asInt(element.getHeight()));
        break;
      case "SET_BOUNDS":
        element.setBounds(
            step.path("x").asInt(element.getX()),
            step.path("y").asInt(element.getY()),
            step.path("width").asInt(element.getWidth()),
            step.path("height").asInt(element.getHeight()));
        break;
      case "FIT_SIZE":
        if (element instanceof IShapeUIModel) {
          ((IShapeUIModel) element).fitSize();
        } else {
          throw new IllegalArgumentException("FIT_SIZE applies to shapes only");
        }
        break;
      case "RESET_CAPTION":
        element.resetCaptionSize();
        break;
      default:
        throw new IllegalArgumentException(
            "Unknown repair action: " + action
                + " (MOVE|RESIZE|SET_BOUNDS|FIT_SIZE|RESET_CAPTION)");
    }
    applied.put(
        "resultBounds",
        element.getX() + "," + element.getY() + " " + element.getWidth() + "x"
            + element.getHeight());
    return applied;
  }

  // ------------------------------------------------------------------ raster

  private void exportPng(IDiagramUIModel diagram, File file, int scalePercent) throws Exception {
    ExportDiagramAsImageOption option =
        new ExportDiagramAsImageOption(ExportDiagramAsImageOption.IMAGE_TYPE_PNG);
    option.setScale(scalePercent / 100.0f);
    option.setTextAntiAliasing(true);
    option.setGraphicAntiAliasing(true);
    option.setShowIndicatorsOnShapes(false);
    option.setImageMargin(ExportDiagramImageMargin.Default);
    application().getModelConvertionManager().exportDiagramAsImage(diagram, file, option);
    if (!file.isFile() || file.length() == 0) {
      throw new IllegalStateException("Visual Paradigm did not create the render: " + file);
    }
  }

  private RasterAnalysis analyzeRaster(File file) throws Exception {
    BufferedImage image = ImageIO.read(file);
    if (image == null) {
      throw new IllegalStateException("Cannot decode render: " + file);
    }
    int width = image.getWidth();
    int height = image.getHeight();
    int minX = width;
    int minY = height;
    int maxX = -1;
    int maxY = -1;
    long ink = 0;
    for (int y = 0; y < height; y += 2) {
      for (int x = 0; x < width; x += 2) {
        int argb = image.getRGB(x, y);
        int alpha = (argb >>> 24) & 0xff;
        int red = (argb >>> 16) & 0xff;
        int green = (argb >>> 8) & 0xff;
        int blue = argb & 0xff;
        int luminance = (red * 299 + green * 587 + blue * 114) / 1000;
        boolean inkPixel = alpha > 32 && luminance < 245;
        if (!inkPixel) {
          continue;
        }
        ink++;
        if (x < minX) minX = x;
        if (y < minY) minY = y;
        if (x > maxX) maxX = x;
        if (y > maxY) maxY = y;
      }
    }
    return new RasterAnalysis(width, height, ink * 4, minX, minY, maxX, maxY);
  }

  private Path resolveRenderPath(String diagramName, String outputPath) throws Exception {
    if (outputPath != null && !outputPath.isBlank()) {
      Path path = Paths.get(outputPath.trim()).normalize();
      if (!path.isAbsolute()) {
        throw BridgeException.invalidArgument("$.outputPath", "outputPath must be absolute");
      }
      if (path.getParent() != null) {
        Files.createDirectories(path.getParent());
      }
      return path;
    }
    return Files.createTempFile("vp-qa-" + clean(diagramName).replaceAll("\\W+", "-") + "-",
        ".png");
  }

  private static int normalizeScale(int scalePercent) {
    if (scalePercent == 0) {
      return 100;
    }
    if (scalePercent < 25 || scalePercent > 400) {
      throw BridgeException.invalidArgument(
          "$.scalePercent", "scalePercent must be between 25 and 400");
    }
    return scalePercent;
  }

  // ------------------------------------------------------------------ records

  private static final class Tolerances {
    final int alignment;
    final int spacing;
    final int edgeMargin;
    final int font;

    Tolerances(int alignment, int spacing, int edgeMargin, int font) {
      this.alignment = alignment <= 0 ? DEFAULT_ALIGNMENT_TOLERANCE : alignment;
      this.spacing = spacing <= 0 ? DEFAULT_SPACING_TOLERANCE : spacing;
      this.edgeMargin = edgeMargin < 0 ? DEFAULT_EDGE_MARGIN : edgeMargin;
      this.font = font <= 0 ? DEFAULT_FONT_TOLERANCE : font;
    }

    static Tolerances defaults() {
      return new Tolerances(0, 0, 0, 0);
    }
  }

  /** Snapshot of one diagram element's real post-layout state. */
  private static final class ElementInfo {
    final String id;
    final String parentId;
    final String modelType;
    final String shapeType;
    final String name;
    final Rectangle bounds;
    final boolean connector;
    final String fromId;
    final String toId;
    final java.awt.Point[] points;
    final Rectangle captionBounds;
    final int fontSize;
    final int textWidth;
    final int textHeight;

    ElementInfo(IDiagramUIModel diagram, IDiagramElement element, String parentId) {
      String rawId = element.getId();
      this.id = rawId == null ? "" : rawId;
      this.parentId = parentId;
      IModelElement model = element.getModelElement();
      String st = element.getShapeType();
      this.shapeType = st == null ? "" : st;
      this.modelType =
          model != null ? model.getClass().getSimpleName()
              : this.shapeType.isEmpty() ? "Unknown" : this.shapeType;
      this.name = model == null ? "" : clean(model.getName());
      this.bounds = new Rectangle(element.getX(), element.getY(),
          element.getWidth(), element.getHeight());
      this.connector = element instanceof IConnectorUIModel;
      IConnectorUIModel link = this.connector ? (IConnectorUIModel) element : null;
      this.fromId = link != null && link.getFromShape() != null ? link.getFromShape().getId() : null;
      this.toId = link != null && link.getToShape() != null ? link.getToShape().getId() : null;
      this.points = link != null && link.getPoints() != null ? link.getPoints()
          : new java.awt.Point[0];
      ICaptionUIModel caption = element.getCaptionUIModel();
      this.captionBounds =
          caption != null && caption.isVisible() && caption.getWidth() > 0
              ? new Rectangle(caption.getX(), caption.getY(),
                  caption.getWidth(), caption.getHeight())
              : null;
      Font font = element.getElementFont() == null ? null : element.getElementFont().getFont();
      this.fontSize = font == null ? 0 : font.getSize();
      Rectangle2D textBounds = font == null || name.isEmpty()
          ? null
          : font.getStringBounds(name.replace('\n', ' '), FONT_CONTEXT);
      this.textWidth = textBounds == null ? 0 : (int) Math.ceil(textBounds.getWidth());
      this.textHeight = font == null ? 0 : (int) Math.ceil(font.getStringBounds("Ag", FONT_CONTEXT).getHeight());
    }

    Map<String, Object> toJson() {
      Map<String, Object> json = new LinkedHashMap<>();
      json.put("id", id);
      if (parentId != null) {
        json.put("parentId", parentId);
      }
      json.put("modelType", modelType);
      json.put("shapeType", shapeType);
      json.put("name", name);
      json.put("kind", connector ? "connector" : "shape");
      json.put("x", bounds.x);
      json.put("y", bounds.y);
      json.put("width", bounds.width);
      json.put("height", bounds.height);
      if (connector) {
        if (fromId != null) json.put("fromId", fromId);
        if (toId != null) json.put("toId", toId);
        if (points.length > 0) {
          List<String> route = new ArrayList<>();
          for (java.awt.Point point : points) {
            route.add(point.x + "," + point.y);
          }
          json.put("points", route);
        }
        if (captionBounds != null) {
          Map<String, Object> caption = new LinkedHashMap<>();
          caption.put("x", captionBounds.x);
          caption.put("y", captionBounds.y);
          caption.put("width", captionBounds.width);
          caption.put("height", captionBounds.height);
          json.put("caption", caption);
        }
      }
      if (fontSize > 0) {
        json.put("fontSize", fontSize);
        json.put("textWidth", textWidth);
      }
      return json;
    }
  }

  /** One structured QA issue: severity, code, element, message, suggested fix, metrics. */
  private static final class Issue {
    final String severity;
    final String code;
    final String elementId;
    final String message;
    final List<String> elementIds;
    final Map<String, ?> suggestedFix;
    final List<?> metrics;

    private Issue(
        String severity,
        String code,
        String elementId,
        String message,
        List<String> elementIds,
        Map<String, ?> suggestedFix,
        List<?> metrics) {
      this.severity = severity;
      this.code = code;
      this.elementId = elementId;
      this.message = message;
      this.elementIds = elementIds;
      this.suggestedFix = suggestedFix;
      this.metrics = metrics;
    }

    static Issue of(
        String severity,
        String code,
        String elementId,
        String message,
        List<String> elementIds,
        Map<String, ?> suggestedFix,
        List<?> metrics) {
      return new Issue(severity, code, elementId, message, elementIds, suggestedFix, metrics);
    }

    /** Stable identity used for stall detection; excludes volatile metrics. */
    String signature() {
      return code + ":" + String.join(",", elementIds);
    }

    Map<String, Object> toJson() {
      Map<String, Object> json = new LinkedHashMap<>();
      json.put("severity", severity);
      json.put("code", code);
      json.put("elementId", elementId);
      json.put("elementIds", elementIds);
      json.put("message", message);
      if (!suggestedFix.isEmpty()) {
        json.put("suggestedFix", suggestedFix);
      }
      if (!metrics.isEmpty()) {
        json.put("metrics", metrics);
      }
      return json;
    }
  }

  /** Rasterized-output analysis: ink bounding box, blank ratio, edge proximity. */
  private static final class RasterAnalysis {
    final int width;
    final int height;
    final long inkPixels;
    final int inkMinX;
    final int inkMinY;
    final int inkMaxX;
    final int inkMaxY;

    RasterAnalysis(int width, int height, long inkPixels,
        int inkMinX, int inkMinY, int inkMaxX, int inkMaxY) {
      this.width = width;
      this.height = height;
      this.inkPixels = inkPixels;
      this.inkMinX = inkMinX;
      this.inkMinY = inkMinY;
      this.inkMaxX = inkMaxX;
      this.inkMaxY = inkMaxY;
    }

    Map<String, Object> toJson() {
      Map<String, Object> json = new LinkedHashMap<>();
      json.put("imageWidth", width);
      json.put("imageHeight", height);
      double total = width * (double) height;
      json.put("blankRatio", total <= 0 ? 1.0 : round3(1.0 - inkPixels / total));
      if (inkMaxX >= 0) {
        Map<String, Object> inkBox = new LinkedHashMap<>();
        inkBox.put("x", inkMinX);
        inkBox.put("y", inkMinY);
        inkBox.put("width", inkMaxX - inkMinX + 1);
        inkBox.put("height", inkMaxY - inkMinY + 1);
        json.put("inkBounds", inkBox);
      }
      return json;
    }

    void addIssues(List<Issue> issues) {
      if (inkMaxX < 0) {
        issues.add(
            Issue.of(
                "HIGH",
                "RENDER_BLANK",
                "",
                "Rendered output contains no visible content",
                List.of(),
                Map.of("action", "INSPECT", "hint", "diagram rendered empty"),
                List.of()));
        return;
      }
      int edge = 2;
      if (inkMinX <= edge || inkMinY <= edge || inkMaxX >= width - edge || inkMaxY >= height - edge) {
        issues.add(
            Issue.of(
                "MEDIUM",
                "EDGE_INK",
                "",
                "Ink reaches the render edge - content may be clipped at export bounds",
                List.of(),
                Map.of("action", "INSPECT", "hint", "check edge elements for clipping"),
                List.of()));
      }
    }

    private static double round3(double value) {
      return Math.round(value * 1000.0) / 1000.0;
    }
  }
}

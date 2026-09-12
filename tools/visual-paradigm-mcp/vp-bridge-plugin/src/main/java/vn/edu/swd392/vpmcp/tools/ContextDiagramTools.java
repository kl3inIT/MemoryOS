package vn.edu.swd392.vpmcp.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vp.plugin.diagram.ICaptionUIModel;
import com.vp.plugin.diagram.IConnectorUIModel;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IDiagramTypeConstants;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.diagram.IShapeUIModel;
import com.vp.plugin.diagram.connector.IDFDataFlowUIModel;
import com.vp.plugin.diagram.shape.IDFExternalEntityUIModel;
import com.vp.plugin.diagram.shape.IDFProcessUIModel;
import com.vp.plugin.diagram.format.IFillColor;
import com.vp.plugin.model.IDFDataFlow;
import com.vp.plugin.model.IDFDataStore;
import com.vp.plugin.model.IDFExternalEntity;
import com.vp.plugin.model.IDFProcess;
import com.vp.plugin.model.IModelElement;
import com.vp.plugin.model.IProjectTransaction;
import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import vn.edu.swd392.vpmcp.bridge.BridgeException;
import vn.edu.swd392.vpmcp.bridge.Tool;

public final class ContextDiagramTools extends VpAccess {
  private static final String SYSTEM_ID = "system";
  private static final String ID_TAG = "vp-mcp:id=";
  private static final String FACTS_TAG = "vp-mcp:facts=";
  private static final String OPERATION_TAG = "vp-mcp:operationId=";
  private static final String SPEC_HASH_TAG = "vp-mcp:specHash=";
  private static final int OPERATION_CACHE_SIZE = 128;
  private static final Color DEFAULT_BLUE = new Color(124, 200, 235);

  private final ObjectMapper mapper = new ObjectMapper();
  private final Map<String, CachedOperation> operationCache =
      new LinkedHashMap<String, CachedOperation>(OPERATION_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedOperation> eldest) {
          return size() > OPERATION_CACHE_SIZE;
        }
      };

  @Tool(
      description =
          "Create one native level-0 DFD Context Diagram from a strict declarative specification. "
              + "The tool creates exactly one system process, external entities, and named directed "
              + "data flows; data stores and internal processes are forbidden. Reuse operationId "
              + "only when retrying the identical specification.",
      inputSchema = ContextDiagramSchemas.CREATE_CONTEXT,
      idempotent = true)
  public Map<String, Object> createContextDiagram(ContextDiagramSpec spec, boolean dryRun)
      throws Exception {
    ContextDiagramSpec actual = ContextDiagramSpecValidator.validate(spec);
    String specHash = specHash(actual);
    CachedOperation cached = operationCache.get(actual.operationId);
    if (cached != null) {
      if (!cached.specHash.equals(specHash)) {
        throw BridgeException.conflict(
            "IDEMPOTENCY_CONFLICT",
            "$.spec.operationId",
            "operationId was already used with a different Context Diagram specification");
      }
      boolean diagramStillExists =
          onEdt(
              () -> {
                try {
                  contextDiagram(actual.diagramName);
                  return true;
                } catch (IllegalArgumentException exception) {
                  return false;
                }
              });
      if (diagramStillExists) {
        return withReplay(cached.result);
      }
      operationCache.remove(actual.operationId);
    }

    Map<String, Bounds> plannedBounds = planBounds(actual);
    if (dryRun) {
      Map<String, Object> result = baseResult(actual, specHash);
      result.put("applied", false);
      result.put("plan", layoutPlan(actual, plannedBounds));
      result.put("validation", validValidation());
      return result;
    }

    return onEdt(
        () -> {
          IDiagramUIModel existing = findDiagramOrNull(actual.diagramName);
          if (existing != null) {
            String existingOperation = metadata(existing.getDocumentation(), OPERATION_TAG);
            String existingHash = metadata(existing.getDocumentation(), SPEC_HASH_TAG);
            if (actual.operationId.equals(existingOperation) && specHash.equals(existingHash)) {
              Map<String, Object> replay = creationResult(actual, specHash, existing);
              operationCache.put(
                  actual.operationId, new CachedOperation(specHash, replay));
              return withReplay(replay);
            }
            throw BridgeException.conflict(
                "DUPLICATE_DIAGRAM",
                "$.spec.diagramName",
                "Diagram already exists and was not created by this identical operation: "
                    + actual.diagramName);
          }

          IDiagramUIModel diagram = null;
          List<IModelElement> createdModels = new ArrayList<>();
          IProjectTransaction transaction = project().startProjectTransaction();
          try {
            diagram =
                createDiagram(
                    IDiagramTypeConstants.DIAGRAM_TYPE_DATA_FLOW_DIAGRAM,
                    actual.diagramName);
            diagram.setDocumentation(
                OPERATION_TAG
                    + actual.operationId
                    + "\n"
                    + SPEC_HASH_TAG
                    + specHash);
            configureDiagram(diagram);

            Map<String, IDiagramElement> endpoints = new LinkedHashMap<>();
            IDFProcess process = models().createDFProcess();
            createdModels.add(process);
            process.setDocumentation(metadata(SYSTEM_ID, Collections.emptyList()));
            Bounds processBounds = plannedBounds.get(SYSTEM_ID);
            IDiagramElement processShape =
                addShape(
                    diagram,
                    process,
                    actual.systemName,
                    processBounds.x,
                    processBounds.y,
                    processBounds.width,
                    processBounds.height);
            if (!(processShape instanceof IDFProcessUIModel)) {
              throw new IllegalStateException("Visual Paradigm did not create a native DFProcess view");
            }
            styleContextShape(processShape, true, processBounds);
            endpoints.put(SYSTEM_ID, processShape);

            for (ContextDiagramSpec.EntitySpec entity : actual.entities) {
              IDFExternalEntity model = models().createDFExternalEntity();
              createdModels.add(model);
              model.setDocumentation(metadata(entity.id, entity.factIds));
              Bounds bounds = plannedBounds.get(entity.id);
              IDiagramElement shape =
                  addShape(
                      diagram,
                      model,
                      entity.name,
                      bounds.x,
                      bounds.y,
                      bounds.width,
                      bounds.height);
              if (!(shape instanceof IDFExternalEntityUIModel)) {
                throw new IllegalStateException(
                    "Visual Paradigm did not create a native DFExternalEntity view");
              }
              styleContextShape(shape, false, bounds);
              endpoints.put(entity.id, shape);
            }

            Map<String, Integer> totalFlowsByEntity = totalFlowsByEntity(actual.flows);
            Map<String, Integer> nextFlowByEntity = new HashMap<>();
            ContextDiagramSpec.LayoutSpec layout =
                actual.layout == null ? new ContextDiagramSpec.LayoutSpec() : actual.layout;
            int defaultConnectorStyle = contextConnectorStyle(layout.connectorStyle);
            for (ContextDiagramSpec.FlowSpec flowSpec : actual.flows) {
              IDiagramElement from = endpoints.get(flowSpec.from);
              IDiagramElement to = endpoints.get(flowSpec.to);
              if (from == null || to == null) {
                throw new IllegalStateException(
                    "Validated Context endpoint is missing: "
                        + flowSpec.from
                        + " -> "
                        + flowSpec.to);
              }
              IDFDataFlow flow = models().createDFDataFlow();
              createdModels.add(flow);
              flow.setName(flowSpec.name);
              flow.setBidirectional(false);
              flow.setDocumentation(metadata(flowSpec.id, flowSpec.factIds));
              IDiagramElement connectorElement =
                  diagrams().createConnector(diagram, flow, from, to, new Point[0]);
              if (!(connectorElement instanceof IDFDataFlowUIModel)) {
                throw new IllegalStateException(
                    "Visual Paradigm did not create a native DFDataFlow view");
              }
              IConnectorUIModel connector = (IConnectorUIModel) connectorElement;
              String entityId =
                  SYSTEM_ID.equals(flowSpec.from) ? flowSpec.to : flowSpec.from;
              int index = nextFlowByEntity.getOrDefault(entityId, 0);
              nextFlowByEntity.put(entityId, index + 1);
              routeFlow(
                  connector,
                  index,
                  totalFlowsByEntity.getOrDefault(entityId, 1),
                  flowSpec.name,
                  defaultConnectorStyle);
            }
            diagrams().openDiagram(diagram);

            Map<String, Object> validation = validateDiagram(diagram);
            if (!Boolean.TRUE.equals(validation.get("valid"))) {
              throw new IllegalStateException(
                  "Created Context Diagram failed its native validation gate");
            }
            Map<String, Object> result = creationResult(actual, specHash, diagram);
            operationCache.put(
                actual.operationId, new CachedOperation(specHash, result));
            return result;
          } catch (Exception exception) {
            cleanupFailedCreation(diagram, createdModels);
            throw exception;
          } finally {
            transaction.endTransaction();
          }
        });
  }

  @Tool(
      description =
          "Inspect a native Context Diagram as structured nodes, directed flows, stable IDs, "
              + "bounds, connector points, fact traces, and a deterministic revision.",
      readOnly = true,
      idempotent = true)
  public Map<String, Object> inspectContextDiagram(String diagramName) throws Exception {
    return onEdt(() -> snapshot(contextDiagram(diagramName)));
  }

  @Tool(
      description =
          "Validate native notation, the single central process rule, external-entity connectivity, "
              + "directed named data flows, forbidden data stores, stable IDs, and visible overlaps.",
      readOnly = true,
      idempotent = true)
  public Map<String, Object> validateContextDiagram(String diagramName) throws Exception {
    return onEdt(() -> validateDiagram(contextDiagram(diagramName)));
  }

  @Tool(
      description =
          "Prepare one validated Context Diagram for a clean submission screenshot by removing "
              + "internal vp-mcp Documentation metadata that Visual Paradigm renders as small "
              + "document icons. Call only after final layout and validation because stable IDs "
              + "and fact traces are intentionally removed from the .vpp presentation.")
  public Map<String, Object> prepareContextDiagramForSubmission(String diagramName)
      throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = contextDiagram(diagramName);
          Map<String, Object> validation = validateDiagram(diagram);
          if (!Boolean.TRUE.equals(validation.get("valid"))) {
            throw BridgeException.conflict(
                "VALIDATION_FAILED",
                "$.diagramName",
                "Context Diagram must pass validation before submission cleanup");
          }

          int cleared = 0;
          Set<IModelElement> visited =
              Collections.newSetFromMap(new java.util.IdentityHashMap<>());
          for (Object item : diagram.toDiagramElementArray()) {
            if (!(item instanceof IDiagramElement)) {
              continue;
            }
            IModelElement model = ((IDiagramElement) item).getModelElement();
            if (model != null
                && visited.add(model)
                && model.getDocumentation() != null
                && !model.getDocumentation().trim().isEmpty()) {
              model.setDocumentation("");
              cleared++;
            }
          }

          Map<String, Object> result = new LinkedHashMap<>();
          result.put("diagramName", diagram.getName());
          result.put("metadataDocumentsCleared", cleared);
          result.put("readyForScreenshot", true);
          return result;
        });
  }

  @Tool(
      description =
          "Rename one native Context data flow by stable flow ID. Use a concise noun phrase that "
              + "describes the information crossing the system boundary, not an action or use-case "
              + "name. The attached caption is resized and remains bound to the connector.")
  public Map<String, Object> renameContextDataFlow(
      String diagramName, String flowId, String newName, String expectedRevision)
      throws Exception {
    return onEdt(
        () -> {
          String targetName = clean(newName);
          if (targetName.isEmpty()) {
            throw BridgeException.invalidArgument(
                "$.newName", "Context data-flow name must not be blank");
          }
          IDiagramUIModel diagram = contextDiagram(diagramName);
          requireRevision(diagram, expectedRevision);
          IConnectorUIModel connector = contextFlow(diagram, flowId);
          connector.getModelElement().setName(targetName);
          connector.resetCaption();
          connector.resetCaptionSize();
          return mutationResult(diagram);
        });
  }

  @Tool(
      description =
          "Move and resize one Context process or external entity by stable model/view ID. "
              + "Pass the current revision from inspectContextDiagram, or an empty string to skip "
              + "optimistic concurrency checking.")
  public Map<String, Object> layoutContextElement(
      String diagramName,
      String elementId,
      int x,
      int y,
      int width,
      int height,
      String expectedRevision)
      throws Exception {
    return onEdt(
        () -> {
          if (width < 80 || height < 50) {
            throw BridgeException.invalidArgument(
                "$", "Context element width must be at least 80 and height at least 50");
          }
          IDiagramUIModel diagram = contextDiagram(diagramName);
          requireRevision(diagram, expectedRevision);
          IDiagramElement element = contextElement(diagram, elementId);
          element.setBounds(x, y, width, height);
          element.resetCaption();
          return mutationResult(diagram);
        });
  }

  @Tool(
      description =
          "Route one native Context data flow by stable flow ID through explicit x:y points. "
              + "connectorStyle is curve, rectilinear, or oblique. All input is validated before points "
              + "are changed.")
  public Map<String, Object> routeContextDataFlow(
      String diagramName,
      String flowId,
      String pointsCsv,
      String connectorStyle,
      String expectedRevision)
      throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = contextDiagram(diagramName);
          requireRevision(diagram, expectedRevision);
          IConnectorUIModel connector = contextFlow(diagram, flowId);
          Point[] points = parsePoints(pointsCsv);
          int style = connectorStyle(connectorStyle);
          connector.clearPoints();
          for (Point point : points) {
            connector.addPoint(point);
          }
          connector.setConnectorStyle(style);
          connector.resetCaption();
          connector.resetCaptionSize();
          return mutationResult(diagram);
        });
  }

  @Tool(
      description =
          "Move one Context data-flow caption by stable flow ID after inspecting a preview. "
              + "The label remains attached to its native connector.")
  public Map<String, Object> layoutContextDataFlowLabel(
      String diagramName,
      String flowId,
      int x,
      int y,
      String expectedRevision)
      throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = contextDiagram(diagramName);
          requireRevision(diagram, expectedRevision);
          IConnectorUIModel connector = contextFlow(diagram, flowId);
          ICaptionUIModel caption = connector.getCaptionUIModel();
          caption.setVisible(true);
          caption.setSide(ICaptionUIModel.SIDE_FREEMOVE);
          int width = Math.max(120, clean(connector.getModelElement().getName()).length() * 7);
          caption.setBounds(x, y, width, 28);
          return mutationResult(diagram);
        });
  }

  @Tool(
      description =
          "Deterministically reroute every Context data flow using the current node bounds, "
              + "parallel lanes for reciprocal flows, and fresh attached captions.")
  public Map<String, Object> repairContextDiagramLayout(
      String diagramName, String expectedRevision) throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = contextDiagram(diagramName);
          requireRevision(diagram, expectedRevision);
          List<IConnectorUIModel> flows = contextFlows(diagram);
          Map<String, Integer> totals = new HashMap<>();
          for (IConnectorUIModel connector : flows) {
            String entityId = externalEndpointId(connector);
            totals.put(entityId, totals.getOrDefault(entityId, 0) + 1);
          }
          Map<String, Integer> indexes = new HashMap<>();
          for (IConnectorUIModel connector : flows) {
            String entityId = externalEndpointId(connector);
            int index = indexes.getOrDefault(entityId, 0);
            indexes.put(entityId, index + 1);
            routeFlow(
                connector,
                index,
                totals.getOrDefault(entityId, 1),
                clean(connector.getModelElement().getName()),
                connector.getConnectorStyle() == IConnectorUIModel.CS_USE_DEFAULT
                    ? IConnectorUIModel.CS_CURVE
                    : connector.getConnectorStyle());
          }
          return mutationResult(diagram);
        });
  }

  private Map<String, Object> creationResult(
      ContextDiagramSpec spec, String specHash, IDiagramUIModel diagram) {
    Map<String, Object> result = baseResult(spec, specHash);
    result.put("applied", true);
    result.put("diagramId", diagram.getId());
    result.put("revision", revision(diagram));
    result.put("snapshot", snapshot(diagram));
    result.put("validation", validateDiagram(diagram));
    return result;
  }

  private static Map<String, Object> baseResult(ContextDiagramSpec spec, String specHash) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("operationId", spec.operationId);
    result.put("diagramName", spec.diagramName);
    result.put("specHash", specHash);
    result.put("notation", IDiagramTypeConstants.DIAGRAM_TYPE_DATA_FLOW_DIAGRAM);
    result.put("replayed", false);
    return result;
  }

  private static Map<String, Object> withReplay(Map<String, Object> original) {
    Map<String, Object> replay = new LinkedHashMap<>(original);
    replay.put("replayed", true);
    return replay;
  }

  private static Map<String, Object> validValidation() {
    Map<String, Object> validation = new LinkedHashMap<>();
    validation.put("valid", true);
    validation.put("violations", Collections.emptyList());
    return validation;
  }

  private static void configureDiagram(IDiagramUIModel diagram) {
    diagram.setShowConnectorName(IDiagramUIModel.SHOW_CONNECTOR_NAME_YES);
    diagram.setPaintConnectorThroughLabel(
        IDiagramUIModel.PAINT_CONNECTOR_THROUGH_LABEL_NO);
    diagram.setConnectorLabelOrientation(IConnectorUIModel.CLO_HORIZONTAL_ONLY);
  }

  private static Map<String, Bounds> planBounds(ContextDiagramSpec spec) {
    ContextDiagramSpec.LayoutSpec layout =
        spec.layout == null ? new ContextDiagramSpec.LayoutSpec() : spec.layout;
    Map<String, Bounds> result = new LinkedHashMap<>();
    result.put(
        SYSTEM_ID,
        new Bounds(
            layout.centerX - layout.processWidth / 2,
            layout.centerY - layout.processHeight / 2,
            layout.processWidth,
            layout.processHeight));

    Map<String, List<ContextDiagramSpec.EntitySpec>> bySide = new LinkedHashMap<>();
    bySide.put("LEFT", new ArrayList<>());
    bySide.put("RIGHT", new ArrayList<>());
    bySide.put("TOP", new ArrayList<>());
    bySide.put("BOTTOM", new ArrayList<>());
    String[] autoOrder = {"TOP", "RIGHT", "BOTTOM", "LEFT"};
    for (ContextDiagramSpec.EntitySpec entity : spec.entities) {
      String side = entity.side == null ? "AUTO" : entity.side.toUpperCase(Locale.ROOT);
      if ("AUTO".equals(side)) {
        side = autoOrder[0];
        for (String candidate : autoOrder) {
          if (bySide.get(candidate).size() < bySide.get(side).size()) {
            side = candidate;
          }
        }
      }
      bySide.get(side).add(entity);
    }

    placeVertical(
        bySide.get("LEFT"),
        layout.centerX - layout.processWidth / 2 - layout.horizontalGap - layout.entityWidth,
        layout,
        result);
    placeVertical(
        bySide.get("RIGHT"),
        layout.centerX + layout.processWidth / 2 + layout.horizontalGap,
        layout,
        result);
    placeHorizontal(
        bySide.get("TOP"),
        layout.centerY - layout.processHeight / 2 - layout.verticalGap - layout.entityHeight,
        layout,
        result);
    placeHorizontal(
        bySide.get("BOTTOM"),
        layout.centerY + layout.processHeight / 2 + layout.verticalGap,
        layout,
        result);
    return result;
  }

  private static void placeVertical(
      List<ContextDiagramSpec.EntitySpec> entities,
      int x,
      ContextDiagramSpec.LayoutSpec layout,
      Map<String, Bounds> result) {
    if (entities.isEmpty()) {
      return;
    }
    int span = (entities.size() - 1) * layout.verticalGap + layout.entityHeight;
    int startY = layout.centerY - span / 2;
    for (int index = 0; index < entities.size(); index++) {
      result.put(
          entities.get(index).id,
          new Bounds(
              x,
              startY + index * layout.verticalGap,
              layout.entityWidth,
              layout.entityHeight));
    }
  }

  private static void placeHorizontal(
      List<ContextDiagramSpec.EntitySpec> entities,
      int y,
      ContextDiagramSpec.LayoutSpec layout,
      Map<String, Bounds> result) {
    if (entities.isEmpty()) {
      return;
    }
    int span = (entities.size() - 1) * layout.horizontalGap + layout.entityWidth;
    int startX = layout.centerX - span / 2;
    for (int index = 0; index < entities.size(); index++) {
      result.put(
          entities.get(index).id,
          new Bounds(
              startX + index * layout.horizontalGap,
              y,
              layout.entityWidth,
              layout.entityHeight));
    }
  }

  private static List<Map<String, Object>> layoutPlan(
      ContextDiagramSpec spec, Map<String, Bounds> bounds) {
    List<Map<String, Object>> result = new ArrayList<>();
    result.add(boundsMap(SYSTEM_ID, spec.systemName, "PROCESS", bounds.get(SYSTEM_ID)));
    for (ContextDiagramSpec.EntitySpec entity : spec.entities) {
      result.add(boundsMap(entity.id, entity.name, "EXTERNAL_ENTITY", bounds.get(entity.id)));
    }
    return result;
  }

  private static Map<String, Object> boundsMap(
      String id, String name, String kind, Bounds bounds) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", id);
    result.put("name", name);
    result.put("kind", kind);
    result.put("x", bounds.x);
    result.put("y", bounds.y);
    result.put("width", bounds.width);
    result.put("height", bounds.height);
    return result;
  }

  private static Map<String, Integer> totalFlowsByEntity(
      List<ContextDiagramSpec.FlowSpec> flows) {
    Map<String, Integer> result = new HashMap<>();
    for (ContextDiagramSpec.FlowSpec flow : flows) {
      String entityId = SYSTEM_ID.equals(flow.from) ? flow.to : flow.from;
      result.put(entityId, result.getOrDefault(entityId, 0) + 1);
    }
    return result;
  }

  private static void routeFlow(
      IConnectorUIModel connector, int index, int total, String flowName, int connectorStyle) {
    IDiagramElement from = connector.getFromShape();
    IDiagramElement to = connector.getToShape();
    double lane = index - (total - 1) / 2.0;
    int offset = (int) Math.round(lane * 56.0);
    int fromCenterX = from.getX() + from.getWidth() / 2;
    int fromCenterY = from.getY() + from.getHeight() / 2;
    int toCenterX = to.getX() + to.getWidth() / 2;
    int toCenterY = to.getY() + to.getHeight() / 2;
    List<Point> points = new ArrayList<>();

    if (Math.abs(toCenterX - fromCenterX) >= Math.abs(toCenterY - fromCenterY)) {
      int startX = fromCenterX < toCenterX ? from.getX() + from.getWidth() : from.getX();
      int endX = fromCenterX < toCenterX ? to.getX() : to.getX() + to.getWidth();
      int startY = fromCenterY + offset;
      int endY = toCenterY + offset;
      int middleX = (startX + endX) / 2;
      points.add(new Point(startX, startY));
      points.add(new Point(middleX, startY));
      points.add(new Point(middleX, endY));
      points.add(new Point(endX, endY));
    } else {
      int startY = fromCenterY < toCenterY ? from.getY() + from.getHeight() : from.getY();
      int endY = fromCenterY < toCenterY ? to.getY() : to.getY() + to.getHeight();
      int startX = fromCenterX + offset;
      int endX = toCenterX + offset;
      int middleY = (startY + endY) / 2;
      points.add(new Point(startX, startY));
      points.add(new Point(startX, middleY));
      points.add(new Point(endX, middleY));
      points.add(new Point(endX, endY));
    }

    connector.clearPoints();
    for (Point point : points) {
      connector.addPoint(point);
    }
    connector.setConnectorStyle(connectorStyle);
    connector.setConnectorLabelOrientation(IConnectorUIModel.CLO_HORIZONTAL_ONLY);
    connector.setPaintThroughLabel(IDiagramUIModel.PAINT_CONNECTOR_THROUGH_LABEL_NO);
    connector.resetCaption();
    connector.resetCaptionSize();
    Point middle = points.get(points.size() / 2);
    ICaptionUIModel caption = connector.getCaptionUIModel();
    caption.setVisible(true);
    caption.setSide(ICaptionUIModel.SIDE_FREEMOVE);
    int labelWidth = Math.max(120, flowName.length() * 7);
    boolean horizontal =
        Math.abs(toCenterX - fromCenterX) >= Math.abs(toCenterY - fromCenterY);
    if (horizontal) {
      int labelY = lane <= 0 ? middle.y - 34 : middle.y + 8;
      caption.setBounds(middle.x - labelWidth / 2, labelY, labelWidth, 28);
    } else {
      int labelX = lane < 0 ? middle.x - labelWidth - 16 : middle.x + 16;
      caption.setBounds(labelX, middle.y - 14, labelWidth, 28);
    }
  }

  private static void styleContextShape(
      IDiagramElement element, boolean centralProcess, Bounds bounds) {
    if (element instanceof IShapeUIModel) {
      IShapeUIModel shape = (IShapeUIModel) element;
      shape.getFillColor().setType(IFillColor.TYPE_SOLID);
      shape.getFillColor().setColor1(DEFAULT_BLUE);
      shape.getFillColor().setColor2(DEFAULT_BLUE);
      shape.getFillColor().setTransparency(IFillColor.OPAQUE);
      shape.getFillColor().applySetting();
      if (centralProcess) {
        shape.setPresentationOption(IShapeUIModel.PRESENTATION_OPTION_PRIMITIVE);
        shape.setPrimitiveShapeType(IShapeUIModel.PRIMITIVE_SHAPE_TYPE_OVAL);
        shape.setCustomText(clean(element.getModelElement().getName()));
        element.setBounds(bounds.x, bounds.y, bounds.width, bounds.height);
        element.resetCaption();
      }
    }
  }

  private Map<String, Object> snapshot(IDiagramUIModel diagram) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("diagramId", diagram.getId());
    result.put("diagramName", diagram.getName());
    result.put("diagramType", diagram.getType());
    result.put("revision", revision(diagram));
    List<Map<String, Object>> nodes = new ArrayList<>();
    List<Map<String, Object>> flows = new ArrayList<>();
    Iterator<?> iterator = diagram.diagramElementIterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (!(item instanceof IDiagramElement)) {
        continue;
      }
      IDiagramElement element = (IDiagramElement) item;
      IModelElement model = element.getModelElement();
      if (model instanceof IDFProcess || model instanceof IDFExternalEntity
          || model instanceof IDFDataStore) {
        nodes.add(nodeSnapshot(element, model));
      } else if (model instanceof IDFDataFlow && element instanceof IConnectorUIModel) {
        flows.add(flowSnapshot((IConnectorUIModel) element, (IDFDataFlow) model));
      }
    }
    nodes.sort(Comparator.comparing(item -> item.get("id").toString()));
    flows.sort(Comparator.comparing(item -> item.get("id").toString()));
    result.put("nodes", nodes);
    result.put("flows", flows);
    return result;
  }

  private static Map<String, Object> nodeSnapshot(
      IDiagramElement element, IModelElement model) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", stableId(model));
    result.put("modelId", model.getId());
    result.put("viewId", element.getId());
    result.put("kind", nodeKind(model));
    result.put("name", model.getName());
    result.put("factIds", facts(model));
    result.put("x", element.getX());
    result.put("y", element.getY());
    result.put("width", element.getWidth());
    result.put("height", element.getHeight());
    return result;
  }

  private static Map<String, Object> flowSnapshot(
      IConnectorUIModel connector, IDFDataFlow flow) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", stableId(flow));
    result.put("modelId", flow.getId());
    result.put("viewId", connector.getId());
    result.put("name", flow.getName());
    result.put("from", stableId(connector.getFromShape().getModelElement()));
    result.put("to", stableId(connector.getToShape().getModelElement()));
    result.put("bidirectional", flow.isBidirectional());
    result.put("factIds", facts(flow));
    List<Map<String, Integer>> points = new ArrayList<>();
    for (Point point : connector.getPoints()) {
      Map<String, Integer> value = new LinkedHashMap<>();
      value.put("x", point.x);
      value.put("y", point.y);
      points.add(value);
    }
    result.put("points", points);
    ICaptionUIModel caption = connector.getCaptionUIModel();
    Map<String, Object> label = new LinkedHashMap<>();
    label.put("visible", caption.isVisible());
    label.put("x", caption.getX());
    label.put("y", caption.getY());
    label.put("width", caption.getWidth());
    label.put("height", caption.getHeight());
    result.put("label", label);
    return result;
  }

  private Map<String, Object> validateDiagram(IDiagramUIModel diagram) {
    List<Map<String, Object>> violations = new ArrayList<>();
    if (!IDiagramTypeConstants.DIAGRAM_TYPE_DATA_FLOW_DIAGRAM.equals(diagram.getType())) {
      violation(
          violations,
          "WRONG_DIAGRAM_TYPE",
          "ERROR",
          "Expected native DataFlowDiagram but found " + diagram.getType(),
          diagram.getId());
    }

    List<IDiagramElement> processes = new ArrayList<>();
    List<IDiagramElement> entities = new ArrayList<>();
    List<IDiagramElement> stores = new ArrayList<>();
    List<IConnectorUIModel> flows = new ArrayList<>();
    Iterator<?> iterator = diagram.diagramElementIterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (!(item instanceof IDiagramElement)) {
        continue;
      }
      IDiagramElement element = (IDiagramElement) item;
      IModelElement model = element.getModelElement();
      if (model instanceof IDFProcess) {
        processes.add(element);
      } else if (model instanceof IDFExternalEntity) {
        entities.add(element);
      } else if (model instanceof IDFDataStore) {
        stores.add(element);
      } else if (model instanceof IDFDataFlow && element instanceof IConnectorUIModel) {
        flows.add((IConnectorUIModel) element);
      }
    }

    if (processes.size() != 1) {
      violation(
          violations,
          "PROCESS_COUNT",
          "ERROR",
          "A Context Diagram must contain exactly one DFProcess; found " + processes.size(),
          diagram.getId());
    } else {
      IDiagramElement process = processes.get(0);
      boolean circularPrimitive =
          process instanceof IShapeUIModel
              && ((IShapeUIModel) process).getPresentationOption()
                  == IShapeUIModel.PRESENTATION_OPTION_PRIMITIVE
              && ((IShapeUIModel) process).getPrimitiveShapeType()
                  == IShapeUIModel.PRIMITIVE_SHAPE_TYPE_OVAL
              && process.getWidth() == process.getHeight();
      if (!circularPrimitive) {
        violation(
            violations,
            "PROCESS_NOT_CIRCULAR",
            "ERROR",
            "The central DFProcess must use a circular primitive presentation",
            process.getId());
      }
      if (process instanceof IShapeUIModel
          && !clean(process.getModelElement().getName())
              .equals(clean(((IShapeUIModel) process).getCustomText()))) {
        violation(
            violations,
            "PROCESS_NAME_NOT_VISIBLE",
            "ERROR",
            "The circular central process must visibly show its system name",
            process.getId());
      }
    }
    if (entities.isEmpty()) {
      violation(
          violations,
          "NO_EXTERNAL_ENTITY",
          "ERROR",
          "A Context Diagram must contain at least one DFExternalEntity",
          diagram.getId());
    }
    if (!stores.isEmpty()) {
      violation(
          violations,
          "DATA_STORE_FORBIDDEN",
          "ERROR",
          "Level-0 Context Diagram must not contain data stores",
          stores.get(0).getId());
    }

    Set<String> connectedEntityModelIds = new HashSet<>();
    Set<String> stableIds = new HashSet<>();
    for (IDiagramElement node : concat(processes, entities)) {
      IModelElement model = node.getModelElement();
      String id = stableId(model);
      if (!stableIds.add(id)) {
        violation(
            violations,
            "DUPLICATE_STABLE_ID",
            "ERROR",
            "Duplicate stable Context ID: " + id,
            node.getId());
      }
      if (facts(model).isEmpty() && model instanceof IDFExternalEntity) {
        violation(
            violations,
            "MISSING_FACT_TRACE",
            "WARNING",
            "External entity has no requirement fact trace: " + model.getName(),
            node.getId());
      }
    }

    for (IConnectorUIModel connector : flows) {
      IModelElement from = connector.getFromShape() == null
          ? null
          : connector.getFromShape().getModelElement();
      IModelElement to = connector.getToShape() == null
          ? null
          : connector.getToShape().getModelElement();
      boolean validEndpoints =
          (from instanceof IDFProcess && to instanceof IDFExternalEntity)
              || (from instanceof IDFExternalEntity && to instanceof IDFProcess);
      if (!validEndpoints) {
        violation(
            violations,
            "INVALID_FLOW_ENDPOINTS",
            "ERROR",
            "Every Context flow must connect the central process and one external entity",
            connector.getId());
      } else {
        IModelElement entity = from instanceof IDFExternalEntity ? from : to;
        connectedEntityModelIds.add(entity.getId());
      }
      IDFDataFlow flow = (IDFDataFlow) connector.getModelElement();
      if (clean(flow.getName()).isEmpty()) {
        violation(
            violations,
            "UNNAMED_FLOW",
            "ERROR",
            "Every Context data flow must have a noun-phrase name",
            connector.getId());
      }
      if (flow.isBidirectional()) {
        violation(
            violations,
            "BIDIRECTIONAL_FLOW",
            "ERROR",
            "Use two directed flows when request and response payloads differ",
            connector.getId());
      }
      if (facts(flow).isEmpty()) {
        violation(
            violations,
            "MISSING_FACT_TRACE",
            "WARNING",
            "Data flow has no requirement fact trace: " + flow.getName(),
            connector.getId());
      }
    }

    for (IDiagramElement entity : entities) {
      if (!connectedEntityModelIds.contains(entity.getModelElement().getId())) {
        violation(
            violations,
            "ORPHAN_EXTERNAL_ENTITY",
            "ERROR",
            "External entity has no data flow: " + entity.getModelElement().getName(),
            entity.getId());
      }
    }
    validateOverlaps(processes, entities, flows, violations);

    boolean valid =
        violations.stream().noneMatch(item -> "ERROR".equals(item.get("severity")));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("valid", valid);
    result.put("revision", revision(diagram));
    result.put("processCount", processes.size());
    result.put("externalEntityCount", entities.size());
    result.put("dataFlowCount", flows.size());
    result.put("violations", violations);
    return result;
  }

  private static void validateOverlaps(
      List<IDiagramElement> processes,
      List<IDiagramElement> entities,
      List<IConnectorUIModel> flows,
      List<Map<String, Object>> violations) {
    List<IDiagramElement> shapes = concat(processes, entities);
    for (int first = 0; first < shapes.size(); first++) {
      Rectangle firstBounds = rectangle(shapes.get(first));
      for (int second = first + 1; second < shapes.size(); second++) {
        if (firstBounds.intersects(rectangle(shapes.get(second)))) {
          violation(
              violations,
              "SHAPE_OVERLAP",
              "ERROR",
              "Context shapes overlap: "
                  + shapes.get(first).getModelElement().getName()
                  + " and "
                  + shapes.get(second).getModelElement().getName(),
              shapes.get(second).getId());
        }
      }
    }
    for (IConnectorUIModel flow : flows) {
      ICaptionUIModel caption = flow.getCaptionUIModel();
      if (!caption.isVisible() || caption.getWidth() <= 0 || caption.getHeight() <= 0) {
        continue;
      }
      Rectangle label = rectangle(caption);
      for (IDiagramElement shape : shapes) {
        if (label.intersects(rectangle(shape))) {
          violation(
              violations,
              "FLOW_LABEL_OVERLAP",
              "ERROR",
              "Data-flow label overlaps shape: " + shape.getModelElement().getName(),
              flow.getId());
          break;
        }
      }
    }
    for (int first = 0; first < flows.size(); first++) {
      ICaptionUIModel firstCaption = flows.get(first).getCaptionUIModel();
      if (!firstCaption.isVisible()
          || firstCaption.getWidth() <= 0
          || firstCaption.getHeight() <= 0) {
        continue;
      }
      Rectangle firstLabel = rectangle(firstCaption);
      for (int second = first + 1; second < flows.size(); second++) {
        ICaptionUIModel secondCaption = flows.get(second).getCaptionUIModel();
        if (!secondCaption.isVisible()
            || secondCaption.getWidth() <= 0
            || secondCaption.getHeight() <= 0) {
          continue;
        }
        if (firstLabel.intersects(rectangle(secondCaption))) {
          violation(
              violations,
              "FLOW_LABEL_OVERLAP",
              "ERROR",
              "Two data-flow labels overlap",
              flows.get(second).getId());
        }
      }
    }
  }

  private static void violation(
      List<Map<String, Object>> violations,
      String code,
      String severity,
      String message,
      String elementId) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("code", code);
    item.put("severity", severity);
    item.put("message", message);
    item.put("elementId", elementId);
    violations.add(item);
  }

  private IDiagramUIModel contextDiagram(String diagramName) {
    IDiagramUIModel diagram = findDiagram(diagramName);
    if (!IDiagramTypeConstants.DIAGRAM_TYPE_DATA_FLOW_DIAGRAM.equals(diagram.getType())) {
      throw new IllegalArgumentException(
          "Target is not a native Data Flow Diagram: " + diagramName);
    }
    return diagram;
  }

  private IDiagramUIModel findDiagramOrNull(String diagramName) {
    IDiagramUIModel match = null;
    int count = 0;
    Iterator<?> iterator = project().diagramIterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (item instanceof IDiagramUIModel
          && diagramName.equals(((IDiagramUIModel) item).getName())) {
        match = (IDiagramUIModel) item;
        count++;
      }
    }
    if (count > 1) {
      throw BridgeException.conflict(
          "AMBIGUOUS_DIAGRAM",
          "$.spec.diagramName",
          "More than one diagram uses the name: " + diagramName);
    }
    return match;
  }

  private static IDiagramElement contextElement(
      IDiagramUIModel diagram, String elementId) {
    Iterator<?> iterator = diagram.diagramElementIterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (!(item instanceof IDiagramElement)) {
        continue;
      }
      IDiagramElement element = (IDiagramElement) item;
      IModelElement model = element.getModelElement();
      if ((model instanceof IDFProcess || model instanceof IDFExternalEntity)
          && (elementId.equals(element.getId())
              || elementId.equals(model.getId())
              || elementId.equals(stableId(model)))) {
        return element;
      }
    }
    throw new IllegalArgumentException("Context element not found: " + elementId);
  }

  private static IConnectorUIModel contextFlow(
      IDiagramUIModel diagram, String flowId) {
    Iterator<?> iterator = diagram.diagramElementIterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (!(item instanceof IConnectorUIModel)) {
        continue;
      }
      IConnectorUIModel connector = (IConnectorUIModel) item;
      IModelElement model = connector.getModelElement();
      if (model instanceof IDFDataFlow
          && (flowId.equals(connector.getId())
              || flowId.equals(model.getId())
              || flowId.equals(stableId(model)))) {
        return connector;
      }
    }
    throw new IllegalArgumentException("Context data flow not found: " + flowId);
  }

  private static List<IConnectorUIModel> contextFlows(IDiagramUIModel diagram) {
    List<IConnectorUIModel> result = new ArrayList<>();
    Iterator<?> iterator = diagram.diagramElementIterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (item instanceof IConnectorUIModel
          && ((IConnectorUIModel) item).getModelElement() instanceof IDFDataFlow) {
        result.add((IConnectorUIModel) item);
      }
    }
    result.sort(Comparator.comparing(item -> stableId(item.getModelElement())));
    return result;
  }

  private static String externalEndpointId(IConnectorUIModel connector) {
    IModelElement from = connector.getFromShape().getModelElement();
    IModelElement to = connector.getToShape().getModelElement();
    IModelElement entity = from instanceof IDFExternalEntity ? from : to;
    return stableId(entity);
  }

  private static Map<String, Object> mutationResult(IDiagramUIModel diagram) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("diagramId", diagram.getId());
    result.put("revision", revision(diagram));
    return result;
  }

  private static void requireRevision(IDiagramUIModel diagram, String expectedRevision) {
    String expected = clean(expectedRevision);
    if (!expected.isEmpty()) {
      String actual = revision(diagram);
      if (!expected.equals(actual)) {
        throw BridgeException.conflict(
            "REVISION_CONFLICT",
            "$.expectedRevision",
            "Diagram changed since inspection; expected " + expected + " but found " + actual);
      }
    }
  }

  private static String revision(IDiagramUIModel diagram) {
    List<String> elements = new ArrayList<>();
    Iterator<?> iterator = diagram.diagramElementIterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (!(item instanceof IDiagramElement)) {
        continue;
      }
      IDiagramElement element = (IDiagramElement) item;
      IModelElement model = element.getModelElement();
      if (model == null) {
        continue;
      }
      StringBuilder value =
          new StringBuilder(model.getModelType())
              .append('|')
              .append(model.getId())
              .append('|')
              .append(model.getName())
              .append('|')
              .append(element.getX())
              .append(',')
              .append(element.getY())
              .append(',')
              .append(element.getWidth())
              .append(',')
              .append(element.getHeight());
      if (element instanceof IConnectorUIModel) {
        IConnectorUIModel connector = (IConnectorUIModel) element;
        value
            .append('|')
            .append(connector.getFromShape() == null ? "" : connector.getFromShape().getId())
            .append("->")
            .append(connector.getToShape() == null ? "" : connector.getToShape().getId());
        for (Point point : connector.getPoints()) {
          value.append('|').append(point.x).append(':').append(point.y);
        }
        ICaptionUIModel caption = connector.getCaptionUIModel();
        value
            .append("|label:")
            .append(caption.getX())
            .append(',')
            .append(caption.getY())
            .append(',')
            .append(caption.getWidth())
            .append(',')
            .append(caption.getHeight());
      }
      elements.add(value.toString());
    }
    Collections.sort(elements);
    return sha256(String.join("\n", elements));
  }

  private String specHash(ContextDiagramSpec spec) {
    try {
      return sha256(mapper.writeValueAsString(spec));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Cannot hash Context Diagram specification", exception);
    }
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(hash.length * 2);
      for (byte item : hash) {
        result.append(String.format("%02x", item & 0xff));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String metadata(String id, List<String> factIds) {
    List<String> facts = factIds == null ? Collections.emptyList() : factIds;
    return ID_TAG + id + "\n" + FACTS_TAG + String.join("|", facts);
  }

  private static String metadata(String documentation, String prefix) {
    if (documentation == null) {
      return "";
    }
    for (String line : documentation.split("\\R")) {
      if (line.startsWith(prefix)) {
        return line.substring(prefix.length()).trim();
      }
    }
    return "";
  }

  private static String stableId(IModelElement model) {
    String id = metadata(model.getDocumentation(), ID_TAG);
    return id.isEmpty() ? model.getId() : id;
  }

  private static List<String> facts(IModelElement model) {
    String raw = metadata(model.getDocumentation(), FACTS_TAG);
    if (raw.isEmpty()) {
      return Collections.emptyList();
    }
    List<String> result = new ArrayList<>();
    for (String value : raw.split("\\|")) {
      if (!value.trim().isEmpty()) {
        result.add(value.trim());
      }
    }
    return result;
  }

  private static String nodeKind(IModelElement model) {
    if (model instanceof IDFProcess) {
      return "PROCESS";
    }
    if (model instanceof IDFExternalEntity) {
      return "EXTERNAL_ENTITY";
    }
    if (model instanceof IDFDataStore) {
      return "DATA_STORE";
    }
    return model.getModelType();
  }

  private static Rectangle rectangle(IDiagramElement element) {
    return new Rectangle(element.getX(), element.getY(), element.getWidth(), element.getHeight());
  }

  private static Rectangle rectangle(ICaptionUIModel caption) {
    return new Rectangle(caption.getX(), caption.getY(), caption.getWidth(), caption.getHeight());
  }

  private static <T> List<T> concat(List<T> first, List<T> second) {
    List<T> result = new ArrayList<>(first.size() + second.size());
    result.addAll(first);
    result.addAll(second);
    return result;
  }

  private static Point[] parsePoints(String pointsCsv) {
    String[] tokens = clean(pointsCsv).split(",");
    if (tokens.length < 2) {
      throw BridgeException.invalidArgument(
          "$.pointsCsv", "pointsCsv must contain at least two x:y points");
    }
    Point[] points = new Point[tokens.length];
    for (int index = 0; index < tokens.length; index++) {
      String[] coordinates = tokens[index].trim().split(":", 2);
      if (coordinates.length != 2) {
        throw BridgeException.invalidArgument(
            "$.pointsCsv", "Invalid point '" + tokens[index] + "'; expected x:y");
      }
      try {
        points[index] =
            new Point(
                Integer.parseInt(coordinates[0].trim()),
                Integer.parseInt(coordinates[1].trim()));
      } catch (NumberFormatException exception) {
        throw BridgeException.invalidArgument(
            "$.pointsCsv", "Invalid numeric point '" + tokens[index] + "'");
      }
    }
    return points;
  }

  private static int connectorStyle(String value) {
    String style = clean(value).toLowerCase(Locale.ROOT);
    if ("curve".equals(style)) {
      return IConnectorUIModel.CS_CURVE;
    }
    if ("rectilinear".equals(style)) {
      return IConnectorUIModel.CS_RECTI_LINEAR;
    }
    if ("oblique".equals(style)) {
      return IConnectorUIModel.CS_OBLIQUE;
    }
    throw BridgeException.invalidArgument(
        "$.connectorStyle", "connectorStyle must be curve, rectilinear, or oblique");
  }

  private static int contextConnectorStyle(String value) {
    String style = value == null ? "CURVE" : value.toUpperCase(Locale.ROOT);
    if ("CURVE".equals(style)) {
      return IConnectorUIModel.CS_CURVE;
    }
    if ("RECTILINEAR".equals(style)) {
      return IConnectorUIModel.CS_RECTI_LINEAR;
    }
    if ("OBLIQUE".equals(style)) {
      return IConnectorUIModel.CS_OBLIQUE;
    }
    throw BridgeException.invalidArgument(
        "$.spec.layout.connectorStyle",
        "connectorStyle must be CURVE, RECTILINEAR, or OBLIQUE");
  }

  private static void cleanupFailedCreation(
      IDiagramUIModel diagram, List<IModelElement> createdModels) {
    if (diagram != null) {
      try {
        diagram.delete();
      } catch (RuntimeException ignored) {
        // Continue compensating model cleanup.
      }
    }
    for (int index = createdModels.size() - 1; index >= 0; index--) {
      try {
        createdModels.get(index).delete();
      } catch (RuntimeException ignored) {
        // Best-effort compensation because VP OpenAPI has no rollback method.
      }
    }
  }

  private static final class Bounds {
    private final int x;
    private final int y;
    private final int width;
    private final int height;

    private Bounds(int x, int y, int width, int height) {
      this.x = x;
      this.y = y;
      this.width = width;
      this.height = height;
    }
  }

  private static final class CachedOperation {
    private final String specHash;
    private final Map<String, Object> result;

    private CachedOperation(String specHash, Map<String, Object> result) {
      this.specHash = specHash;
      this.result = new LinkedHashMap<>(result);
    }
  }
}

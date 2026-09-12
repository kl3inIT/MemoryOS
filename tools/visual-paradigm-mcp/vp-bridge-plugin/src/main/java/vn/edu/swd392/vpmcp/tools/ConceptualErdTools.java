package vn.edu.swd392.vpmcp.tools;

import com.vp.plugin.diagram.ICaptionUIModel;
import com.vp.plugin.diagram.IConnectorUIModel;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IDiagramTypeConstants;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.diagram.IERDiagramUIModel;
import com.vp.plugin.diagram.format.IFillColor;
import com.vp.plugin.diagram.connector.IDBForeignKeyUIModel;
import com.vp.plugin.diagram.shape.IDBTableUIModel;
import com.vp.plugin.model.IDBForeignKey;
import com.vp.plugin.model.IDBTable;
import com.vp.plugin.model.IDBView;
import com.vp.plugin.model.IModelElement;
import com.vp.plugin.model.IProjectTransaction;
import java.awt.Color;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import vn.edu.swd392.vpmcp.bridge.BridgeException;
import vn.edu.swd392.vpmcp.bridge.Tool;

public final class ConceptualErdTools extends VpAccess {
  private static final String ID_TAG = "vp-mcp:id=";
  private static final String FACTS_TAG = "vp-mcp:facts=";
  private static final String DESCRIPTION_TAG = "vp-mcp:description=";
  private static final Color VP_DESKTOP_CONCEPTUAL_ENTITY = new Color(230, 245, 122);

  @Tool(
      description =
          "Create one native Visual Paradigm Crow's Foot Entity Relationship Diagram in "
              + "Conceptual data-model mode from a fact-grounded declarative specification. "
              + "Entities are singular business nouns with descriptions but no columns, keys, "
              + "foreign keys, or data types. Every named relationship has explicit cardinality "
              + "at both ends and a business rationale.",
      inputSchema = ConceptualErdSchemas.CREATE,
      idempotent = true)
  public Map<String, Object> createConceptualErd(ConceptualErdSpec spec, boolean dryRun)
      throws Exception {
    ConceptualErdSpec actual = ConceptualErdSpecValidator.validate(spec);
    LayoutPlan plan = plan(actual);
    if (dryRun) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("operationId", actual.operationId);
      result.put("diagramName", actual.diagramName);
      result.put("applied", false);
      result.put("plan", plan.snapshot());
      result.put("validation", validValidation());
      return result;
    }

    return onEdt(
        () -> {
          ensureDiagramNameAvailable(actual.diagramName);
          IDiagramUIModel diagram = null;
          List<IModelElement> createdModels = new ArrayList<>();
          IProjectTransaction transaction = project().startProjectTransaction();
          try {
            diagram =
                createDiagram(
                    IDiagramTypeConstants.DIAGRAM_TYPE_ER_DIAGRAM, actual.diagramName);
            if (!(diagram instanceof IERDiagramUIModel)) {
              throw new IllegalStateException(
                  "Visual Paradigm did not create a native Entity Relationship Diagram");
            }
            IERDiagramUIModel erd = (IERDiagramUIModel) diagram;
            configureDiagram(erd);

            Map<String, IDiagramElement> elements = new LinkedHashMap<>();
            for (ConceptualErdSpec.EntitySpec entitySpec : actual.entities) {
              IDBTable entity = models().createDBTable();
              createdModels.add(entity);
              entity.setDataModel(IDBTable.DATA_MODEL_CONCEPTUAL);
              entity.setSyncType(IDBTable.SYNC_TYPE_NOT_SYNC);
              entity.setDocumentation(
                  metadata(entitySpec.id, entitySpec.factIds, entitySpec.description));
              Bounds bounds = plan.bounds.get(entitySpec.id);
              IDiagramElement shape =
                  addShape(
                      diagram,
                      entity,
                      entitySpec.name,
                      bounds.x,
                      bounds.y,
                      bounds.width,
                      bounds.height);
              if (!(shape instanceof IDBTableUIModel)) {
                throw new IllegalStateException(
                    "Visual Paradigm did not create a native ERD Entity view");
              }
              configureEntityShape((IDBTableUIModel) shape);
              elements.put(entitySpec.id, shape);
            }

            int connectorStyle = connectorStyle(actual.layout);
            for (ConceptualErdSpec.RelationshipSpec relationshipSpec : actual.relationships) {
              IDBForeignKey relationship = models().createDBForeignKey();
              createdModels.add(relationship);
              relationship.setName(relationshipSpec.name);
              relationship.setDataModel(IDBForeignKey.DATA_MODEL_CONCEPTUAL);
              relationship.setSyncType(IDBForeignKey.SYNC_TYPE_NOT_SYNC);
              relationship.setIdentifying(false);
              relationship.setSubtype(false);
              relationship.setFromMultiplicity(
                  cardinalityValue(relationshipSpec.fromCardinality));
              relationship.setToMultiplicity(cardinalityValue(relationshipSpec.toCardinality));
              relationship.setDocumentation(
                  metadata(
                      relationshipSpec.id,
                      relationshipSpec.factIds,
                      relationshipSpec.rationale));
              IDiagramElement connector =
                  addConnector(
                      diagram,
                      relationship,
                      elements.get(relationshipSpec.from),
                      elements.get(relationshipSpec.to));
              if (!(connector instanceof IDBForeignKeyUIModel)) {
                throw new IllegalStateException(
                    "Visual Paradigm did not create a native ERD relationship view");
              }
              routeRelationship((IConnectorUIModel) connector, connectorStyle);
            }

            diagrams().openDiagram(diagram);
            Map<String, Object> validation = validateDiagram(erd);
            if (!Boolean.TRUE.equals(validation.get("valid"))) {
              throw new IllegalStateException(
                  "Created Conceptual ERD failed validation: " + validation.get("violations"));
            }
            Map<String, Object> result = snapshot(erd);
            result.put("operationId", actual.operationId);
            result.put("applied", true);
            result.put("validation", validation);
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
          "Inspect one native Visual Paradigm Conceptual ERD as business entities, descriptions, "
              + "bounds, named Crow's Foot relationships, cardinalities, and stable source IDs.",
      readOnly = true,
      idempotent = true)
  public Map<String, Object> inspectConceptualErd(String diagramName) throws Exception {
    return onEdt(() -> snapshot(conceptualDiagram(findDiagram(diagramName))));
  }

  @Tool(
      description =
          "Validate that one native ER Diagram is in Conceptual mode, contains only compact "
              + "column-free entities and native Crow's Foot relationships, has explicit names "
              + "and cardinalities, has no orphan or overlapping entities, and has valid endpoints.",
      readOnly = true,
      idempotent = true)
  public Map<String, Object> validateConceptualErd(String diagramName) throws Exception {
    return onEdt(() -> validateDiagram(conceptualDiagram(findDiagram(diagramName))));
  }

  @Tool(
      description =
          "Delete exactly one MCP-owned native Conceptual ERD and its entity/relationship models. "
              + "The operation refuses diagrams containing any model without a vp-mcp stable ID, "
              + "so manually authored ERDs cannot be deleted accidentally.",
      destructive = true,
      idempotent = false)
  public Map<String, Object> deleteOwnedConceptualErd(String diagramName) throws Exception {
    return onEdt(
        () -> {
          IERDiagramUIModel diagram = conceptualDiagram(findDiagram(diagramName));
          Set<IModelElement> ownedModels = new HashSet<>();
          Iterator<?> iterator = diagram.diagramElementIterator();
          while (iterator.hasNext()) {
            Object item = iterator.next();
            if (!(item instanceof IDiagramElement)) {
              continue;
            }
            IModelElement model = ((IDiagramElement) item).getModelElement();
            if (model instanceof IDBTable
                || model instanceof IDBForeignKey
                || model instanceof IDBView) {
              boolean owned = !metadata(model.getDocumentation(), ID_TAG).isEmpty();
              boolean emptyStarter =
                  (model instanceof IDBTable
                          && clean(model.getName()).matches("Entity\\d*")
                          && ((IDBTable) model).dBColumnCount() == 0)
                      || (model instanceof IDBView
                          && clean(model.getName()).matches("View\\d*")
                          && ((IDBView) model).dBColumnCount() == 0);
              if (!owned && !emptyStarter) {
                throw BridgeException.conflict(
                    "UNOWNED_ERD_CONTENT",
                    "$.diagramName",
                    "Refusing deletion because the ERD contains a model without an MCP stable ID: "
                        + model.getName());
              }
              ownedModels.add(model);
            }
          }
          IProjectTransaction transaction = project().startProjectTransaction();
          try {
            diagram.delete();
            for (IModelElement model : ownedModels) {
              model.delete();
            }
          } finally {
            transaction.endTransaction();
          }
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("diagramName", diagramName);
          result.put("deletedModelCount", ownedModels.size());
          result.put("deleted", true);
          return result;
        });
  }

  @Tool(
      description =
          "Deterministically relayout an existing native Conceptual ERD from its original "
              + "validated grid specification, retain native default entity styling, and reroute "
              + "relationship labels without changing any business semantics.",
      inputSchema = ConceptualErdSchemas.CREATE,
      idempotent = true)
  public Map<String, Object> relayoutConceptualErd(ConceptualErdSpec spec, boolean dryRun)
      throws Exception {
    ConceptualErdSpec actual = ConceptualErdSpecValidator.validate(spec);
    LayoutPlan plan = plan(actual);
    if (dryRun) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("operationId", actual.operationId);
      result.put("diagramName", actual.diagramName);
      result.put("applied", false);
      result.put("plan", plan.snapshot());
      result.put("validation", validValidation());
      return result;
    }

    return onEdt(
        () -> {
          IERDiagramUIModel diagram = conceptualDiagram(findDiagram(actual.diagramName));
          configureDiagram(diagram);
          Map<String, String> expectedIdByName = new HashMap<>();
          for (ConceptualErdSpec.EntitySpec entity : actual.entities) {
            expectedIdByName.put(entity.name, entity.id);
          }
          Map<String, IDiagramElement> elements = new LinkedHashMap<>();
          List<IConnectorUIModel> connectors = new ArrayList<>();
          Iterator<?> iterator = diagram.diagramElementIterator();
          while (iterator.hasNext()) {
            Object item = iterator.next();
            if (!(item instanceof IDiagramElement)) {
              continue;
            }
            IDiagramElement element = (IDiagramElement) item;
            IModelElement model = element.getModelElement();
            if (model instanceof IDBTable) {
              String id = metadata(model.getDocumentation(), ID_TAG);
              if (id.isEmpty()) {
                id = expectedIdByName.getOrDefault(clean(model.getName()), "");
              }
              elements.put(id, element);
            } else if (model instanceof IDBForeignKey && element instanceof IConnectorUIModel) {
              connectors.add((IConnectorUIModel) element);
            }
          }
          for (Map.Entry<String, Bounds> entry : plan.bounds.entrySet()) {
            IDiagramElement element = elements.get(entry.getKey());
            if (element == null) {
              throw BridgeException.conflict(
                  "MISSING_DIAGRAM_ELEMENT",
                  "$.spec",
                  "Existing Conceptual ERD is missing entity id: " + entry.getKey());
            }
            Bounds bounds = entry.getValue();
            element.setBounds(bounds.x, bounds.y, bounds.width, bounds.height);
            configureEntityShape((IDBTableUIModel) element);
          }
          int style = connectorStyle(actual.layout);
          for (IConnectorUIModel connector : connectors) {
            routeRelationship(connector, style);
          }
          Map<String, Object> result = snapshot(diagram);
          result.put("operationId", actual.operationId);
          result.put("applied", true);
          result.put("plan", plan.snapshot());
          result.put("validation", validateDiagram(diagram));
          return result;
        });
  }

  @Tool(
      description =
          "Repair only the presentation of a native Conceptual ERD: enforce Conceptual mode, hide "
              + "all columns, retain native default entity styling, and place relationship names near "
              + "their connector midpoints. Business entities, relationships, and cardinalities "
              + "are unchanged.")
  public Map<String, Object> repairConceptualErdPresentation(String diagramName)
      throws Exception {
    return onEdt(
        () -> {
          IERDiagramUIModel diagram = conceptualDiagram(findDiagram(diagramName));
          configureDiagram(diagram);
          Iterator<?> iterator = diagram.diagramElementIterator();
          while (iterator.hasNext()) {
            Object item = iterator.next();
            if (!(item instanceof IDiagramElement)) {
              continue;
            }
            IDiagramElement element = (IDiagramElement) item;
            if (element instanceof IDBTableUIModel) {
              configureEntityShape((IDBTableUIModel) element);
            } else if (element instanceof IDBForeignKeyUIModel) {
              routeRelationship((IConnectorUIModel) element, IConnectorUIModel.CS_OBLIQUE);
            }
          }
          Map<String, Object> result = snapshot(diagram);
          result.put("validation", validateDiagram(diagram));
          return result;
        });
  }

  @Tool(
      description =
          "Prepare one validated Conceptual ERD for a clean submission screenshot by removing "
              + "internal vp-mcp Documentation metadata that Visual Paradigm renders as small "
              + "document icons. Call only after final relayout because stable IDs and entity "
              + "descriptions are intentionally removed from the .vpp presentation.")
  public Map<String, Object> prepareConceptualErdForSubmission(String diagramName)
      throws Exception {
    return onEdt(
        () -> {
          IERDiagramUIModel diagram = conceptualDiagram(findDiagram(diagramName));
          Map<String, Object> validation = validateDiagram(diagram);
          if (!Boolean.TRUE.equals(validation.get("valid"))) {
            throw BridgeException.conflict(
                "CONCEPTUAL_ERD_VALIDATION_FAILED",
                "$.diagramName",
                "Refusing submission cleanup because the diagram is invalid: "
                    + validation.get("violations"));
          }
          int cleared = 0;
          Set<IModelElement> visited = new HashSet<>();
          Iterator<?> iterator = diagram.diagramElementIterator();
          while (iterator.hasNext()) {
            Object item = iterator.next();
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
          result.put("validation", validateDiagram(diagram));
          return result;
        });
  }

  private static LayoutPlan plan(ConceptualErdSpec spec) {
    ConceptualErdSpec.LayoutSpec layout =
        spec.layout == null ? new ConceptualErdSpec.LayoutSpec() : spec.layout;
    LayoutPlan plan = new LayoutPlan();
    for (ConceptualErdSpec.EntitySpec entity : spec.entities) {
      int naturalWidth = Math.max(100, 32 + clean(entity.name).length() * 8);
      int actualWidth = Math.min(layout.entityWidth, naturalWidth);
      int cellX = layout.originX + entity.column * (layout.entityWidth + layout.horizontalGap);
      int x = cellX + (layout.entityWidth - actualWidth) / 2;
      int y = layout.originY + entity.row * (layout.entityHeight + layout.verticalGap);
      plan.bounds.put(
          entity.id, new Bounds(x, y, actualWidth, layout.entityHeight));
    }
    return plan;
  }

  private static void configureDiagram(IERDiagramUIModel diagram) {
    diagram.setDataModel(IDBTable.DATA_MODEL_CONCEPTUAL);
    try {
      diagram.setShowDBTableColumnsMode(IDBTableUIModel.SHOW_COLUMNS_MODE_SHOW_ALL);
    } catch (UnsupportedOperationException ignored) {
      // Conceptual ERDs in some VP releases control this only at entity-view level.
    }
    try {
      diagram.setShowColumnTypes(false);
    } catch (UnsupportedOperationException ignored) {
      // Column types are not part of a Conceptual ERD.
    }
    try {
      diagram.setShowSchemaName(false);
    } catch (UnsupportedOperationException ignored) {
      // Schemas are a Logical/Physical concern and may be unsupported here.
    }
    diagram.setShowConnectorName(IDiagramUIModel.SHOW_CONNECTOR_NAME_YES);
    diagram.setPaintConnectorThroughLabel(IDiagramUIModel.PAINT_CONNECTOR_THROUGH_LABEL_NO);
    diagram.setConnectorLabelOrientation(IConnectorUIModel.CLO_HORIZONTAL_ONLY);
    try {
      diagram.setForeignKeyArrowHeadSize(12);
    } catch (UnsupportedOperationException ignored) {
      // Keep the Visual Paradigm Conceptual default when this option is unavailable.
    }
  }

  private static void configureEntityShape(IDBTableUIModel shape) {
    shape.setShowColumnsMode(IDBTableUIModel.SHOW_COLUMNS_MODE_SHOW_ALL);
    shape.getFillColor().setType(IFillColor.TYPE_SOLID);
    shape.getFillColor().setColor1(VP_DESKTOP_CONCEPTUAL_ENTITY);
    shape.getFillColor().setColor2(VP_DESKTOP_CONCEPTUAL_ENTITY);
    shape.getFillColor().setTransparency(IFillColor.OPAQUE);
    shape.getFillColor().applySetting();
    shape.resetCaption();
    shape.resetCaptionSize();
  }

  private static void routeRelationship(IConnectorUIModel connector, int style) {
    IDiagramElement from = connector.getFromShape();
    IDiagramElement to = connector.getToShape();
    if (from == null || to == null) {
      return;
    }
    int fromCx = from.getX() + from.getWidth() / 2;
    int fromCy = from.getY() + from.getHeight() / 2;
    int toCx = to.getX() + to.getWidth() / 2;
    int toCy = to.getY() + to.getHeight() / 2;
    boolean horizontal = Math.abs(toCx - fromCx) >= Math.abs(toCy - fromCy);
    Point fromPoint;
    Point toPoint;
    if (horizontal) {
      boolean right = toCx > fromCx;
      fromPoint = new Point(right ? from.getX() + from.getWidth() : from.getX(), fromCy);
      toPoint = new Point(right ? to.getX() : to.getX() + to.getWidth(), toCy);
    } else {
      boolean down = toCy > fromCy;
      fromPoint = new Point(fromCx, down ? from.getY() + from.getHeight() : from.getY());
      toPoint = new Point(toCx, down ? to.getY() : to.getY() + to.getHeight());
    }
    connector.clearPoints();
    connector.addPoint(fromPoint);
    connector.addPoint(toPoint);
    connector.setConnectorStyle(style);
    connector.setConnectorLabelOrientation(IConnectorUIModel.CLO_HORIZONTAL_ONLY);
    connector.setPaintThroughLabel(IDiagramUIModel.PAINT_CONNECTOR_THROUGH_LABEL_NO);
    connector.resetCaption();
    connector.resetCaptionSize();
    ICaptionUIModel caption = connector.getCaptionUIModel();
    if (caption != null) {
      int middleX = (fromPoint.x + toPoint.x) / 2;
      int middleY = (fromPoint.y + toPoint.y) / 2;
      int labelWidth = Math.max(90, clean(connector.getModelElement().getName()).length() * 8);
      caption.setVisible(true);
      caption.setSide(ICaptionUIModel.SIDE_FREEMOVE);
      caption.setBounds(middleX - labelWidth / 2, middleY - 28, labelWidth, 24);
    }
  }

  private static String cardinalityValue(String cardinality) {
    switch (cardinality) {
      case "ONE":
        return IDBForeignKey.TO_MULTIPLICITY_ONE;
      case "ZERO_OR_ONE":
        return IDBForeignKey.TO_MULTIPLICITY_ZERO_TO_ONE;
      case "ONE_OR_MANY":
        return IDBForeignKey.TO_MULTIPLICITY_ONE_TO_MANY;
      case "ZERO_OR_MANY":
        return IDBForeignKey.TO_MULTIPLICITY_ZERO_TO_MANY;
      default:
        throw new IllegalArgumentException("Unsupported cardinality: " + cardinality);
    }
  }

  private static String cardinalityName(String value) {
    if (IDBForeignKey.TO_MULTIPLICITY_ONE.equals(value)) {
      return "ONE";
    }
    if (IDBForeignKey.TO_MULTIPLICITY_ZERO_TO_ONE.equals(value)) {
      return "ZERO_OR_ONE";
    }
    if (IDBForeignKey.TO_MULTIPLICITY_ONE_TO_MANY.equals(value)) {
      return "ONE_OR_MANY";
    }
    if (IDBForeignKey.TO_MULTIPLICITY_ZERO_TO_MANY.equals(value)) {
      return "ZERO_OR_MANY";
    }
    return clean(value);
  }

  private static int connectorStyle(ConceptualErdSpec.LayoutSpec layout) {
    return layout != null && "RECTILINEAR".equals(layout.connectorStyle)
        ? IConnectorUIModel.CS_RECTI_LINEAR
        : IConnectorUIModel.CS_OBLIQUE;
  }

  private static void ensureDiagramNameAvailable(String name) {
    // createDiagram performs the same check, but this keeps the mutation boundary explicit.
    requireText(name, "diagramName");
  }

  private static IERDiagramUIModel conceptualDiagram(IDiagramUIModel diagram) {
    if (!(diagram instanceof IERDiagramUIModel)) {
      throw BridgeException.conflict(
          "WRONG_DIAGRAM_TYPE",
          "$.diagramName",
          "Diagram is not a native Entity Relationship Diagram: " + diagram.getName());
    }
    IERDiagramUIModel erd = (IERDiagramUIModel) diagram;
    if (erd.getDataModel() != IDBTable.DATA_MODEL_CONCEPTUAL) {
      throw BridgeException.conflict(
          "WRONG_ERD_DATA_MODEL",
          "$.diagramName",
          "ER Diagram is not in Conceptual data-model mode: " + diagram.getName());
    }
    return erd;
  }

  private static Map<String, Object> snapshot(IERDiagramUIModel diagram) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("diagramId", diagram.getId());
    result.put("diagramName", diagram.getName());
    result.put("diagramType", diagram.getType());
    result.put("dataModel", "CONCEPTUAL");
    List<Map<String, Object>> entities = new ArrayList<>();
    List<Map<String, Object>> relationships = new ArrayList<>();
    Iterator<?> iterator = diagram.diagramElementIterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (!(item instanceof IDiagramElement)) {
        continue;
      }
      IDiagramElement element = (IDiagramElement) item;
      IModelElement model = element.getModelElement();
      if (model instanceof IDBTable) {
        IDBTable entity = (IDBTable) model;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", metadata(model.getDocumentation(), ID_TAG));
        value.put("name", model.getName());
        value.put("description", metadata(model.getDocumentation(), DESCRIPTION_TAG));
        value.put("columnCount", entity.dBColumnCount());
        value.put("x", element.getX());
        value.put("y", element.getY());
        value.put("width", element.getWidth());
        value.put("height", element.getHeight());
        entities.add(value);
      } else if (model instanceof IDBForeignKey && element instanceof IConnectorUIModel) {
        IDBForeignKey relationship = (IDBForeignKey) model;
        IConnectorUIModel connector = (IConnectorUIModel) element;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", metadata(model.getDocumentation(), ID_TAG));
        value.put("name", model.getName());
        value.put(
            "from",
            metadata(
                connector.getFromShape().getModelElement().getDocumentation(), ID_TAG));
        value.put(
            "to",
            metadata(connector.getToShape().getModelElement().getDocumentation(), ID_TAG));
        value.put("fromCardinality", cardinalityName(relationship.getFromMultiplicity()));
        value.put("toCardinality", cardinalityName(relationship.getToMultiplicity()));
        value.put("identifying", relationship.isIdentifying());
        relationships.add(value);
      }
    }
    entities.sort(Comparator.comparing(item -> String.valueOf(item.get("id"))));
    relationships.sort(Comparator.comparing(item -> String.valueOf(item.get("id"))));
    result.put("entities", entities);
    result.put("relationships", relationships);
    return result;
  }

  private static Map<String, Object> validateDiagram(IERDiagramUIModel diagram) {
    List<String> violations = new ArrayList<>();
    List<IDiagramElement> entities = new ArrayList<>();
    List<IConnectorUIModel> relationships = new ArrayList<>();
    Set<IModelElement> connected = new HashSet<>();
    Iterator<?> iterator = diagram.diagramElementIterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (!(item instanceof IDiagramElement)) {
        continue;
      }
      IDiagramElement element = (IDiagramElement) item;
      IModelElement model = element.getModelElement();
      if (model instanceof IDBTable) {
        IDBTable entity = (IDBTable) model;
        entities.add(element);
        if (entity.getDataModel() != IDBTable.DATA_MODEL_CONCEPTUAL) {
          violations.add("Entity is not conceptual: " + entity.getName());
        }
        if (entity.dBColumnCount() != 0) {
          violations.add("Conceptual entity must not contain columns: " + entity.getName());
        }
        if (clean(entity.getName()).isEmpty()) {
          violations.add("Entity name must not be blank");
        }
      } else if (model instanceof IDBForeignKey && element instanceof IConnectorUIModel) {
        IDBForeignKey relationship = (IDBForeignKey) model;
        IConnectorUIModel connector = (IConnectorUIModel) element;
        relationships.add(connector);
        if (connector.getFromShape() == null || connector.getToShape() == null) {
          violations.add("A relationship has a missing endpoint");
          continue;
        }
        IModelElement from = connector.getFromShape().getModelElement();
        IModelElement to = connector.getToShape().getModelElement();
        connected.add(from);
        connected.add(to);
        if (!(from instanceof IDBTable) || !(to instanceof IDBTable)) {
          violations.add("ERD relationship must connect two entities");
        }
        if (relationship.getDataModel() != IDBForeignKey.DATA_MODEL_CONCEPTUAL) {
          violations.add("Relationship is not conceptual: " + relationship.getName());
        }
        if (clean(relationship.getName()).isEmpty()) {
          violations.add("Every conceptual relationship needs a business verb");
        }
        if (clean(relationship.getFromMultiplicity()).isEmpty()
            || clean(relationship.getToMultiplicity()).isEmpty()) {
          violations.add("Relationship needs cardinality at both ends: " + relationship.getName());
        }
      }
    }
    if (diagram.getDataModel() != IDBTable.DATA_MODEL_CONCEPTUAL) {
      violations.add("Diagram data model must be Conceptual");
    }
    if (entities.size() < 2) {
      violations.add("At least two entities are required");
    }
    if (relationships.isEmpty()) {
      violations.add("At least one relationship is required");
    }
    for (int i = 0; i < entities.size(); i++) {
      IDiagramElement entity = entities.get(i);
      if (!connected.contains(entity.getModelElement())) {
        violations.add("Entity is orphaned: " + entity.getModelElement().getName());
      }
      for (int j = i + 1; j < entities.size(); j++) {
        if (overlaps(entity, entities.get(j))) {
          violations.add(
              "Entity shapes overlap: "
                  + entity.getModelElement().getName()
                  + " / "
                  + entities.get(j).getModelElement().getName());
        }
      }
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("valid", violations.isEmpty());
    result.put("dataModel", "CONCEPTUAL");
    result.put("entityCount", entities.size());
    result.put("relationshipCount", relationships.size());
    result.put("violations", violations);
    return result;
  }

  private static boolean overlaps(IDiagramElement first, IDiagramElement second) {
    return first.getX() < second.getX() + second.getWidth()
        && first.getX() + first.getWidth() > second.getX()
        && first.getY() < second.getY() + second.getHeight()
        && first.getY() + first.getHeight() > second.getY();
  }

  private static String metadata(String id, List<String> factIds, String description) {
    StringBuilder result = new StringBuilder(ID_TAG).append(clean(id));
    if (factIds != null && !factIds.isEmpty()) {
      result.append('\n').append(FACTS_TAG).append(String.join(",", factIds));
    }
    if (description != null && !description.trim().isEmpty()) {
      result.append('\n').append(DESCRIPTION_TAG).append(description.trim());
    }
    return result.toString();
  }

  private static String metadata(String documentation, String tag) {
    if (documentation == null) {
      return "";
    }
    for (String line : documentation.split("\\R")) {
      if (line.startsWith(tag)) {
        return line.substring(tag.length()).trim();
      }
    }
    return "";
  }

  private static Map<String, Object> validValidation() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("valid", true);
    result.put("violations", Collections.emptyList());
    return result;
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
        // Visual Paradigm OpenAPI exposes no transaction rollback.
      }
    }
  }

  private static final class LayoutPlan {
    private final Map<String, Bounds> bounds = new LinkedHashMap<>();

    private Map<String, Object> snapshot() {
      Map<String, Object> result = new LinkedHashMap<>();
      for (Map.Entry<String, Bounds> entry : bounds.entrySet()) {
        Bounds bounds = entry.getValue();
        result.put(
            entry.getKey(),
            Map.of(
                "x", bounds.x,
                "y", bounds.y,
                "width", bounds.width,
                "height", bounds.height));
      }
      return result;
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
}

package vn.edu.swd392.vpmcp.tools;

import com.vp.plugin.diagram.ICaptionUIModel;
import com.vp.plugin.diagram.IConnectorUIModel;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IDiagramTypeConstants;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.diagram.IShapeUIModel;
import com.vp.plugin.diagram.connector.IAssociationUIModel;
import com.vp.plugin.diagram.connector.IExtendUIModel;
import com.vp.plugin.diagram.connector.IGeneralizationUIModel;
import com.vp.plugin.diagram.connector.IIncludeUIModel;
import com.vp.plugin.diagram.format.IFillColor;
import com.vp.plugin.diagram.shape.IActorUIModel;
import com.vp.plugin.diagram.shape.ISystemUIModel;
import com.vp.plugin.diagram.shape.IUseCaseUIModel;
import com.vp.plugin.model.IActor;
import com.vp.plugin.model.IAssociation;
import com.vp.plugin.model.IExtend;
import com.vp.plugin.model.IGeneralization;
import com.vp.plugin.model.IInclude;
import com.vp.plugin.model.IModelElement;
import com.vp.plugin.model.IProjectTransaction;
import com.vp.plugin.model.IRelationship;
import com.vp.plugin.model.ISystem;
import com.vp.plugin.model.IUseCase;
import java.awt.Color;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.stream.Collectors;
import vn.edu.swd392.vpmcp.bridge.BridgeException;
import vn.edu.swd392.vpmcp.bridge.Tool;

public final class UseCaseDiagramTools extends VpAccess {
  private static final String ID_TAG = "vp-mcp:id=";
  private static final String FACTS_TAG = "vp-mcp:facts=";
  private static final String DESCRIPTION_TAG = "vp-mcp:description=";
  private static final Color DEFAULT_BLUE = new Color(124, 200, 235);
  private static final int RELATIONSHIP_LABEL_GAP = 24;
  private static final int ACTOR_VERTICAL_GAP = 40;

  @Tool(
      description =
          "Create one native UML Use Case Diagram from a strict declarative specification. "
              + "Actors remain outside the named system boundary; verb-led use cases remain inside. "
              + "Associations are solid participation links. INCLUDE is base-to-included, EXTEND is "
              + "extension-to-base, and GENERALIZATION is specialized-to-general.",
      inputSchema = UseCaseDiagramSchemas.CREATE_USE_CASE,
      idempotent = true)
  public Map<String, Object> createUseCaseDiagram(UseCaseDiagramSpec spec, boolean dryRun)
      throws Exception {
    UseCaseDiagramSpec actual = UseCaseDiagramSpecValidator.validate(spec);
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
          Iterator<?> diagramsIterator = project().diagramIterator();
          while (diagramsIterator.hasNext()) {
            Object item = diagramsIterator.next();
            if (item instanceof IDiagramUIModel
                && actual.diagramName.equals(((IDiagramUIModel) item).getName())) {
              throw BridgeException.conflict(
                  "DUPLICATE_DIAGRAM",
                  "$.spec.diagramName",
                  "Diagram already exists: " + actual.diagramName);
            }
          }

          IDiagramUIModel diagram = null;
          List<IModelElement> createdModels = new ArrayList<>();
          IProjectTransaction transaction = project().startProjectTransaction();
          try {
            diagram =
                createDiagram(
                    IDiagramTypeConstants.DIAGRAM_TYPE_USE_CASE_DIAGRAM, actual.diagramName);
            configureDiagram(diagram);

            Map<String, IDiagramElement> elements = new LinkedHashMap<>();
            ISystem system = models().createSystem();
            createdModels.add(system);
            system.setDocumentation(metadata("system", Collections.emptyList(), ""));
            Bounds boundary = plan.bounds.get("system");
            IDiagramElement systemShape =
                addShape(
                    diagram,
                    system,
                    actual.systemName,
                    boundary.x,
                    boundary.y,
                    boundary.width,
                    boundary.height);
            if (!(systemShape instanceof ISystemUIModel)) {
              throw new IllegalStateException("Visual Paradigm did not create a native System view");
            }
            styleShape(systemShape);
            layoutSystemCaption(systemShape);
            elements.put("system", systemShape);

            for (UseCaseDiagramSpec.UseCaseSpec useCaseSpec : actual.useCases) {
              IUseCase useCase = system.createUseCase();
              createdModels.add(useCase);
              useCase.setAbstract(useCaseSpec.abstractUseCase);
              useCase.setDocumentation(
                  metadata(useCaseSpec.id, useCaseSpec.factIds, useCaseSpec.description));
              Bounds bounds = plan.bounds.get(useCaseSpec.id);
              IDiagramElement shape =
                  addShape(
                      diagram,
                      useCase,
                      useCaseSpec.name,
                      bounds.x,
                      bounds.y,
                      bounds.width,
                      bounds.height);
              if (!(shape instanceof IUseCaseUIModel)) {
                throw new IllegalStateException(
                    "Visual Paradigm did not create a native UseCase view");
              }
              styleShape(shape);
              elements.put(useCaseSpec.id, shape);
            }

            for (UseCaseDiagramSpec.ActorSpec actorSpec : actual.actors) {
              IActor actor = models().createActor();
              createdModels.add(actor);
              actor.setDocumentation(metadata(actorSpec.id, actorSpec.factIds, ""));
              Bounds bounds = plan.bounds.get(actorSpec.id);
              IDiagramElement shape =
                  addShape(
                      diagram,
                      actor,
                      actorSpec.name,
                      bounds.x,
                      bounds.y,
                      bounds.width,
                      bounds.height);
              if (!(shape instanceof IActorUIModel)) {
                throw new IllegalStateException("Visual Paradigm did not create a native Actor view");
              }
              styleShape(shape);
              layoutActorCaption(shape);
              elements.put(actorSpec.id, shape);
            }

            int connectorStyle = connectorStyle(actual.layout.connectorStyle);
            for (UseCaseDiagramSpec.AssociationSpec associationSpec : actual.associations) {
              IAssociation association = models().createAssociation();
              createdModels.add(association);
              association.setName("");
              association.setDocumentation(
                  metadata(associationSpec.id, associationSpec.factIds, ""));
              IDiagramElement connector =
                  addConnector(
                      diagram,
                      association,
                      elements.get(associationSpec.actorId),
                      elements.get(associationSpec.useCaseId));
              if (!(connector instanceof IAssociationUIModel)) {
                throw new IllegalStateException(
                    "Visual Paradigm did not create a native Association view");
              }
              configureConnector((IConnectorUIModel) connector, connectorStyle);
            }

            for (UseCaseDiagramSpec.RelationshipSpec relationshipSpec : actual.relationships) {
              IRelationship relationship;
              Class<?> expectedView;
              switch (relationshipSpec.type) {
                case "INCLUDE":
                  relationship = models().createInclude();
                  expectedView = IIncludeUIModel.class;
                  break;
                case "EXTEND":
                  IExtend extend = models().createExtend();
                  extend.setCondition(clean(relationshipSpec.condition));
                  relationship = extend;
                  expectedView = IExtendUIModel.class;
                  break;
                case "GENERALIZATION":
                  relationship = models().createGeneralization();
                  expectedView = IGeneralizationUIModel.class;
                  break;
                default:
                  throw new IllegalStateException(
                      "Validated relationship type is unsupported: " + relationshipSpec.type);
              }
              createdModels.add(relationship);
              relationship.setDocumentation(
                  metadata(
                      relationshipSpec.id,
                      relationshipSpec.factIds,
                      relationshipSpec.rationale));
              IDiagramElement relationshipFrom = elements.get(relationshipSpec.from);
              IDiagramElement relationshipTo = elements.get(relationshipSpec.to);
              if ("EXTEND".equals(relationshipSpec.type)) {
                // Visual Paradigm's OpenAPI expects base use case at the model/view From end
                // and extension use case at the To end. The declarative contract intentionally
                // uses UML reading direction (extension -> base), so adapt it here.
                relationshipFrom = elements.get(relationshipSpec.to);
                relationshipTo = elements.get(relationshipSpec.from);
              }
              IDiagramElement connector =
                  addConnector(
                      diagram,
                      relationship,
                      relationshipFrom,
                      relationshipTo);
              if (!expectedView.isInstance(connector)) {
                throw new IllegalStateException(
                    "Visual Paradigm created the wrong view for " + relationshipSpec.type);
              }
              if (connector instanceof IExtendUIModel) {
                ((IExtendUIModel) connector).setShowCondition(IExtendUIModel.SHOW_CONDITION_YES);
                IDiagramElement baseUseCase = elements.get(relationshipSpec.to);
                if (baseUseCase instanceof IUseCaseUIModel) {
                  ((IUseCaseUIModel) baseUseCase).setShowExtensionPoint(false);
                }
              }
              configureConnector((IConnectorUIModel) connector, connectorStyle);
            }

            diagrams().openDiagram(diagram);
            Map<String, Object> validation = validateDiagram(diagram);
            if (!Boolean.TRUE.equals(validation.get("valid"))) {
              throw new IllegalStateException(
                  "Created Use Case Diagram failed its native validation gate: "
                      + validation.get("violations"));
            }
            Map<String, Object> result = snapshot(diagram);
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
          "Inspect one native UML Use Case Diagram as system boundaries, actors, use cases, "
              + "associations, include/extend/generalization relationships, bounds, and stable IDs.",
      readOnly = true,
      idempotent = true)
  public Map<String, Object> inspectUseCaseDiagram(String diagramName) throws Exception {
    return onEdt(() -> snapshot(useCaseDiagram(diagramName)));
  }

  @Tool(
      description =
          "Validate that a native UML Use Case Diagram has one named system boundary, actors "
              + "outside it, named use cases inside it, correct endpoint types and relationship "
              + "directions, no orphan actors/use cases, and no overlapping use-case shapes.",
      readOnly = true,
      idempotent = true)
  public Map<String, Object> validateUseCaseDiagram(String diagramName) throws Exception {
    return onEdt(() -> validateDiagram(useCaseDiagram(diagramName)));
  }

  @Tool(
      description =
          "Repair native Use Case presentation without changing semantics: show and place the "
              + "system/actor captions, keep the blue default style, and reroute every actor-use-case "
              + "association as a direct solid line between the correct shape edges.")
  public Map<String, Object> repairUseCaseDiagramPresentation(String diagramName)
      throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = useCaseDiagram(diagramName);
          List<IConnectorUIModel> associations = new ArrayList<>();
          List<IConnectorUIModel> useCaseRelationships = new ArrayList<>();
          Iterator<?> iterator = diagram.diagramElementIterator();
          while (iterator.hasNext()) {
            Object item = iterator.next();
            if (!(item instanceof IDiagramElement)) {
              continue;
            }
            IDiagramElement element = (IDiagramElement) item;
            IModelElement model = element.getModelElement();
            if (model instanceof ISystem) {
              styleShape(element);
              layoutSystemCaption(element);
            } else if (model instanceof IActor) {
              styleShape(element);
              layoutActorCaption(element);
            } else if (model instanceof IUseCase) {
              styleShape(element);
              element.resetCaption();
            } else if (model instanceof IAssociation && element instanceof IConnectorUIModel) {
              associations.add((IConnectorUIModel) element);
            } else if ((model instanceof IInclude
                    || model instanceof IExtend
                    || model instanceof IGeneralization)
                && element instanceof IConnectorUIModel) {
              useCaseRelationships.add((IConnectorUIModel) element);
            }
          }
          routeAssociations(associations);
          for (IConnectorUIModel relationship : useCaseRelationships) {
            routeUseCaseRelationship(relationship);
          }
          Map<String, Object> result = snapshot(diagram);
          result.put("validation", validateDiagram(diagram));
          return result;
        });
  }

  @Tool(
      description =
          "Deterministically relayout an existing native UML Use Case Diagram from its original "
              + "validated specification. Use cases remain in left/right lanes but receive extra "
              + "whitespace between named business groups; actors are centered on the use cases "
              + "they participate in and relationship pairs remain local.",
      inputSchema = UseCaseDiagramSchemas.CREATE_USE_CASE,
      idempotent = true)
  public Map<String, Object> relayoutUseCaseDiagram(UseCaseDiagramSpec spec, boolean dryRun)
      throws Exception {
    UseCaseDiagramSpec actual = UseCaseDiagramSpecValidator.validate(spec);
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
          IDiagramUIModel diagram = useCaseDiagram(actual.diagramName);
          Map<String, IDiagramElement> elements = new LinkedHashMap<>();
          Map<String, String> expectedIdByName = new HashMap<>();
          expectedIdByName.put(actual.systemName, "system");
          for (UseCaseDiagramSpec.ActorSpec actor : actual.actors) {
            expectedIdByName.put(actor.name, actor.id);
          }
          for (UseCaseDiagramSpec.UseCaseSpec useCase : actual.useCases) {
            expectedIdByName.put(useCase.name, useCase.id);
          }
          List<IConnectorUIModel> associations = new ArrayList<>();
          List<IConnectorUIModel> useCaseRelationships = new ArrayList<>();
          Iterator<?> iterator = diagram.diagramElementIterator();
          while (iterator.hasNext()) {
            Object item = iterator.next();
            if (!(item instanceof IDiagramElement)) {
              continue;
            }
            IDiagramElement element = (IDiagramElement) item;
            IModelElement model = element.getModelElement();
            String id = metadata(model.getDocumentation(), ID_TAG);
            if (model instanceof ISystem
                || model instanceof IActor
                || model instanceof IUseCase) {
              if (id.isEmpty()) {
                id = expectedIdByName.getOrDefault(clean(model.getName()), "");
              }
              elements.put(id, element);
            } else if (model instanceof IAssociation
                && element instanceof IConnectorUIModel) {
              associations.add((IConnectorUIModel) element);
            } else if ((model instanceof IInclude
                    || model instanceof IExtend
                    || model instanceof IGeneralization)
                && element instanceof IConnectorUIModel) {
              useCaseRelationships.add((IConnectorUIModel) element);
            }
          }

          for (Map.Entry<String, Bounds> entry : plan.bounds.entrySet()) {
            IDiagramElement element = elements.get(entry.getKey());
            if (element == null) {
              throw BridgeException.conflict(
                  "MISSING_DIAGRAM_ELEMENT",
                  "$.spec",
                  "Existing diagram is missing stable element id: " + entry.getKey());
            }
            Bounds bounds = entry.getValue();
            element.setBounds(bounds.x, bounds.y, bounds.width, bounds.height);
            styleShape(element);
            if (element.getModelElement() instanceof ISystem) {
              layoutSystemCaption(element);
            } else if (element.getModelElement() instanceof IActor) {
              layoutActorCaption(element);
            } else {
              element.resetCaption();
              element.resetCaptionSize();
            }
          }

          routeAssociations(associations);
          for (IConnectorUIModel relationship : useCaseRelationships) {
            routeUseCaseRelationship(relationship);
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
          "Prepare one validated Use Case Diagram for a clean submission screenshot by removing "
              + "the internal vp-mcp Documentation metadata that Visual Paradigm renders as small "
              + "document icons. Call only after semantic creation and final relayout because stable "
              + "tool IDs and descriptions are intentionally removed from the .vpp presentation.")
  public Map<String, Object> prepareUseCaseDiagramForSubmission(String diagramName)
      throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = useCaseDiagram(diagramName);
          Map<String, Object> validation = validateDiagram(diagram);
          if (!Boolean.TRUE.equals(validation.get("valid"))) {
            throw BridgeException.conflict(
                "USE_CASE_VALIDATION_FAILED",
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

  @Tool(
      description =
          "Replace one native include/extend/generalization connector between two named use cases. "
              + "Directions follow UML: INCLUDE is base-to-included, EXTEND is extension-to-base, "
              + "and GENERALIZATION is specialized-to-general. A non-empty business rationale and "
              + "verified fact IDs are mandatory; EXTEND requires a condition while INCLUDE and "
              + "GENERALIZATION forbid one.")
  public Map<String, Object> replaceUseCaseRelationship(
      String diagramName,
      String existingFirstUseCaseName,
      String existingSecondUseCaseName,
      String newType,
      String newFromUseCaseName,
      String newToUseCaseName,
      String condition,
      String rationale,
      String factIdsCsv)
      throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = useCaseDiagram(diagramName);
          String normalizedType = clean(newType).toUpperCase(Locale.ROOT);
          if (!"INCLUDE".equals(normalizedType)
              && !"EXTEND".equals(normalizedType)
              && !"GENERALIZATION".equals(normalizedType)) {
            throw BridgeException.invalidArgument(
                "$.newType", "newType must be INCLUDE, EXTEND, or GENERALIZATION");
          }
          String normalizedCondition = clean(condition);
          if ("EXTEND".equals(normalizedType) && normalizedCondition.isEmpty()) {
            throw BridgeException.invalidArgument(
                "$.condition", "EXTEND requires an explicit business condition");
          }
          if (!"EXTEND".equals(normalizedType) && !normalizedCondition.isEmpty()) {
            throw BridgeException.invalidArgument(
                "$.condition", normalizedType + " does not accept an execution condition");
          }
          if (clean(rationale).isEmpty()) {
            throw BridgeException.invalidArgument(
                "$.rationale", "A verified business rationale is required");
          }
          List<String> verifiedFactIds =
              Arrays.stream(clean(factIdsCsv).split(","))
                  .map(String::trim)
                  .filter(value -> !value.isEmpty())
                  .distinct()
                  .collect(Collectors.toList());
          if (verifiedFactIds.isEmpty()) {
            throw BridgeException.invalidArgument(
                "$.factIdsCsv", "At least one verified requirement fact ID is required");
          }

          Map<String, IDiagramElement> useCasesByName = new HashMap<>();
          List<IDiagramElement> candidates = new ArrayList<>();
          Iterator<?> iterator = diagram.diagramElementIterator();
          while (iterator.hasNext()) {
            Object item = iterator.next();
            if (!(item instanceof IDiagramElement)) {
              continue;
            }
            IDiagramElement element = (IDiagramElement) item;
            IModelElement model = element.getModelElement();
            if (model instanceof IUseCase) {
              useCasesByName.put(clean(model.getName()), element);
            } else if ((model instanceof IInclude
                    || model instanceof IExtend
                    || model instanceof IGeneralization)
                && element instanceof IConnectorUIModel) {
              IConnectorUIModel connector = (IConnectorUIModel) element;
              String fromName = clean(connector.getFromShape().getModelElement().getName());
              String toName = clean(connector.getToShape().getModelElement().getName());
              boolean samePair =
                  (clean(existingFirstUseCaseName).equals(fromName)
                          && clean(existingSecondUseCaseName).equals(toName))
                      || (clean(existingFirstUseCaseName).equals(toName)
                          && clean(existingSecondUseCaseName).equals(fromName));
              if (samePair) {
                candidates.add(element);
              }
            }
          }
          if (candidates.size() != 1) {
            throw BridgeException.conflict(
                "USE_CASE_RELATIONSHIP_NOT_UNIQUE",
                "$.existingFirstUseCaseName",
                "Expected exactly one existing use-case relationship between the named pair, found "
                    + candidates.size());
          }
          IDiagramElement from = useCasesByName.get(clean(newFromUseCaseName));
          IDiagramElement to = useCasesByName.get(clean(newToUseCaseName));
          if (from == null || to == null) {
            throw BridgeException.invalidArgument(
                "$.newFromUseCaseName", "New relationship endpoints must name existing use cases");
          }

          IProjectTransaction transaction = project().startProjectTransaction();
          try {
            IDiagramElement oldConnector = candidates.get(0);
            IModelElement oldRelationship = oldConnector.getModelElement();
            diagram.removeDiagramElement(oldConnector);
            oldRelationship.delete();

            IRelationship relationship;
            if ("INCLUDE".equals(normalizedType)) {
              relationship = models().createInclude();
            } else if ("EXTEND".equals(normalizedType)) {
              IExtend extend = models().createExtend();
              extend.setCondition(normalizedCondition);
              relationship = extend;
            } else {
              relationship = models().createGeneralization();
            }
            relationship.setDocumentation(
                metadata(
                    "replacement-" + normalizedType.toLowerCase(Locale.ROOT),
                    verifiedFactIds,
                    clean(rationale)));
            IDiagramElement connectorFrom = from;
            IDiagramElement connectorTo = to;
            if ("EXTEND".equals(normalizedType)) {
              // Adapt semantic UML direction (extension -> base) to Visual Paradigm's
              // OpenAPI endpoint convention (base From, extension To).
              connectorFrom = to;
              connectorTo = from;
            }
            IDiagramElement newConnector =
                addConnector(diagram, relationship, connectorFrom, connectorTo);
            if (newConnector instanceof IExtendUIModel) {
              ((IExtendUIModel) newConnector).setShowCondition(IExtendUIModel.SHOW_CONDITION_YES);
              if (to instanceof IUseCaseUIModel) {
                ((IUseCaseUIModel) to).setShowExtensionPoint(false);
              }
            }
            configureConnector((IConnectorUIModel) newConnector, IConnectorUIModel.CS_OBLIQUE);
            routeUseCaseRelationship((IConnectorUIModel) newConnector);
          } finally {
            transaction.endTransaction();
          }
          Map<String, Object> result = snapshot(diagram);
          result.put("validation", validateDiagram(diagram));
          return result;
        });
  }

  private IDiagramUIModel useCaseDiagram(String diagramName) {
    IDiagramUIModel diagram = findDiagram(diagramName);
    if (!IDiagramTypeConstants.DIAGRAM_TYPE_USE_CASE_DIAGRAM.equals(diagram.getType())) {
      throw BridgeException.invalidArgument(
          "$.diagramName", "Target diagram is not a native UML Use Case Diagram");
    }
    return diagram;
  }

  private static LayoutPlan plan(UseCaseDiagramSpec spec) {
    UseCaseDiagramSpec.LayoutSpec layout = spec.layout;
    List<UseCaseDiagramSpec.ActorSpec> leftActors = new ArrayList<>();
    List<UseCaseDiagramSpec.ActorSpec> rightActors = new ArrayList<>();
    for (UseCaseDiagramSpec.ActorSpec actor : spec.actors) {
      ("RIGHT".equals(actor.side) ? rightActors : leftActors).add(actor);
    }
    int useCaseContentHeight = groupedUseCaseContentHeight(spec, layout);
    int maximumActorCount = Math.max(leftActors.size(), rightActors.size());
    int actorContentHeight =
        maximumActorCount == 0
            ? 0
            : maximumActorCount * layout.actorHeight
                + Math.max(0, maximumActorCount - 1) * ACTOR_VERTICAL_GAP;
    int boundaryHeight =
        layout.topPadding
            + Math.max(useCaseContentHeight, actorContentHeight)
            + layout.bottomPadding;
    LayoutPlan plan = new LayoutPlan();
    plan.bounds.put(
        "system",
        new Bounds(
            layout.boundaryX, layout.boundaryY, layout.boundaryWidth, boundaryHeight));
    int leftX = layout.boundaryX + 90;
    int rightX = layout.boundaryX + layout.boundaryWidth - 90 - layout.useCaseWidth;
    addGroupedUseCaseBounds(plan, spec, layout, leftX, rightX);
    addActorBounds(plan, leftActors, false, spec, layout, boundaryHeight);
    addActorBounds(plan, rightActors, true, spec, layout, boundaryHeight);
    return plan;
  }

  private static List<String> businessGroups(UseCaseDiagramSpec spec) {
    List<String> groups = new ArrayList<>();
    for (UseCaseDiagramSpec.UseCaseSpec useCase : spec.useCases) {
      String group = clean(useCase.group);
      if (!groups.contains(group)) {
        groups.add(group);
      }
    }
    return groups;
  }

  private static List<UseCaseDiagramSpec.UseCaseSpec> groupLaneUseCases(
      UseCaseDiagramSpec spec, String group, String lane) {
    List<UseCaseDiagramSpec.UseCaseSpec> result = new ArrayList<>();
    for (UseCaseDiagramSpec.UseCaseSpec useCase : spec.useCases) {
      if (group.equals(clean(useCase.group)) && lane.equals(useCase.lane)) {
        result.add(useCase);
      }
    }
    return result;
  }

  private static int groupedUseCaseContentHeight(
      UseCaseDiagramSpec spec, UseCaseDiagramSpec.LayoutSpec layout) {
    int height = 0;
    List<String> groups = businessGroups(spec);
    for (int index = 0; index < groups.size(); index++) {
      String group = groups.get(index);
      List<UseCaseDiagramSpec.UseCaseSpec> left =
          groupLaneUseCases(spec, group, "LEFT");
      List<UseCaseDiagramSpec.UseCaseSpec> right =
          groupLaneUseCases(spec, group, "RIGHT");
      if (index > 0) {
        height += layout.groupGap;
      }
      height +=
          Math.max(
              useCaseGroupHeight(left, spec, layout),
              useCaseGroupHeight(right, spec, layout));
    }
    return height;
  }

  private static void addGroupedUseCaseBounds(
      LayoutPlan plan,
      UseCaseDiagramSpec spec,
      UseCaseDiagramSpec.LayoutSpec layout,
      int leftX,
      int rightX) {
    int y = layout.boundaryY + layout.topPadding;
    List<String> groups = businessGroups(spec);
    for (int index = 0; index < groups.size(); index++) {
      String group = groups.get(index);
      List<UseCaseDiagramSpec.UseCaseSpec> left =
          groupLaneUseCases(spec, group, "LEFT");
      List<UseCaseDiagramSpec.UseCaseSpec> right =
          groupLaneUseCases(spec, group, "RIGHT");
      if (index > 0) {
        y += layout.groupGap;
      }
      addUseCaseGroupBounds(plan, left, leftX, y, spec, layout);
      addUseCaseGroupBounds(plan, right, rightX, y, spec, layout);
      y +=
          Math.max(
              useCaseGroupHeight(left, spec, layout),
              useCaseGroupHeight(right, spec, layout));
    }
  }

  private static void addUseCaseGroupBounds(
      LayoutPlan plan,
      List<UseCaseDiagramSpec.UseCaseSpec> useCases,
      int x,
      int startY,
      UseCaseDiagramSpec spec,
      UseCaseDiagramSpec.LayoutSpec layout) {
    int y = startY;
    UseCaseDiagramSpec.UseCaseSpec previous = null;
    for (UseCaseDiagramSpec.UseCaseSpec useCase : useCases) {
      if (previous != null && hasRelationshipBetween(previous.id, useCase.id, spec)) {
        y += RELATIONSHIP_LABEL_GAP;
      }
      plan.bounds.put(
          useCase.id,
          new Bounds(x, y, layout.useCaseWidth, layout.useCaseHeight));
      y += layout.useCaseHeight + layout.rowGap;
      previous = useCase;
    }
  }

  private static int useCaseGroupHeight(
      List<UseCaseDiagramSpec.UseCaseSpec> useCases,
      UseCaseDiagramSpec spec,
      UseCaseDiagramSpec.LayoutSpec layout) {
    if (useCases.isEmpty()) {
      return 0;
    }
    int height =
        useCases.size() * layout.useCaseHeight
            + Math.max(0, useCases.size() - 1) * layout.rowGap;
    for (int index = 1; index < useCases.size(); index++) {
      if (hasRelationshipBetween(useCases.get(index - 1).id, useCases.get(index).id, spec)) {
        height += RELATIONSHIP_LABEL_GAP;
      }
    }
    return height;
  }

  private static void addUseCaseBounds(
      LayoutPlan plan,
      List<UseCaseDiagramSpec.UseCaseSpec> useCases,
      int x,
      UseCaseDiagramSpec spec,
      UseCaseDiagramSpec.LayoutSpec layout) {
    int y = layout.boundaryY + layout.topPadding;
    UseCaseDiagramSpec.UseCaseSpec previous = null;
    for (UseCaseDiagramSpec.UseCaseSpec useCase : useCases) {
      if (previous != null) {
        if (!clean(previous.group).equals(clean(useCase.group))) {
          y += layout.groupGap;
        }
        if (hasRelationshipBetween(previous.id, useCase.id, spec)) {
          y += RELATIONSHIP_LABEL_GAP;
        }
      }
      plan.bounds.put(
          useCase.id,
          new Bounds(x, y, layout.useCaseWidth, layout.useCaseHeight));
      y += layout.useCaseHeight + layout.rowGap;
      previous = useCase;
    }
  }

  private static int useCaseColumnHeight(
      List<UseCaseDiagramSpec.UseCaseSpec> useCases,
      UseCaseDiagramSpec spec,
      UseCaseDiagramSpec.LayoutSpec layout) {
    if (useCases.isEmpty()) {
      return 0;
    }
    int height =
        useCases.size() * layout.useCaseHeight
            + Math.max(0, useCases.size() - 1) * layout.rowGap;
    for (int i = 1; i < useCases.size(); i++) {
      UseCaseDiagramSpec.UseCaseSpec previous = useCases.get(i - 1);
      UseCaseDiagramSpec.UseCaseSpec current = useCases.get(i);
      if (!clean(previous.group).equals(clean(current.group))) {
        height += layout.groupGap;
      }
      if (hasRelationshipBetween(previous.id, current.id, spec)) {
        height += RELATIONSHIP_LABEL_GAP;
      }
    }
    return height;
  }

  private static boolean hasRelationshipBetween(
      String firstId, String secondId, UseCaseDiagramSpec spec) {
    for (UseCaseDiagramSpec.RelationshipSpec relationship : spec.relationships) {
      if ((firstId.equals(relationship.from) && secondId.equals(relationship.to))
          || (firstId.equals(relationship.to) && secondId.equals(relationship.from))) {
        return true;
      }
    }
    return false;
  }

  private static void addActorBounds(
      LayoutPlan plan,
      List<UseCaseDiagramSpec.ActorSpec> actors,
      boolean right,
      UseCaseDiagramSpec spec,
      UseCaseDiagramSpec.LayoutSpec layout,
      int boundaryHeight) {
    int x =
        right
            ? layout.boundaryX + layout.boundaryWidth + layout.actorGap
            : layout.boundaryX - layout.actorGap - layout.actorWidth;
    int[] desiredCenters = new int[actors.size()];
    for (int i = 0; i < actors.size(); i++) {
      UseCaseDiagramSpec.ActorSpec actor = actors.get(i);
      int totalY = 0;
      int connectedUseCases = 0;
      for (UseCaseDiagramSpec.AssociationSpec association : spec.associations) {
        if (!actor.id.equals(association.actorId)) {
          continue;
        }
        Bounds useCaseBounds = plan.bounds.get(association.useCaseId);
        if (useCaseBounds != null) {
          totalY += useCaseBounds.y + useCaseBounds.height / 2;
          connectedUseCases++;
        }
      }
      int centerY =
          connectedUseCases == 0
              ? layout.boundaryY + ((i + 1) * boundaryHeight) / (actors.size() + 1)
              : totalY / connectedUseCases;
      int minimumCenter = layout.boundaryY + layout.actorHeight / 2;
      int maximumCenter =
          layout.boundaryY + boundaryHeight - layout.actorHeight / 2;
      centerY = Math.max(minimumCenter, Math.min(maximumCenter, centerY));
      desiredCenters[i] = centerY;
    }

    Integer[] order = new Integer[actors.size()];
    for (int index = 0; index < actors.size(); index++) {
      order[index] = index;
    }
    Arrays.sort(
        order,
        Comparator.comparingInt((Integer index) -> desiredCenters[index])
            .thenComparingInt(index -> index));
    int[] resolvedCenters = new int[actors.size()];
    int minimumCenter = layout.boundaryY + layout.actorHeight / 2;
    int maximumCenter =
        layout.boundaryY + boundaryHeight - layout.actorHeight / 2;
    int separation = layout.actorHeight + ACTOR_VERTICAL_GAP;
    for (int position = 0; position < order.length; position++) {
      int index = order[position];
      int lowerBound =
          position == 0 ? minimumCenter : resolvedCenters[order[position - 1]] + separation;
      resolvedCenters[index] = Math.max(desiredCenters[index], lowerBound);
    }
    if (order.length > 0 && resolvedCenters[order[order.length - 1]] > maximumCenter) {
      resolvedCenters[order[order.length - 1]] = maximumCenter;
      for (int position = order.length - 2; position >= 0; position--) {
        int index = order[position];
        resolvedCenters[index] =
            Math.min(resolvedCenters[index], resolvedCenters[order[position + 1]] - separation);
      }
    }

    for (int i = 0; i < actors.size(); i++) {
      UseCaseDiagramSpec.ActorSpec actor = actors.get(i);
      plan.bounds.put(
          actor.id,
          new Bounds(
              x,
              resolvedCenters[i] - layout.actorHeight / 2,
              layout.actorWidth,
              layout.actorHeight));
    }
  }

  private static void configureDiagram(IDiagramUIModel diagram) {
    diagram.setShowConnectorName(IDiagramUIModel.SHOW_CONNECTOR_NAME_YES);
    diagram.setPaintConnectorThroughLabel(IDiagramUIModel.PAINT_CONNECTOR_THROUGH_LABEL_NO);
    diagram.setConnectorLabelOrientation(IConnectorUIModel.CLO_HORIZONTAL_ONLY);
  }

  private static void styleShape(IDiagramElement element) {
    if (!(element instanceof IShapeUIModel)) {
      return;
    }
    IShapeUIModel shape = (IShapeUIModel) element;
    shape.getFillColor().setType(IFillColor.TYPE_SOLID);
    shape.getFillColor().setColor1(DEFAULT_BLUE);
    shape.getFillColor().setColor2(DEFAULT_BLUE);
    shape.getFillColor().setTransparency(IFillColor.OPAQUE);
    shape.getFillColor().applySetting();
    element.getElementFont().setSize(12);
  }

  private static void layoutSystemCaption(IDiagramElement element) {
    if (element instanceof ISystemUIModel) {
      ((ISystemUIModel) element).setCaptionAlignment(ISystemUIModel.INNER_TOP);
    }
    element.resetCaption();
    ICaptionUIModel caption = element.getCaptionUIModel();
    if (caption == null) {
      return;
    }
    element.getElementFont().setSize(15);
    element.getElementFont().setBold(true);
    int width = Math.max(360, clean(element.getModelElement().getName()).length() * 9);
    caption.setVisible(true);
    caption.setSide(ICaptionUIModel.SIDE_FREEMOVE);
    caption.setBounds(
        (element.getWidth() - width) / 2,
        12,
        width,
        32);
  }

  private static void layoutActorCaption(IDiagramElement element) {
    element.resetCaption();
    ICaptionUIModel caption = element.getCaptionUIModel();
    if (caption == null) {
      return;
    }
    int width = Math.max(120, clean(element.getModelElement().getName()).length() * 8);
    caption.setVisible(true);
    caption.setSide(ICaptionUIModel.SIDE_FREEMOVE);
    caption.setBounds(
        element.getX() + (element.getWidth() - width) / 2,
        element.getY() + element.getHeight() + 4,
        width,
        26);
  }

  private static void routeAssociations(List<IConnectorUIModel> connectors) {
    Map<IDiagramElement, List<IConnectorUIModel>> byActor = new LinkedHashMap<>();
    for (IConnectorUIModel connector : connectors) {
      IDiagramElement from = connector.getFromShape();
      IDiagramElement to = connector.getToShape();
      if (from == null || to == null) {
        continue;
      }
      IDiagramElement actor =
          from.getModelElement() instanceof IActor ? from : to;
      byActor.computeIfAbsent(actor, ignored -> new ArrayList<>()).add(connector);
    }
    for (Map.Entry<IDiagramElement, List<IConnectorUIModel>> entry : byActor.entrySet()) {
      IDiagramElement actor = entry.getKey();
      List<IConnectorUIModel> actorConnectors = entry.getValue();
      actorConnectors.sort(
          Comparator.comparingInt(
              connector -> {
                IDiagramElement from = connector.getFromShape();
                IDiagramElement to = connector.getToShape();
                IDiagramElement useCase =
                    from.getModelElement() instanceof IUseCase ? from : to;
                return useCase.getY() + useCase.getHeight() / 2;
              }));
      for (int i = 0; i < actorConnectors.size(); i++) {
        int topPort = actor.getY() + Math.max(12, actor.getHeight() / 6);
        int bottomPort = actor.getY() + actor.getHeight() - Math.max(12, actor.getHeight() / 6);
        int actorY =
            topPort
                + ((i + 1) * Math.max(0, bottomPort - topPort))
                    / (actorConnectors.size() + 1);
        routeAssociation(actorConnectors.get(i), actorY);
      }
    }
  }

  private static void routeAssociation(IConnectorUIModel connector, int actorY) {
    IDiagramElement from = connector.getFromShape();
    IDiagramElement to = connector.getToShape();
    if (from == null || to == null) {
      return;
    }
    IDiagramElement actor =
        from.getModelElement() instanceof IActor ? from : to;
    IDiagramElement useCase =
        from.getModelElement() instanceof IUseCase ? from : to;
    boolean actorOnLeft = actor.getX() < useCase.getX();
    Point actorPoint =
        new Point(
            actorOnLeft ? actor.getX() + actor.getWidth() : actor.getX(),
            actorY);
    Point useCasePoint =
        new Point(
            actorOnLeft ? useCase.getX() : useCase.getX() + useCase.getWidth(),
            useCase.getY() + useCase.getHeight() / 2);
    connector.clearPoints();
    if (from == actor) {
      connector.addPoint(actorPoint);
      connector.addPoint(useCasePoint);
    } else {
      connector.addPoint(useCasePoint);
      connector.addPoint(actorPoint);
    }
    connector.setConnectorStyle(IConnectorUIModel.CS_OBLIQUE);
    connector.resetCaption();
  }

  private static void routeUseCaseRelationship(IConnectorUIModel connector) {
    IDiagramElement from = connector.getFromShape();
    IDiagramElement to = connector.getToShape();
    if (from == null || to == null) {
      return;
    }
    int fromCenterX = from.getX() + from.getWidth() / 2;
    int toCenterX = to.getX() + to.getWidth() / 2;
    int fromCenterY = from.getY() + from.getHeight() / 2;
    int toCenterY = to.getY() + to.getHeight() / 2;
    Point fromPoint;
    Point toPoint;
    if (Math.abs(fromCenterX - toCenterX) <= Math.max(from.getWidth(), to.getWidth()) / 2) {
      boolean down = fromCenterY < toCenterY;
      fromPoint =
          new Point(fromCenterX, down ? from.getY() + from.getHeight() : from.getY());
      toPoint = new Point(toCenterX, down ? to.getY() : to.getY() + to.getHeight());
    } else {
      boolean right = fromCenterX < toCenterX;
      fromPoint =
          new Point(right ? from.getX() + from.getWidth() : from.getX(), fromCenterY);
      toPoint =
          new Point(right ? to.getX() : to.getX() + to.getWidth(), toCenterY);
    }
    connector.clearPoints();
    connector.addPoint(fromPoint);
    connector.addPoint(toPoint);
    connector.setConnectorStyle(IConnectorUIModel.CS_OBLIQUE);
    connector.setConnectorLabelOrientation(IConnectorUIModel.CLO_HORIZONTAL_ONLY);
    connector.setPaintThroughLabel(IDiagramUIModel.PAINT_CONNECTOR_THROUGH_LABEL_NO);
    connector.resetCaption();
    connector.resetCaptionSize();
    ICaptionUIModel caption = connector.getCaptionUIModel();
    if (caption != null) {
      int middleX = (fromPoint.x + toPoint.x) / 2;
      int middleY = (fromPoint.y + toPoint.y) / 2;
      int labelWidth = 120;
      caption.setVisible(true);
      caption.setSide(ICaptionUIModel.SIDE_FREEMOVE);
      caption.setBounds(middleX - labelWidth / 2, middleY - 14, labelWidth, 28);
    }
  }

  private static void configureConnector(IConnectorUIModel connector, int style) {
    connector.setConnectorStyle(style);
    connector.resetCaption();
    connector.resetCaptionSize();
  }

  private static int connectorStyle(String value) {
    return "RECTILINEAR".equals(value)
        ? IConnectorUIModel.CS_RECTI_LINEAR
        : IConnectorUIModel.CS_OBLIQUE;
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

  private static Map<String, Object> snapshot(IDiagramUIModel diagram) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("diagramId", diagram.getId());
    result.put("diagramName", diagram.getName());
    result.put("diagramType", diagram.getType());
    List<Map<String, Object>> elements = new ArrayList<>();
    List<Map<String, Object>> relationships = new ArrayList<>();
    Iterator<?> iterator = diagram.diagramElementIterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (!(item instanceof IDiagramElement)) {
        continue;
      }
      IDiagramElement element = (IDiagramElement) item;
      IModelElement model = element.getModelElement();
      if (model instanceof ISystem || model instanceof IActor || model instanceof IUseCase) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", metadata(model.getDocumentation(), ID_TAG));
        node.put("kind", kind(model));
        node.put("name", model.getName());
        node.put("description", metadata(model.getDocumentation(), DESCRIPTION_TAG));
        node.put("x", element.getX());
        node.put("y", element.getY());
        node.put("width", element.getWidth());
        node.put("height", element.getHeight());
        elements.add(node);
      } else if (model instanceof IAssociation
          || model instanceof IInclude
          || model instanceof IExtend
          || model instanceof IGeneralization) {
        IConnectorUIModel connector = (IConnectorUIModel) element;
        Map<String, Object> relation = new LinkedHashMap<>();
        relation.put("id", metadata(model.getDocumentation(), ID_TAG));
        relation.put("kind", kind(model));
        relation.put(
            "from",
            metadata(
                connector.getFromShape().getModelElement().getDocumentation(), ID_TAG));
        relation.put(
            "to",
            metadata(connector.getToShape().getModelElement().getDocumentation(), ID_TAG));
        if (model instanceof IExtend) {
          relation.put("condition", ((IExtend) model).getCondition());
        }
        relationships.add(relation);
      }
    }
    elements.sort(
        Comparator.comparing(item -> String.valueOf(item.get("kind")) + ":" + item.get("id")));
    relationships.sort(Comparator.comparing(item -> String.valueOf(item.get("id"))));
    result.put("elements", elements);
    result.put("relationships", relationships);
    return result;
  }

  private static Map<String, Object> validateDiagram(IDiagramUIModel diagram) {
    List<String> violations = new ArrayList<>();
    List<IDiagramElement> systems = new ArrayList<>();
    List<IDiagramElement> actors = new ArrayList<>();
    List<IDiagramElement> useCases = new ArrayList<>();
    List<IConnectorUIModel> connectors = new ArrayList<>();
    Iterator<?> iterator = diagram.diagramElementIterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (!(item instanceof IDiagramElement)) {
        continue;
      }
      IDiagramElement element = (IDiagramElement) item;
      IModelElement model = element.getModelElement();
      if (model instanceof ISystem) {
        systems.add(element);
      } else if (model instanceof IActor) {
        actors.add(element);
      } else if (model instanceof IUseCase) {
        useCases.add(element);
      } else if (element instanceof IConnectorUIModel) {
        connectors.add((IConnectorUIModel) element);
      }
    }
    if (systems.size() != 1) {
      violations.add("Exactly one System boundary is required");
    }
    if (actors.isEmpty()) {
      violations.add("At least one Actor is required");
    }
    if (useCases.isEmpty()) {
      violations.add("At least one Use Case is required");
    }
    IDiagramElement system = systems.size() == 1 ? systems.get(0) : null;
    Set<IModelElement> connected = new HashSet<>();
    for (IConnectorUIModel connector : connectors) {
      if (connector.getFromShape() == null || connector.getToShape() == null) {
        violations.add("A relationship has a missing endpoint");
        continue;
      }
      IModelElement from = connector.getFromShape().getModelElement();
      IModelElement to = connector.getToShape().getModelElement();
      connected.add(from);
      connected.add(to);
      IModelElement relation = connector.getModelElement();
      if (relation instanceof IAssociation
          && !((from instanceof IActor && to instanceof IUseCase)
              || (from instanceof IUseCase && to instanceof IActor))) {
        violations.add("Association must connect one Actor and one Use Case");
      }
      if ((relation instanceof IInclude || relation instanceof IExtend)
          && !(from instanceof IUseCase && to instanceof IUseCase)) {
        violations.add("Include/extend must connect two Use Cases");
      }
      if (relation instanceof IExtend && clean(((IExtend) relation).getCondition()).isEmpty()) {
        violations.add("Extend relationship must have an explicit business condition");
      }
    }
    for (int i = 0; i < actors.size(); i++) {
      IDiagramElement actor = actors.get(i);
      if (system != null && overlaps(actor, system)) {
        violations.add("Actor must remain outside the System boundary: " + actor.getModelElement().getName());
      }
      if (!connected.contains(actor.getModelElement())) {
        violations.add("Actor is orphaned: " + actor.getModelElement().getName());
      }
      for (int j = i + 1; j < actors.size(); j++) {
        if (overlaps(actor, actors.get(j))) {
          violations.add(
              "Actor shapes overlap: "
                  + actor.getModelElement().getName()
                  + " / "
                  + actors.get(j).getModelElement().getName());
        }
      }
    }
    for (int i = 0; i < useCases.size(); i++) {
      IDiagramElement useCase = useCases.get(i);
      if (clean(useCase.getModelElement().getName()).isEmpty()) {
        violations.add("Use Case name must not be blank");
      }
      if (system != null && !contains(system, useCase)) {
        violations.add("Use Case must remain inside the System boundary: " + useCase.getModelElement().getName());
      }
      if (!connected.contains(useCase.getModelElement())) {
        violations.add("Use Case is orphaned: " + useCase.getModelElement().getName());
      }
      for (int j = i + 1; j < useCases.size(); j++) {
        if (overlaps(useCase, useCases.get(j))) {
          violations.add(
              "Use Case shapes overlap: "
                  + useCase.getModelElement().getName()
                  + " / "
                  + useCases.get(j).getModelElement().getName());
        }
      }
    }
    Map<String, Object> validation = new LinkedHashMap<>();
    validation.put("valid", violations.isEmpty());
    validation.put("systemCount", systems.size());
    validation.put("actorCount", actors.size());
    validation.put("useCaseCount", useCases.size());
    validation.put("relationshipCount", connectors.size());
    validation.put("violations", violations);
    return validation;
  }

  private static String kind(IModelElement model) {
    if (model instanceof ISystem) {
      return "SYSTEM";
    }
    if (model instanceof IActor) {
      return "ACTOR";
    }
    if (model instanceof IUseCase) {
      return "USE_CASE";
    }
    if (model instanceof IInclude) {
      return "INCLUDE";
    }
    if (model instanceof IExtend) {
      return "EXTEND";
    }
    if (model instanceof IGeneralization) {
      return "GENERALIZATION";
    }
    if (model instanceof IAssociation) {
      return "ASSOCIATION";
    }
    return model.getModelType();
  }

  private static boolean contains(IDiagramElement outer, IDiagramElement inner) {
    return inner.getX() >= outer.getX()
        && inner.getY() >= outer.getY()
        && inner.getX() + inner.getWidth() <= outer.getX() + outer.getWidth()
        && inner.getY() + inner.getHeight() <= outer.getY() + outer.getHeight();
  }

  private static boolean overlaps(IDiagramElement first, IDiagramElement second) {
    return first.getX() < second.getX() + second.getWidth()
        && first.getX() + first.getWidth() > second.getX()
        && first.getY() < second.getY() + second.getHeight()
        && first.getY() + first.getHeight() > second.getY();
  }

  private static Map<String, Object> validValidation() {
    Map<String, Object> validation = new LinkedHashMap<>();
    validation.put("valid", true);
    validation.put("violations", Collections.emptyList());
    return validation;
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
    for (int i = createdModels.size() - 1; i >= 0; i--) {
      try {
        createdModels.get(i).delete();
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
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("x", bounds.x);
        item.put("y", bounds.y);
        item.put("width", bounds.width);
        item.put("height", bounds.height);
        result.put(entry.getKey(), item);
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

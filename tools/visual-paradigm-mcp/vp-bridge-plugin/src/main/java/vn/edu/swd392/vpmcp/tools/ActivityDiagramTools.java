package vn.edu.swd392.vpmcp.tools;

import com.vp.plugin.diagram.IActivityDiagramUIModel;
import com.vp.plugin.diagram.ICaptionUIModel;
import com.vp.plugin.diagram.IConnectorUIModel;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IDiagramTypeConstants;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.diagram.IShapeTypeConstants;
import com.vp.plugin.diagram.IShapeUIModel;
import com.vp.plugin.diagram.shape.IActivityPartitionHeaderUIModel;
import com.vp.plugin.diagram.shape.IActivitySwimlane2CompartmentUIModel;
import com.vp.plugin.diagram.shape.IActivitySwimlane2NewUIModel;
import com.vp.plugin.model.IActivityAction;
import com.vp.plugin.model.IActivityFinalNode;
import com.vp.plugin.model.IActivityPartition;
import com.vp.plugin.model.IActivitySwimlane2;
import com.vp.plugin.model.IControlFlow;
import com.vp.plugin.model.IDecisionNode;
import com.vp.plugin.model.IForkNode;
import com.vp.plugin.model.IInitialNode;
import com.vp.plugin.model.IJoinNode;
import com.vp.plugin.model.IMergeNode;
import com.vp.plugin.model.IModelElement;
import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import vn.edu.swd392.vpmcp.bridge.Tool;

public final class ActivityDiagramTools extends VpAccess {
  @Tool(description = "Create and open a UML activity diagram.")
  public String createActivityDiagram(String diagramName) throws Exception {
    return onEdt(
        () -> {
          createDiagram(IDiagramTypeConstants.DIAGRAM_TYPE_ACTIVITY_DIAGRAM, diagramName);
          return "Created activity diagram: " + diagramName;
        });
  }

  @Tool(
      description =
          "Add a native vertical UML activity swimlane with two or more responsibility "
              + "partitions. partitionNamesCsv is ordered left-to-right. Coordinates and sizes "
              + "are absolute diagram pixels.")
  public String addVerticalActivitySwimlane(
      String diagramName,
      String swimlaneName,
      String partitionNamesCsv,
      int x,
      int y,
      int width,
      int height,
      int headerHeight)
      throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel rawDiagram = findDiagram(diagramName);
          if (!(rawDiagram instanceof IActivityDiagramUIModel)) {
            throw new IllegalArgumentException(
                "Target is not an activity diagram: " + diagramName);
          }
          requireText(swimlaneName, "swimlaneName");
          String[] partitionNames = parsePartitionNames(partitionNamesCsv);
          if (width < partitionNames.length * 180) {
            throw new IllegalArgumentException(
                "width must allow at least 180 pixels per vertical partition");
          }
          if (height < 300) {
            throw new IllegalArgumentException("height must be at least 300 pixels");
          }
          if (headerHeight < 24 || headerHeight > 100) {
            throw new IllegalArgumentException("headerHeight must be between 24 and 100 pixels");
          }

          IActivityDiagramUIModel diagram = (IActivityDiagramUIModel) rawDiagram;
          diagram.setShowPartitionHeader(IActivityDiagramUIModel.ALWAYS_SHOW);

          IActivitySwimlane2 swimlane = models().createActivitySwimlane2();
          swimlane.setName(swimlaneName);
          IActivitySwimlane2NewUIModel swimlaneShape =
              (IActivitySwimlane2NewUIModel) diagrams().createDiagramElement(diagram, swimlane);
          swimlaneShape.setBounds(x, y, width, height);
          swimlaneShape.setRequestResetCaption(true);

          List<String> headerIds = new ArrayList<>();
          List<IActivityPartitionHeaderUIModel> headers = new ArrayList<>();
          List<String> compartmentIds = new ArrayList<>();
          int baseWidth = width / partitionNames.length;
          int laneX = x;
          for (int index = 0; index < partitionNames.length; index++) {
            int laneWidth =
                index == partitionNames.length - 1 ? x + width - laneX : baseWidth;

            IActivityPartition partition = models().createActivityPartition();
            partition.setName(partitionNames[index]);
            swimlane.addVerticalPartition(partition);
            swimlane.addChild(partition);

            IActivityPartitionHeaderUIModel header =
                (IActivityPartitionHeaderUIModel)
                    diagrams().createDiagramElement(diagram, partition);
            header.setHorizontal(false);
            header.setSwimlane(swimlaneShape);
            swimlaneShape.addChild(header);
            header.setBounds(laneX, y, laneWidth, headerHeight);
            header.setRequestResetCaption(true);
            header.resetCaption();
            header.resetCaptionSize();
            headerIds.add(header.getId());
            headers.add(header);

            IActivitySwimlane2CompartmentUIModel compartment =
                (IActivitySwimlane2CompartmentUIModel)
                    diagrams()
                        .createDiagramElement(
                            diagram,
                            IShapeTypeConstants.SHAPE_TYPE_ACTIVITY_SWIMLANE2_COMPARTMENT);
            compartment.setVerticalPartitionId(header.getId());
            compartment.setBounds(laneX, y, laneWidth, height);
            swimlaneShape.addChild(compartment);
            compartmentIds.add(compartment.getId());
            laneX += laneWidth;
          }

          swimlaneShape.setHorizontalPartitionIds(new String[0]);
          swimlaneShape.setVerticalPartitionIds(headerIds.toArray(new String[0]));
          swimlaneShape.setCompartmentIds(compartmentIds.toArray(new String[0]));
          swimlaneShape.sendToBack();
          for (IActivityPartitionHeaderUIModel header : headers) {
            header.bringToFront();
            header.setRequestResetCaption(true);
            header.resetCaption();
            header.resetCaptionSize();
          }
          diagrams().openDiagram(diagram);
          return "Added vertical activity swimlane "
              + swimlaneName
              + " with partitions: "
              + String.join(", ", partitionNames);
        });
  }

  @Tool(
      description =
          "Assign an existing activity node to one native vertical swimlane partition. "
              + "Create the swimlane first and keep the node bounds inside that partition.")
  public String assignActivityNodeToPartition(
      String diagramName, String swimlaneName, String partitionName, String nodeName)
      throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          IActivitySwimlane2NewUIModel swimlaneShape =
              swimlaneShape(diagram, swimlaneName);
          IActivitySwimlane2 swimlane =
              (IActivitySwimlane2) swimlaneShape.getModelElement();
          IActivityPartition partition = verticalPartition(swimlane, partitionName);
          IActivitySwimlane2CompartmentUIModel compartment =
              verticalCompartment(swimlaneShape, partition);
          IDiagramElement nodeShape = activityNode(diagram, nodeName);
          IModelElement node = nodeShape.getModelElement();

          if (!containsModel(swimlane.childIterator(), node)) {
            swimlane.addChild(node);
          }
          if (!containsModel(partition.containedElementIterator(), node)) {
            partition.addContainedElement(node);
          }
          if (nodeShape instanceof IShapeUIModel
              && compartment.indexOfChild((IShapeUIModel) nodeShape) < 0) {
            compartment.addChild((IShapeUIModel) nodeShape);
          }
          if (nodeShape instanceof IShapeUIModel) {
            IShapeUIModel shape = (IShapeUIModel) nodeShape;
            shape.bringToFront();
            shape.setRequestResetCaption(true);
            shape.resetCaption();
            shape.resetCaptionSize();
          }
          return "Assigned " + nodeName + " to partition " + partitionName;
        });
  }

  @Tool(
      description =
          "Move and resize an existing activity node with explicit bounds. "
              + "Keep the bounds inside its assigned swimlane partition.")
  public String layoutActivityNode(
      String diagramName, String nodeName, int x, int y, int width, int height)
      throws Exception {
    return onEdt(
        () -> {
          if (width < 16 || height < 16) {
            throw new IllegalArgumentException("width and height must be at least 16 pixels");
          }
          IDiagramElement shape = activityNode(findDiagram(diagramName), nodeName);
          shape.setBounds(x, y, width, height);
          return "Laid out activity node " + nodeName;
        });
  }

  @Tool(
      description =
          "Move or hide an activity-node caption after inspecting an export. Use visible=false "
              + "for internal merge identifiers and unlabeled initial/final nodes; use "
              + "visible=true with explicit coordinates to keep a decision question clear of guards.")
  public String layoutActivityNodeLabel(
      String diagramName, String nodeName, int x, int y, boolean visible) throws Exception {
    return onEdt(
        () -> {
          IDiagramElement shape = activityNode(findDiagram(diagramName), nodeName);
          ICaptionUIModel caption = shape.getCaptionUIModel();
          caption.setVisible(visible);
          if (visible) {
            caption.setSide(ICaptionUIModel.SIDE_FREEMOVE);
            caption.setX(x);
            caption.setY(y);
          }
          return (visible ? "Moved" : "Hid") + " activity node label " + nodeName;
        });
  }

  @Tool(description = "Add a named action to an activity diagram.")
  public String addActivityAction(String diagramName, String actionName, int x, int y)
      throws Exception {
    return addNode(
        diagramName,
        () -> models().createActivityAction(),
        actionName,
        x,
        y,
        190,
        70,
        "action");
  }

  @Tool(description = "Add the initial node to an activity diagram.")
  public String addInitialNode(String diagramName, String nodeName, int x, int y)
      throws Exception {
    return addNode(
        diagramName,
        () -> models().createInitialNode(),
        nodeName,
        x,
        y,
        30,
        30,
        "initial node");
  }

  @Tool(description = "Add an activity final node.")
  public String addActivityFinalNode(String diagramName, String nodeName, int x, int y)
      throws Exception {
    return addNode(
        diagramName,
        () -> models().createActivityFinalNode(),
        nodeName,
        x,
        y,
        36,
        36,
        "activity final node");
  }

  @Tool(description = "Add a decision node.")
  public String addDecisionNode(String diagramName, String nodeName, int x, int y)
      throws Exception {
    return addNode(
        diagramName,
        () -> models().createDecisionNode(),
        nodeName,
        x,
        y,
        45,
        45,
        "decision node");
  }

  @Tool(description = "Add a merge node.")
  public String addMergeNode(String diagramName, String nodeName, int x, int y)
      throws Exception {
    return addNode(
        diagramName,
        () -> models().createMergeNode(),
        nodeName,
        x,
        y,
        45,
        45,
        "merge node");
  }

  @Tool(description = "Add a fork node.")
  public String addForkNode(String diagramName, String nodeName, int x, int y)
      throws Exception {
    return addNode(
        diagramName,
        () -> models().createForkNode(),
        nodeName,
        x,
        y,
        160,
        16,
        "fork node");
  }

  @Tool(description = "Add a join node.")
  public String addJoinNode(String diagramName, String nodeName, int x, int y)
      throws Exception {
    return addNode(
        diagramName,
        () -> models().createJoinNode(),
        nodeName,
        x,
        y,
        160,
        16,
        "join node");
  }

  @Tool(
      description =
          "Connect two activity nodes with a control flow. Use an empty guard for an unconditional flow.")
  public String addControlFlow(
      String diagramName, String fromNode, String toNode, String guard) throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          IDiagramElement from = activityNode(diagram, fromNode);
          IDiagramElement to = activityNode(diagram, toNode);
          IControlFlow flow = models().createControlFlow();
          if (!clean(guard).isEmpty()) {
            flow.setGuard(clean(guard));
          }
          IActivitySwimlane2 swimlane =
              commonSwimlane(diagram, from.getModelElement(), to.getModelElement());
          if (swimlane != null) {
            swimlane.addChild(flow);
          }
          addConnector(diagram, flow, from, to);
          return "Added control flow " + fromNode + " -> " + toNode;
        });
  }

  @Tool(
      description =
          "Route an existing activity control flow through explicit points. pointsCsv uses "
              + "ordered x:y pairs from fromNode to toNode, including both endpoints. "
              + "connectorStyle is rectilinear or oblique.")
  public String routeActivityControlFlow(
      String diagramName,
      String fromNode,
      String toNode,
      String pointsCsv,
      String connectorStyle)
      throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          IConnectorUIModel connector = findControlFlow(diagram, fromNode, toNode);
          Point[] points = parsePoints(pointsCsv);
          int style = connectorStyle(connectorStyle);
          String actualFrom = connector.getFromShape().getModelElement().getName();

          connector.clearPoints();
          if (fromNode.equals(actualFrom)) {
            for (Point point : points) {
              connector.addPoint(point);
            }
          } else {
            for (int index = points.length - 1; index >= 0; index--) {
              connector.addPoint(points[index]);
            }
          }

          connector.setConnectorStyle(style);
          connector.resetCaption();
          connector.resetCaptionSize();
          return "Routed activity control flow " + fromNode + " -> " + toNode;
        });
  }

  @Tool(
      description =
          "Move a guarded activity control-flow label after routing. Keep it close to the "
              + "correct outgoing decision branch and clear of node captions and other guards.")
  public String layoutActivityControlFlowLabel(
      String diagramName, String fromNode, String toNode, int x, int y) throws Exception {
    return onEdt(
        () -> {
          IConnectorUIModel connector =
              findControlFlow(findDiagram(diagramName), fromNode, toNode);
          ICaptionUIModel caption = connector.getCaptionUIModel();
          caption.setVisible(true);
          caption.setSide(ICaptionUIModel.SIDE_FREEMOVE);
          caption.setX(x);
          caption.setY(y);
          return "Moved activity guard label "
              + fromNode
              + " -> "
              + toNode
              + " to ("
              + x
              + ", "
              + y
              + ")";
        });
  }

  private String addNode(
      String diagramName,
      Supplier<? extends IModelElement> modelFactory,
      String nodeName,
      int x,
      int y,
      int width,
      int height,
      String label)
      throws Exception {
    return onEdt(
        () -> {
          addShape(
              findDiagram(diagramName),
              modelFactory.get(),
              nodeName,
              x,
              y,
              width,
              height);
          return "Added " + label + ": " + nodeName;
        });
  }

  private IDiagramElement activityNode(IDiagramUIModel diagram, String name) {
    Class<?>[] supported = {
      IActivityAction.class,
      IInitialNode.class,
      IActivityFinalNode.class,
      IDecisionNode.class,
      IMergeNode.class,
      IForkNode.class,
      IJoinNode.class
    };
    for (Class<?> type : supported) {
      try {
        @SuppressWarnings("unchecked")
        Class<? extends IModelElement> modelType = (Class<? extends IModelElement>) type;
        return findShape(diagram, name, modelType);
      } catch (IllegalArgumentException ignored) {
        // Try the next supported activity node type.
      }
    }
    throw new IllegalArgumentException("Activity node not found: " + name);
  }

  private IActivitySwimlane2NewUIModel swimlaneShape(
      IDiagramUIModel diagram, String swimlaneName) {
    IDiagramElement shape = findShape(diagram, swimlaneName, IActivitySwimlane2.class);
    if (!(shape instanceof IActivitySwimlane2NewUIModel)) {
      throw new IllegalArgumentException(
          "Native activity swimlane view not found: " + swimlaneName);
    }
    return (IActivitySwimlane2NewUIModel) shape;
  }

  private static IActivityPartition verticalPartition(
      IActivitySwimlane2 swimlane, String partitionName) {
    Iterator<?> iterator = swimlane.verticalPartitionIterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (item instanceof IActivityPartition
          && partitionName.equals(((IActivityPartition) item).getName())) {
        return (IActivityPartition) item;
      }
    }
    throw new IllegalArgumentException("Vertical activity partition not found: " + partitionName);
  }

  private static IActivitySwimlane2CompartmentUIModel verticalCompartment(
      IActivitySwimlane2NewUIModel swimlaneShape, IActivityPartition partition) {
    String headerId = null;
    for (IShapeUIModel child : swimlaneShape.toChildArray()) {
      if (child instanceof IActivityPartitionHeaderUIModel
          && partition.equals(child.getModelElement())) {
        headerId = child.getId();
        break;
      }
    }
    if (headerId == null) {
      throw new IllegalArgumentException(
          "Partition header view not found: " + partition.getName());
    }
    for (IShapeUIModel child : swimlaneShape.toChildArray()) {
      if (child instanceof IActivitySwimlane2CompartmentUIModel) {
        IActivitySwimlane2CompartmentUIModel compartment =
            (IActivitySwimlane2CompartmentUIModel) child;
        if (headerId.equals(compartment.getVerticalPartitionId())) {
          return compartment;
        }
      }
    }
    throw new IllegalArgumentException(
        "Partition compartment view not found: " + partition.getName());
  }

  private static boolean containsModel(Iterator<?> iterator, IModelElement target) {
    while (iterator.hasNext()) {
      if (target.equals(iterator.next())) {
        return true;
      }
    }
    return false;
  }

  private static IActivitySwimlane2 commonSwimlane(
      IDiagramUIModel diagram, IModelElement from, IModelElement to) {
    Iterator<?> iterator = diagram.diagramElementIterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (!(item instanceof IDiagramElement)) {
        continue;
      }
      IModelElement model = ((IDiagramElement) item).getModelElement();
      if (model instanceof IActivitySwimlane2) {
        IActivitySwimlane2 swimlane = (IActivitySwimlane2) model;
        if (containsModel(swimlane.childIterator(), from)
            && containsModel(swimlane.childIterator(), to)) {
          return swimlane;
        }
      }
    }
    return null;
  }

  private static IConnectorUIModel findControlFlow(
      IDiagramUIModel diagram, String fromNode, String toNode) {
    Iterator<?> iterator = diagram.diagramElementIterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (!(item instanceof IConnectorUIModel)) {
        continue;
      }
      IConnectorUIModel connector = (IConnectorUIModel) item;
      if (!(connector.getModelElement() instanceof IControlFlow)
          || connector.getFromShape() == null
          || connector.getToShape() == null) {
        continue;
      }
      String actualFrom = connector.getFromShape().getModelElement().getName();
      String actualTo = connector.getToShape().getModelElement().getName();
      if ((fromNode.equals(actualFrom) && toNode.equals(actualTo))
          || (fromNode.equals(actualTo) && toNode.equals(actualFrom))) {
        return connector;
      }
    }
    throw new IllegalArgumentException(
        "Control flow not found on diagram '"
            + diagram.getName()
            + "': "
            + fromNode
            + " <-> "
            + toNode);
  }

  private static String[] parsePartitionNames(String partitionNamesCsv) {
    requireText(partitionNamesCsv, "partitionNamesCsv");
    String[] rawNames = partitionNamesCsv.split(",");
    List<String> names = new ArrayList<>();
    Set<String> unique = new HashSet<>();
    for (String rawName : rawNames) {
      String name = clean(rawName);
      if (name.isEmpty()) {
        throw new IllegalArgumentException("partitionNamesCsv contains an empty name");
      }
      if (!unique.add(name)) {
        throw new IllegalArgumentException("Duplicate activity partition name: " + name);
      }
      names.add(name);
    }
    if (names.size() < 2) {
      throw new IllegalArgumentException(
          "partitionNamesCsv must contain at least two partition names");
    }
    return names.toArray(new String[0]);
  }

  private static Point[] parsePoints(String pointsCsv) {
    String[] tokens = clean(pointsCsv).split(",");
    if (tokens.length < 2) {
      throw new IllegalArgumentException("pointsCsv must contain at least two x:y points");
    }
    Point[] points = new Point[tokens.length];
    for (int index = 0; index < tokens.length; index++) {
      String[] coordinates = tokens[index].trim().split(":", 2);
      if (coordinates.length != 2) {
        throw new IllegalArgumentException(
            "Invalid point '" + tokens[index] + "'; expected x:y");
      }
      try {
        points[index] =
            new Point(
                Integer.parseInt(coordinates[0].trim()),
                Integer.parseInt(coordinates[1].trim()));
      } catch (NumberFormatException exception) {
        throw new IllegalArgumentException(
            "Invalid numeric point '" + tokens[index] + "'", exception);
      }
    }
    return points;
  }

  private static int connectorStyle(String value) {
    String style = clean(value).toLowerCase();
    if ("rectilinear".equals(style)) {
      return IConnectorUIModel.CS_RECTI_LINEAR;
    }
    if ("oblique".equals(style)) {
      return IConnectorUIModel.CS_OBLIQUE;
    }
    throw new IllegalArgumentException("connectorStyle must be rectilinear or oblique");
  }
}

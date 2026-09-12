package vn.edu.swd392.vpmcp.tools;

import com.vp.plugin.diagram.ICaptionUIModel;
import com.vp.plugin.diagram.IConnectorUIModel;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IDiagramTypeConstants;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.diagram.IShapeUIModel;
import com.vp.plugin.diagram.shape.IBaseState2UIModel;
import com.vp.plugin.model.IActivity;
import com.vp.plugin.model.IFinalState2;
import com.vp.plugin.model.IInitialPseudoState;
import com.vp.plugin.model.IModelElement;
import com.vp.plugin.model.IState2;
import com.vp.plugin.model.ITransition2;
import java.awt.Point;
import java.util.Iterator;
import vn.edu.swd392.vpmcp.bridge.Tool;

public final class StateDiagramTools extends VpAccess {
  @Tool(description = "Create and open a UML state machine diagram.")
  public String createStateDiagram(String diagramName) throws Exception {
    return onEdt(
        () -> {
          createDiagram(IDiagramTypeConstants.DIAGRAM_TYPE_STATE_DIAGRAM, diagramName);
          return "Created state diagram: " + diagramName;
        });
  }

  @Tool(
      description =
          "Add a state with optional entry, do, and exit activity labels. Pass empty strings when absent.")
  public String addState(
      String diagramName,
      String stateName,
      String entryAction,
      String doActivity,
      String exitAction,
      int x,
      int y)
      throws Exception {
    return onEdt(
        () -> {
          IState2 state = models().createState2();
          if (!clean(entryAction).isEmpty()) {
            state.setEntry(activity(clean(entryAction)));
          }
          if (!clean(doActivity).isEmpty()) {
            state.setDoActivity(activity(clean(doActivity)));
          }
          if (!clean(exitAction).isEmpty()) {
            state.setExit(activity(clean(exitAction)));
          }
          addShape(findDiagram(diagramName), state, stateName, x, y, 210, 110);
          return "Added state: " + stateName;
        });
  }

  @Tool(description = "Add the initial pseudostate.")
  public String addInitialState(String diagramName, String nodeName, int x, int y)
      throws Exception {
    return onEdt(
        () -> {
          addShape(
              findDiagram(diagramName),
              models().createInitialPseudoState(),
              nodeName,
              x,
              y,
              30,
              30);
          return "Added initial state";
        });
  }

  @Tool(description = "Add the final state.")
  public String addFinalState(String diagramName, String nodeName, int x, int y)
      throws Exception {
    return onEdt(
        () -> {
          addShape(
              findDiagram(diagramName),
              models().createFinalState2(),
              nodeName,
              x,
              y,
              36,
              36);
          return "Added final state";
        });
  }

  @Tool(
      description =
          "Move and resize an existing state-machine node with explicit bounds. "
              + "Use compact content-fitted bounds for ordinary states.")
  public String layoutStateNode(
      String diagramName, String stateName, int x, int y, int width, int height)
      throws Exception {
    return onEdt(
        () -> {
          if (width < 16 || height < 16) {
            throw new IllegalArgumentException("width and height must be at least 16 pixels");
          }
          IDiagramElement shape = stateNode(findDiagram(diagramName), stateName);
          shape.setBounds(x, y, width, height);
          if (shape instanceof IBaseState2UIModel) {
            IBaseState2UIModel stateShape = (IBaseState2UIModel) shape;
            stateShape.setShowInternalActivities(false);
            stateShape.setShowInternalTransitions(false);
            stateShape.setCenterCaptionVertically(
                IBaseState2UIModel.CENTER_CAPTION_VERTICALLY_YES);
          }
          if (shape instanceof IShapeUIModel) {
            IShapeUIModel stateShape = (IShapeUIModel) shape;
            stateShape.setRequestResetCaption(true);
            stateShape.resetCaption();
            stateShape.resetCaptionSize();
          }
          return "Laid out state-machine node " + stateName;
        });
  }

  @Tool(
      description =
          "Move or hide a state-machine node caption after inspecting an export. "
              + "Hide internal names on initial and final pseudostates.")
  public String layoutStateNodeLabel(
      String diagramName, String stateName, int x, int y, boolean visible) throws Exception {
    return onEdt(
        () -> {
          IDiagramElement shape = stateNode(findDiagram(diagramName), stateName);
          ICaptionUIModel caption = shape.getCaptionUIModel();
          caption.setVisible(visible);
          if (visible) {
            caption.setSide(ICaptionUIModel.SIDE_FREEMOVE);
            caption.setX(x);
            caption.setY(y);
          }
          return (visible ? "Moved" : "Hid") + " state-machine node label " + stateName;
        });
  }

  @Tool(
      description =
          "Add a transition. trigger, guard, and effect are rendered as trigger [guard] / effect; pass empty strings when absent.")
  public String addTransition(
      String diagramName,
      String fromState,
      String toState,
      String trigger,
      String guard,
      String effect)
      throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          IDiagramElement from = stateNode(diagram, fromState);
          IDiagramElement to = stateNode(diagram, toState);
          ITransition2 transition = models().createTransition2();
          transition.setName(transitionLabel(trigger, guard, effect));
          addConnector(diagram, transition, from, to);
          return "Added transition " + fromState + " -> " + toState;
        });
  }

  @Tool(
      description =
          "Route an existing state transition through explicit points. pointsCsv uses "
              + "ordered x:y pairs from fromState to toState, including both endpoints. "
              + "connectorStyle is rectilinear or oblique.")
  public String routeStateTransition(
      String diagramName,
      String fromState,
      String toState,
      String pointsCsv,
      String connectorStyle)
      throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          IConnectorUIModel connector = findTransition(diagram, fromState, toState);
          Point[] points = parsePoints(pointsCsv);
          int style = connectorStyle(connectorStyle);
          String actualFrom = connector.getFromShape().getModelElement().getName();

          connector.clearPoints();
          if (fromState.equals(actualFrom)) {
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
          return "Routed state transition " + fromState + " -> " + toState;
        });
  }

  @Tool(
      description =
          "Move a state-transition label after routing. Keep the trigger, optional guard, "
              + "and optional effect close to the correct transition and clear of states.")
  public String layoutStateTransitionLabel(
      String diagramName, String fromState, String toState, int x, int y) throws Exception {
    return onEdt(
        () -> {
          IConnectorUIModel connector =
              findTransition(findDiagram(diagramName), fromState, toState);
          ICaptionUIModel caption = connector.getCaptionUIModel();
          caption.setVisible(true);
          caption.setSide(ICaptionUIModel.SIDE_FREEMOVE);
          caption.setX(x);
          caption.setY(y);
          return "Moved state transition label "
              + fromState
              + " -> "
              + toState
              + " to ("
              + x
              + ", "
              + y
              + ")";
        });
  }

  private IActivity activity(String name) {
    IActivity activity = models().createActivity();
    activity.setName(name);
    return activity;
  }

  private IDiagramElement stateNode(IDiagramUIModel diagram, String name) {
    Class<?>[] supported = {IState2.class, IInitialPseudoState.class, IFinalState2.class};
    for (Class<?> type : supported) {
      try {
        @SuppressWarnings("unchecked")
        Class<? extends IModelElement> modelType = (Class<? extends IModelElement>) type;
        return findShape(diagram, name, modelType);
      } catch (IllegalArgumentException ignored) {
        // Try the next supported state node type.
      }
    }
    throw new IllegalArgumentException("State node not found: " + name);
  }

  private static IConnectorUIModel findTransition(
      IDiagramUIModel diagram, String fromState, String toState) {
    Iterator<?> iterator = diagram.diagramElementIterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (!(item instanceof IConnectorUIModel)) {
        continue;
      }
      IConnectorUIModel connector = (IConnectorUIModel) item;
      if (!(connector.getModelElement() instanceof ITransition2)
          || connector.getFromShape() == null
          || connector.getToShape() == null) {
        continue;
      }
      String actualFrom = connector.getFromShape().getModelElement().getName();
      String actualTo = connector.getToShape().getModelElement().getName();
      if ((fromState.equals(actualFrom) && toState.equals(actualTo))
          || (fromState.equals(actualTo) && toState.equals(actualFrom))) {
        return connector;
      }
    }
    throw new IllegalArgumentException(
        "State transition not found on diagram '"
            + diagram.getName()
            + "': "
            + fromState
            + " <-> "
            + toState);
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

  private static String transitionLabel(String trigger, String guard, String effect) {
    StringBuilder label = new StringBuilder(clean(trigger));
    if (!clean(guard).isEmpty()) {
      if (label.length() > 0) {
        label.append(' ');
      }
      label.append('[').append(clean(guard)).append(']');
    }
    if (!clean(effect).isEmpty()) {
      if (label.length() > 0) {
        label.append(' ');
      }
      label.append("/ ").append(clean(effect));
    }
    return label.toString();
  }
}

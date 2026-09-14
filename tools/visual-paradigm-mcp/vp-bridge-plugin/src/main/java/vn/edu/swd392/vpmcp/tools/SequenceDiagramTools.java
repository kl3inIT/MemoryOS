package vn.edu.swd392.vpmcp.tools;

import com.vp.plugin.diagram.IInteractionDiagramUIModel;
import com.vp.plugin.diagram.ICaptionUIModel;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IDiagramTypeConstants;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.diagram.connector.IMessageUIModel;
import com.vp.plugin.diagram.shape.IActivationUIModel;
import com.vp.plugin.diagram.shape.ICombinedFragmentUIModel;
import com.vp.plugin.diagram.shape.IInteractionActorUIModel;
import com.vp.plugin.diagram.shape.IInteractionLifeLineUIModel;
import com.vp.plugin.diagram.shape.IInteractionOperandUIModel;
import com.vp.plugin.model.IActivation;
import com.vp.plugin.model.IClass;
import com.vp.plugin.model.ICombinedFragment;
import com.vp.plugin.model.IFrame;
import com.vp.plugin.model.IInteractionActor;
import com.vp.plugin.model.IInteractionConstraint;
import com.vp.plugin.model.IInteractionLifeLine;
import com.vp.plugin.model.IInteractionOperand;
import com.vp.plugin.model.IMessage;
import com.vp.plugin.model.IModel;
import com.vp.plugin.model.IModelElement;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import vn.edu.swd392.vpmcp.bridge.Tool;

public final class SequenceDiagramTools extends VpAccess {
  private static final int LIFELINE_Y = 25;
  private static final int LIFELINE_WIDTH = 160;
  private static final int LIFELINE_HEIGHT = 940;
  private static final int FIRST_MESSAGE_Y = 180;
  private static final int MESSAGE_GAP = 38;

  @Tool(
      description =
          "Create and open a UML sequence diagram. Call this first. The diagram uses Visual Paradigm auto-extended execution specifications, visible message names, and manual nested numbering such as 1, 1.1, and 1.1.1.")
  public String createSequenceDiagram(String diagramName) throws Exception {
    return onEdt(
        () -> {
          IInteractionDiagramUIModel diagram =
              (IInteractionDiagramUIModel)
                  createDiagram(
                      IDiagramTypeConstants.DIAGRAM_TYPE_INTERACTION_DIAGRAM, diagramName);
          IModel owner = models().createModel();
          owner.setName(diagramName + " Model");
          owner.addSubDiagram(diagram);
          diagram.getRootFrame(true);
          // Create execution specifications explicitly. This follows Visual Paradigm's
          // official OpenAPI sample and lets every message reference its caller/callee
          // activation without producing duplicate auto-activation presentations.
          diagram.setAutoExtendActivations(false);
          diagram.setShowActivations(true);
          diagram.setShowMessageName(true);
          diagram.setShowSequenceNumbers(true);
          diagram.setSequenceNumberHandling(IInteractionDiagramUIModel.MANUAL);
          diagram.setSequenceNumbering(IInteractionDiagramUIModel.NESTED_LEVEL);
          return "Created sequence diagram: " + diagramName;
        });
  }

  @Tool(
      description =
          "Add a real external UML actor, normally as the leftmost participant. Use a role name from the requirements. An actor starts or participates in the use-case interaction but does not require a final return message.")
  public String addSequenceActor(String diagramName, String actorName, int x) throws Exception {
    return onEdt(
        () -> {
          IInteractionDiagramUIModel diagram = sequenceDiagram(diagramName);
          IInteractionActor actor = models().createInteractionActor();
          actor.setName(actorName);
          rootFrame(diagram).addInteractionActor(actor);
          IInteractionActorUIModel shape =
              (IInteractionActorUIModel) diagrams().createDiagramElement(diagram, actor);
          shape.setBounds(x, LIFELINE_Y, LIFELINE_WIDTH, LIFELINE_HEIGHT);
          shape.resetCaption();
          return "Added sequence actor " + actorName;
        });
  }

  @Tool(
      description =
          "Add a system participant lifeline, ordered left-to-right by first participation. stereotype may be boundary, control, entity, repository, service, or empty. Use addSequenceActor instead for a human or external role.")
  public String addLifeline(
      String diagramName,
      String lifelineName,
      String classifierName,
      String stereotype,
      int x)
      throws Exception {
    return onEdt(
        () -> {
          IInteractionDiagramUIModel diagram = sequenceDiagram(diagramName);
          IClass classifier = models().createClass();
          IModelElement owner = diagram.getParentModel();
          if (owner == null) {
            throw new IllegalStateException("Sequence diagram does not have an owning model");
          }
          owner.addChild(classifier);
          classifier.setName(classifierName);
          if (!clean(stereotype).isEmpty()) {
            classifier.addStereotype(clean(stereotype));
          }
          IInteractionLifeLine lifeline = models().createInteractionLifeLine();
          lifeline.setBaseClassifier(classifier);
          lifeline.setName(lifelineName);
          rootFrame(diagram).addInteractionLifeLine(lifeline);
          IInteractionLifeLineUIModel shape =
              (IInteractionLifeLineUIModel) diagrams().createDiagramElement(diagram, lifeline);
          shape.setBounds(x, LIFELINE_Y, LIFELINE_WIDTH, LIFELINE_HEIGHT);
          shape.setShowClassifier(true);
          shape.resetCaption();
          return "Added lifeline " + lifelineName;
        });
  }

  @Tool(
      description =
          "Move an existing sequence actor or lifeline horizontally without changing message order or vertical extent. Re-layout activations and combined fragments after compacting participants.")
  public String layoutSequenceParticipant(
      String diagramName, String participantName, int x) throws Exception {
    return onEdt(
        () -> {
          IInteractionDiagramUIModel diagram = sequenceDiagram(diagramName);
          Participant participant = participant(diagram, participantName);
          IDiagramElement shape = participant.shape;
          shape.setBounds(x, shape.getY(), shape.getWidth(), shape.getHeight());
          shape.resetCaption();
          return "Moved sequence participant " + participantName + " to x=" + x;
        });
  }

  @Tool(
      description =
          "Ensure a lifeline has an activation model. Normally omit this because createSequenceDiagram enables Visual Paradigm auto-extended activations; use layoutActivation after messages to set the visible execution span.")
  public String addActivation(String diagramName, String lifelineName) throws Exception {
    return onEdt(
        () -> {
          IInteractionDiagramUIModel diagram = sequenceDiagram(diagramName);
          IInteractionLifeLine lifeline =
              model(diagram, lifelineName, IInteractionLifeLine.class);
          activation(lifeline, diagram);
          return "Added activation to " + lifelineName;
        });
  }

  @Tool(
      description =
          "Merge every activation presentation of one lifeline into an explicit execution span. "
              + "Use after adding all messages. startY is when that participant begins executing and endY is when it completes; the lifeline remains dashed outside that interval.")
  public String layoutActivation(
      String diagramName, String lifelineName, int startY, int endY) throws Exception {
    return onEdt(
        () -> {
          if (endY <= startY) {
            throw new IllegalArgumentException("endY must be greater than startY");
          }
          IInteractionDiagramUIModel diagram = sequenceDiagram(diagramName);
          IInteractionLifeLine lifeline =
              model(diagram, lifelineName, IInteractionLifeLine.class);
          if (lifeline.activationCount() == 0) {
            activation(lifeline, diagram);
          }
          IDiagramElement lifelineShape =
              findShape(diagram, lifelineName, IInteractionLifeLine.class);
          int centerX =
              lifelineShape.getX()
                  + (lifelineShape.getWidth() - IActivationUIModel.BODY_WIDTH) / 2;
          List<IActivationUIModel> presentations = new ArrayList<>();
          for (IActivation activation : lifeline.toActivationArray()) {
            for (IDiagramElement element : diagramElements(diagram)) {
              if (element instanceof IActivationUIModel
                  && sameModel(element.getModelElement(), activation)) {
                presentations.add((IActivationUIModel) element);
              }
            }
          }
          for (IActivationUIModel presentation : presentations) {
            presentation.setBounds(
                centerX, startY, IActivationUIModel.BODY_WIDTH, endY - startY);
          }
          return "Laid out "
              + presentations.size()
              + " activation presentation(s) for "
              + lifelineName
              + " from y="
              + startY
              + " to y="
              + endY;
        });
  }

  @Tool(
      description =
          "Add a call message between sequence participants. Use a synchronous call for an operation whose result is needed before continuing, and asynchronous only when the sender does not wait. The same source and target creates a self-message. A top-level actor request is numbered 1 or 2; calls made while handling it use nested numbers such as 2.1 and 2.1.1.")
  public String addMessage(
      String diagramName,
      String fromLifeline,
      String toLifeline,
      String messageName,
      String sequenceNumber,
      boolean asynchronous)
      throws Exception {
    return onEdt(
        () -> {
          IInteractionDiagramUIModel diagram = sequenceDiagram(diagramName);
          Participant from = participant(diagram, fromLifeline);
          Participant to = participant(diagram, toLifeline);
          IMessage message = models().createMessage();
          message.setName(messageName);
          message.setSequenceNumber(clean(sequenceNumber));
          message.setAsynchronous(asynchronous);
          if (from.model == to.model) {
            message.setType(IMessage.TYPE_SELF_MESSAGE);
          }
          createMessageConnector(diagram, message, from, to);
          return "Added message " + fromLifeline + " -> " + toLifeline + ": " + messageName;
        });
  }

  @Tool(
      description =
          "Rename one existing sequence message while preserving its sender, receiver, numbering, return linkage, operand assignment, and vertical position.")
  public String renameMessage(
      String diagramName, String messageName, String newMessageName) throws Exception {
    return onEdt(
        () -> {
          requireText(newMessageName, "newMessageName");
          IInteractionDiagramUIModel diagram = sequenceDiagram(diagramName);
          String targetName = clean(newMessageName);
          for (IDiagramElement item : diagramElements(diagram)) {
            Object model = item.getModelElement();
            if (model instanceof IMessage
                && targetName.equals(((IMessage) model).getName())
                && !messageName.equals(targetName)) {
              throw new IllegalArgumentException(
                  "Another message already uses the name: " + targetName);
            }
          }
          IMessage model = message(diagram, messageName);
          model.setName(targetName);
          diagramElement(diagram, model).resetCaption();
          return "Renamed message " + messageName + " -> " + targetName;
        });
  }

  @Tool(
      description =
          "Add a true result/control return linked to an earlier call. "
              + "The source must be the earlier callee and the target must be that call's caller. Do not use this merely to end a scenario or display feedback to an actor; use a boundary self-message such as displayConfirmation() or a result-page lifeline. Return sequenceNumber should normally be empty.")
  public String addReturnMessage(
      String diagramName,
      String originalMessageName,
      String fromLifeline,
      String toLifeline,
      String messageName,
      String sequenceNumber)
      throws Exception {
    return onEdt(
        () -> {
          IInteractionDiagramUIModel diagram = sequenceDiagram(diagramName);
          IMessage original = message(diagram, originalMessageName);
          Participant from = participant(diagram, fromLifeline);
          Participant to = participant(diagram, toLifeline);
          IMessage reply = models().createMessage();
          reply.setName(messageName);
          reply.setSequenceNumber(clean(sequenceNumber));
          original.setReturnMessage(reply);
          createMessageConnector(diagram, reply, from, to);
          return "Added return message " + messageName;
        });
  }

  @Tool(
      description =
          "Move one message caption only when the exported image shows a concrete overlap or clipping problem. Keep the caption above and close to its own message line.")
  public String layoutMessageLabel(
      String diagramName, String messageName, int x, int y) throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          IMessage model = message(diagram, messageName);
          IDiagramElement element = diagramElement(diagram, model);
          if (!(element instanceof IMessageUIModel)) {
            throw new IllegalStateException("Message presentation has an unexpected type");
          }
          ICaptionUIModel caption = element.getCaptionUIModel();
          caption.setSide(ICaptionUIModel.SIDE_FREEMOVE);
          caption.setX(x);
          caption.setY(y);
          return "Moved message label " + messageName + " to (" + x + ", " + y + ")";
        });
  }

  @Tool(
      description =
          "Move one complete sequence message to an explicit vertical position after all messages exist. Use this to keep a branch below its operand guard/separator; message order must still increase from top to bottom.")
  public String layoutMessage(String diagramName, String messageName, int y) throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          IMessage model = message(diagram, messageName);
          IDiagramElement element = diagramElement(diagram, model);
          if (!(element instanceof IMessageUIModel)) {
            throw new IllegalStateException("Message presentation has an unexpected type");
          }
          IMessageUIModel connector = (IMessageUIModel) element;
          IDiagramElement fromShape = connector.getFromShape();
          IDiagramElement toShape = connector.getToShape();
          Point fromPoint = new Point(centerX(fromShape), y);
          Point toPoint = new Point(centerX(toShape), y);
          connector.clearPoints();
          for (Point point : messagePoints(fromPoint, toPoint)) {
            connector.addPoint(point);
          }
          connector.resetCaption();
          return "Moved message " + messageName + " to y=" + y;
        });
  }

  @Tool(
      description =
          "Add a semantic UML combined fragment. Use alt for mutually exclusive guarded paths, opt for one optional path, loop for repetition, break for an aborting path, and par for concurrent paths. guardsCsv creates operands in top-to-bottom order; coveredLifelinesCsv must list every participant crossed by the enclosed messages.")
  public String addCombinedFragment(
      String diagramName,
      String operator,
      String guardsCsv,
      String coveredLifelinesCsv,
      int x,
      int y,
      int width,
      int height)
      throws Exception {
    return onEdt(
        () -> {
          IInteractionDiagramUIModel diagram = sequenceDiagram(diagramName);
          ICombinedFragment fragment = models().createCombinedFragment();
          fragment.setName(clean(operator));
          fragment.setInteractionOperator(operator(operator));
          List<IInteractionOperandUIModel> operandShapes = new ArrayList<>();
          for (String guardText : guardsCsv.split(",", -1)) {
            IInteractionOperand operand = models().createInteractionOperand();
            if (!guardText.isBlank()) {
              IInteractionConstraint constraint = models().createInteractionConstraint();
              constraint.setName(guardText.trim());
              constraint.setConstraint(guardText.trim());
              operand.setGuard(constraint);
            }
            fragment.addOperand(operand);
            IInteractionOperandUIModel operandShape =
                (IInteractionOperandUIModel) diagrams().createDiagramElement(diagram, operand);
            operandShapes.add(operandShape);
          }
          for (String name : coveredLifelinesCsv.split(",")) {
            if (!name.isBlank()) {
              fragment.addCoveredLifeLine(participant(diagram, name.trim()).model);
            }
          }
          rootFrame(diagram).addCombinedFragment(fragment);
          ICombinedFragmentUIModel fragmentShape =
              (ICombinedFragmentUIModel) diagrams().createDiagramElement(diagram, fragment);
          fragmentShape.setBounds(x, y, width, height);
          int bodyY = y + 28;
          int availableHeight = Math.max(operandShapes.size(), height - 28);
          int operandHeight = availableHeight / operandShapes.size();
          for (int index = 0; index < operandShapes.size(); index++) {
            IInteractionOperandUIModel operandShape = operandShapes.get(index);
            int actualHeight =
                index == operandShapes.size() - 1
                    ? availableHeight - operandHeight * index
                    : operandHeight;
            operandShape.setBounds(x, bodyY + operandHeight * index, width, actualHeight);
            fragmentShape.addChild(operandShape);
            operandShape.resetCaption();
          }
          fragmentShape.resetCaption();
          return "Added " + operator + " fragment with " + fragment.operandCount() + " operands";
        });
  }

  @Tool(
      description =
          "Move and resize an existing combined fragment identified by one unique operand guard. Call layoutCombinedFragmentOperands afterwards so every operand is positioned relative to the new frame.")
  public String layoutCombinedFragment(
      String diagramName,
      String fragmentGuard,
      int x,
      int y,
      int width,
      int height)
      throws Exception {
    return onEdt(
        () -> {
          if (width < 100 || height < 80) {
            throw new IllegalArgumentException(
                "Combined fragment width must be at least 100 and height at least 80");
          }
          IDiagramUIModel diagram = findDiagram(diagramName);
          ICombinedFragment fragment = combinedFragment(diagram, fragmentGuard);
          ICombinedFragmentUIModel fragmentShape =
              (ICombinedFragmentUIModel) diagramElement(diagram, fragment);
          fragmentShape.setBounds(x, y, width, height);
          fragmentShape.resetCaption();
          return "Moved fragment identified by ["
              + fragmentGuard
              + "] to ("
              + x
              + ", "
              + y
              + ") with size "
              + width
              + "x"
              + height;
        });
  }

  @Tool(
      description =
          "Set top-to-bottom operand body heights after messages and nested fragments are known. Give every guard and message enough vertical room; this changes presentation only, not UML containment.")
  public String layoutCombinedFragmentOperands(
      String diagramName, String fragmentGuard, String operandHeightsCsv) throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          ICombinedFragment fragment = combinedFragment(diagram, fragmentGuard);
          ICombinedFragmentUIModel fragmentShape =
              (ICombinedFragmentUIModel) diagramElement(diagram, fragment);
          String[] heightValues = operandHeightsCsv.split(",");
          IInteractionOperand[] operands = fragment.toOperandArray();
          if (heightValues.length != operands.length) {
            throw new IllegalArgumentException(
                "operandHeightsCsv must contain exactly "
                    + operands.length
                    + " values for this fragment");
          }
          int currentY = fragmentShape.getY() + 28;
          int totalHeight = 28;
          for (int index = 0; index < operands.length; index++) {
            int height = Integer.parseInt(heightValues[index].trim());
            if (height < 40) {
              throw new IllegalArgumentException("Each operand height must be at least 40");
            }
            IInteractionOperandUIModel shape = operandShape(diagram, operands[index]);
            shape.setBounds(
                fragmentShape.getX(), currentY, fragmentShape.getWidth(), height);
            shape.resetCaption();
            currentY += height;
            totalHeight += height;
          }
          fragmentShape.setBounds(
              fragmentShape.getX(),
              fragmentShape.getY(),
              fragmentShape.getWidth(),
              totalHeight);
          fragmentShape.resetCaption();
          return "Laid out "
              + operands.length
              + " operands for fragment identified by ["
              + fragmentGuard
              + "]";
        });
  }

  @Tool(
      description =
          "Assign existing sequence messages to one combined-fragment operand. "
              + "Use | between message names when a message label itself contains commas. "
              + "Assign every message visually enclosed by the operand so the frame is semantic UML rather than a decorative rectangle.")
  public String assignMessagesToOperand(
      String diagramName,
      String fragmentGuard,
      String operandGuard,
      String messageNamesCsv)
      throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          ICombinedFragment fragment = combinedFragment(diagram, fragmentGuard);
          IInteractionOperand operand = operand(fragment, operandGuard);
          int count = 0;
          String separator = messageNamesCsv.contains("|") ? "\\|" : ",";
          for (String messageName : messageNamesCsv.split(separator)) {
            if (!messageName.isBlank()) {
              IMessage message = message(diagram, messageName.trim());
              operand.addMessage(message);
              count++;
            }
          }
          return "Assigned "
              + count
              + " messages to operand ["
              + operandGuard
              + "] in fragment identified by ["
              + fragmentGuard
              + "]";
        });
  }

  @Tool(
      description =
          "Nest a child fragment semantically and visually inside one exact parent operand. Use this when a condition such as capacityAvailable/capacityFull is evaluated only inside an eventOpen branch.")
  public String nestCombinedFragment(
      String diagramName,
      String parentFragmentGuard,
      String parentOperandGuard,
      String childFragmentGuard)
      throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          ICombinedFragment parent = combinedFragment(diagram, parentFragmentGuard);
          ICombinedFragment child = combinedFragment(diagram, childFragmentGuard);
          IInteractionOperand parentOperand = operand(parent, parentOperandGuard);
          IInteractionOperandUIModel parentOperandShape = operandShape(diagram, parentOperand);
          ICombinedFragmentUIModel childShape =
              (ICombinedFragmentUIModel) diagramElement(diagram, child);
          parentOperand.addCombinedFragment(child);
          parentOperandShape.addChild(childShape);
          return "Nested fragment identified by ["
              + childFragmentGuard
              + "] under operand ["
              + parentOperandGuard
              + "]";
        });
  }

  private IActivation activation(
      IInteractionLifeLine lifeline, IInteractionDiagramUIModel diagram) {
    IActivation primary = null;
    int primaryHeight = -1;
    Iterator<?> iterator = lifeline.activationIterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (item instanceof IActivation) {
        IActivation candidate = (IActivation) item;
        int height = maximumActivationPresentationHeight(diagram, candidate);
        if (primary == null || height > primaryHeight) {
          primary = candidate;
          primaryHeight = height;
        }
      }
    }
    if (primary == null) {
      primary = models().createActivation();
      lifeline.addActivation(primary);
      IActivationUIModel shape =
          (IActivationUIModel) diagrams().createDiagramElement(diagram, primary);
      IDiagramElement lifelineShape =
          findShape(diagram, lifeline.getName(), IInteractionLifeLine.class);
      int centerX =
          lifelineShape.getX() + (lifelineShape.getWidth() - IActivationUIModel.BODY_WIDTH) / 2;
      shape.setBounds(
          centerX,
          FIRST_MESSAGE_Y - 20,
          IActivationUIModel.BODY_WIDTH,
          LIFELINE_HEIGHT - FIRST_MESSAGE_Y);
    }
    return primary;
  }

  private IMessageUIModel createMessageConnector(
      IInteractionDiagramUIModel diagram,
      IMessage message,
      Participant from,
      Participant to) {
    if (from.lifeline != null) {
      message.setFromActivation(activation(from.lifeline, diagram));
    }
    if (to.lifeline != null) {
      message.setToActivation(activation(to.lifeline, diagram));
    }
    int y = FIRST_MESSAGE_Y + messageCount(diagram) * MESSAGE_GAP;
    Point fromPoint = new Point(centerX(from.shape), y);
    Point toPoint = new Point(centerX(to.shape), y);
    IMessageUIModel connector =
        (IMessageUIModel)
            diagrams()
                .createConnector(
                    diagram, message, from.shape, to.shape, messagePoints(fromPoint, toPoint));
    connector.setShowMessageName(IMessageUIModel.SHOW_MESSAGE_NAME_YES);
    connector.resetCaption();
    return connector;
  }

  private static Point[] messagePoints(Point from, Point to) {
    if (from.x != to.x) {
      return new Point[] {from, to};
    }
    return new Point[] {
      from,
      new Point(from.x + 70, from.y),
      new Point(from.x + 70, from.y + 24),
      new Point(from.x, from.y + 24)
    };
  }

  private static int centerX(IDiagramElement element) {
    return element.getX() + element.getWidth() / 2;
  }

  private static int messageCount(IDiagramUIModel diagram) {
    int count = 0;
    Iterator<?> iterator = diagram.diagramElementIterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (item instanceof IDiagramElement
          && ((IDiagramElement) item).getModelElement() instanceof IMessage) {
        count++;
      }
    }
    return count;
  }

  private static int maximumActivationPresentationHeight(
      IDiagramUIModel diagram, IActivation activation) {
    int height = -1;
    for (IDiagramElement element : diagramElements(diagram)) {
      if (element instanceof IActivationUIModel
          && sameModel(element.getModelElement(), activation)) {
        height = Math.max(height, element.getHeight());
      }
    }
    return height;
  }

  private static boolean sameModel(Object left, Object right) {
    return left == right || (left != null && left.equals(right));
  }

  private IMessage message(IDiagramUIModel diagram, String name) {
    for (IDiagramElement item : diagramElements(diagram)) {
      Object model = item.getModelElement();
      if (model instanceof IMessage && name.equals(((IMessage) model).getName())) {
        return (IMessage) model;
      }
    }
    throw new IllegalArgumentException("Message not found on diagram: " + name);
  }

  private ICombinedFragment combinedFragment(IDiagramUIModel diagram, String identifyingGuard) {
    requireText(identifyingGuard, "fragmentGuard");
    ICombinedFragment match = null;
    for (IDiagramElement item : diagramElements(diagram)) {
      Object model = item.getModelElement();
      if (model instanceof ICombinedFragment
          && hasOperand((ICombinedFragment) model, identifyingGuard)) {
        ICombinedFragment candidate = (ICombinedFragment) model;
        if (match != null && !sameModel(match, candidate)) {
          throw new IllegalArgumentException(
              "Multiple combined fragments contain guard: " + identifyingGuard);
        }
        match = candidate;
      }
    }
    if (match == null) {
      throw new IllegalArgumentException(
          "Combined fragment not found by guard: " + identifyingGuard);
    }
    return match;
  }

  private static Participant participant(IDiagramUIModel diagram, String name) {
    for (IDiagramElement shape : diagramElements(diagram)) {
      Object model = shape.getModelElement();
      if (model instanceof IInteractionLifeLine
          && name.equals(((IInteractionLifeLine) model).getName())) {
        return new Participant((IModelElement) model, shape, (IInteractionLifeLine) model);
      }
      if (model instanceof IInteractionActor
          && name.equals(((IInteractionActor) model).getName())) {
        return new Participant((IModelElement) model, shape, null);
      }
    }
    throw new IllegalArgumentException("Sequence participant not found: " + name);
  }

  private static boolean hasOperand(ICombinedFragment fragment, String guard) {
    Iterator<?> iterator = fragment.operandIterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (item instanceof IInteractionOperand
          && guard.equals(guardText((IInteractionOperand) item))) {
        return true;
      }
    }
    return false;
  }

  private static IInteractionOperand operand(ICombinedFragment fragment, String guard) {
    Iterator<?> iterator = fragment.operandIterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (item instanceof IInteractionOperand
          && guard.equals(guardText((IInteractionOperand) item))) {
        return (IInteractionOperand) item;
      }
    }
    throw new IllegalArgumentException("Operand not found by guard: " + guard);
  }

  private static String guardText(IInteractionOperand operand) {
    IInteractionConstraint guard = operand.getGuard();
    return guard == null ? "" : clean(guard.getConstraint());
  }

  private IInteractionDiagramUIModel sequenceDiagram(String diagramName) {
    IDiagramUIModel diagram = findDiagram(diagramName);
    if (!(diagram instanceof IInteractionDiagramUIModel)) {
      throw new IllegalArgumentException("Not a sequence diagram: " + diagramName);
    }
    return (IInteractionDiagramUIModel) diagram;
  }

  private static IFrame rootFrame(IInteractionDiagramUIModel diagram) {
    return diagram.getRootFrame(true);
  }

  private static IDiagramElement diagramElement(
      IDiagramUIModel diagram, Object modelElement) {
    for (IDiagramElement item : diagramElements(diagram)) {
      Object presentedModel = item.getModelElement();
      if (sameModel(presentedModel, modelElement)) {
        return item;
      }
    }
    throw new IllegalArgumentException("Diagram presentation not found for model element");
  }

  private static List<IDiagramElement> diagramElements(IDiagramUIModel diagram) {
    List<IDiagramElement> elements = new ArrayList<>();
    Set<IDiagramElement> visited =
        Collections.newSetFromMap(new IdentityHashMap<IDiagramElement, Boolean>());
    Iterator<?> iterator = diagram.diagramElementIterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (item instanceof IDiagramElement) {
        collectDiagramElement((IDiagramElement) item, elements, visited);
      }
    }
    return elements;
  }

  private static void collectDiagramElement(
      IDiagramElement element, List<IDiagramElement> elements, Set<IDiagramElement> visited) {
    if (!visited.add(element)) {
      return;
    }
    elements.add(element);
    Iterator<?> children = element.childIterator();
    while (children.hasNext()) {
      Object child = children.next();
      if (child instanceof IDiagramElement) {
        collectDiagramElement((IDiagramElement) child, elements, visited);
      }
    }
  }

  private static IInteractionOperandUIModel operandShape(
      IDiagramUIModel diagram, IInteractionOperand operand) {
    IDiagramElement element = diagramElement(diagram, operand);
    if (!(element instanceof IInteractionOperandUIModel)) {
      throw new IllegalStateException("Operand presentation has an unexpected type");
    }
    return (IInteractionOperandUIModel) element;
  }

  private static String operator(String value) {
    switch (clean(value).toLowerCase()) {
      case "opt":
        return ICombinedFragment.INTERACTION_OPERATOR_OPT;
      case "loop":
        return ICombinedFragment.INTERACTION_OPERATOR_LOOP;
      case "break":
        return ICombinedFragment.INTERACTION_OPERATOR_BREAK;
      case "par":
        return ICombinedFragment.INTERACTION_OPERATOR_PAR;
      case "alt":
      default:
        return ICombinedFragment.INTERACTION_OPERATOR_ALT;
    }
  }

  private static final class Participant {
    private final IModelElement model;
    private final IDiagramElement shape;
    private final IInteractionLifeLine lifeline;

    private Participant(
        IModelElement model, IDiagramElement shape, IInteractionLifeLine lifeline) {
      this.model = model;
      this.shape = shape;
      this.lifeline = lifeline;
    }
  }
}

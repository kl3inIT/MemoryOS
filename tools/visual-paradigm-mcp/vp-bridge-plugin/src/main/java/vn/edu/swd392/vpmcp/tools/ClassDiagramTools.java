package vn.edu.swd392.vpmcp.tools;

import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IDiagramTypeConstants;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.diagram.IConnectorUIModel;
import com.vp.plugin.diagram.shape.IClassUIModel;
import com.vp.plugin.model.IAssociation;
import com.vp.plugin.model.IAssociationEnd;
import com.vp.plugin.model.IAttribute;
import com.vp.plugin.model.IClass;
import com.vp.plugin.model.IDependency;
import com.vp.plugin.model.IEnumerationLiteral;
import com.vp.plugin.model.IGeneralization;
import com.vp.plugin.model.IOperation;
import com.vp.plugin.model.IParameter;
import com.vp.plugin.model.IRealization;
import com.vp.plugin.model.IRelationship;
import com.vp.plugin.model.ITemplateParameter;
import java.awt.Point;
import java.util.Iterator;
import java.util.function.Supplier;
import vn.edu.swd392.vpmcp.bridge.Tool;

public final class ClassDiagramTools extends VpAccess {
  @Tool(description = "Create and open a UML class diagram.")
  public String createClassDiagram(String diagramName) throws Exception {
    return onEdt(
        () -> {
          createDiagram(IDiagramTypeConstants.DIAGRAM_TYPE_CLASS_DIAGRAM, diagramName);
          return "Created class diagram: " + diagramName;
        });
  }

  @Tool(
      description =
          "Add a class at an explicit position. stereotype may be empty, Boundary, Control, Entity, or Interface.")
  public String addClass(
      String diagramName, String className, String stereotype, int x, int y) throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          IClass model = models().createClass();
          if (!clean(stereotype).isEmpty()) {
            model.addStereotype(clean(stereotype));
          }
          addShape(diagram, model, className, x, y, 190, 120);
          return "Added class " + className;
        });
  }

  @Tool(
      description =
          "Position a class, interface, or enumeration and set its width. The vertical size is always content-fitted so the box ends directly below its last visible member; the height argument is retained for API compatibility and cannot create a large blank compartment.")
  public String layoutClass(
      String diagramName, String className, int x, int y, int width, int height)
      throws Exception {
    return onEdt(
        () -> {
          if (width < 120 || height < 60) {
            throw new IllegalArgumentException(
                "Class width must be at least 120 and height at least 60");
          }
          IDiagramUIModel diagram = findDiagram(diagramName);
          IDiagramElement shape = findShape(diagram, className, IClass.class);
          if (!(shape instanceof IClassUIModel)) {
            throw new IllegalStateException(
                "Classifier presentation has an unexpected type: " + className);
          }
          IClassUIModel classShape = (IClassUIModel) shape;
          classShape.fitSize();
          int fittedWidth = classShape.getWidth();
          int fittedHeight = classShape.getHeight();
          shape.setBounds(x, y, Math.max(width, fittedWidth), fittedHeight);
          shape.resetCaption();
          return "Laid out class "
              + className
              + " at ("
              + x
              + ", "
              + y
              + ") with content-fitted height "
              + fittedHeight;
        });
  }

  @Tool(
      description =
          "Fit a class, interface, or enumeration to the exact size required by its visible stereotype, name, attributes/literals, and operations. Use this before final positioning so boxes do not contain large blank areas.")
  public String fitClassToContents(String diagramName, String className) throws Exception {
    return onEdt(
        () -> {
          IDiagramElement element =
              findShape(findDiagram(diagramName), className, IClass.class);
          if (!(element instanceof IClassUIModel)) {
            throw new IllegalStateException(
                "Classifier presentation has an unexpected type: " + className);
          }
          IClassUIModel shape = (IClassUIModel) element;
          shape.fitSize();
          shape.resetCaption();
          return "Fit class " + className + " to " + shape.getWidth() + "x" + shape.getHeight();
        });
  }

  @Tool(
      description =
          "Rename an existing class, interface, or enumeration while preserving its attributes, operations, and relationships.")
  public String renameClass(String diagramName, String className, String newClassName)
      throws Exception {
    return onEdt(
        () -> {
          requireText(newClassName, "newClassName");
          IDiagramUIModel diagram = findDiagram(diagramName);
          IClass classifier = model(diagram, className, IClass.class);
          classifier.setName(clean(newClassName));
          IDiagramElement shape = findShape(diagram, clean(newClassName), IClass.class);
          shape.resetCaption();
          return "Renamed class " + className + " to " + clean(newClassName);
        });
  }

  @Tool(
      description =
          "Mark a class as abstract or concrete. Visual Paradigm renders an abstract class name in italics, following UML class notation.")
  public String setClassAbstract(String diagramName, String className, boolean abstractClass)
      throws Exception {
    return onEdt(
        () -> {
          IClass owner = model(findDiagram(diagramName), className, IClass.class);
          owner.setAbstract(abstractClass);
          return "Set class " + className + " abstract=" + abstractClass;
        });
  }

  @Tool(
      description =
          "Add a native UML enumeration literal to an Enumeration-stereotyped class, in the order it should appear in the literal compartment.")
  public String addEnumerationLiteral(
      String diagramName, String enumerationName, String literalName) throws Exception {
    return onEdt(
        () -> {
          IClass enumeration =
              model(findDiagram(diagramName), enumerationName, IClass.class);
          IEnumerationLiteral literal = models().createEnumerationLiteral();
          literal.setName(literalName);
          enumeration.addEnumerationLiteral(literal);
          return "Added enumeration literal " + enumerationName + "." + literalName;
        });
  }

  @Tool(
      description =
          "Add a native UML template parameter to a generic class or interface. Use parameterName T for Repository<T> instead of embedding angle brackets in the class name.")
  public String addTemplateParameter(
      String diagramName, String className, String parameterName) throws Exception {
    return onEdt(
        () -> {
          IClass owner = model(findDiagram(diagramName), className, IClass.class);
          ITemplateParameter parameter = models().createTemplateParameter();
          parameter.setName(parameterName);
          owner.addTemplateParameter(parameter);
          IDiagramElement element =
              findShape(findDiagram(diagramName), className, IClass.class);
          if (element instanceof IClassUIModel) {
            IClassUIModel shape = (IClassUIModel) element;
            shape.setShowTemplateParameters(true);
            shape.resetCaption();
          }
          return "Added template parameter " + parameterName + " to " + className;
        });
  }

  @Tool(
      description =
          "Remove a native UML template parameter from a class or interface when it is duplicated by an explicit generic classifier name such as Repository<T>, or when its detached template box harms the final layout.")
  public String removeTemplateParameter(
      String diagramName, String className, String parameterName) throws Exception {
    return onEdt(
        () -> {
          IClass owner = model(findDiagram(diagramName), className, IClass.class);
          for (ITemplateParameter parameter : owner.toTemplateParameterArray()) {
            if (parameterName.equals(parameter.getName())) {
              owner.removeTemplateParameter(parameter);
              parameter.delete();
              IDiagramElement element =
                  findShape(findDiagram(diagramName), className, IClass.class);
              element.resetCaption();
              return "Removed template parameter " + parameterName + " from " + className;
            }
          }
          throw new IllegalArgumentException(
              "Template parameter not found: " + className + "." + parameterName);
        });
  }

  @Tool(description = "Add an attribute to a class on a diagram.")
  public String addAttribute(
      String diagramName,
      String className,
      String attributeName,
      String attributeType,
      String visibility)
      throws Exception {
    return onEdt(
        () -> {
          IClass owner = model(findDiagram(diagramName), className, IClass.class);
          IAttribute attribute = models().createAttribute();
          attribute.setName(attributeName);
          attribute.setType(attributeType);
          attribute.setVisibility(normalizeVisibility(visibility));
          owner.addAttribute(attribute);
          return "Added attribute " + className + "." + attributeName;
        });
  }

  @Tool(
      description =
          "Add an operation. parametersCsv format is name:type,name:type; use an empty string for no parameters.")
  public String addOperation(
      String diagramName,
      String className,
      String operationName,
      String returnType,
      String parametersCsv,
      String visibility)
      throws Exception {
    return onEdt(
        () -> {
          IClass owner = model(findDiagram(diagramName), className, IClass.class);
          IOperation operation = models().createOperation();
          operation.setName(operationName);
          operation.setReturnType(returnType);
          operation.setVisibility(normalizeVisibility(visibility));
          for (String token : clean(parametersCsv).split(",")) {
            if (token.isBlank()) {
              continue;
            }
            String[] parts = token.trim().split(":", 2);
            IParameter parameter = models().createParameter();
            parameter.setName(parts[0].trim());
            if (parts.length == 2) {
              parameter.setType(parts[1].trim());
            }
            operation.addParameter(parameter);
          }
          owner.addOperation(operation);
          return "Added operation " + className + "." + operationName;
        });
  }

  @Tool(description = "Add a UML association with a name and multiplicities.")
  public String addAssociation(
      String diagramName,
      String fromClass,
      String toClass,
      String name,
      String fromMultiplicity,
      String toMultiplicity)
      throws Exception {
    return addAssociationLike(
        diagramName,
        fromClass,
        toClass,
        name,
        fromMultiplicity,
        toMultiplicity,
        IAssociationEnd.AGGREGATION_KIND_NONE);
  }

  @Tool(description = "Add a UML shared aggregation; fromClass is the whole/diamond end.")
  public String addAggregation(
      String diagramName,
      String fromClass,
      String toClass,
      String fromMultiplicity,
      String toMultiplicity)
      throws Exception {
    return addAssociationLike(
        diagramName,
        fromClass,
        toClass,
        "",
        fromMultiplicity,
        toMultiplicity,
        IAssociationEnd.AGGREGATION_KIND_SHARED);
  }

  @Tool(description = "Add a UML composition; fromClass is the whole/filled-diamond end.")
  public String addComposition(
      String diagramName,
      String fromClass,
      String toClass,
      String fromMultiplicity,
      String toMultiplicity)
      throws Exception {
    return addAssociationLike(
        diagramName,
        fromClass,
        toClass,
        "",
        fromMultiplicity,
        toMultiplicity,
        IAssociationEnd.AGGREGATION_KIND_COMPOSITED);
  }

  @Tool(description = "Add generalization from a subclass to its superclass.")
  public String addGeneralization(String diagramName, String subclass, String superclass)
      throws Exception {
    // Visual Paradigm OpenAPI models generalization from the general classifier to
    // the specific classifier. The rendered hollow triangle therefore points to
    // superclass even though the semantic tool input is subclass -> superclass.
    return addSimpleRelationship(
        diagramName,
        superclass,
        subclass,
        () -> models().createGeneralization(),
        "generalization");
  }

  @Tool(description = "Add a dependency from one class to another.")
  public String addDependency(String diagramName, String fromClass, String toClass)
      throws Exception {
    return addSimpleRelationship(
        diagramName, fromClass, toClass, () -> models().createDependency(), "dependency");
  }

  @Tool(description = "Add interface realization from a class to an Interface-stereotyped class.")
  public String addRealization(String diagramName, String fromClass, String interfaceName)
      throws Exception {
    // Follow the official Visual Paradigm OpenAPI sample: interface is the relationship
    // from-end and implementing class is the to-end.
    return addSimpleRelationship(
        diagramName,
        interfaceName,
        fromClass,
        () -> models().createRealization(),
        "realization");
  }

  @Tool(
      description =
          "Route the existing relationship between two classifiers through explicit points. "
              + "pointsCsv uses x:y pairs from fromClass to toClass, including both endpoints. "
              + "connectorStyle is rectilinear or oblique.")
  public String routeClassRelationship(
      String diagramName,
      String fromClass,
      String toClass,
      String pointsCsv,
      String connectorStyle)
      throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          IConnectorUIModel connector = findConnector(diagram, fromClass, toClass);
          Point[] points = parsePoints(pointsCsv);
          int style = connectorStyle(connectorStyle);

          String actualFrom = connector.getFromShape().getModelElement().getName();
          connector.clearPoints();
          if (fromClass.equals(actualFrom)) {
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
          return "Routed relationship " + fromClass + " -> " + toClass;
        });
  }

  private String addAssociationLike(
      String diagramName,
      String fromClass,
      String toClass,
      String name,
      String fromMultiplicity,
      String toMultiplicity,
      String aggregationKind)
      throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          IDiagramElement from = findShape(diagram, fromClass, IClass.class);
          IDiagramElement to = findShape(diagram, toClass, IClass.class);
          IAssociation association = models().createAssociation();
          association.setName(clean(name));
          association.setFrom(from.getModelElement());
          association.setTo(to.getModelElement());
          IAssociationEnd fromEnd = (IAssociationEnd) association.getFromEnd();
          IAssociationEnd toEnd = (IAssociationEnd) association.getToEnd();
          fromEnd.setMultiplicity(clean(fromMultiplicity));
          toEnd.setMultiplicity(clean(toMultiplicity));
          fromEnd.setAggregationKind(aggregationKind);
          diagrams().createConnector(diagram, association, from, to, null);
          return "Added relationship " + fromClass + " -> " + toClass;
        });
  }

  private String addSimpleRelationship(
      String diagramName,
      String fromName,
      String toName,
      Supplier<? extends IRelationship> relationshipFactory,
      String label)
      throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          IDiagramElement from = findShape(diagram, fromName, IClass.class);
          IDiagramElement to = findShape(diagram, toName, IClass.class);
          IRelationship relationship = relationshipFactory.get();
          addConnector(diagram, relationship, from, to);
          return "Added " + label + " " + fromName + " -> " + toName;
        });
  }

  private static IConnectorUIModel findConnector(
      IDiagramUIModel diagram, String fromClass, String toClass) {
    Iterator<?> iterator = diagram.diagramElementIterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (!(item instanceof IConnectorUIModel)) {
        continue;
      }
      IConnectorUIModel connector = (IConnectorUIModel) item;
      if (connector.getFromShape() == null || connector.getToShape() == null) {
        continue;
      }
      String actualFrom = connector.getFromShape().getModelElement().getName();
      String actualTo = connector.getToShape().getModelElement().getName();
      if ((fromClass.equals(actualFrom) && toClass.equals(actualTo))
          || (fromClass.equals(actualTo) && toClass.equals(actualFrom))) {
        return connector;
      }
    }
    throw new IllegalArgumentException(
        "Relationship not found on diagram '"
            + diagram.getName()
            + "': "
            + fromClass
            + " <-> "
            + toClass);
  }

  private static Point[] parsePoints(String pointsCsv) {
    String[] tokens = clean(pointsCsv).split(",");
    if (tokens.length < 2) {
      throw new IllegalArgumentException(
          "pointsCsv must contain at least two x:y points");
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

  private static String normalizeVisibility(String visibility) {
    switch (clean(visibility).toLowerCase()) {
      case "public":
        return IAttribute.VISIBILITY_PUBLIC;
      case "protected":
        return IAttribute.VISIBILITY_PROTECTED;
      case "package":
      case "default":
        return IAttribute.VISIBILITY_PACKAGE;
      default:
        return IAttribute.VISIBILITY_PRIVATE;
    }
  }
}

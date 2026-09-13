package vn.edu.swd392.vpmcp.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class ToolDefinition {
  private final String name;
  private final String description;
  private final ObjectNode inputSchema;
  private final boolean readOnly;
  private final boolean destructive;
  private final boolean idempotent;
  private final Object target;
  private final Method method;
  private final ObjectMapper mapper;

  private ToolDefinition(
      String name,
      String description,
      ObjectNode inputSchema,
      boolean readOnly,
      boolean destructive,
      boolean idempotent,
      Object target,
      Method method,
      ObjectMapper mapper) {
    this.name = name;
    this.description = description;
    this.inputSchema = inputSchema;
    this.readOnly = readOnly;
    this.destructive = destructive;
    this.idempotent = idempotent;
    this.target = target;
    this.method = method;
    this.mapper = mapper;
  }

  static List<ToolDefinition> scan(Object target, ObjectMapper mapper) {
    List<ToolDefinition> result = new ArrayList<>();
    for (Method method : target.getClass().getMethods()) {
      Tool tool = method.getAnnotation(Tool.class);
      if (tool == null) {
        continue;
      }
      String name = tool.name().isBlank() ? method.getName() : tool.name();
      result.add(
          new ToolDefinition(
              name,
              tool.description(),
              schemaFor(method, tool, mapper),
              tool.readOnly(),
              tool.destructive(),
              tool.idempotent(),
              target,
              method,
              mapper));
    }
    result.sort(Comparator.comparing(ToolDefinition::name));
    return result;
  }

  private static ObjectNode schemaFor(Method method, Tool tool, ObjectMapper mapper) {
    if (!tool.inputSchema().isBlank()) {
      try {
        JsonNode customSchema = mapper.readTree(tool.inputSchema());
        if (!(customSchema instanceof ObjectNode)) {
          throw new IllegalArgumentException(
              "Custom input schema must be a JSON object for " + method.getName());
        }
        return (ObjectNode) customSchema;
      } catch (Exception exception) {
        throw new IllegalArgumentException(
            "Cannot parse custom input schema for " + method.getName(), exception);
      }
    }
    ObjectNode schema = mapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode properties = mapper.createObjectNode();
    ArrayNode required = mapper.createArrayNode();
    for (Parameter parameter : method.getParameters()) {
      ObjectNode property = mapper.createObjectNode();
      property.put("type", jsonType(parameter.getType()));
      property.put("description", parameterDescription(parameter.getName()));
      properties.set(parameter.getName(), property);
      required.add(parameter.getName());
    }
    schema.set("properties", properties);
    schema.set("required", required);
    schema.put("additionalProperties", false);
    return schema;
  }

  private static String jsonType(Class<?> type) {
    if (type == boolean.class || type == Boolean.class) {
      return "boolean";
    }
    if (type == byte.class
        || type == short.class
        || type == int.class
        || type == long.class
        || Number.class.isAssignableFrom(type)) {
      return "integer";
    }
    return "string";
  }

  private static String parameterDescription(String name) {
    switch (name) {
      case "diagramName":
        return "Exact unique name of the target Visual Paradigm diagram";
      case "className":
        return "Exact class, interface, or enumeration name on the target class diagram";
      case "newClassName":
        return "New unique classifier name to display on the target class diagram";
      case "attributeName":
        return "Attribute name without a visibility symbol";
      case "attributeType":
        return "UML attribute type shown after the colon";
      case "operationName":
        return "Operation name without parentheses";
      case "returnType":
        return "UML operation return type shown after the signature";
      case "enumerationName":
        return "Exact name of an Enumeration-stereotyped class";
      case "literalName":
        return "Enumeration literal name, conventionally UPPER_SNAKE_CASE";
      case "parameterName":
        return "Generic template parameter name such as T";
      case "abstractClass":
        return "true to render an abstract classifier; false for a concrete class";
      case "x":
        return "Horizontal position in diagram pixels";
      case "y":
        return "Vertical position in diagram pixels; sequence messages must remain ordered top-to-bottom";
      case "startY":
        return "Top of the participant's execution span, aligned with the initiating call";
      case "endY":
        return "Bottom of the participant's execution span, aligned with its completed work";
      case "width":
        return "Shape width in diagram pixels";
      case "height":
        return "Shape height in diagram pixels";
      case "outputPath":
        return "Absolute output file path including a supported extension";
      case "outputDirectory":
        return "Absolute directory where exported diagram images will be written";
      case "imageFormat":
        return "Export format: png, png-transparent, jpg, svg, pdf, tiff, or tiff-transparent";
      case "scalePercent":
        return "Export scale from 25 to 400 percent";
      case "overwrite":
        return "Whether an existing output file may be replaced";
      case "visibility":
        return "UML visibility: public, protected, package, or private";
      case "stereotype":
        return "Stereotype name or an empty string when none is required";
      case "parametersCsv":
        return "Comma-separated operation parameters in name:type format, or an empty string";
      case "fromMultiplicity":
      case "toMultiplicity":
        return "UML multiplicity such as 1, 0..1, *, or 1..*";
      case "pointsCsv":
        return "Ordered connector points as comma-separated x:y pairs, including both endpoints";
      case "connectorStyle":
        return "Connector geometry: curve, rectilinear, or oblique";
      case "elementId":
        return "Stable Context element ID returned by inspectContextDiagram";
      case "flowId":
        return "Stable Context data-flow ID returned by inspectContextDiagram";
      case "expectedRevision":
        return "Exact revision returned by the latest Context inspection; rejects stale mutations";
      case "spec":
        return "Complete declarative Context Diagram specification";
      case "dryRun":
        return "true to validate and return a deterministic layout plan without mutating Visual Paradigm";
      case "swimlaneName":
        return "Exact unique name of the native UML activity swimlane";
      case "partitionNamesCsv":
        return "Comma-separated responsibility partitions ordered left-to-right, such as Staff,System";
      case "partitionName":
        return "Exact native UML activity partition name that owns the node";
      case "nodeName":
        return "Exact unique activity-node name used for lookup and display";
      case "stateName":
        return "Exact unique state-machine node name used for lookup and display";
      case "fromState":
        return "Exact source state or pseudostate name";
      case "toState":
        return "Exact target state or pseudostate name";
      case "fromNode":
        return "Exact source activity-node name";
      case "toNode":
        return "Exact target activity-node name";
      case "headerHeight":
        return "Height of the activity partition header row in diagram pixels";
      case "visible":
        return "Whether the target diagram-element caption remains visible";
      case "asynchronous":
        return "true only when the sender does not wait; false for a normal synchronous operation call";
      case "actorName":
        return "External role name, such as Student/Lecturer";
      case "lifelineName":
        return "Exact participant instance name used by sequence messages";
      case "classifierName":
        return "Classifier/type shown after the participant instance name";
      case "fromLifeline":
        return "Exact source actor or lifeline instance name";
      case "toLifeline":
        return "Exact target actor or lifeline instance name";
      case "messageName":
        return "Unique operation or result label shown above the message line, preferably operationName(arguments)";
      case "originalMessageName":
        return "Exact earlier call-message name whose result/control is being returned";
      case "sequenceNumber":
        return "Manual UML number such as 2, 2.1, or 2.1.1; use an empty string for an unnumbered return";
      case "operator":
        return "Combined-fragment operator: alt, opt, loop, break, or par";
      case "guardsCsv":
        return "Comma-separated guard conditions without brackets, in top-to-bottom operand order";
      case "coveredLifelinesCsv":
        return "Comma-separated exact lifeline names covered by the fragment";
      case "fragmentGuard":
        return "A unique operand guard used to identify the target combined fragment";
      case "parentFragmentGuard":
        return "A unique operand guard used to identify the parent combined fragment";
      case "parentOperandGuard":
        return "Exact guard of the parent operand that will contain the nested fragment";
      case "childFragmentGuard":
        return "A unique operand guard used to identify the nested combined fragment";
      case "operandGuard":
        return "Exact guard of the operand that will contain the messages";
      case "messageNamesCsv":
        return "Exact message names separated by |; comma separation is also accepted when labels contain no commas";
      case "operandHeightsCsv":
        return "Comma-separated operand body heights in display order, in diagram pixels";
      case "guard":
        return "Guard condition without brackets, or an empty string";
      case "trigger":
        return "Transition trigger text, or an empty string";
      case "effect":
        return "Transition effect text, or an empty string";
      case "entryAction":
        return "State entry action, or an empty string";
      case "doActivity":
        return "State do activity, or an empty string";
      case "exitAction":
        return "State exit action, or an empty string";
      default:
        return "Value for " + name;
    }
  }

  Object execute(JsonNode arguments) throws Exception {
    JsonNode actualArguments =
        arguments == null || arguments.isNull() ? mapper.createObjectNode() : arguments;
    JsonSchemaValidator.validate(actualArguments, inputSchema);
    Parameter[] parameters = method.getParameters();
    Object[] values = new Object[parameters.length];
    for (int index = 0; index < parameters.length; index++) {
      Parameter parameter = parameters[index];
      JsonNode node = actualArguments.get(parameter.getName());
      values[index] = convert(node, parameter.getType());
    }
    try {
      Object value = method.invoke(target, values);
      return value == null ? "OK" : value;
    } catch (InvocationTargetException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      throw exception;
    }
  }

  private Object convert(JsonNode node, Class<?> type) {
    if (type == int.class || type == Integer.class) {
      return node.intValue();
    }
    if (type == long.class || type == Long.class) {
      return node.longValue();
    }
    if (type == boolean.class || type == Boolean.class) {
      return node.booleanValue();
    }
    if (type == String.class) {
      return node.textValue();
    }
    if (JsonNode.class.isAssignableFrom(type)) {
      return node;
    }
    try {
      return mapper.treeToValue(node, type);
    } catch (Exception exception) {
      throw BridgeException.invalidArgument(
          "$", "Cannot convert tool arguments to " + type.getSimpleName());
    }
  }

  ObjectNode toJson(ObjectMapper mapper) {
    ObjectNode result = mapper.createObjectNode();
    result.put("name", name);
    result.put("description", description);
    result.set("inputSchema", inputSchema);
    ObjectNode annotations = mapper.createObjectNode();
    annotations.put("readOnlyHint", readOnly);
    annotations.put("destructiveHint", destructive);
    annotations.put("idempotentHint", idempotent);
    annotations.put("openWorldHint", false);
    result.set("annotations", annotations);
    return result;
  }

  String name() {
    return name;
  }

  boolean readOnly() {
    return readOnly;
  }
}

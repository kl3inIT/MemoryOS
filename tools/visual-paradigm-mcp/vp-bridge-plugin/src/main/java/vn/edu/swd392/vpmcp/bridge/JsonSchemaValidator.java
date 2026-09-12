package vn.edu.swd392.vpmcp.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class JsonSchemaValidator {
  private JsonSchemaValidator() {}

  static void validate(JsonNode value, JsonNode schema) {
    validate(value, schema, "$");
  }

  private static void validate(JsonNode value, JsonNode schema, String path) {
    if (schema == null || schema.isMissingNode() || schema.isNull()) {
      return;
    }
    String type = schema.path("type").asText("");
    if (!type.isEmpty() && !matchesType(value, type)) {
      throw BridgeException.invalidArgument(
          path, "Expected " + type + " at " + path + " but received " + nodeType(value));
    }

    if ("object".equals(type)) {
      validateObject(value, schema, path);
    } else if ("array".equals(type)) {
      validateArray(value, schema, path);
    } else if ("string".equals(type)) {
      validateString(value, schema, path);
    } else if ("integer".equals(type) || "number".equals(type)) {
      validateNumber(value, schema, path);
    }
    validateEnum(value, schema, path);
  }

  private static void validateObject(JsonNode value, JsonNode schema, String path) {
    JsonNode properties = schema.path("properties");
    Set<String> required = new HashSet<>();
    JsonNode requiredNode = schema.path("required");
    if (requiredNode.isArray()) {
      for (JsonNode item : requiredNode) {
        required.add(item.asText());
      }
    }
    for (String name : required) {
      if (!value.has(name) || value.get(name).isNull()) {
        throw BridgeException.invalidArgument(
            childPath(path, name), "Missing required argument: " + childPath(path, name));
      }
    }

    boolean allowAdditional = !schema.has("additionalProperties")
        || schema.path("additionalProperties").asBoolean(true);
    Iterator<Map.Entry<String, JsonNode>> fields = value.properties().iterator();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      JsonNode propertySchema = properties.get(field.getKey());
      if (propertySchema == null) {
        if (!allowAdditional) {
          throw BridgeException.invalidArgument(
              childPath(path, field.getKey()),
              "Unknown argument: " + childPath(path, field.getKey()));
        }
        continue;
      }
      validate(field.getValue(), propertySchema, childPath(path, field.getKey()));
    }
  }

  private static void validateArray(JsonNode value, JsonNode schema, String path) {
    int size = value.size();
    if (schema.has("minItems") && size < schema.path("minItems").asInt()) {
      throw BridgeException.invalidArgument(
          path, path + " must contain at least " + schema.path("minItems").asInt() + " items");
    }
    if (schema.has("maxItems") && size > schema.path("maxItems").asInt()) {
      throw BridgeException.invalidArgument(
          path, path + " must contain at most " + schema.path("maxItems").asInt() + " items");
    }
    JsonNode itemSchema = schema.path("items");
    for (int index = 0; index < size; index++) {
      validate(value.get(index), itemSchema, path + "[" + index + "]");
    }
  }

  private static void validateString(JsonNode value, JsonNode schema, String path) {
    String text = value.textValue();
    if (schema.has("minLength") && text.length() < schema.path("minLength").asInt()) {
      throw BridgeException.invalidArgument(
          path, path + " must contain at least " + schema.path("minLength").asInt() + " characters");
    }
    if (schema.has("maxLength") && text.length() > schema.path("maxLength").asInt()) {
      throw BridgeException.invalidArgument(
          path, path + " must contain at most " + schema.path("maxLength").asInt() + " characters");
    }
    if (schema.has("pattern")) {
      try {
        if (!Pattern.compile(schema.path("pattern").asText()).matcher(text).matches()) {
          throw BridgeException.invalidArgument(path, path + " has an invalid format");
        }
      } catch (PatternSyntaxException exception) {
        throw new IllegalStateException("Invalid tool schema pattern at " + path, exception);
      }
    }
  }

  private static void validateNumber(JsonNode value, JsonNode schema, String path) {
    double number = value.asDouble();
    if (schema.has("minimum") && number < schema.path("minimum").asDouble()) {
      throw BridgeException.invalidArgument(
          path, path + " must be at least " + schema.path("minimum").asText());
    }
    if (schema.has("maximum") && number > schema.path("maximum").asDouble()) {
      throw BridgeException.invalidArgument(
          path, path + " must be at most " + schema.path("maximum").asText());
    }
  }

  private static void validateEnum(JsonNode value, JsonNode schema, String path) {
    JsonNode allowed = schema.path("enum");
    if (!allowed.isArray()) {
      return;
    }
    for (JsonNode candidate : allowed) {
      if (candidate.equals(value)) {
        return;
      }
    }
    throw BridgeException.invalidArgument(path, path + " is not one of the allowed values");
  }

  private static boolean matchesType(JsonNode value, String type) {
    if (value == null || value.isNull()) {
      return false;
    }
    switch (type) {
      case "object":
        return value.isObject();
      case "array":
        return value.isArray();
      case "string":
        return value.isTextual();
      case "integer":
        return value.isIntegralNumber();
      case "number":
        return value.isNumber();
      case "boolean":
        return value.isBoolean();
      default:
        throw new IllegalStateException("Unsupported JSON schema type: " + type);
    }
  }

  private static String nodeType(JsonNode value) {
    return value == null ? "missing" : value.getNodeType().name().toLowerCase();
  }

  private static String childPath(String path, String name) {
    return "$".equals(path) ? "$." + name : path + "." + name;
  }
}

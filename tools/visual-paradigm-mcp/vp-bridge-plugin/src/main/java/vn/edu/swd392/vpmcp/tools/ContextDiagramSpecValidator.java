package vn.edu.swd392.vpmcp.tools;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import vn.edu.swd392.vpmcp.bridge.BridgeException;

final class ContextDiagramSpecValidator {
  private ContextDiagramSpecValidator() {}

  static ContextDiagramSpec validate(ContextDiagramSpec spec) {
    if (spec == null) {
      throw BridgeException.invalidArgument("$.spec", "spec is required");
    }
    requireText(spec.operationId, "$.spec.operationId");
    requireText(spec.diagramName, "$.spec.diagramName");
    requireText(spec.systemName, "$.spec.systemName");
    if (spec.entities == null || spec.entities.isEmpty()) {
      throw BridgeException.invalidArgument(
          "$.spec.entities", "A Context Diagram needs at least one external entity");
    }
    if (spec.flows == null || spec.flows.isEmpty()) {
      throw BridgeException.invalidArgument(
          "$.spec.flows", "A Context Diagram needs at least one directed data flow");
    }

    Set<String> entityIds = new HashSet<>();
    Set<String> entityNames = new HashSet<>();
    for (int index = 0; index < spec.entities.size(); index++) {
      ContextDiagramSpec.EntitySpec entity = spec.entities.get(index);
      String path = "$.spec.entities[" + index + "]";
      if (entity == null) {
        throw BridgeException.invalidArgument(path, "External entity must not be null");
      }
      requireText(entity.id, path + ".id");
      requireText(entity.name, path + ".name");
      if ("system".equalsIgnoreCase(entity.id)) {
        throw BridgeException.invalidArgument(
            path + ".id", "The reserved endpoint id 'system' cannot be used by an entity");
      }
      if (!entityIds.add(entity.id)) {
        throw BridgeException.invalidArgument(path + ".id", "Duplicate entity id: " + entity.id);
      }
      if (!entityNames.add(entity.name)) {
        throw BridgeException.invalidArgument(
            path + ".name", "Duplicate external entity name: " + entity.name);
      }
      String side = entity.side == null ? "AUTO" : entity.side.toUpperCase(Locale.ROOT);
      if (!Set.of("AUTO", "LEFT", "RIGHT", "TOP", "BOTTOM").contains(side)) {
        throw BridgeException.invalidArgument(path + ".side", "Unsupported entity side: " + side);
      }
      entity.side = side;
    }

    Set<String> flowIds = new HashSet<>();
    Set<String> connectedEntities = new HashSet<>();
    for (int index = 0; index < spec.flows.size(); index++) {
      ContextDiagramSpec.FlowSpec flow = spec.flows.get(index);
      String path = "$.spec.flows[" + index + "]";
      if (flow == null) {
        throw BridgeException.invalidArgument(path, "Data flow must not be null");
      }
      requireText(flow.id, path + ".id");
      requireText(flow.name, path + ".name");
      requireText(flow.from, path + ".from");
      requireText(flow.to, path + ".to");
      if (!flowIds.add(flow.id)) {
        throw BridgeException.invalidArgument(path + ".id", "Duplicate flow id: " + flow.id);
      }
      boolean fromSystem = "system".equalsIgnoreCase(flow.from);
      boolean toSystem = "system".equalsIgnoreCase(flow.to);
      if (fromSystem == toSystem) {
        throw BridgeException.invalidArgument(
            path,
            "Every Context data flow must connect the system and exactly one external entity");
      }
      String entityId = fromSystem ? flow.to : flow.from;
      if (!entityIds.contains(entityId)) {
        throw BridgeException.invalidArgument(
            path, "Unknown external entity endpoint: " + entityId);
      }
      connectedEntities.add(entityId);
      flow.from = fromSystem ? "system" : entityId;
      flow.to = toSystem ? "system" : entityId;
    }

    for (String entityId : entityIds) {
      if (!connectedEntities.contains(entityId)) {
        throw BridgeException.invalidArgument(
            "$.spec.entities", "External entity has no data flow: " + entityId);
      }
    }
    validateLayout(spec.layout);
    return spec;
  }

  private static void validateLayout(ContextDiagramSpec.LayoutSpec layout) {
    if (layout == null) {
      return;
    }
    requireRange(layout.centerX, 300, 4000, "$.spec.layout.centerX");
    requireRange(layout.centerY, 250, 4000, "$.spec.layout.centerY");
    requireRange(layout.processWidth, 220, 900, "$.spec.layout.processWidth");
    requireRange(layout.processHeight, 120, 600, "$.spec.layout.processHeight");
    requireRange(layout.entityWidth, 140, 600, "$.spec.layout.entityWidth");
    requireRange(layout.entityHeight, 60, 300, "$.spec.layout.entityHeight");
    requireRange(layout.horizontalGap, 100, 1000, "$.spec.layout.horizontalGap");
    requireRange(layout.verticalGap, 90, 600, "$.spec.layout.verticalGap");
    if (layout.processWidth != layout.processHeight) {
      throw BridgeException.invalidArgument(
          "$.spec.layout",
          "Official Context notation uses a circular central process; processWidth and "
              + "processHeight must be equal");
    }
    String connectorStyle =
        layout.connectorStyle == null
            ? "CURVE"
            : layout.connectorStyle.toUpperCase(Locale.ROOT);
    if (!Set.of("CURVE", "RECTILINEAR", "OBLIQUE").contains(connectorStyle)) {
      throw BridgeException.invalidArgument(
          "$.spec.layout.connectorStyle",
          "connectorStyle must be CURVE, RECTILINEAR, or OBLIQUE");
    }
    layout.connectorStyle = connectorStyle;
  }

  private static void requireRange(int value, int minimum, int maximum, String path) {
    if (value < minimum || value > maximum) {
      throw BridgeException.invalidArgument(
          path, path + " must be between " + minimum + " and " + maximum);
    }
  }

  private static void requireText(String value, String path) {
    if (value == null || value.trim().isEmpty()) {
      throw BridgeException.invalidArgument(path, path + " must not be blank");
    }
  }
}

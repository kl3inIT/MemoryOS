package vn.edu.swd392.vpmcp.tools;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import vn.edu.swd392.vpmcp.bridge.BridgeException;

final class ConceptualErdSpecValidator {
  private static final Set<String> CARDINALITIES =
      Set.of("ONE", "ZERO_OR_ONE", "ONE_OR_MANY", "ZERO_OR_MANY");

  private ConceptualErdSpecValidator() {}

  static ConceptualErdSpec validate(ConceptualErdSpec spec) {
    if (spec == null) {
      throw BridgeException.invalidArgument("$.spec", "spec is required");
    }
    requireText(spec.operationId, "$.spec.operationId");
    requireText(spec.diagramName, "$.spec.diagramName");
    if (spec.facts == null || spec.facts.isEmpty()) {
      throw BridgeException.invalidArgument(
          "$.spec.facts", "A fact ledger grounded in the exam narrative is required");
    }
    if (spec.entities == null || spec.entities.size() < 2) {
      throw BridgeException.invalidArgument(
          "$.spec.entities", "A Conceptual ERD needs at least two entities");
    }
    if (spec.relationships == null || spec.relationships.isEmpty()) {
      throw BridgeException.invalidArgument(
          "$.spec.relationships", "A Conceptual ERD needs at least one relationship");
    }

    Set<String> factIds = new HashSet<>();
    for (int index = 0; index < spec.facts.size(); index++) {
      ConceptualErdSpec.FactSpec fact = spec.facts.get(index);
      String path = "$.spec.facts[" + index + "]";
      if (fact == null) {
        throw BridgeException.invalidArgument(path, "Fact must not be null");
      }
      requireText(fact.id, path + ".id");
      requireText(fact.text, path + ".text");
      if (!factIds.add(fact.id)) {
        throw BridgeException.invalidArgument(path + ".id", "Duplicate fact id: " + fact.id);
      }
    }

    Set<String> entityIds = new HashSet<>();
    Set<String> entityNames = new HashSet<>();
    Set<String> occupiedSlots = new HashSet<>();
    for (int index = 0; index < spec.entities.size(); index++) {
      ConceptualErdSpec.EntitySpec entity = spec.entities.get(index);
      String path = "$.spec.entities[" + index + "]";
      if (entity == null) {
        throw BridgeException.invalidArgument(path, "Entity must not be null");
      }
      requireText(entity.id, path + ".id");
      requireText(entity.name, path + ".name");
      requireText(entity.description, path + ".description");
      if (!entityIds.add(entity.id)) {
        throw BridgeException.invalidArgument(path + ".id", "Duplicate entity id: " + entity.id);
      }
      String normalizedName = entity.name.trim().toLowerCase(Locale.ROOT);
      if (!entityNames.add(normalizedName)) {
        throw BridgeException.invalidArgument(path + ".name", "Duplicate entity name: " + entity.name);
      }
      if (entity.column < 0 || entity.column > 6 || entity.row < 0 || entity.row > 8) {
        throw BridgeException.invalidArgument(
            path, "Entity column must be 0..6 and row must be 0..8");
      }
      String slot = entity.column + ":" + entity.row;
      if (!occupiedSlots.add(slot)) {
        throw BridgeException.invalidArgument(
            path, "Two entities cannot occupy the same layout slot: " + slot);
      }
      requireFacts(entity.factIds, factIds, path + ".factIds");
    }

    Set<String> relationshipIds = new HashSet<>();
    Set<String> connected = new HashSet<>();
    Set<String> semanticKeys = new HashSet<>();
    for (int index = 0; index < spec.relationships.size(); index++) {
      ConceptualErdSpec.RelationshipSpec relationship = spec.relationships.get(index);
      String path = "$.spec.relationships[" + index + "]";
      if (relationship == null) {
        throw BridgeException.invalidArgument(path, "Relationship must not be null");
      }
      requireText(relationship.id, path + ".id");
      requireText(relationship.name, path + ".name");
      requireText(relationship.from, path + ".from");
      requireText(relationship.to, path + ".to");
      requireText(relationship.rationale, path + ".rationale");
      if (!relationshipIds.add(relationship.id)) {
        throw BridgeException.invalidArgument(
            path + ".id", "Duplicate relationship id: " + relationship.id);
      }
      if (!entityIds.contains(relationship.from) || !entityIds.contains(relationship.to)) {
        throw BridgeException.invalidArgument(path, "Relationship endpoint does not exist");
      }
      if (relationship.from.equals(relationship.to)) {
        throw BridgeException.invalidArgument(path, "Self relationships are not accepted here");
      }
      relationship.fromCardinality =
          normalizeCardinality(relationship.fromCardinality, path + ".fromCardinality");
      relationship.toCardinality =
          normalizeCardinality(relationship.toCardinality, path + ".toCardinality");
      String key =
          relationship.from
              + "|"
              + relationship.to
              + "|"
              + relationship.name.trim().toLowerCase(Locale.ROOT);
      if (!semanticKeys.add(key)) {
        throw BridgeException.invalidArgument(path, "Duplicate relationship semantics");
      }
      requireFacts(relationship.factIds, factIds, path + ".factIds");
      connected.add(relationship.from);
      connected.add(relationship.to);
    }
    for (String entityId : entityIds) {
      if (!connected.contains(entityId)) {
        throw BridgeException.invalidArgument(
            "$.spec.entities", "Entity is orphaned: " + entityId);
      }
    }
    validateLayout(spec.layout);
    return spec;
  }

  private static String normalizeCardinality(String value, String path) {
    requireText(value, path);
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    if (!CARDINALITIES.contains(normalized)) {
      throw BridgeException.invalidArgument(
          path, "Cardinality must be ONE, ZERO_OR_ONE, ONE_OR_MANY, or ZERO_OR_MANY");
    }
    return normalized;
  }

  private static void requireFacts(List<String> ids, Set<String> facts, String path) {
    if (ids == null || ids.isEmpty()) {
      throw BridgeException.invalidArgument(path, "At least one source fact id is required");
    }
    Set<String> unique = new HashSet<>();
    for (String id : ids) {
      requireText(id, path);
      if (!facts.contains(id)) {
        throw BridgeException.invalidArgument(path, "Unknown source fact id: " + id);
      }
      if (!unique.add(id)) {
        throw BridgeException.invalidArgument(path, "Duplicate source fact id: " + id);
      }
    }
  }

  private static void validateLayout(ConceptualErdSpec.LayoutSpec layout) {
    if (layout == null) {
      return;
    }
    requireRange(layout.originX, 40, 2000, "$.spec.layout.originX");
    requireRange(layout.originY, 40, 2000, "$.spec.layout.originY");
    requireRange(layout.entityWidth, 150, 420, "$.spec.layout.entityWidth");
    requireRange(layout.entityHeight, 40, 140, "$.spec.layout.entityHeight");
    requireRange(layout.horizontalGap, 80, 500, "$.spec.layout.horizontalGap");
    requireRange(layout.verticalGap, 80, 400, "$.spec.layout.verticalGap");
    String style =
        layout.connectorStyle == null
            ? "OBLIQUE"
            : layout.connectorStyle.trim().toUpperCase(Locale.ROOT);
    if (!Set.of("OBLIQUE", "RECTILINEAR").contains(style)) {
      throw BridgeException.invalidArgument(
          "$.spec.layout.connectorStyle", "connectorStyle must be OBLIQUE or RECTILINEAR");
    }
    layout.connectorStyle = style;
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

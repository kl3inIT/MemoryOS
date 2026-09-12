package vn.edu.swd392.vpmcp.tools;

import java.util.ArrayList;
import java.util.List;

public final class ConceptualErdSpec {
  public String operationId;
  public String diagramName;
  public List<FactSpec> facts = new ArrayList<>();
  public List<EntitySpec> entities = new ArrayList<>();
  public List<RelationshipSpec> relationships = new ArrayList<>();
  public LayoutSpec layout;

  public static final class FactSpec {
    public String id;
    public String text;
  }

  public static final class EntitySpec {
    public String id;
    public String name;
    public String description;
    public int column;
    public int row;
    public List<String> factIds = new ArrayList<>();
  }

  public static final class RelationshipSpec {
    public String id;
    public String name;
    public String from;
    public String to;
    public String fromCardinality;
    public String toCardinality;
    public String rationale;
    public List<String> factIds = new ArrayList<>();
  }

  public static final class LayoutSpec {
    public int originX = 180;
    public int originY = 150;
    public int entityWidth = 230;
    public int entityHeight = 48;
    public int horizontalGap = 180;
    public int verticalGap = 150;
    public String connectorStyle = "OBLIQUE";
  }
}

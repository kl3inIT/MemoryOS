package vn.edu.swd392.vpmcp.tools;

import java.util.ArrayList;
import java.util.List;

public final class ContextDiagramSpec {
  public String operationId;
  public String diagramName;
  public String systemName;
  public List<EntitySpec> entities = new ArrayList<>();
  public List<FlowSpec> flows = new ArrayList<>();
  public LayoutSpec layout;

  public static final class EntitySpec {
    public String id;
    public String name;
    public String side = "AUTO";
    public List<String> factIds = new ArrayList<>();
  }

  public static final class FlowSpec {
    public String id;
    public String name;
    public String from;
    public String to;
    public List<String> factIds = new ArrayList<>();
  }

  public static final class LayoutSpec {
    public int centerX = 760;
    public int centerY = 480;
    public int processWidth = 300;
    public int processHeight = 300;
    public int entityWidth = 230;
    public int entityHeight = 86;
    public int horizontalGap = 260;
    public int verticalGap = 145;
    public String connectorStyle = "CURVE";
  }
}

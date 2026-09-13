package vn.edu.swd392.vpmcp.bridge;

import com.vp.plugin.VPPlugin;
import com.vp.plugin.VPPluginInfo;
import vn.edu.swd392.vpmcp.tools.ActivityDiagramTools;
import vn.edu.swd392.vpmcp.tools.ClassDiagramTools;
import vn.edu.swd392.vpmcp.tools.CommonDiagramTools;
import vn.edu.swd392.vpmcp.tools.ContextDiagramTools;
import vn.edu.swd392.vpmcp.tools.ConceptualErdTools;
import vn.edu.swd392.vpmcp.tools.SequenceDiagramTools;
import vn.edu.swd392.vpmcp.tools.StateDiagramTools;
import vn.edu.swd392.vpmcp.tools.UseCaseDiagramTools;

public final class VpMcpPlugin implements VPPlugin {
  private VpBridgeServer bridge;

  @Override
  public void loaded(VPPluginInfo info) {
    String pluginId = info == null ? "vn.edu.swd392.vpmcp" : info.getPluginId();
    System.out.println("Loading " + pluginId);
    try {
      int port = Integer.getInteger("swd392.vp.bridge.port", 2026);
      bridge = new VpBridgeServer(port);
      bridge.register(
          new CommonDiagramTools(),
          new ContextDiagramTools(),
          new ClassDiagramTools(),
          new SequenceDiagramTools(),
          new ActivityDiagramTools(),
          new StateDiagramTools(),
          new UseCaseDiagramTools(),
          new ConceptualErdTools());
      bridge.start();
    } catch (RuntimeException exception) {
      System.err.println("Cannot start SWD392 VP bridge: " + exception.getMessage());
      exception.printStackTrace();
    }
  }

  @Override
  public void unloaded() {
    if (bridge != null) {
      bridge.close();
      bridge = null;
    }
  }
}

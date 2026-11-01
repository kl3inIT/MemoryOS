package vn.edu.swd392.vpmcp.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-diagram design verification state. Enforces the invariant
 * "no design output without post-generation verification": every mutating tool
 * call marks its diagram dirty, and {@code finalizeDesign} only reaches DONE after
 * {@code verifyDesign} reports VERIFIED on the current (post-mutation) epoch.
 *
 * Lifecycle per epoch (one epoch = everything since the last mutation):
 *   GENERATED -> INSPECTING/REVIEWING (verify passes) -> NEEDS_FIX | VERIFIED -> DONE
 * GENERATED -> DONE is impossible: finalize requires verifiedEpoch == epoch.
 */
public final class DesignQaRegistry {
  public static final int MIN_REVIEW_PASSES = 2;
  public static final int MAX_ITERATIONS = 5;
  /** Same unresolved issue signature persisting across this many passes = stalled repair. */
  public static final int STALL_THRESHOLD = 2;

  private static final DesignQaRegistry INSTANCE = new DesignQaRegistry();

  private final Map<String, QaState> states = new ConcurrentHashMap<>();

  private DesignQaRegistry() {}

  public static DesignQaRegistry get() {
    return INSTANCE;
  }

  /** Tools that never change layout (or manage their own epoch). */
  private static final java.util.Set<String> NO_LAYOUT_MUTATION =
      java.util.Set.of("repairDesign", "exportDiagramImage", "exportAllDiagrams", "saveProject");

  /**
   * Called by the bridge after every successful non-read-only tool execution.
   * Resolves the affected diagram key from common argument fields; unknown or
   * project-level mutations reset everything (project replacement invalidates all).
   */
  public void noteMutation(String toolName, JsonNode arguments) {
    if ("createProjectFile".equals(toolName)) {
      states.clear();
      return;
    }
    if (NO_LAYOUT_MUTATION.contains(toolName)) {
      return;
    }
    String diagramName = diagramArgument(arguments);
    if (diagramName == null) {
      return;
    }
    if ("deleteDiagram".equals(toolName)) {
      states.remove(diagramName);
      return;
    }
    stateFor(diagramName).markMutated();
  }

  /** Binds state to the diagram's stable name as used by the caller. */
  public QaState stateFor(String diagramName) {
    return states.computeIfAbsent(diagramName, key -> new QaState());
  }

  private static String diagramArgument(JsonNode arguments) {
    if (arguments == null || !arguments.isObject()) {
      return null;
    }
    for (String field : new String[] {"diagramName", "diagram", "name"}) {
      JsonNode node = arguments.get(field);
      if (node != null && node.isTextual() && !node.asText().isBlank()) {
        return node.asText();
      }
    }
    JsonNode spec = arguments.get("spec");
    if (spec != null && spec.isObject()) {
      JsonNode node = spec.get("diagramName");
      if (node != null && node.isTextual() && !node.asText().isBlank()) {
        return node.asText();
      }
    }
    return null;
  }

  public static final class QaState {
    private long epoch;
    private int passes;
    private int renderPasses;
    private boolean verified;
    private long verifiedEpoch = -1;
    private String lastSignature = "";
    private int sameSignaturePasses;
    private List<String> lastIssueKeys = new ArrayList<>();
    private int lastIssueCount;
    private int ineffectiveRepairs;

    public synchronized void markMutated() {
      epoch++;
      passes = 0;
      renderPasses = 0;
      verified = false;
      lastSignature = "";
      sameSignaturePasses = 0;
      lastIssueKeys = new ArrayList<>();
      lastIssueCount = 0;
      ineffectiveRepairs = 0;
    }

    /** Records one verification pass against current geometry. */
    public synchronized PassOutcome notePass(List<String> issueKeys, String signature, boolean rendered) {
      passes++;
      if (rendered) {
        renderPasses++;
      }
      boolean stalled =
          !issueKeys.isEmpty()
              && signature.equals(lastSignature)
              && sameSignaturePasses + 1 >= STALL_THRESHOLD;
      boolean regressed = !lastIssueKeys.isEmpty() && issueKeys.size() > lastIssueCount;
      boolean ineffective = !lastIssueKeys.isEmpty() && issueKeys.size() >= lastIssueCount;
      if (signature.equals(lastSignature)) {
        sameSignaturePasses++;
      } else {
        sameSignaturePasses = 1;
      }
      lastSignature = signature;
      lastIssueKeys = new ArrayList<>(issueKeys);
      lastIssueCount = issueKeys.size();
      if (ineffective && !issueKeys.isEmpty()) {
        ineffectiveRepairs++;
      }
      return new PassOutcome(stalled, regressed, ineffective, ineffectiveRepairs);
    }

    /** Records a render-only pass (renderDesign): counts toward render budget, not review passes. */
    public synchronized void noteRenderOnly() {
      renderPasses++;
    }

    /** Marks this epoch verified; finalize requires verifiedEpoch == epoch. */
    public synchronized void verify() {
      verified = true;
      verifiedEpoch = epoch;
    }

    public synchronized boolean isVerified() {
      return verified && verifiedEpoch == epoch;
    }

    public synchronized boolean dirty() {
      return !(verified && verifiedEpoch == epoch);
    }

    public synchronized int passes() {
      return passes;
    }

    public synchronized int renderPasses() {
      return renderPasses;
    }

    public synchronized long epoch() {
      return epoch;
    }

    public synchronized int lastIssueCount() {
      return lastIssueCount;
    }
  }

  public static final class PassOutcome {
    public final boolean stalled;
    public final boolean regressed;
    public final boolean ineffective;
    public final int ineffectiveRepairs;

    PassOutcome(boolean stalled, boolean regressed, boolean ineffective, int ineffectiveRepairs) {
      this.stalled = stalled;
      this.regressed = regressed;
      this.ineffective = ineffective;
      this.ineffectiveRepairs = ineffectiveRepairs;
    }
  }
}

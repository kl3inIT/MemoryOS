package vn.edu.swd392.vpmcp.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.Deque;

/** Bounded in-memory record of bridge executions for the management console. */
final class ActivityLog {
  private static final int CAPACITY = 500;

  private final Deque<ObjectNode> entries = new ArrayDeque<>();
  private long seq = 0;

  synchronized void add(ObjectNode entry) {
    entry.put("seq", ++seq);
    entries.addLast(entry);
    while (entries.size() > CAPACITY) {
      entries.removeFirst();
    }
  }

  synchronized ArrayNode snapshot(long afterSeq, ObjectMapper mapper) {
    ArrayNode out = mapper.createArrayNode();
    for (ObjectNode entry : entries) {
      if (entry.path("seq").asLong() > afterSeq) {
        out.add(entry.deepCopy());
      }
    }
    return out;
  }

  synchronized long latest() {
    return seq;
  }
}

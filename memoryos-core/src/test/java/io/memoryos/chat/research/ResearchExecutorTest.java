package io.memoryos.chat.research;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ResearchExecutorTest {
    @Test
    void collapseRenumbersMappedMarkersLikeOnyxAndKeepsTheRest() {
        String report = "Revenue grew [1]. Costs [2, 3] and [[1]] or 【2】 and ［1, 9］; not a citation [x].";
        assertEquals(Set.of(1, 2, 3, 9), ResearchExecutor.markers(report));
        assertEquals("Revenue grew [4]. Costs [7, 3] and [[4]] or 【7】 and ［4, 9］; not a citation [x].",
                ResearchExecutor.collapse(report, Map.of(1, 4, 2, 7)));
        assertEquals("[1,2]".replace(",", ", "), ResearchExecutor.collapse("[1,2]", Map.of()), "Onyx rejoins numbers with a space");
    }
}

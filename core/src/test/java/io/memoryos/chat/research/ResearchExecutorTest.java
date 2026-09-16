package io.memoryos.chat.research;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
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

    @Test
    void onyxPromptHelpersFormatDateToolListAndLanguage() {
        assertEquals("Tuesday September 01, 2026", ResearchPrompts.currentDatetime(ZonedDateTime.of(2026, 9, 1, 8, 0, 0, 0, ZoneId.of("UTC"))));
        assertEquals("", ResearchPrompts.toolList(List.of()));
        assertEquals("search_knowledge and web_search", ResearchPrompts.toolList(List.of("search_knowledge", "web_search")));
        assertEquals("search_knowledge, web_search, and open_url", ResearchPrompts.toolList(List.of("search_knowledge", "web_search", "open_url")));
        assertEquals("## Language\nThe user's interface language is Vietnamese. Reply in Vietnamese. If the user explicitly asks for another language, use that one.\n",
                ResearchPrompts.languageSection("vi"));
        assertEquals(ResearchPrompts.QUERY_LANGUAGE_PROMPT, ResearchPrompts.languageSection("en"));
        assertEquals("Prompt\n\n" + ResearchPrompts.QUERY_LANGUAGE_PROMPT, ResearchPrompts.withLanguage("Prompt", ResearchPrompts.languageSection(null)));
    }
}

package io.memoryos.chat.web;

/** Implemented external protocols; native model tools are not search-engine connections. */
public enum WebProvider {
    BRAVE, TAVILY, EXA, SERPER, GOOGLE_PSE, SEARXNG, FIRECRAWL;

    public boolean search() { return this != FIRECRAWL; }
    public boolean content() { return this == TAVILY || this == EXA || this == FIRECRAWL; }
    public boolean requiresKey() { return this != SEARXNG; }
    /** Onyx external-search baseline: Exa does not accept the site: query operator. */
    public boolean supportsSiteFilter() { return search() && this != EXA; }
}

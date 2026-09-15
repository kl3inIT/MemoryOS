package io.memoryos.chat.web;

/** Implemented external protocols; native model tools are not search-engine connections. */
public enum WebProvider {
    BRAVE, TAVILY, EXA, SERPER, GOOGLE_PSE, SEARXNG, NINEROUTER, FIRECRAWL;

    public boolean search() { return this != FIRECRAWL; }
    public boolean content() { return this == TAVILY || this == EXA || this == FIRECRAWL; }
    public boolean requiresKey() { return this != SEARXNG; }
    /** A gateway routes to an engine chosen per request, so its engine identity is configured. */
    public boolean requiresEngine() { return this == GOOGLE_PSE || this == NINEROUTER; }
    /** An endpoint is the whole address of a self-hosted or gateway deployment. */
    public boolean requiresEndpoint() { return this == SEARXNG || this == NINEROUTER; }
    /** Onyx external-search baseline: Exa does not accept the site: query operator. */
    public boolean supportsSiteFilter() { return search() && this != EXA && this != NINEROUTER; }
}

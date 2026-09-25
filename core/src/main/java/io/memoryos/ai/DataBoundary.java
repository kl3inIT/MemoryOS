package io.memoryos.ai;

/** Where a provider sits relative to the organization's data, as its administrator stated it; recorded and shown only, not enforced. */
public enum DataBoundary {
    /** Self-hosted, or an enterprise agreement without retention or training. */
    INTERNAL,
    EXTERNAL
}

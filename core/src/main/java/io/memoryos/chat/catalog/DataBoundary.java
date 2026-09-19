package io.memoryos.chat.catalog;

/** Where a provider sits relative to the organization's data; recorded by administrators, enforced by the external data gate. */
public enum DataBoundary {
    /** Self-hosted, or an enterprise agreement without retention or training. */
    INTERNAL,
    EXTERNAL
}

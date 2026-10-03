package io.memoryos.chat.web;

/**
 * What a Web provider needs and supports. Whether it searches or reads pages follows from the functions its adapter
 * implements.
 *
 * @param requiresKey      a connection is unusable without a stored credential
 * @param requiresEndpoint the endpoint is the whole address of a self-hosted or gateway deployment
 * @param requiresEngine   a search engine identity is configured (Google PSE {@code cx}, 9Router engine)
 * @param siteFilter       the provider honors the {@code site:} query operator (Onyx external-search baseline)
 */
public record WebProviderCapabilities(boolean requiresKey, boolean requiresEndpoint, boolean requiresEngine,
                                      boolean siteFilter) {}

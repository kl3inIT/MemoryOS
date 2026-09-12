package vn.edu.swd392.vpmcp.server;

import com.fasterxml.jackson.databind.JsonNode;

record BridgeTool(
    String name, String description, JsonNode inputSchema, JsonNode annotations) {}

package io.memoryos.mcp.persistence;

import io.memoryos.mcp.McpTokenEndpointAuthMethod;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores the RFC 7591 wire value so the column matches authorization-server metadata. */
@Converter
class McpTokenEndpointAuthMethodConverter implements AttributeConverter<McpTokenEndpointAuthMethod, String> {
    @Override
    public String convertToDatabaseColumn(McpTokenEndpointAuthMethod method) {
        return method == null ? null : method.wireValue();
    }

    @Override
    public McpTokenEndpointAuthMethod convertToEntityAttribute(String value) {
        return value == null ? null : McpTokenEndpointAuthMethod.fromWire(value);
    }
}

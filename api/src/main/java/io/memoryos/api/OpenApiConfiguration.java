package io.memoryos.api;

import io.memoryos.api.security.BrowserMutation;
import io.memoryos.connector.SourceRunTrigger;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info = @Info(
                title = "MemoryOS browser API",
                version = "1.0.0",
                description = "The same-origin contract consumed by the MemoryOS browser application."
        ),
        servers = @Server(url = "/", description = "Same-origin MemoryOS runtime")
)
@SecurityScheme(
        name = "browserSession",
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.COOKIE,
        paramName = "SESSION",
        description = "HttpOnly JDBC-backed Spring Security session cookie."
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
class OpenApiConfiguration {

    @Bean
    GroupedOpenApi browserOpenApi() {
        return GroupedOpenApi.builder()
                .group("browser")
                .pathsToMatch("/api/**")
                .addOperationCustomizer(browserMutationHeader())
                .addOpenApiCustomizer(openApi -> {
                    if (openApi.getComponents() == null) {
                        openApi.setComponents(new Components());
                    }
                    openApi.getComponents().addSchemas("ApiProblem", apiProblemSchema());
                    Components components = openApi.getComponents();
                    configureNullableProperty(components, "CurrentIdentity", "tenant",
                            new Schema<>().$ref("#/components/schemas/CurrentTenant"));
                    configureNullableProperty(components, "GoogleDriveConfigurationResponse", "pendingSelectionOperation",
                            new Schema<>().$ref("#/components/schemas/SourceOperation"));
                    configureNullableProperty(components, "SourceItemPage", "nextCursor", new StringSchema());
                    for (String summary : List.of("current", "lastCompleted", "lastSuccessful")) {
                        configureNullableProperty(components, "SourceRunPage", summary,
                                new Schema<>().$ref("#/components/schemas/SourceRun"));
                    }
                    configureNullableProperty(components, "SourceRun", "trigger",
                            new StringSchema()._enum(Arrays.stream(SourceRunTrigger.values()).map(Enum::name).toList()));
                    configureNullableProperty(components, "UserListItem", "role",
                            new Schema<>().$ref("#/components/schemas/TenantMembershipRole"));
                    configureNullableProperty(components, "UserListItem", "accountType",
                            new Schema<>().$ref("#/components/schemas/AccountType"));
                    configureNullableProperty(components, "UserGroup", "systemKey",
                            new Schema<>().$ref("#/components/schemas/GroupSystemKey"));
                    configureNullableProperty(components, "GroupSummary", "systemKey",
                            new Schema<>().$ref("#/components/schemas/GroupSystemKey"));
                    configureNullableProperty(components, "SourceGroup", "systemKey",
                            new Schema<>().$ref("#/components/schemas/GroupSystemKey"));
                })
                .build();
    }

    /** Documents the header the mutation interceptor enforces on every unsafe API operation. */
    private static OperationCustomizer browserMutationHeader() {
        return (operation, handlerMethod) -> {
            if (handlerMethod.hasMethodAnnotation(PostMapping.class)
                    || handlerMethod.hasMethodAnnotation(PutMapping.class)
                    || handlerMethod.hasMethodAnnotation(PatchMapping.class)
                    || handlerMethod.hasMethodAnnotation(DeleteMapping.class)) {
                List<io.swagger.v3.oas.models.parameters.Parameter> parameters =
                        operation.getParameters() == null ? new ArrayList<>() : operation.getParameters();
                parameters.addFirst(new HeaderParameter()
                        .name(BrowserMutation.HEADER)
                        .description(BrowserMutation.DESCRIPTION)
                        .required(true)
                        .schema(new StringSchema().addEnumItem(BrowserMutation.VALUE)));
                operation.setParameters(parameters);
            }
            return operation;
        };
    }

    private static void configureNullableProperty(
            Components components, String schemaName, String propertyName, Schema<?> value) {
        Schema<?> parent = Objects.requireNonNull(
                components.getSchemas().get(schemaName), schemaName + " schema must exist");
        Schema<?> generated = Objects.requireNonNull(
                parent.getProperties().get(propertyName), schemaName + "." + propertyName + " schema must exist");
        Schema<Object> nullValue = new Schema<>();
        nullValue.setTypes(Set.of("null"));
        Schema<Object> nullableProperty = new Schema<>();
        nullableProperty.setDescription(generated.getDescription());
        nullableProperty.setOneOf(List.of(value, nullValue));
        parent.addProperty(propertyName, nullableProperty);
    }

    private static Schema<?> apiProblemSchema() {
        ObjectSchema schema = new ObjectSchema();
        schema.setAdditionalProperties(false);
        schema.setRequired(List.of("title", "status", "detail", "instance"));
        schema.addProperty(
                "type",
                new StringSchema()
                        .format("uri")
                        .description("Stable problem type for capability failures; omitted means RFC 9457 `about:blank`.")
        );
        schema.addProperty("title", new StringSchema().description("Short human-readable problem category."));
        schema.addProperty("status", new IntegerSchema().format("int32").description("HTTP status code."));
        schema.addProperty("detail", new StringSchema().description("Safe human-readable fallback detail."));
        schema.addProperty(
                "instance",
                new StringSchema().format("uri-reference").description("Request path that produced the problem.")
        );
        schema.addProperty(
                "code",
                new StringSchema()
                        .pattern("^[A-Z][A-Z0-9_]*$")
                        .description("Stable capability-prefixed code; present only for expected capability failures.")
        );
        return schema;
    }
}

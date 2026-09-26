package io.memoryos.api.source;

import io.memoryos.api.source.contract.CreateSharePointSourceRequest;
import io.memoryos.api.source.contract.ReplaceSharePointScopeRequest;
import io.memoryos.connector.SharePointSourceService;
import java.io.IOException;
import java.lang.reflect.Type;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** Refuses a SharePoint Source creation or scope replacement above the selection byte budget while it is read. */
@ControllerAdvice(assignableTypes = SharePointSourceController.class)
@NullMarked
final class SharePointSelectionBodyAdvice extends RequestBodyAdviceAdapter {
    private final SharePointSourceService sources;

    SharePointSelectionBodyAdvice(SharePointSourceService sources) {
        this.sources = sources;
    }

    @Override
    public boolean supports(MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
        return type == CreateSharePointSourceRequest.class || type == ReplaceSharePointScopeRequest.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type type,
            Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        return SelectionRequestBodies.bounded(input, sources.selectionRequestByteLimit());
    }
}

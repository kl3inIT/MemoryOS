package io.memoryos.api.source;

import io.memoryos.api.source.contract.CreateGoogleDriveSourceRequest;
import io.memoryos.api.source.contract.ReplaceGoogleDriveRootsRequest;
import io.memoryos.connector.GoogleDriveSourceService;
import java.io.IOException;
import java.lang.reflect.Type;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

@ControllerAdvice(assignableTypes=GoogleDriveSourceController.class)
@NullMarked
final class GoogleDriveSelectionBodyAdvice extends RequestBodyAdviceAdapter {
    private final GoogleDriveSourceService sources;
    GoogleDriveSelectionBodyAdvice(GoogleDriveSourceService sources) { this.sources=sources; }

    @Override public boolean supports(MethodParameter parameter,Type type,Class<? extends HttpMessageConverter<?>> converter) {
        return type==CreateGoogleDriveSourceRequest.class || type==ReplaceGoogleDriveRootsRequest.class;
    }

    @Override public HttpInputMessage beforeBodyRead(HttpInputMessage input,MethodParameter parameter,
            Type type,Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        return SelectionRequestBodies.bounded(input, sources.selectionRequestByteLimit());
    }
}

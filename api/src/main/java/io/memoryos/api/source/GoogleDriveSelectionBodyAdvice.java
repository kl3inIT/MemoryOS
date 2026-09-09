package io.memoryos.api.source;

import io.memoryos.api.source.contract.CreateGoogleDriveSourceRequest;
import io.memoryos.api.source.contract.ReplaceGoogleDriveRootsRequest;
import io.memoryos.connector.GoogleDriveSourceService;
import io.memoryos.connector.SourceException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

@ControllerAdvice(assignableTypes=GoogleDriveSourceController.class)
final class GoogleDriveSelectionBodyAdvice extends RequestBodyAdviceAdapter {
    private final GoogleDriveSourceService sources;
    GoogleDriveSelectionBodyAdvice(GoogleDriveSourceService sources) { this.sources=sources; }

    @Override public boolean supports(MethodParameter parameter,Type type,Class<? extends HttpMessageConverter<?>> converter) {
        return type==CreateGoogleDriveSourceRequest.class || type==ReplaceGoogleDriveRootsRequest.class;
    }

    @Override public HttpInputMessage beforeBodyRead(HttpInputMessage input,MethodParameter parameter,
            Type type,Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        int limit=sources.selectionRequestByteLimit();
        if (input.getHeaders().getContentLength()>limit) throw tooLarge();
        InputStream bounded=new FilterInputStream(input.getBody()) {
            private long count;
            @Override public int read() throws IOException {
                int value=super.read();
                if (value>=0 && ++count>limit) throw tooLarge();
                return value;
            }
            @Override public int read(byte[] bytes,int offset,int length) throws IOException {
                int read=in.read(bytes,offset,(int)Math.min(length,limit-count+1));
                if (read>0 && (count+=read)>limit) throw tooLarge();
                return read;
            }
        };
        return new HttpInputMessage() {
            @Override public InputStream getBody() { return bounded; }
            @Override public HttpHeaders getHeaders() { return input.getHeaders(); }
        };
    }

    private static SourceException tooLarge() {
        return SourceException.invalid("The selection request exceeds the configured byte limit.","selection transport byte budget exceeded");
    }
}

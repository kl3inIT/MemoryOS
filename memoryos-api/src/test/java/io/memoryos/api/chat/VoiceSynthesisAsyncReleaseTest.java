package io.memoryos.api.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.api.chat.contract.VoiceSynthesisRequest;
import io.memoryos.iam.IdentityContext;
import io.memoryos.shared.ActorId;
import io.memoryos.voice.VoiceSynthesisService;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.async.StandardServletAsyncWebRequest;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBodyReturnValueHandler;

/**
 * The read-aloud response releases its speech however the async request ends, through Spring MVC's real
 * StreamingResponseBody handling: after the body ran, after a timeout before it ran, and when the executor refused it.
 */
class VoiceSynthesisAsyncReleaseTest {
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/voice/synthesize");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final AtomicInteger written = new AtomicInteger();
    private final VoiceSynthesisService.SpeechStream speech = mock(VoiceSynthesisService.SpeechStream.class);

    @Test
    void aBodyThatRanReleasesTheSpeechWhenTheRequestCompletes() throws Exception {
        start(Runnable::run);
        complete();
        assertEquals(1, written.get());
        verify(speech, times(1)).close();
    }

    @Test
    void aTimeoutBeforeTheBodyRanReleasesTheSpeech() throws Exception {
        start(_ -> { });
        var context = (MockAsyncContext) request.getAsyncContext();
        for (AsyncListener listener : context.getListeners()) listener.onTimeout(new AsyncEvent(context));
        complete();
        assertEquals(0, written.get());
        verify(speech, times(1)).close();
    }

    @Test
    void anExecutorThatRefusesTheBodyStillReleasesTheSpeech() throws Exception {
        start(_ -> { throw new RejectedExecutionException("saturated"); });
        complete();
        assertEquals(0, written.get());
        verify(speech, times(1)).close();
    }

    private void start(Executor executor) throws Exception {
        request.setAsyncSupported(true);
        var manager = WebAsyncUtils.getAsyncManager(request);
        manager.setAsyncWebRequest(new StandardServletAsyncWebRequest(request, response));
        manager.setTaskExecutor(new ConcurrentTaskExecutor(executor));
        // The mocked stream writes without closing, so only the completion hook can close it.
        doAnswer(invocation -> {
            written.incrementAndGet();
            invocation.<OutputStream>getArgument(0).write("mp3".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(speech).writeTo(any());
        var synthesis = mock(VoiceSynthesisService.class);
        when(synthesis.open(any(), any(), anyDouble())).thenReturn(speech);
        var entity = new VoiceSynthesisController(synthesis).synthesize(new IdentityContext(new ActorId(UUID.randomUUID())),
                new VoiceSynthesisRequest("Xin chào.", 1.0), request);
        var returnType = new MethodParameter(VoiceSynthesisController.class.getDeclaredMethod("synthesize",
                IdentityContext.class, VoiceSynthesisRequest.class, HttpServletRequest.class), -1);
        new StreamingResponseBodyReturnValueHandler().handleReturnValue(entity, returnType,
                new ModelAndViewContainer(), new ServletWebRequest(request, response));
    }

    /** What the container does once the dispatched request has been handled. */
    private void complete() {
        request.getAsyncContext().complete();
    }
}

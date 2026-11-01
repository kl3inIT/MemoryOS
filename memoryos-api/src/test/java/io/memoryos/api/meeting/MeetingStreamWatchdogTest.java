package io.memoryos.api.meeting;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.meeting.MeetingService;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * A recording that nobody is speaking into writes nothing to the browser for minutes. Every proxy in front reads that
 * as an idle upstream and cuts the connection, so the watchdog has to keep the socket alive by itself.
 */
class MeetingStreamWatchdogTest {
    private final WebSocketSession socket = mock(WebSocketSession.class);
    private final MeetingStreamWebSocketHandler handler =
            new MeetingStreamWebSocketHandler(mock(MeetingService.class));

    @Test
    void aQuietRecordingIsPingedOftenEnoughToSurviveEveryProxyInFront() throws Exception {
        when(socket.isOpen()).thenReturn(true);
        var live = new MeetingStreamWebSocketHandler.Live(socket, mock(MeetingService.TrackSession.class));

        // Four watchdog runs is twenty seconds, the first ping.
        for (int run = 0; run < 3; run++) handler.expire(live);
        verify(socket, never()).sendMessage(any(PingMessage.class));
        handler.expire(live);
        verify(socket, times(1)).sendMessage(any(PingMessage.class));

        for (int run = 0; run < 4; run++) handler.expire(live);
        verify(socket, times(2)).sendMessage(any(PingMessage.class));
        verify(socket, never()).close(any(CloseStatus.class));
    }

    @Test
    void audioThatStopsAltogetherStillEndsTheTrack() throws Exception {
        when(socket.isOpen()).thenReturn(true);
        var live = new MeetingStreamWebSocketHandler.Live(socket, mock(MeetingService.TrackSession.class));
        live.lastAudioNanos = System.nanoTime() - MeetingStreamWebSocketHandler.IDLE.plusSeconds(1).toNanos();

        handler.expire(live);

        verify(socket).close(any(CloseStatus.class));
        verify(socket, never()).sendMessage(any(PingMessage.class));
    }
}

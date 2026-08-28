package io.memoryos.connector;


import java.util.List;

public interface ConnectorCleanupPort {

    List<CleanupWork> claim(int batchSize);

    boolean execute(CleanupWork work);

    boolean fail(CleanupWork work, String errorCode);
}

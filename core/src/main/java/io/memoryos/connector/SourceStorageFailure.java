package io.memoryos.connector;

import io.memoryos.objectstorage.ObjectStorageException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

public final class SourceStorageFailure {
    private SourceStorageFailure() {}

    public static String code(ObjectStorageException exception) {
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof SSLException) return "TLS";
            if (cause instanceof UnknownHostException || cause instanceof ConnectException || cause instanceof SocketException)
                return "CONNECTIVITY";
        }
        return exception.code().name();
    }
}

package io.memoryos.integration.whatsapp;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The MemoryOS database as the bridge's PostgreSQL driver addresses it: the process's own JDBC address, user and
 * password, with the bridge's schema as its search path. It goes to the bridge in its environment, never its arguments,
 * so it shows in no process list.
 */
final class WhatsAppDatabase {
    /** The schema whatsmeow keeps its session store in; Flyway creates it (V192). */
    static final String SCHEMA = "whatsapp";
    private static final Pattern JDBC = Pattern.compile("^jdbc:postgresql://([^/?#]+)/([^?#]+)(?:\\?([^#]*))?$");
    /** The JDBC parameters that mean the same to the bridge's driver; the others are the JDBC driver's own. */
    private static final Set<String> CARRIED = Set.of("sslmode", "sslrootcert", "connect_timeout", "application_name");

    private WhatsAppDatabase() {
    }

    static String address(String jdbcUrl, @Nullable String user, @Nullable String password) {
        var matched = JDBC.matcher(jdbcUrl.strip());
        if (!matched.matches()) throw new IllegalArgumentException("The database address is not a PostgreSQL JDBC address");
        var address = new StringBuilder("postgres://");
        if (user != null && !user.isEmpty()) {
            address.append(encode(user));
            if (password != null && !password.isEmpty()) address.append(':').append(encode(password));
            address.append('@');
        }
        address.append(matched.group(1)).append('/').append(matched.group(2)).append("?search_path=").append(SCHEMA);
        String query = matched.group(3);
        if (query != null) {
            for (String pair : query.split("&")) {
                int equals = pair.indexOf('=');
                if (equals > 0 && CARRIED.contains(pair.substring(0, equals))) address.append('&').append(pair);
            }
        }
        // A malformed host or database name fails here, before the bridge is started with it.
        URI.create(address.toString());
        return address.toString();
    }

    private static String encode(String part) {
        return URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20");
    }
}

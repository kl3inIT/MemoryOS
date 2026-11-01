package io.memoryos;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Records the SQL of every statement prepared through it, so a test can assert how many queries a flow issues.
 * JdbcClient and Hibernate both prepare every statement; plain {@code createStatement} is recorded as {@code <plain>}.
 */
public final class StatementCounter extends DelegatingDataSource {
    private final List<String> statements = new ArrayList<>();

    public StatementCounter(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(super.getConnection(username, password));
    }

    public synchronized void reset() {
        statements.clear();
    }

    public synchronized List<String> statements() {
        return List.copyOf(statements);
    }

    /** Statements whose SQL contains {@code fragment}, ignoring case and collapsing whitespace. */
    public long count(String fragment) {
        String wanted = normalize(fragment);
        return count(sql -> normalize(sql).contains(wanted));
    }

    public synchronized long count(Predicate<String> matching) {
        return statements.stream().filter(matching).count();
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private synchronized void record(String sql) {
        statements.add(sql);
    }

    private Connection wrap(Connection connection) {
        InvocationHandler handler = (proxy, method, arguments) -> {
            String name = method.getName();
            if ((name.equals("prepareStatement") || name.equals("prepareCall")) && arguments != null
                    && arguments.length > 0 && arguments[0] instanceof String sql) {
                record(sql);
            } else if (name.equals("createStatement")) {
                record("<plain>");
            }
            try {
                return method.invoke(connection, arguments);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                handler);
    }
}

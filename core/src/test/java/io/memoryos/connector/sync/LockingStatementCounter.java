package io.memoryos.connector.sync;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/**
 * Wraps a DataSource and counts the row-locking statements ({@code FOR UPDATE}, {@code FOR SHARE}) prepared
 * through it, so a test can assert how many locks a synchronization step takes instead of timing it.
 */
public final class LockingStatementCounter {
    private final AtomicInteger locking = new AtomicInteger();
    private final DataSource dataSource;

    public LockingStatementCounter(DataSource target) {
        this.dataSource = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, forward(target, (method, args, result) -> {
                    if (result instanceof Connection connection) return connection(connection);
                    return result;
                }));
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public int count() {
        return locking.get();
    }

    public void reset() {
        locking.set(0);
    }

    private Connection connection(Connection target) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, forward(target, (method, args, result) -> {
                    if (method.getName().startsWith("prepare") && args != null && args.length > 0
                            && args[0] instanceof String sql) {
                        String normalized = sql.toUpperCase(Locale.ROOT);
                        if (normalized.contains("FOR UPDATE") || normalized.contains("FOR SHARE")) {
                            locking.incrementAndGet();
                        }
                    }
                    return result;
                }));
    }

    private interface After {
        Object apply(Method method, Object[] args, Object result);
    }

    private static InvocationHandler forward(Object target, After after) {
        return (proxy, method, args) -> {
            if (method.getName().equals("equals") && args != null && args.length == 1) return proxy == args[0];
            if (method.getName().equals("hashCode") && (args == null || args.length == 0)) {
                return System.identityHashCode(proxy);
            }
            try {
                return after.apply(method, args, method.invoke(target, args));
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        };
    }
}

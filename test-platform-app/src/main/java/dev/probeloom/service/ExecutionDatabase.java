package dev.probeloom.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** 业务连接的原生锁等待与 JDBC 语句执行时限。 */
final class ExecutionDatabase {
    private ExecutionDatabase() { }

    static boolean outcomeUnknown(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.ConnectException) return false;
            if (cause instanceof java.net.SocketTimeoutException || cause instanceof java.net.SocketException
                    || cause instanceof java.io.EOFException || cause instanceof java.io.InterruptedIOException
                    || cause instanceof java.nio.channels.ClosedByInterruptException
                    || cause instanceof InterruptedException || cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof RemoteOutcomeUnknownException || cause instanceof java.sql.SQLTimeoutException
                    || cause instanceof java.sql.SQLRecoverableException) return true;
            if (cause instanceof SQLException) {
                String state = ((SQLException) cause).getSQLState();
                if (state != null && (state.startsWith("08") || state.startsWith("HYT"))) return true;
            }
        }
        return false;
    }

    static void configure(Connection connection, String databaseType, int timeoutSeconds) throws SQLException {
        try (Statement statement = timeout(connection.createStatement(), timeoutSeconds)) {
            if ("POSTGRESQL".equals(databaseType)) {
                statement.execute("SET lock_timeout='" + timeoutSeconds + "s'");
            } else if ("SQLSERVER".equals(databaseType)) {
                statement.execute("SET LOCK_TIMEOUT " + timeoutSeconds * 1000L);
            } else if ("MYSQL".equals(databaseType)) {
                statement.execute("SET SESSION innodb_lock_wait_timeout=" + timeoutSeconds);
                statement.execute("SET SESSION lock_wait_timeout=" + timeoutSeconds);
            }
        }
    }

    static <T extends Statement> T timeout(T statement, int timeoutSeconds) throws SQLException {
        try {
            statement.setQueryTimeout(timeoutSeconds);
            return statement;
        } catch (SQLException failure) {
            try { statement.close(); }
            catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }
}

package audit.linlang.audit;

import api.linlang.audit.called.LinLogs;

/**
 * Backward-compatible facade. Delegates to API-level Logs.
 * Prevents interface mismatch and module cycles.
 */
public final class Linlogs {
    private Linlogs() {}

    public static void install(LinLogs.Provider p){ LinLogs.install(p); }

    public static void debug(String m, Object...kv){ LinLogs.debug(m, kv); }
    public static void info (String m, Object...kv){ LinLogs.info(m, kv); }
    public static void warn (String m, Object...kv){ LinLogs.warn(m, kv); }
    public static void error(String m, Throwable t, Object...kv){ LinLogs.error(m, t, kv); }
    public static void audit(String event, Object...kv){ LinLogs.audit(event, kv); }
}
package audit.linlang.audit;

import api.linlang.audit.called.LinLog;

/**
 * Backward-compatible facade. Delegates to API-level Logs.
 * Prevents interface mismatch and module cycles.
 */
public final class Audit {
    private Audit() {}

    public static void install(api.linlang.audit.called.LinLog.Provider p){ LinLog.install(p); }

    public static void debug(String m, Object...kv){ LinLog.debug(m, kv); }
    public static void info (String m, Object...kv){ LinLog.info(m, kv); }
    public static void warn (String m, Object...kv){ LinLog.warn(m, kv); }
    public static void error(String m, Throwable t, Object...kv){ LinLog.error(m, t, kv); }
    public static void audit(String event, Object...kv){ LinLog.audit(event, kv); }
}
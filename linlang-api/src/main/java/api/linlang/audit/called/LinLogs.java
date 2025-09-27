package api.linlang.audit.called;


public final class LinLogs {
    public interface Provider {
        void log(String level, String msg, Object... kv);
        void audit(String event, Object... kv);
    }
    private static volatile Provider P = new Noop();

    public static void install(Provider p){ P = (p==null? new Noop(): p); }

    public static void debug(String m, Object...kv){ P.log("DEBUG", m, kv); }
    public static void info (String m, Object...kv){ P.log("INFO",  m, kv); }
    public static void warn (String m, Object...kv){ P.log("WARN",  m, kv); }
    public static void error(String m, Throwable t, Object...kv){
        Object[] kv2 = kv;
        if (t != null) kv2 = append(kv, "err", t.toString());
        P.log("ERROR", m, kv2);
    }
    public static void audit(String event, Object...kv){ P.audit(event, kv); }

    private static final class Noop implements Provider {
        public void log(String l,String m,Object...kv) {}
        public void audit(String e,Object...kv) {}
    }
    private static Object[] append(Object[] a, Object...b){
        Object[] r = new Object[a.length+b.length];
        System.arraycopy(a,0,r,0,a.length);
        System.arraycopy(b,0,r,a.length,b.length);
        return r;
    }
    private LinLogs() {}
}
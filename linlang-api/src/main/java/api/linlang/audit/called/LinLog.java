package api.linlang.audit.called;


/**
 * 统一日志与审计门面。
 * <p>
 * 在启动时调用 {@link #install(Provider)} 安装；
 * 之后业务侧可直接调用 {@link #debug(String, Object...)}, {@link #info(String, Object...)},
 * {@link #warn(String, Object...)}, {@link #error(String, Throwable, Object...)}, {@link #audit(String, Object...)}。
 * <br>
 * 键值参数以 <code>key, value, key, value...</code> 形式传入，日志实现应按需结构化输出。
 * </p>
 */
public final class LinLog {
    /**
     * 日志实现提供者。由适配层（例如 Bukkit/Log4j/Slf4j）实现并注入。
     */
    public interface Provider {
        /**
         * 输出一条日志。
         *
         * @param level 级别，如 DEBUG/INFO/WARN/ERROR
         * @param msg   人类可读的消息文本
         * @param kv    扩展键值对：<code>key, value, key, value...</code>
         */
        void log(String level, String msg, Object... kv);

        /**
         * 记录一条审计事件（用于重要操作留痕）。
         *
         * @param event 事件名称/类型
         * @param kv    结构化字段：<code>key, value, ...</code>
         */
        void audit(String event, Object... kv);
    }
    private static volatile Provider P = new Noop();

    /**
     * 安装日志提供者。
     * <p>若传入 {@code null}，则回退为空实现（不输出）。</p>
     * @param p 提供者实例
     */
    public static void install(Provider p){ P = (p==null? new Noop(): p); }

    /** 输出 DEBUG 日志。 */
    public static void debug(String m, Object...kv){ P.log("DEBUG", m, kv); }
    /** 输出 INFO 日志。 */
    public static void info (String m, Object...kv){ P.log("INFO",  m, kv); }
    /** 输出 WARN 日志。 */
    public static void warn (String m, Object...kv){ P.log("WARN",  m, kv); }
    /** 输出模块加载日志  */
    public static void init(String m, Object...kv){ P.log("INIT",  m, kv); }
    /**
     * 输出 ERROR 日志。
     * <p>若提供异常，将其摘要追加到键值中，键为 <code>err</code>。</p>
     */
    public static void error(String m, Throwable t, Object...kv){
        Object[] kv2 = kv;
        if (t != null) kv2 = append(kv, "err", t.toString());
        P.log("ERROR", m, kv2);
    }
    public static void op(String m, Object...kv){ P.log("OP",  m, kv); }
    public static void startup(String m, Object...kv){P.log("STARTUP",  m, kv);}
    /**
     * 记录审计事件。
     * <p>用于安全、合规、关键路径操作的留痕与检索。</p>
     */
    public static void audit(String event, Object...kv){ P.audit(event, kv); }

    /**
     * 空实现：未安装 Provider 时使用，不做任何输出。
     */
    private static final class Noop implements Provider {
        public void log(String l,String m,Object...kv) {}
        public void audit(String e,Object...kv) {}
    }
    /**
     * 将两个可变参数数组拼接为一个新数组。
     */
    private static Object[] append(Object[] a, Object...b){
        Object[] r = new Object[a.length+b.length];
        System.arraycopy(a,0,r,0,a.length);
        System.arraycopy(b,0,r,a.length,b.length);
        return r;
    }
    private LinLog() {}
}
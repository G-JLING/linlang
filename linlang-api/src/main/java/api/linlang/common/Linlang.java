package api.linlang.common;

import api.linlang.file.service.ConfigService;
import api.linlang.file.database.services.DataService;
import api.linlang.file.service.LangService;
import api.linlang.file.service.Services;

/**
 * Linlang：文件与语言、数据服务的全局门面入口。
 *
 * <p>在插件启动时调用 {@code LinlangBukkitBootstrap.install(JavaPlugin)} 以装载；</p>
 * <p>业务侧在任意位置通过 {@link #services()} 取得 {@link Services} 并进一步获取</p>
 * {@link api.linlang.file.service.ConfigService}、{@link api.linlang.file.service.LangService}、
 * {@link DataService}。
 *
 */
public final class Linlang {
    private static volatile Services S = new Noop();

    /**
     * 安装服务集合。
     * <p>通常在适配层引导（Bootstrap）中调用一次，用于向全局注入实现。</p>
     * @param s 服务集合实现，不能为空
     * @throws IllegalArgumentException 当参数为 {@code null} 时抛出
     */
    public static void install(Services s) {
        if (s == null) throw new IllegalArgumentException("services null");
        S = s;
    }

    /**
     * 获取当前已安装的服务集合。
     * <p>业务模块通过该方法取得 Config/Lang/Data 等服务。</p>
     * @return 已安装的 {@link Services} 实例；若未安装则为一个抛异常的占位实现
     */
    public static Services services() { return S; }

    /**
     * 占位实现：在未调用 {@link #install(Services)} 前使用。
     * <p>任何方法调用都会抛出清晰的异常，以提醒先执行适配层引导。</p>
     */
    private static final class Noop implements Services {
        private static final RuntimeException E = new IllegalStateException(
                "Linlang services not installed. Call linlang-adapter bootstrap first.");
        /** @return 永不返回；调用即抛出异常 */
        public ConfigService config() { throw E; }
        /** @return 永不返回；调用即抛出异常 */
        public LangService   lang()   { throw E; }
        /** @return 永不返回；调用即抛出异常 */
        public DataService data()   { throw E; }
    }
    private Linlang() {}
}
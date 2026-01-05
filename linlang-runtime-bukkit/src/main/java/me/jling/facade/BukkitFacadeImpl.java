package me.jling.facade;

import api.linlang.command.LinCommand;
import api.linlang.file.LinFile;
import api.linlang.messenger.LinMessenger;
import api.linlang.runtime.Linlang;
import core.linlang.event.api.LinEventBus;
import core.linlang.runtime.FacadeCore;
import core.linlang.total.i18n.LocaleController;
import me.jling.runtime.BukkitRuntimeImpl;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.function.Function;

/**
 * Bukkit 平台的 Linlang 门面（薄壳）。
 * <p>
 * 该类只保留 Bukkit 侧需要的 API 形状与类型绑定（JavaPlugin），
 * 核心逻辑全部委托给 core 的 {@link FacadeCore}。
 */
public final class BukkitFacadeImpl implements Linlang, Linlang.Configurable, Linlang.Parametric, AutoCloseable {

    private final JavaPlugin owner;
    private final BukkitRuntimeImpl runtime;
    private final FacadeCore<JavaPlugin> core;

    /**
     * 创建并注册一个新的 BukkitFacadeImpl。
     */
    public static BukkitFacadeImpl create(BukkitRuntimeImpl runtime, JavaPlugin owner) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(owner, "owner bukkit");
        BukkitFacadeImpl f = new BukkitFacadeImpl(runtime, owner);
        // 兼容旧逻辑：运行时仍维护一份 facade 集合
        runtime.registerFacade(f);
        return f;
    }

    private BukkitFacadeImpl(BukkitRuntimeImpl runtime, JavaPlugin owner) {
        this.runtime = runtime;
        this.owner = owner;
        // core Facade（平台无关逻辑）
        this.core = runtime.getCore().createFacade(owner);
    }

    /** 获取该门面对应的 Bukkit 插件实例。 */
    public JavaPlugin owner() {
        return owner;
    }

    /** 获取 facade 级事件总线。 */
    public LinEventBus events() {
        return core.events();
    }

    /** 获取 facade 级语言控制器（语言代码唯一真相源）。 */
    public LocaleController locale() {
        return core.locale();
    }

    /**
     * 获取运行时版本（由运行时插件决定）。
     */
    @Override
    public String runtimeVersion() {
        return core.runtimeVersion();
    }

    /**
     * 文件服务视图。
     */
    @Override
    public LinFile linFile() {
        return core.linFile();
    }

    /**
     * 命令服务。
     */
    @Override
    public LinCommand linCommand() {
        return core.linCommand();
    }

    /**
     * 消息服务。
     */
    @Override
    public LinMessenger linMessenger() {
        return core.linMessenger();
    }

    /**
     * 设置平台上下文。
     * <p>Bukkit 平台下，platformContext 固定为 JavaPlugin；只允许与 owner 一致。</p>
     */
    @Override
    public BukkitFacadeImpl withPlatformContext(Object platformContext) {
        core.withPlatformContext(platformContext);
        return this;
    }

    /**
     * 设置全局前缀名（facade 级唯一入口）。
     */
    @Override
    public BukkitFacadeImpl totalPrefix(String prefix) {
        core.totalPrefix(prefix);
        return this;
    }

    /**
     * 设置全局前缀名提供函数（facade 级唯一入口）。
     */
    @Override
    public BukkitFacadeImpl totalPrefixProvider(Function<Object, String> provider) {
        core.totalPrefixProvider(provider);
        return this;
    }

    /**
     * 设置初始语言（仅记录，需 reload/restart 才会应用）。
     */
    @Override
    public BukkitFacadeImpl totalLocale(String locale) {
        core.totalLocale(locale);
        return this;
    }

    /**
     * 配置是否使用插件 logger。
     */
    @Override
    public BukkitFacadeImpl usingPluginLogger(boolean usePluginLogger) {
        core.usingPluginLogger(usePluginLogger);
        return this;
    }

    /**
     * 软重载：刷新 facade 的软配置（例如前缀/语言）。
     */
    @Override
    public void reload() {
        core.reload();
    }

    /**
     * 硬重启：销毁并重建该插件的 Linlang 服务实例。
     */
    @Override
    public void restart() {
        core.restart();
    }

    /**
     * 关闭门面实例并释放资源。
     */
    @Override
    public void close() {
        try {
            runtime.unregisterFacade(this);
        } catch (Throwable ignore) {
        }
        core.close();
    }
}
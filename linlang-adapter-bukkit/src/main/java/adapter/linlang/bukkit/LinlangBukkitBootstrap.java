package adapter.linlang.bukkit;

// file.io.linlang.adapter.bukkit.common.file.LinlangFileBoostrap

import adapter.linlang.bukkit.file.common.file.BukkitFsHotReloader;
import adapter.linlang.bukkit.file.common.file.BukkitPathResolver;
import adapter.linlang.bukkit.runtime.LinlangBootstrapRuntime;
import api.linlang.file.service.ConfigService;
import api.linlang.file.database.services.DataService;
import api.linlang.file.service.LangService;
import api.linlang.file.database.config.DbConfig;
import api.linlang.common.Linlang;
import api.linlang.file.service.Services;
import api.linlang.file.database.repo.Repository;
import core.linlang.file.impl.ConfigServiceImpl;
import core.linlang.database.impl.DataServiceImpl;
import core.linlang.file.impl.LangServiceImpl;
import api.linlang.file.PathResolver;
import api.linlang.file.database.types.DbType;
import lombok.Getter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.function.Function;

import adapter.linlang.bukkit.command.LinlangBukkitCommand;

public final class LinlangBukkitBootstrap implements AutoCloseable {

    public static LinlangBukkitBootstrap install(JavaPlugin plugin) {
        return new LinlangBukkitBootstrap(plugin);
    }

    @Getter
    public final ConfigServiceImpl config;
    @Getter
    public final LangServiceImpl lang;
    @Getter
    public final DataServiceImpl data;

    public LinlangBukkitCommand commands;

    private final BukkitFsHotReloader hot;
    private final PathResolver resolver;

    private final JavaPlugin plugin;

    private LinlangBootstrapRuntime runtime;

    // 装配方法
    public LinlangBukkitBootstrap(JavaPlugin plugin) {
        this.plugin = plugin;


        // 1) 核心路径解析与服务初始化
        this.resolver = new BukkitPathResolver(plugin);
        this.config = new ConfigServiceImpl(resolver, List.of());
        this.lang = new LangServiceImpl(resolver, "zh_CN");
        this.data = new DataServiceImpl(resolver);
        this.hot = new BukkitFsHotReloader(plugin);

        // 2) 注入 Linlang API 门面
        Linlang.install(new Services() {
            public ConfigService config() { return config; }
            public LangService lang() { return lang; }
            public DataService data() { return data; }
        });

        // 3) 使用运行期辅助类初始化审计和命令
        this.runtime = new LinlangBootstrapRuntime(plugin, this);

        this.runtime.installLinMsg();
        this.runtime.installAudit(false);
        this.runtime.initCommands();
        this.commands = this.runtime.getCommands();
    }

    /* 初始化数据源 */
    public void initDb(DbType type, DbConfig cfg) {
        data.init(type, cfg);
    }

    /* 获取仓库 */
    public <T> Repository<T, ?> repo(Class<T> entity) {
        return data.repo(entity);
    }

    @Override
    public void close() {
        try {
            hot.close();
        } catch (Exception ignore) {
        }
        try {
            if (runtime != null) runtime.close();
        } catch (Exception ignore) {
        }
    }

    /**
     * 固定前缀
     */
    public LinlangBukkitBootstrap withCommandPrefix(String prefix) {
        this.runtime.withCommandPrefix(prefix);
        this.commands = this.runtime.getCommands();
        return this;
    }

    /**
     * 自定义前缀提供者
     */
    public LinlangBukkitBootstrap withCommandPrefix(Function<JavaPlugin, String> provider) {
        this.runtime.withCommandPrefix(provider);
        this.commands = this.runtime.getCommands();
        return this;
    }

    /**
     * 切换是否使用 Plugin.getLogger() 输出日志
     */
    public LinlangBukkitBootstrap withPluginLogger(boolean usePluginLogger) {
        this.runtime.withPluginLogger(usePluginLogger);
        return this;
    }

    public LinlangBukkitBootstrap withInitialLanguage(String locale) {
        this.runtime.withInitialLanguage(locale);
        this.commands = this.runtime.getCommands();
        return this;
    }

    public LinlangBukkitBootstrap reload() {
        this.runtime.reloadI18n();
        this.commands = this.runtime.getCommands();
        return this;
    }
}
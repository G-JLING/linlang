package me.jling;

import adapter.linlang.bukkit.file.common.file.BukkitPathResolver;
import adapter.linlang.bukkit.messenger.MessengerImpl;
import adapter.linlang.bukkit.file.common.file.BukkitFsHotReloader;
import adapter.linlang.bukkit.command.LinlangBukkitCommand;

import api.linlang.command.LinCommand;
import api.linlang.file.LinFile;
import api.linlang.file.database.config.DbConfig;
import api.linlang.file.database.repo.Repository;
import api.linlang.file.database.services.DataService;
import api.linlang.file.database.types.DbType;
import api.linlang.file.path.PathResolver;
import api.linlang.file.service.ConfigService;
import api.linlang.file.service.LangService;

import api.linlang.messenger.LinMessenger;
import api.linlang.runtime.Linlang;
import core.linlang.database.impl.DataServiceImpl;
import core.linlang.file.impl.ConfigServiceImpl;
import core.linlang.file.impl.LangServiceImpl;

import lombok.Getter;
import me.jling.runtime.LinlangBootstrapRuntime;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.function.Function;

public final class LinlangBukkitBootstrap implements AutoCloseable, Linlang, Linlang.Configurable {

    public static LinlangBukkitBootstrap install(JavaPlugin plugin) {
        return new LinlangBukkitBootstrap(plugin);
    }

    @Getter
    public final ConfigServiceImpl config;
    @Getter
    public final LangServiceImpl language;
    @Getter
    public final DataServiceImpl database;

    public LinlangBukkitCommand command;
    public LinMessenger messenger;

    private final BukkitFsHotReloader hot;
    private final PathResolver resolver;

    private final JavaPlugin plugin;

    private LinlangBootstrapRuntime runtime;

    /** 提供给 API 的服务门面（由本实现返回） */
    private LinFile linFile;

    // 装配方法
    public LinlangBukkitBootstrap(JavaPlugin plugin) {
        this.plugin = plugin;

        // 1 核心路径解析与服务初始化
        this.resolver = new BukkitPathResolver(plugin);
        this.config = new ConfigServiceImpl(resolver, List.of());
        this.language = new LangServiceImpl(resolver, "zh_CN");
        this.database = new DataServiceImpl(resolver);
        this.hot    = new BukkitFsHotReloader(plugin);

        // 2 提供 Services 实例（供 API 实现返回）
        this.linFile = new LinFile() {
            public ConfigService config() { return config; }
            public LangService   language()   { return language; }
            public DataService   database()   { return database; }
        };

        // 3 使用运行期辅助类初始化审计和命令
        this.runtime = new LinlangBootstrapRuntime(plugin, this);
        this.runtime.installLinMsg();
        this.runtime.installAudit(false);
        this.runtime.initCommands();
        this.command = this.runtime.getCommands();
        this.messenger = new MessengerImpl(this.language);
    }

    /* ================= Linlang 接口实现 ================= */

    @Override
    public String runtimeVersion() {
        try {
            return plugin.getDescription().getVersion();
        } catch (Throwable ignore) {
            return "unknown";
        }
    }

    @Override
    public LinlangBukkitBootstrap withPlatformContext(Object platformContext) {
        return this;
    }

    @Override
    public LinlangBukkitBootstrap withCommandPrefix(String prefix) {
        this.runtime.withCommandPrefix(prefix);
        this.command = this.runtime.getCommands();
        return this;
    }

    @Override
    public LinlangBukkitBootstrap withCommandPrefixProvider(Function<Object, String> provider) {
        this.runtime.withCommandPrefix(p -> provider.apply(p));
        this.command = this.runtime.getCommands();
        return this;
    }

    @Override
    public LinlangBukkitBootstrap withPluginLogger(boolean usePluginLogger) {
        this.runtime.withPluginLogger(usePluginLogger);
        return this;
    }

    @Override
    public LinlangBukkitBootstrap withInitialLanguage(String locale) {
        this.runtime.withInitialLanguage(locale);
        this.command = this.runtime.getCommands();
        return this;
    }

    @Override
    public void reload() {
        this.runtime.reloadI18n();
        this.command = this.runtime.getCommands();
        this.messenger = new MessengerImpl(this.language);
    }

    @Override
    public void initDb(DbType type, DbConfig cfg) {
        database.init(type, cfg);
    }

    @Override
    public <T> Repository<T, ?> repo(Class<T> entity) {
        return database.repo(entity);
    }

    @Override
    public LinFile linFile() {
        return this.linFile;
    }

    @Override
    public LinCommand linCommand() {
        return this.command;
    }

    @Override
    public LinMessenger linMessenger() {
        return this.messenger;
    }

    /* ================= 资源释放 ================= */

    @Override
    public void close() {
        try { hot.close(); } catch (Exception ignore) {}
        try { if (runtime != null) runtime.close(); } catch (Exception ignore) {}
    }
}
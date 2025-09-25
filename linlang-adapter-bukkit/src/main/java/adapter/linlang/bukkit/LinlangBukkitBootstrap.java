package adapter.linlang.bukkit;

// file.io.linlang.adapter.bukkit.common.file.LinlangFileBoostrap

import adapter.linlang.bukkit.audit.common.BukkitAuditProvider;
import adapter.linlang.bukkit.file.common.file.BukkitFsHotReloader;
import adapter.linlang.bukkit.file.common.file.BukkitPathResolver;
import api.linlang.file.service.ConfigService;
import api.linlang.database.services.DataService;
import api.linlang.file.service.LangService;
import audit.linlang.audit.Linlogs;
import api.linlang.database.config.DbConfig;
import api.linlang.file.called.LinFile;
import api.linlang.file.service.Services;
import api.linlang.database.repo.Repository;
import core.linlang.file.impl.AddonServiceImpl;
import core.linlang.file.impl.ConfigServiceImpl;
import core.linlang.database.impl.DataServiceImpl;
import core.linlang.file.impl.LangServiceImpl;
import core.linlang.file.runtime.PathResolver;
import api.linlang.database.types.DbType;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.List;

/* 在 Bukkit 中装配 linlang 的文件与数据模块 */
public final class LinlangBukkitBootstrap implements AutoCloseable {


    /**
     * 安装并返回 Bootstrap 实例。
     * 该方法：
     * 1) 安装 Bukkit 日志/审计 Provider 到 Linlogs；
     * 2) 将 core 实现实例注入到 API 门面 LinFile；
     */
    public static LinlangBukkitBootstrap install(JavaPlugin plugin){
        return new LinlangBukkitBootstrap(plugin);
    }
    public final ConfigServiceImpl config;
    public final AddonServiceImpl addon;
    public final LangServiceImpl lang;
    public final DataServiceImpl data;

    private final BukkitFsHotReloader hot;
    private final PathResolver resolver;

    public LinlangBukkitBootstrap(JavaPlugin plugin){
        this.resolver = new BukkitPathResolver(plugin);
        this.config = new ConfigServiceImpl(resolver, List.of());
        this.addon  = new AddonServiceImpl(resolver);
        this.lang   = new LangServiceImpl(resolver, "zh_CN");
        this.data   = new DataServiceImpl(resolver);
        this.hot    = new BukkitFsHotReloader(plugin);

        // 1) 安装日志/审计
        Linlogs.install(new BukkitAuditProvider(plugin));

        // 2) 注入 API 门面
        LinFile.install(new Services() {
            public ConfigService config(){ return config; }
            public LangService lang()  { return lang; }
            public DataService data()  { return data; }
        });
    }

    /* 启用热重载监听。 */
    public LinlangBukkitBootstrap enableHotReloadFor(Class<?> cfgClz, Class<?> addonClz){
        Path root = resolver.root();
        hot.watchConfigDir(root.resolve("config"), config, cfgClz);
        hot.watchAddonDir (root.resolve("addons"), addon,  addonClz);
        hot.watchLangDir  (root.resolve("lang"),   lang);
        return this;
    }

    /* 初始化数据源。 */
    public void initDb(DbType type, DbConfig cfg){
        data.init(type, cfg);
    }

    /* 获取仓库。 */
    public <T> Repository<T, ?> repo(Class<T> entity){ return data.repo(entity); }

    @Override public void close(){ hot.close(); }
}
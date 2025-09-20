package io.linlang.adapter.bukkit.common;

// io.linlang.adapter.bukkit.common.LinlangBukkitBootstrap

import io.linlang.filesystem.DbConfig;
import io.linlang.filesystem.Repository;
import io.linlang.filesystem.impl.AddonServiceImpl;
import io.linlang.filesystem.impl.ConfigServiceImpl;
import io.linlang.filesystem.impl.DataServiceImpl;
import io.linlang.filesystem.impl.LangServiceImpl;
import io.linlang.filesystem.runtime.PathResolver;
import io.linlang.filesystem.types.DbType;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.List;

/* 在 Bukkit 中装配 linlang 的文件与数据模块 */
public final class LinlangBukkitBootstrap implements AutoCloseable {
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
        this.data   = new DataServiceImpl();
        this.hot    = new BukkitFsHotReloader(plugin);
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
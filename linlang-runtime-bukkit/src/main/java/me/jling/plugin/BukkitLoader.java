package me.jling.plugin;

import api.linlang.audit.LinLog;
import api.linlang.runtime.Lin;
import api.linlang.runtime.Linlang;
import me.jling.bukkit.LinlangBukkitBootstrap;
import me.jling.plugin.command.CommandListener;
import me.jling.runtime.LinlangRuntime;
import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.plugin.ServicePriority;

public class BukkitLoader extends JavaPlugin {

    private LinlangBukkitBootstrap bootstrap;
    private LinlangRuntime runtime;

    @Override
    public void onEnable() {
        long t0 = System.nanoTime();
        try {
            // 1) 初始化运行时装配器
            this.bootstrap = LinlangBukkitBootstrap.install(this);
            this.runtime = bootstrap.getRuntime();
            new CommandListener(this, runtime).register(bootstrap.linCommand());

            // 2) 确保服务总线上只保留本次注册（兼容热重载/重复启用）
            var sm = getServer().getServicesManager();
            try {
                sm.unregisterAll(this);
            } catch (Throwable ignored) {
            }
            sm.register(Linlang.class, bootstrap, this, ServicePriority.Highest);

            long ms = (System.nanoTime() - t0) / 1_000_000L;
            LinLog.info("[linlang] Linlang bootstrap enabled in " + ms + "ms. API=" + Lin.API_VERSION
                    + ", Runtime=" + bootstrap.runtimeVersion()
                    + ", Plugin=" + getDescription().getVersion());

            LinLog.info("[linlang] registered Linlang provider: providerClass={}, providerCL={}",
                    bootstrap.getClass().getName(), bootstrap.getClass().getClassLoader());

            LinLog.info("[linlang] Linlang interface classloader: {}", api.linlang.runtime.Linlang.class.getClassLoader());

        } catch (Throwable t) {
            LinLog.error("Failed to enable Linlang bootstrap: ", t);
            try {
                getServer().getServicesManager().unregisterAll(this);
            } catch (Throwable ignored) {
            }
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        try {
            getServer().getServicesManager().unregisterAll(this);
        } catch (Throwable ignored) {
        }

        if (bootstrap != null) {
            try {
                bootstrap.close();
            } catch (Exception ignored) {
            }
            bootstrap = null;
        }
        LinLog.info("[linlang] Linlang bootstrap disabled.");
    }
}
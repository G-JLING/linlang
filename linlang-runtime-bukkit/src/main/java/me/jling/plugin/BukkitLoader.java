package me.jling.plugin;

import api.linlang.audit.LinLog;
import api.linlang.runtime.Lin;
import api.linlang.runtime.Linlang;
import core.linlang.audit.problem.BuiltinProblemCatalog;
import me.jling.bukkit.LinlangBukkitBootstrap;
import me.jling.plugin.command.CommandListener;
import me.jling.runtime.BukkitRuntimeImpl;
import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.plugin.ServicePriority;

import java.util.logging.Level;

public class BukkitLoader extends JavaPlugin {

    private LinlangBukkitBootstrap bootstrap;
    private BukkitRuntimeImpl runtime;

    @Override
    public void onEnable() {
        long t0 = System.nanoTime();
        try {
            // 1) 初始化运行时装配器
            this.bootstrap = LinlangBukkitBootstrap.install(this);
            this.runtime = bootstrap.getRuntime();
            this.bootstrap.totalPrefix("§f[§dlinlang§f] ").reload();
            new CommandListener(this, runtime).register(bootstrap.linCommand());

            // 2) 确保服务总线上只保留本次注册（兼容热重载/重复启用）
            var sm = getServer().getServicesManager();
            try {
                sm.unregisterAll(this);
            } catch (RuntimeException exception) {
                reportCloseFailure(exception, "stale-bukkit-service-registration");
            }
            sm.register(Linlang.class, bootstrap, this, ServicePriority.Highest);

            long ms = (System.nanoTime() - t0) / 1_000_000L;
            LinLog.info("Linlang bootstrap enabled in " + ms + "ms. API=" + Lin.API_VERSION
                    + ", Runtime=" + bootstrap.runtimeVersion()
                    + ", Plugin=" + getDescription().getVersion());

//            LinLog.info("[linlang] registered Linlang provider: providerClass={}, providerCL={}",
//                    bootstrap.getClass().getName(), bootstrap.getClass().getClassLoader());
//
//            LinLog.info("[linlang] Linlang interface classloader: {}", api.linlang.runtime.Linlang.class.getClassLoader());

        } catch (Throwable t) {
            if (runtime != null) {
                runtime.audit().problem().report(
                        BuiltinProblemCatalog.RUNTIME_ENABLE_FAILED,
                        t,
                        "plugin", getName()
                );
            } else {
                getLogger().log(
                        Level.SEVERE,
                        '[' + BuiltinProblemCatalog.RUNTIME_ENABLE_FAILED + "] plugin=" + getName(),
                        t
                );
            }
            try {
                getServer().getServicesManager().unregisterAll(this);
            } catch (RuntimeException exception) {
                reportCloseFailure(exception, "failed-enable-service-registration");
            }
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        LinLog.info("[linlang] Linlang bootstrap disabling.");
        try {
            getServer().getServicesManager().unregisterAll(this);
        } catch (RuntimeException exception) {
            reportCloseFailure(exception, "bukkit-service-registration");
        }

        if (bootstrap != null) {
            try {
                bootstrap.close();
            } catch (Exception exception) {
                reportCloseFailure(exception, "bootstrap");
            }
            bootstrap = null;
        }
        getLogger().info("[linlang] Linlang bootstrap disabled.");
    }

    private void reportCloseFailure(Throwable cause, String resource) {
        if (runtime != null) {
            runtime.audit().problem().report(
                    BuiltinProblemCatalog.RESOURCE_CLOSE_FAILED,
                    cause,
                    "resource", resource
            );
            return;
        }
        getLogger().log(
                Level.WARNING,
                '[' + BuiltinProblemCatalog.RESOURCE_CLOSE_FAILED + "] resource=" + resource,
                cause
        );
    }
}

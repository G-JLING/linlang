package me.jling.bukkit;

import api.linlang.audit.called.LinLog;
import api.linlang.common.Lin;
import api.linlang.common.Linlang;
import org.bukkit.plugin.java.JavaPlugin;

import me.jling.LinlangBukkitBootstrap;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public class LinlangRuntimeBukkit extends JavaPlugin {

    private LinlangBukkitBootstrap runtime;

    @Override
    public void onEnable() {
        long t0 = System.nanoTime();
        try {
            // 1) 初始化运行时装配器
            this.runtime = LinlangBukkitBootstrap.install(this);

            // 2) 确保服务总线上只保留本次注册（兼容热重载/重复启用）
            var sm = getServer().getServicesManager();
            try { sm.unregisterAll(this); } catch (Throwable ignored) {}
            sm.register(Linlang.class, runtime, this, ServicePriority.Highest);

            long ms = (System.nanoTime() - t0) / 1_000_000L;
            LinLog.info("[linlang] Linlang runtime enabled in " + ms + "ms. API=" + Lin.API_VERSION
                    + ", Runtime=" + runtime.runtimeVersion()
                    + ", Plugin=" + getDescription().getVersion());

        } catch (Throwable t) {
            LinLog.error("Failed to enable Linlang runtime: ", t);
            try { getServer().getServicesManager().unregisterAll(this); } catch (Throwable ignored) {}
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        try {
            getServer().getServicesManager().unregisterAll(this);
        } catch (Throwable ignored) {}

        if (runtime != null) {
            try { runtime.close(); } catch (Exception ignored) {}
            runtime = null;
        }
        LinLog.info("[linlang] Linlang runtime disabled.");
    }
}
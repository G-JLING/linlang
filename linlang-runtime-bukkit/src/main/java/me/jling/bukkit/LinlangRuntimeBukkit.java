package me.jling.bukkit;

import api.linlang.audit.called.LinLog;
import api.linlang.init.Linlang;
import org.bukkit.plugin.java.JavaPlugin;

import me.jling.LinlangBukkitBootstrap;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public class LinlangRuntimeBukkit extends JavaPlugin {

    private LinlangBukkitBootstrap runtime;

    @Override
    public void onEnable() {
        try {
            // 初始化运行时装配器
            this.runtime = LinlangBukkitBootstrap.install(this);

            // 向 Bukkit 服务总线注册 API 实现
            getServer().getServicesManager()
                    .register(Linlang.class, runtime, this, ServicePriority.Normal);

            LinLog.info("[linlang] Linlang runtime enabled. API=" + Linlang.API_VERSION +
                    ", Runtime=" + runtime.runtimeVersion());

        } catch (Throwable t) {
            LinLog.error("Failed to enable Linlang runtime: ", t);
            t.printStackTrace();
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
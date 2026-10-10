package me.jling.plugin.config;

import api.linlang.file.file.annotations.ConfigFile;

/**
 * Linlang Bukkit 运行时配置。
 */
@ConfigFile
public final class RuntimeConfig {

    public FileService fileService = new FileService();
    public Compatibility compatibility = new Compatibility();

    public static final class FileService {
        public boolean autoRepairMissingKeys = false;
    }

    public static final class Compatibility {
        public boolean warnOnCompatibleVersionDifference = true;
    }
}

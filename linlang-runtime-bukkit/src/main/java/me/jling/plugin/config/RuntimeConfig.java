package me.jling.plugin.config;

import api.linlang.file.file.annotations.ConfigFile;

/**
 * Linlang Bukkit 运行时配置。
 */
@ConfigFile
public final class RuntimeConfig {

    public FileService fileService = new FileService();
    public Compatibility compatibility = new Compatibility();
    public volatile GameText gameText = new GameText();

    public static final class FileService {
        public boolean autoRepairMissingKeys = false;
    }

    public static final class Compatibility {
        public boolean warnOnCompatibleVersionDifference = true;
    }

    public static final class GameText {
        public volatile boolean chat = false;
        public volatile boolean title = true;
        public volatile boolean actionBar = true;
        public volatile boolean book = false;
        public volatile boolean anvil = false;
        public volatile boolean sign = false;
    }
}

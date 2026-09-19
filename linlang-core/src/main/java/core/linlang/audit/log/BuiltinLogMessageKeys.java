package core.linlang.audit.log;

import api.linlang.file.file.FileType;
import api.linlang.file.file.annotations.LangPack;
import api.linlang.file.file.annotations.NamingStyle;

/**
 * Linlang 内建日志模板的语言字段。
 */
@LangPack(filePath = "linlang/linlog/message", format = FileType.YAML, normalizeLocale = true)
@NamingStyle(value = NamingStyle.Style.KEBAB)
public final class BuiltinLogMessageKeys {

    public LinRuntime linRuntime = new LinRuntime();
    public LinFile linFile = new LinFile();
    public LinData linData = new LinData();
    public LinBanner linBanner = new LinBanner();

    public static final class LinRuntime {
        public String loading = "Linlang 正在加载";
        public String enabled =
                "Linlang 运行时已启用，耗时 {elapsed}ms。API={api}, Runtime={runtime}, Plugin={plugin}";
        public String disabling = "Linlang 运行时正在关闭";
        public String closed = "Linlang 运行时已关闭";
    }

    public static final class LinFile {
        public File file = new File();
        public Lang lang = new Lang();
        public Watcher watcher = new Watcher();

        public static final class File {
            public String fileMissingKeys =
                    "配置文件 {file} 缺失键 {count} 个，已生成默认键。详情见 {diff}";
            public String fileGeneratedDifferent =
                    "已生成差异告知文件：{diff}";
            public String fileReloaded =
                    "配置文件重新加载成功";
            public String fileSaved =
                    "已保存配置：{file}";
        }

        public static final class Lang {
            public String langMissingKeys =
                    "语言文件 {file} 缺失键 {count} 个，已生成默认键。详情见 {diff}";
            public String langGeneratedDifferent =
                    "已生成差异告知文件：{diff}";
            public String langSaved =
                    "已保存语言文件：{file}";
            public String langReloaded =
                    "语言文件重新加载成功";
        }

        public static final class Watcher {
            public String enabled = "文件动态重载已启用";
            public String eventSkipped = "已忽略文件服务自身写入产生的事件：{file}";
            public String eventAccepted = "已接受文件变更事件：{file}";
            public String configReloaded = "已动态重载配置文件：{file}";
            public String addonReloaded = "已动态重载附加文件：{file}";
            public String languageReloaded = "已动态重载语言文件：{file}";
        }
    }

    public static final class LinData {
        public String connectionPoolClosed = "数据库连接池已关闭：{type}";
        public String dbInit = "已初始化数据库：{type} {url}";
    }

    public static final class LinBanner {
        public String fontSearch = "Bukkit 正在查找字体文件资源";
    }
}

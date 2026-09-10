package core.linlang.audit.internal;


import api.linlang.file.file.FileType;
import api.linlang.file.file.annotations.LangPack;
import api.linlang.file.file.annotations.NamingStyle;

/**
 * 内建的框架层级消息。
 * <p>
 * 添加更多消息时，键应遵循命名规范：
 * 1) 采用“域-动词-补充”的顺序组织词汇，便于分组与检索：
 * - file-*       任意文件相关
 * - message-*       语言的绑定、切换与使用
 * - reload-*     热重载
 * - command-*    命令系统
 * - audit-*      审计与日志
 * 2) 使用占位符 {name} 形式传参
 * 常用占位：{file}、{path}、{count}、{diff}、{reason}、{locale}。
 */
@LangPack(filePath = "linlang/linlog/message", format = FileType.YAML, normalizeLocale = true)
@NamingStyle(value = NamingStyle.Style.KEBAB)
public class LinlangInternalMessageKeys {

    protected static final String ME = "JLING(magicpowered@icloud.com)";
    public LinFile linFile = new LinFile();

    public LinData linData = new LinData();
    public LinCommand linCommand = new LinCommand();
    public LinLog linLog = new LinLog();
    public LinText linText = new LinText();


    public static class LinFile {

        public File file = new File();
        public Lang lang = new Lang();
        public Watcher watcher = new Watcher();

        public static class File {
            public String missingKeys =
                    " + 此键在旧文件中缺失!";
            public String fileMissingKeys =
                    "配置文件 {file} 缺失键 {count} 个，已生成默认键。详情见 {diff}";
            public String fileGeneratedDifferent =
                    "已生成差异告知文件：{diff}";
            public String fileReloaded =
                    "配置文件重新加载成功";
            public String fileSaved =
                    "已保存配置：{file}";
            public String fileSaveFailed =
                    "保存配置失败：{file}。原因：{reason}";
            public String fileReloadFailed = "重新加载配置失败：{file}。原因：{reason}";

        }

        public static class Lang {
            public String missingKeys =
                    " + 此键在旧文件中缺失!";
            public String langMissingKeys =
                    "语言文件 {file} 缺失键 {count} 个，已生成默认键。详情见 {diff}";
            public String langGeneratedDifferent =
                    "已生成差异告知文件：{diff}";
            public String langSaved =
                    "已保存配置：{file}";
            public String langReloaded =
                    "语言文件重新加载成功";
            public String langSaveFailed =
                    "保存配置失败：{file}。原因：{reason}";
            public String langReloadFailed = "重新加载语言失败：{file}。原因：{reason}";
            public String langChangeLocale =
                    "语言已变更 {locale} -> {file}。";
        }

        public static class Watcher {
            public String reloadWatchingStart =
                    "正在监听目录：{path}";
            public String reloadFileChanged =
                    "检测到改动：{file}";
            public String reloadFailed =
                    "热重载失败，请联系 " + ME + "。原因：{reason}";

        }
    }

    public static class LinData {
        public String repositoryFlushCompleted = "仓库刷新调用完成：实体 {entity}，数据表 {table}";
        public String connectionPoolClosed = "数据库连接池已关闭：{type}";
        public String dbInit = "已初始化数据库：{type} {url}";
        public String ensureTable = "已确保表：{table}";
        public String flushOk = "已落盘：{data}";
        public String flushFailed = "落盘失败：{data}，原因：{reason}";
    }

    public static class LinCommand {
        public String commandSetPrefix =
                "已设置命令前缀：{prefix}";
        public String commandLanguageSwitched =
                "命令语言已切换为：{locale}";
        public String commandRouterRebuilt =
                "命令系统已重建";

    }

    public static class LinLog {
        public String auditConfigLoaded =
                "已加载审计配置";
        public String auditConfigReloaded =
                "已重载审计配置";

    }

    public static class LinText {
        public String textSourceTooLong =
                "此高级字符串太长了: {lintext}";
    }
}

package core.linlang.audit.message;


import api.linlang.file.file.annotations.I18nComment;
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
@NamingStyle(NamingStyle.Style.KEBAB)
@I18nComment(locale = "zh_CN", lines = "Linlang 内建消息（框架级日志与提示）")
@I18nComment(locale = "en_GB", lines = "Linlang internal messages (framework-level logs and prompts)")
public class LinlangInternalMessageKeys {

    protected static final String ME = "JLING(magicpowered@icloud.com)";

    @I18nComment(locale = "zh_CN", lines = "文件系统相关")
    @I18nComment(locale = "en_GB", lines = "Linlang related")
    public LinFile linFile = new LinFile();

    public LinData linData = new LinData();

    @I18nComment(locale = "zh_CN", lines = "命令系统相关")
    @I18nComment(locale = "en_GB", lines = "LinCommand related")
    public LinCommand linCommand = new LinCommand();

    @I18nComment(locale = "zh_CN", lines = "审计与日志系统相关")
    @I18nComment(locale = "en_GB", lines = "LinLog related")
    public LinLog linLog = new LinLog();


    public static class LinFile {

        public File file = new File();
        public Watcher watcher = new Watcher();

        public static class File {

            @I18nComment(locale = "zh_CN", lines = "缺失键的注释标记")
            @I18nComment(locale = "zh_CN", lines = "Missing keys annotation marker")
            public String missingKeys =
                    " + 此键在旧文件中缺失!";

            @I18nComment(locale = "zh_CN", lines = "发现缺失键，已在 diffrent 文件中给出缺失项")
            @I18nComment(locale = "en_GB", lines = "Missing keys detected; details written to the diffrent file")
            public String fileMissingKeys =
                    "{file} 缺失键 {count} 个，已生成默认键。详情见 {diff}";

            @I18nComment(locale = "zh_CN", lines = "已生成差异（diffrent）文件以便审阅")
            @I18nComment(locale = "en_GB", lines = "A diffrent file was generated for review")
            public String fileGeneratedDifferent =
                    "已生成差异告知文件：{diff}";

            @I18nComment(locale = "zh_CN", lines = "配置已保存到磁盘")
            @I18nComment(locale = "en_GB", lines = "Configuration saved to disk")
            public String fileSavedConfig =
                    "已保存配置：{file}";

            @I18nComment(locale = "zh_CN", lines = "保存配置失败")
            @I18nComment(locale = "en_GB", lines = "Failed to save configuration")
            public String fileSaveConfigFailed =
                    "保存配置失败：{file}，请联系 " + ME + "。原因：{reason}";

            @I18nComment(locale = "zh_CN", lines = "语言包已生成/更新")
            @I18nComment(locale = "en_GB", lines = "Language pack generated/updated")
            public String langChangeLocale =
                    "语言已变更 {locale} -> {file}。重新启动服务器以使更改生效。";

        }

        public static class Watcher {

            @I18nComment(locale = "zh_CN", lines = "开始监听文件改动以进行热重载")
            @I18nComment(locale = "en_GB", lines = "Start watching files for hot reload")
            public String reloadWatchingStart =
                    "正在监听目录：{path}";

            @I18nComment(locale = "zh_CN", lines = "检测到文件改动，正在应用")
            @I18nComment(locale = "en_GB", lines = "File change detected; applying")
            public String reloadFileChanged =
                    "检测到改动：{file}";

            @I18nComment(locale = "zh_CN", lines = "热重载失败")
            @I18nComment(locale = "en_GB", lines = "Hot reload failed")
            public String reloadFailed =
                    "热重载失败，请联系 " + ME + "。原因：{reason}";

        }
    }

    public static class LinData {

        @I18nComment(locale = "zh_CN", lines = "初始化数据库")
        @I18nComment(locale = "en_GB", lines = "Initialize database")
        public String dbInit = "已初始化数据库：{type} {url}";

        @I18nComment(locale = "zh_CN", lines = "确保表")
        @I18nComment(locale = "en_GB", lines = "ensure table")
        public String ensureTable = "已确保表：{table}";

        @I18nComment(locale = "zh_CN", lines = "成功落盘")
        @I18nComment(locale = "en_GB", lines = "persistence successful")
        public String flushOk = "已落盘：{data}";

        @I18nComment(locale = "zh_CN", lines = "落盘失败")
        @I18nComment(locale = "en_GB", lines = "persistence failed")
        public String flushFailed = "落盘失败：{data}，原因：{reason}";
    }

    public static class LinCommand {

        @I18nComment(locale = "zh_CN", lines = "已设置命令消息前缀")
        @I18nComment(locale = "en_GB", lines = "Command message prefix set")
        public String commandSetPrefix =
                "已设置命令前缀：{prefix}";

        @I18nComment(locale = "zh_CN", lines = "命令消息语言已切换")
        @I18nComment(locale = "en_GB", lines = "Command message language switched")
        public String commandLanguageSwitched =
                "命令语言已切换为：{locale}";

        @I18nComment(locale = "zh_CN", lines = "命令路由/补全器已重建")
        @I18nComment(locale = "en_GB", lines = "Command router/completer rebuilt")
        public String commandRouterRebuilt =
                "命令系统已重建";

    }

    public static class LinLog {

        @I18nComment(locale = "zh_CN", lines = "审计/日志配置已加载")
        @I18nComment(locale = "en_GB", lines = "Audit/logging config loaded")
        public String auditConfigLoaded =
                "已加载审计配置";

        @I18nComment(locale = "zh_CN", lines = "审计/日志配置已重载")
        @I18nComment(locale = "en_GB", lines = "Audit/logging config reloaded")
        public String auditConfigReloaded =
                "已重载审计配置";

    }
}

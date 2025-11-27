package core.linlang.audit.message.i18n;

import core.linlang.audit.message.LinlangInternalMessageKeys;
import api.linlang.file.annotations.NoEmit;
import api.linlang.file.implement.LocaleProvider;
import api.linlang.file.annotations.LangPack;
import api.linlang.file.types.FileType;

@NoEmit
@LangPack(name = "zh_CN", path = "linlang/message", format = FileType.YAML, locale = "zh_CN")
public class ZhCN extends LinlangInternalMessageKeys implements LocaleProvider<LinlangInternalMessageKeys> {

    private static final String ME = LinlangInternalMessageKeys.ME;

    @Override
    public String locale() {
        return "zh_CN";
    }

    @Override
    public void define(LinlangInternalMessageKeys k) {
        // —— Linlang.File —— //
        this.linFile.file.missingKeys =
                " + 此键在旧文件中缺失!";
        this.linFile.file.fileMissingKeys =
                "{file} 缺失键 {count} 个，已生成默认键。详情见 {diff}";
        this.linFile.file.fileGeneratedDifferent =
                "已生成差异告知文件：{diff}";
        this.linFile.file.fileSavedConfig =
                "已保存配置：{file}";
        this.linFile.file.fileSaveConfigFailed =
                "保存配置失败：{file}，请联系 " + ME + "。原因：{reason}";
        this.linFile.file.langChangeLocale =
                "语言已变更 {locale} -> {file}。重新启动服务器以使更改生效。";

        // —— Linlang.Watcher —— //
        this.linFile.watcher.reloadWatchingStart =
                "正在监听目录：{path}";
        this.linFile.watcher.reloadFileChanged =
                "检测到改动：{file}";
        this.linFile.watcher.reloadFailed =
                "热重载失败，请联系 " + ME + "。原因：{reason}";

        // —— LinData —— //
        this.linData.dbInit =
                "已初始化数据库：{type} {url}";
        this.linData.ensureTable =
                "已确保表：{table}";
        this.linData.flushOk =
                "数据库 {data} 已落盘";
        this.linData.flushFailed =
                "数据库 {data} 落盘失败，原因：{reason}";

        // —— LinCommand —— //
        this.linCommand.commandSetPrefix =
                "已设置命令前缀：{prefix}";
        this.linCommand.commandLanguageSwitched =
                "命令语言已切换为：{locale}";
        this.linCommand.commandRouterRebuilt =
                "命令系统已重建";

        // —— LinLog —— //
        this.linLog.auditConfigLoaded =
                "已加载审计配置";
        this.linLog.auditConfigReloaded =
                "已重载审计配置";
    }
}
package api.linlang.audit.common.i18n;

import api.linlang.file.annotations.NoEmit;
import api.linlang.file.implement.LocaleProvider;
import api.linlang.audit.common.LinlangInternalMessageKeys;
import api.linlang.file.annotations.LangPack;
import api.linlang.file.types.FileType;


@NoEmit
@LangPack(name = "en_GB", path = "linlang/message", format = FileType.YAML, locale = "en_GB")
public class EnGB extends LinlangInternalMessageKeys implements LocaleProvider<LinlangInternalMessageKeys> {

    private static final String ME = LinlangInternalMessageKeys.ME;

    @Override
    public String locale() {
        return "en_GB";
    }

    @Override
    public void define(LinlangInternalMessageKeys k) {
        // —— Linlang.File —— //
        this.linFile.file.missingKeys =
                " + This key is missing in old file!";
        this.linFile.file.fileMissingKeys =
                "{file} has {count} missing keys; default keys have been generated. See {diff} for details.";
        this.linFile.file.fileGeneratedDifferent =
                "A difference file has been generated for your review: {diff}";
        this.linFile.file.fileSavedConfig =
                "Configuration saved: {file}";
        this.linFile.file.fileSaveConfigFailed =
                "Failed to save configuration: {file}. Please contact " + ME + ". Reason: {reason}";
        this.linFile.file.langChangeLocale =
                "Language changed from {locale} to {file}. Please restart the server to apply changes.";

        // —— Linlang.Watcher —— //
        this.linFile.watcher.reloadWatchingStart =
                "Now watching directory: {path}";
        this.linFile.watcher.reloadFileChanged =
                "Detected change: {file}";
        this.linFile.watcher.reloadFailed =
                "Hot reload failed. Please contact " + ME + ". Reason: {reason}";

        // —— LinData —— //
        this.linData.dbInit =
                "Initialize database: {type} {url}";
        this.linData.ensureTable =
                "Table: {table} has been ensured";
        this.linData.flushOk =
                "The database {data} has been stored on the disk";
        this.linData.flushFailed =
                "Database {data} failed to stored, reason: {reason}";

        // —— LinCommand —— //
        this.linCommand.commandSetPrefix =
                "Command prefix set to: {prefix}";
        this.linCommand.commandLanguageSwitched =
                "Command language switched to: {locale}";
        this.linCommand.commandRouterRebuilt =
                "Command system rebuilt.";

        // —— LinLog —— //
        this.linLog.auditConfigLoaded =
                "Audit configuration loaded.";
        this.linLog.auditConfigReloaded =
                "Audit configuration reloaded.";
    }
}
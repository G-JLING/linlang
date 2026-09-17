package core.linlang.audit.problem;

import api.linlang.file.file.FileType;
import api.linlang.file.file.LangMap;
import api.linlang.file.file.annotations.LangPack;
import api.linlang.file.file.annotations.NamingStyle;

/**
 * Linlang 内建问题目录的语言字段。
 *
 * <p>映射键使用稳定的问题代码，值分别表示问题含义和处理建议。</p>
 */
@NamingStyle(NamingStyle.Style.KEBAB)
@LangPack(filePath = "linlang/linlog/problem", format = FileType.YAML, normalizeLocale = true)
public final class BuiltinProblemMessageKeys {

    public LangMap descriptions = LangMap.of();
    public LangMap resolutions = LangMap.of();
}

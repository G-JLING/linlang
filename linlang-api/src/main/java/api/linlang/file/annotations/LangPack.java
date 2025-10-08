package api.linlang.file.annotations;

import api.linlang.file.types.FileType;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * 声明当前类为 LinFile 语言文件服务中提供语言的类
 * <p>注意，声明语言文件字段的文件不应该添加此注解</p>
 *
 * <p>此注解应用于 {@code implements LocalProvider<T keys>} 的类</p>
 *
 * @apiNote
 * 指定语言文件的文件名、存放路径以及格式。
 * <pre><code> {@literal @}LangPack(locale = "zh_CN", format = FileType.YAML, path = "linlang/command-message")
 * public final class ZhCN implements LocaleProvider{@literal <}T keys> {
 *      ...
 * }</code></pre>
 */
@Retention(RUNTIME) @Target(TYPE)
public @interface LangPack {

    /**
     * 语言代码，如 @{code zh_CN}
     * 即 language_REGION 格式
     * */
    String locale();

    /**
     * 文件名（不带扩展名），例如 {@code zh_CN}
     * 默认为语言代码
     * */
    String name() default "";

    /**
     * 配置文件相对路径（相对于插件根目录）。
     * 默认为空，即插件根目录。
     */
    String path() default "lang";

    /**
     * 配置文件格式。
     * 默认为 {@link FileType#YAML}。
     */
    FileType format() default FileType.YAML;
}
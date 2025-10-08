package api.linlang.file.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @apiNote
 * 这是一个容器，不要使用。一定要使用时，按照下文<br>
 * <pre><code>
 * {@literal @}I18nComments({
 *   {@literal @}I18nComment(locale = "zh_CN", lines = {"中文注释1", "中文注释2"}),
 *   {@literal @}I18nComment(locale = "en_GB", lines = {"English comment 1", "English comment 2"})
 * }) </code></pre>
 **/
@Retention(RetentionPolicy.RUNTIME)
@Deprecated(forRemoval = false, since = "internal")
@org.jetbrains.annotations.ApiStatus.Internal
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface I18nComments {
    I18nComment[] value();
}

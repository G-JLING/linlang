package api.linlang.file.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * 为类或字段添加注释说明。
 *
 * <p>此注解应用于类和字段。</p>
 *
 * @apiNote
 * 用于提供说明性注释（非多语言）。若需多语言，请使用 {@link I18nComment}。
 */
@Retention(RUNTIME)
@Target({TYPE, FIELD})
public @interface Comment {
    /**
     * 注释内容。每一个数组元素为一行。
     */
    String[] value();
}
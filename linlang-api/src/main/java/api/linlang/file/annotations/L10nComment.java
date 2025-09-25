// called.linlang.annotations.file.L10nComment.java
package api.linlang.file.annotations;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD})
@Repeatable(L10nComments.class)
public @interface L10nComment {
    String locale(); // 语言, e.g. zh_CN
    String[] lines(); // 注释本身
}

// 容器注解（编译器自动生成）

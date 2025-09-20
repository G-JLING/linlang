package io.linlang.filesystem.annotations;

import io.linlang.filesystem.types.FileFormat;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

// —— 附加文件 —— //
@Retention(RUNTIME) @Target(TYPE)
public @interface AddonFile {
    String name(); String path() default "addons";
    FileFormat format() default FileFormat.YAML;
}

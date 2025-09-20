package io.linlang.filesystem.annotations;

import io.linlang.filesystem.types.FileFormat;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Retention(RUNTIME) @Target(TYPE)
public @interface ConfigFile {
    String name();                // e.g. 'config'
    String path() default "config";
    FileFormat format() default FileFormat.YAML;
}
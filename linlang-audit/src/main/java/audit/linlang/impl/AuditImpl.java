package audit.linlang.impl;

import audit.linlang.audit.AuditConfig;
import audit.linlang.audit.Auditor;
import audit.linlang.audit.Logger;
import core.linlang.file.runtime.PathResolver;
import api.linlang.file.service.ConfigService;

// linlang-called/src/main/java/io/linlang/called/AuditImpl.java
public final class AuditImpl implements Auditor, Logger {
    public AuditImpl(PathResolver paths, ConfigService cfgs){
        AuditConfig cfg = cfgs.bind(AuditConfig.class);
        // 按 cfg 装配 sinks（console/file），注册单线程异步写
    }

    @Override
    public void emit(String event, Object... kv) {

    }

    @Override
    public void debug(String msg, Object... kv) {

    }

    @Override
    public void info(String msg, Object... kv) {

    }

    @Override
    public void warn(String msg, Object... kv) {

    }

    @Override
    public void error(String msg, Throwable t, Object... kv) {

    }
    // …emit/info/warn/error 实现：统一结构化输出
}
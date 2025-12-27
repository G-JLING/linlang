package core.linlang.event.api;

/** 监听器期望的执行线程模式 */
public enum ThreadMode {
    /** 在发布线程执行（默认） */
    CURRENT,

    /** 交给平台的“主线程执行器”执行（例如 Bukkit 主线程） */
    MAIN,

    /** 交给平台的“异步执行器”执行（例如线程池） */
    ASYNC
}
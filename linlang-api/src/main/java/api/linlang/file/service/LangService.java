package api.linlang.file.service;

import api.linlang.file.implement.LocaleProvider;
import java.util.List;

/**
 * 语言服务接口（API）。
 * <p>
 * 约定与用法：
 * <ul>
 *   <li>先通过 {@link #bind(Class, String, List)} 绑定一个「键结构类 + 目标语言 + 语言提供者」；
 *       返回的对象作为读取与修改的入口；</li>
 *   <li>修改内存对象后，调用 {@link #save(Class, String)} 持久化；
 *       或调用 {@link #saveAll()} 持久化所有已绑定对象；</li>
 *   <li>消息发送可通过 {@link #tr(String, Object...)} 按键取模板（供 Messenger 等使用）。</li>
 * </ul>
 */
public interface LangService {
    /**
     * 绑定一个语言对象（对象键结构类 + 语言代码 + 多个提供者）。
     * <p>文件存在则优先用文件值，缺失键用提供者的默认值回填，并生成差异提示。</p>
     */
    <T> T bind(Class<T> keysClass, String locale,
                     List<? extends LocaleProvider<T>> providers);
    /** 保存指定语言对象到文件。 */
    <T> void save(Class<T> keysClass, String locale);

    /** 保存所有已绑定的语言对象。 */
    void saveAll();

    /** 当前语言代码（若实现维护全局当前语言）。 */
    String currentLocale();

    /** 设置当前语言代码（可选，全局影响 tr 行为）。 */
    void setLocale(String locale);

    /**
     * 翻译：根据键返回模板文本，并按需处理占位、复数等。
     * @param key 键，例如 "message.no-item-in-hand"
     * @param args 可选参数，交由实现处理（占位替换等）
     */
    String tr(String key, Object... args);
}

package core.linlang.view.render;

import core.linlang.view.eval.Expr;
import core.linlang.view.spec.ActionSpec;
import core.linlang.view.spec.IconSpec;
import core.linlang.view.spec.TemplateSpec;
import core.linlang.view.spec.VariantSpec;

import java.util.Map;

/**
 * 变体选择器：根据 row/state 在 template.variants 中自上而下匹配第一个 when=true 的变体。
 *
 * 合并规则：
 * - 变体中非 null 字段覆盖 template 默认字段。
 * - 变体未提供 icon/action 时回退 template.icon/template.action。
 * - visible/enabled 为可选覆盖：null 表示不覆盖（使用默认 true 或外层逻辑决定）。
 */
public final class VariantSelector {

    private VariantSelector() {}

    public static Selected select(TemplateSpec template,
                                  Map<String, Object> row,
                                  Map<String, Object> state) {
        if (template == null) return new Selected(null, null, null, null);

        IconSpec baseIcon = template.icon();
        ActionSpec baseAction = template.action();

        if (!template.hasVariants()) {
            return new Selected(baseIcon, baseAction, null, null);
        }

        for (VariantSpec v : template.variants()) {
            if (v == null) continue;

            String when = v.when();
            boolean ok;
            try {
                ok = Expr.eval(when, row, state);
            } catch (Throwable ignore) {
                ok = false;
            }

            if (!ok) continue;

            IconSpec icon = (v.icon() != null) ? v.icon() : baseIcon;
            ActionSpec action = (v.action() != null) ? v.action() : baseAction;
            return new Selected(icon, action, v.visible(), v.enabled());
        }

        // 没有任何变体命中：回退 template 默认
        return new Selected(baseIcon, baseAction, null, null);
    }

    /**
     * 变体选择结果（已合并 template 默认 + variant 覆盖）。
     */
    public record Selected(
            IconSpec icon,
            ActionSpec action,
            Boolean visibleOverride,
            Boolean enabledOverride
    ) {}
}
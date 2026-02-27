import CSSRule from '../CSSRule.js';
import CSSKeyframeRule from './CSSKeyframeRule.js';
import CSSRuleTypeEnum from '../CSSRuleTypeEnum.js';
/**
 * CSSRule interface.
 */
export default class CSSKeyframesRule extends CSSRule {
    readonly type = CSSRuleTypeEnum.keyframesRule;
    readonly cssRules: CSSKeyframeRule[];
    readonly name: string;
    /**
     * Returns css text.
     *
     * @returns CSS text.
     */
    get cssText(): string;
    /**
     * Appends a rule.
     *
     * @param rule Rule. E.g. "0% { transform: rotate(360deg); }".
     */
    appendRule(rule: string): void;
    /**
     * Removes a rule.
     *
     * @param rule Rule. E.g. "0%".
     */
    deleteRule(rule: string): void;
}
//# sourceMappingURL=CSSKeyframesRule.d.ts.map
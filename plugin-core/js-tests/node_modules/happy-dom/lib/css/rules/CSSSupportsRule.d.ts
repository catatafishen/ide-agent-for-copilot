import CSSRule from '../CSSRule.js';
import CSSRuleTypeEnum from '../CSSRuleTypeEnum.js';
/**
 * CSSRule interface.
 */
export default class CSSSupportsRule extends CSSRule {
    readonly type = CSSRuleTypeEnum.supportsRule;
    readonly cssRules: CSSRule[];
    readonly conditionText = "";
    /**
     * Returns css text.
     *
     * @returns CSS text.
     */
    get cssText(): string;
}
//# sourceMappingURL=CSSSupportsRule.d.ts.map
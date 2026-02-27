import CSSRule from '../CSSRule.cjs';
import CSSRuleTypeEnum from '../CSSRuleTypeEnum.cjs';
/**
 * CSSRule interface.
 */
export default class CSSContainerRule extends CSSRule {
    readonly type = CSSRuleTypeEnum.containerRule;
    readonly cssRules: CSSRule[];
    readonly conditionText = "";
    /**
     * Returns css text.
     *
     * @returns CSS text.
     */
    get cssText(): string;
}
//# sourceMappingURL=CSSContainerRule.d.ts.map
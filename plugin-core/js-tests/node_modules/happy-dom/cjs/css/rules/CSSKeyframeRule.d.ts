import CSSRule from '../CSSRule.cjs';
import * as PropertySymbol from '../../PropertySymbol.cjs';
import CSSStyleDeclaration from '../declaration/CSSStyleDeclaration.cjs';
import CSSRuleTypeEnum from '../CSSRuleTypeEnum.cjs';
/**
 * CSSRule interface.
 */
export default class CSSKeyframeRule extends CSSRule {
    #private;
    readonly type = CSSRuleTypeEnum.keyframeRule;
    readonly keyText: string;
    [PropertySymbol.cssText]: string;
    /**
     * Returns style.
     *
     * @returns Style.
     */
    get style(): CSSStyleDeclaration;
    /**
     * Returns css text.
     *
     * @returns CSS text.
     */
    get cssText(): string;
}
//# sourceMappingURL=CSSKeyframeRule.d.ts.map
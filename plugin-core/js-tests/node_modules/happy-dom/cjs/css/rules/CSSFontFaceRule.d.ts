import CSSRule from '../CSSRule.cjs';
import * as PropertySymbol from '../../PropertySymbol.cjs';
import CSSStyleDeclaration from '../declaration/CSSStyleDeclaration.cjs';
import CSSRuleTypeEnum from '../CSSRuleTypeEnum.cjs';
/**
 * CSSRule interface.
 */
export default class CSSFontFaceRule extends CSSRule {
    #private;
    readonly type = CSSRuleTypeEnum.fontFaceRule;
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
//# sourceMappingURL=CSSFontFaceRule.d.ts.map
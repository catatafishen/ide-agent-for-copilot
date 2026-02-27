import CSSRule from '../CSSRule.js';
import CSSRuleTypeEnum from '../CSSRuleTypeEnum.js';
/**
 * CSSRule interface.
 */
export default class CSSSupportsRule extends CSSRule {
    type = CSSRuleTypeEnum.supportsRule;
    cssRules = [];
    conditionText = '';
    /**
     * Returns css text.
     *
     * @returns CSS text.
     */
    get cssText() {
        let cssText = '';
        for (const cssRule of this.cssRules) {
            cssText += cssRule.cssText;
        }
        return `@supports ${this.conditionText} { ${cssText} }`;
    }
}
//# sourceMappingURL=CSSSupportsRule.js.map
import CSSRule from '../CSSRule.js';
import CSSRuleTypeEnum from '../CSSRuleTypeEnum.js';
/**
 * CSSRule interface.
 */
export default class CSSContainerRule extends CSSRule {
    type = CSSRuleTypeEnum.containerRule;
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
        return `@container ${this.conditionText} { ${cssText} }`;
    }
}
//# sourceMappingURL=CSSContainerRule.js.map
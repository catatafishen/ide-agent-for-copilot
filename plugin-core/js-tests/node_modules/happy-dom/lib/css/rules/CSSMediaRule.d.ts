import CSSRule from '../CSSRule.js';
import CSSRuleTypeEnum from '../CSSRuleTypeEnum.js';
import MediaList from '../MediaList.js';
/**
 * CSSRule interface.
 */
export default class CSSMediaRule extends CSSRule {
    readonly type = CSSRuleTypeEnum.mediaRule;
    readonly cssRules: CSSRule[];
    readonly media: MediaList;
    /**
     * Returns css text.
     *
     * @returns CSS text.
     */
    get cssText(): string;
    /**
     * Returns conditional text.
     *
     * @returns Conditional text.
     */
    get conditionText(): string;
}
//# sourceMappingURL=CSSMediaRule.d.ts.map
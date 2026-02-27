import CSSRule from '../CSSRule.cjs';
import CSSRuleTypeEnum from '../CSSRuleTypeEnum.cjs';
import MediaList from '../MediaList.cjs';
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
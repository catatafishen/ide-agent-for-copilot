"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const CSSRule_js_1 = __importDefault(require("../CSSRule.cjs"));
const PropertySymbol = __importStar(require("../../PropertySymbol.cjs"));
const CSSStyleRule_js_1 = __importDefault(require("../rules/CSSStyleRule.cjs"));
const CSSKeyframeRule_js_1 = __importDefault(require("../rules/CSSKeyframeRule.cjs"));
const CSSKeyframesRule_js_1 = __importDefault(require("../rules/CSSKeyframesRule.cjs"));
const CSSMediaRule_js_1 = __importDefault(require("../rules/CSSMediaRule.cjs"));
const CSSContainerRule_js_1 = __importDefault(require("../rules/CSSContainerRule.cjs"));
const CSSSupportsRule_js_1 = __importDefault(require("../rules/CSSSupportsRule.cjs"));
const CSSFontFaceRule_js_1 = __importDefault(require("../rules/CSSFontFaceRule.cjs"));
const SelectorParser_js_1 = __importDefault(require("../../query-selector/SelectorParser.cjs"));
const CSSRuleTypeEnum_js_1 = __importDefault(require("../CSSRuleTypeEnum.cjs"));
const COMMENT_REGEXP = /\/\*[\s\S]*?\*\//gm;
/**
 * CSS parser.
 */
class CSSParser {
    /**
     * Parses HTML and returns a root element.
     *
     * @param parentStyleSheet Parent style sheet.
     * @param cssText CSS code.
     * @returns Root element.
     */
    static parseFromString(parentStyleSheet, cssText) {
        const window = parentStyleSheet[PropertySymbol.window];
        const css = cssText.replace(COMMENT_REGEXP, '');
        const cssRules = [];
        const regExp = /{|}/gm;
        const stack = [];
        let parentRule = null;
        let lastIndex = 0;
        let match;
        while ((match = regExp.exec(css))) {
            if (match[0] === '{') {
                const selectorText = css.substring(lastIndex, match.index).trim();
                if (selectorText[0] === '@') {
                    const ruleParts = selectorText.split(' ');
                    const ruleType = ruleParts[0];
                    const ruleParameters = ruleParts.slice(1).join(' ').trim();
                    switch (ruleType) {
                        case '@keyframes':
                        case '@-webkit-keyframes':
                            const keyframesRule = new CSSKeyframesRule_js_1.default(PropertySymbol.illegalConstructor, window);
                            keyframesRule.name = ruleParameters;
                            keyframesRule.parentStyleSheet = parentStyleSheet;
                            if (parentRule) {
                                if (parentRule.type === CSSRuleTypeEnum_js_1.default.mediaRule ||
                                    parentRule.type === CSSRuleTypeEnum_js_1.default.containerRule ||
                                    parentRule.type === CSSRuleTypeEnum_js_1.default.supportsRule) {
                                    parentRule.cssRules.push(keyframesRule);
                                }
                            }
                            else {
                                cssRules.push(keyframesRule);
                            }
                            parentRule = keyframesRule;
                            break;
                        case '@media':
                            const mediums = ruleParameters.split(',');
                            const mediaRule = new CSSMediaRule_js_1.default(PropertySymbol.illegalConstructor, window);
                            for (const medium of mediums) {
                                mediaRule.media.appendMedium(medium.trim());
                            }
                            mediaRule.parentStyleSheet = parentStyleSheet;
                            if (parentRule) {
                                if (parentRule.type === CSSRuleTypeEnum_js_1.default.mediaRule ||
                                    parentRule.type === CSSRuleTypeEnum_js_1.default.containerRule ||
                                    parentRule.type === CSSRuleTypeEnum_js_1.default.supportsRule) {
                                    parentRule.cssRules.push(mediaRule);
                                }
                            }
                            else {
                                cssRules.push(mediaRule);
                            }
                            parentRule = mediaRule;
                            break;
                        case '@container':
                        case '@-webkit-container':
                            const containerRule = new CSSContainerRule_js_1.default(PropertySymbol.illegalConstructor, window);
                            containerRule.conditionText = ruleParameters;
                            containerRule.parentStyleSheet = parentStyleSheet;
                            if (parentRule) {
                                if (parentRule.type === CSSRuleTypeEnum_js_1.default.mediaRule ||
                                    parentRule.type === CSSRuleTypeEnum_js_1.default.containerRule ||
                                    parentRule.type === CSSRuleTypeEnum_js_1.default.supportsRule) {
                                    parentRule.cssRules.push(containerRule);
                                }
                            }
                            else {
                                cssRules.push(containerRule);
                            }
                            parentRule = containerRule;
                            break;
                        case '@supports':
                        case '@-webkit-supports':
                            const supportsRule = new CSSSupportsRule_js_1.default(PropertySymbol.illegalConstructor, window);
                            supportsRule.conditionText = ruleParameters;
                            supportsRule.parentStyleSheet = parentStyleSheet;
                            if (parentRule) {
                                if (parentRule.type === CSSRuleTypeEnum_js_1.default.mediaRule ||
                                    parentRule.type === CSSRuleTypeEnum_js_1.default.containerRule ||
                                    parentRule.type === CSSRuleTypeEnum_js_1.default.supportsRule) {
                                    parentRule.cssRules.push(supportsRule);
                                }
                            }
                            else {
                                cssRules.push(supportsRule);
                            }
                            parentRule = supportsRule;
                            break;
                        case '@font-face':
                            const fontFaceRule = new CSSFontFaceRule_js_1.default(PropertySymbol.illegalConstructor, window);
                            fontFaceRule[PropertySymbol.cssText] = ruleParameters;
                            fontFaceRule.parentStyleSheet = parentStyleSheet;
                            if (parentRule) {
                                if (parentRule.type === CSSRuleTypeEnum_js_1.default.mediaRule ||
                                    parentRule.type === CSSRuleTypeEnum_js_1.default.containerRule ||
                                    parentRule.type === CSSRuleTypeEnum_js_1.default.supportsRule) {
                                    parentRule.cssRules.push(fontFaceRule);
                                }
                            }
                            else {
                                cssRules.push(fontFaceRule);
                            }
                            parentRule = fontFaceRule;
                            break;
                        default:
                            // Unknown rule.
                            // We will create a new rule to let it grab its content, but we will not add it to the cssRules array.
                            const newRule = new CSSRule_js_1.default(PropertySymbol.illegalConstructor, window);
                            newRule.parentStyleSheet = parentStyleSheet;
                            parentRule = newRule;
                            break;
                    }
                }
                else if (parentRule && parentRule.type === CSSRuleTypeEnum_js_1.default.keyframesRule) {
                    const newRule = new CSSKeyframeRule_js_1.default(PropertySymbol.illegalConstructor, window);
                    newRule.keyText = selectorText.trim();
                    newRule.parentStyleSheet = parentStyleSheet;
                    newRule.parentRule = parentRule;
                    parentRule.cssRules.push(newRule);
                    parentRule = newRule;
                }
                else if (parentRule &&
                    (parentRule.type === CSSRuleTypeEnum_js_1.default.mediaRule ||
                        parentRule.type === CSSRuleTypeEnum_js_1.default.containerRule ||
                        parentRule.type === CSSRuleTypeEnum_js_1.default.supportsRule)) {
                    if (this.validateSelectorText(selectorText)) {
                        const newRule = new CSSStyleRule_js_1.default(PropertySymbol.illegalConstructor, window);
                        newRule.selectorText = selectorText;
                        newRule.parentStyleSheet = parentStyleSheet;
                        newRule.parentRule = parentRule;
                        parentRule.cssRules.push(newRule);
                        parentRule = newRule;
                    }
                }
                else {
                    if (this.validateSelectorText(selectorText)) {
                        const newRule = new CSSStyleRule_js_1.default(PropertySymbol.illegalConstructor, window);
                        newRule.selectorText = selectorText;
                        newRule.parentStyleSheet = parentStyleSheet;
                        newRule.parentRule = parentRule;
                        if (!parentRule) {
                            cssRules.push(newRule);
                        }
                        parentRule = newRule;
                    }
                }
                stack.push(parentRule);
            }
            else {
                if (parentRule) {
                    const cssText = css
                        .substring(lastIndex, match.index)
                        .trim()
                        .replace(/([^;])$/, '$1;'); // Ensure last semicolon
                    switch (parentRule.type) {
                        case CSSRuleTypeEnum_js_1.default.fontFaceRule:
                        case CSSRuleTypeEnum_js_1.default.keyframeRule:
                        case CSSRuleTypeEnum_js_1.default.styleRule:
                            parentRule[PropertySymbol.cssText] = cssText;
                            break;
                    }
                }
                stack.pop();
                parentRule = stack[stack.length - 1] || null;
            }
            lastIndex = match.index + 1;
        }
        return cssRules;
    }
    /**
     * Validates a selector text.
     *
     * @see https://www.w3.org/TR/CSS21/syndata.html#rule-sets
     * @param selectorText Selector text.
     * @returns True if valid, false otherwise.
     */
    static validateSelectorText(selectorText) {
        try {
            SelectorParser_js_1.default.getSelectorGroups(selectorText);
        }
        catch (e) {
            return false;
        }
        return true;
    }
}
exports.default = CSSParser;
//# sourceMappingURL=CSSParser.cjs.map
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
const CSSStyleDeclaration_js_1 = __importDefault(require("../declaration/CSSStyleDeclaration.cjs"));
const CSSKeyframeRule_js_1 = __importDefault(require("./CSSKeyframeRule.cjs"));
const PropertySymbol = __importStar(require("../../PropertySymbol.cjs"));
const CSSRuleTypeEnum_js_1 = __importDefault(require("../CSSRuleTypeEnum.cjs"));
const CSS_RULE_REGEXP = /([^{]+){([^}]+)}/;
/**
 * CSSRule interface.
 */
class CSSKeyframesRule extends CSSRule_js_1.default {
    type = CSSRuleTypeEnum_js_1.default.keyframesRule;
    cssRules = [];
    name = null;
    /**
     * Returns css text.
     *
     * @returns CSS text.
     */
    get cssText() {
        let cssText = '';
        for (const cssRule of this.cssRules) {
            cssText += cssRule.cssText + ' ';
        }
        return `@keyframes ${this.name} { ${cssText}}`;
    }
    /**
     * Appends a rule.
     *
     * @param rule Rule. E.g. "0% { transform: rotate(360deg); }".
     */
    appendRule(rule) {
        const match = rule.match(CSS_RULE_REGEXP);
        if (match) {
            const cssRule = new CSSKeyframeRule_js_1.default(PropertySymbol.illegalConstructor, this[PropertySymbol.window]);
            const style = new CSSStyleDeclaration_js_1.default(PropertySymbol.illegalConstructor, this[PropertySymbol.window]);
            cssRule.parentRule = this;
            cssRule.keyText = match[1].trim();
            style.cssText = match[2].trim();
            style.parentRule = this;
            cssRule.style = style;
        }
    }
    /**
     * Removes a rule.
     *
     * @param rule Rule. E.g. "0%".
     */
    deleteRule(rule) {
        for (let i = 0, max = this.cssRules.length; i < max; i++) {
            if (this.cssRules[i].keyText === rule) {
                this.cssRules.splice(i, 1);
                break;
            }
        }
    }
}
exports.default = CSSKeyframesRule;
//# sourceMappingURL=CSSKeyframesRule.cjs.map
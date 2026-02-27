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
const CSSStyleDeclaration_js_1 = __importDefault(require("../declaration/CSSStyleDeclaration.cjs"));
const CSSRuleTypeEnum_js_1 = __importDefault(require("../CSSRuleTypeEnum.cjs"));
/**
 * CSSRule interface.
 */
class CSSFontFaceRule extends CSSRule_js_1.default {
    type = CSSRuleTypeEnum_js_1.default.fontFaceRule;
    [PropertySymbol.cssText] = '';
    #style = null;
    /**
     * Returns style.
     *
     * @returns Style.
     */
    get style() {
        if (!this.#style) {
            this.#style = new CSSStyleDeclaration_js_1.default(PropertySymbol.illegalConstructor, this[PropertySymbol.window]);
            this.#style.parentRule = this;
            this.#style.cssText = this[PropertySymbol.cssText];
        }
        return this.#style;
    }
    /**
     * Returns css text.
     *
     * @returns CSS text.
     */
    get cssText() {
        return `@font-face { ${this.style.cssText} }`;
    }
}
exports.default = CSSFontFaceRule;
//# sourceMappingURL=CSSFontFaceRule.cjs.map
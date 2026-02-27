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
const SVGElement_js_1 = __importDefault(require("../svg-element/SVGElement.cjs"));
const SVGStringList_js_1 = __importDefault(require("../../svg/SVGStringList.cjs"));
const PropertySymbol = __importStar(require("../../PropertySymbol.cjs"));
const ElementEventAttributeUtility_js_1 = __importDefault(require("../element/ElementEventAttributeUtility.cjs"));
/**
 * SVG Animation Element.
 *
 * @see https://developer.mozilla.org/en-US/docs/Web/API/SVGAnimationElement
 */
class SVGAnimationElement extends SVGElement_js_1.default {
    // Internal properties
    [PropertySymbol.requiredExtensions] = null;
    [PropertySymbol.systemLanguage] = null;
    // Events
    /* eslint-disable jsdoc/require-jsdoc */
    get onbegin() {
        return ElementEventAttributeUtility_js_1.default.getEventListener(this, 'onbegin');
    }
    set onbegin(value) {
        this[PropertySymbol.propertyEventListeners].set('onbegin', value);
    }
    get onend() {
        return ElementEventAttributeUtility_js_1.default.getEventListener(this, 'onend');
    }
    set onend(value) {
        this[PropertySymbol.propertyEventListeners].set('onend', value);
    }
    get onrepeat() {
        return ElementEventAttributeUtility_js_1.default.getEventListener(this, 'onrepeat');
    }
    set onrepeat(value) {
        this[PropertySymbol.propertyEventListeners].set('onrepeat', value);
    }
    /* eslint-enable jsdoc/require-jsdoc */
    /**
     * Returns required extensions.
     *
     * @returns Required extensions.
     */
    get requiredExtensions() {
        if (!this[PropertySymbol.requiredExtensions]) {
            this[PropertySymbol.requiredExtensions] = new SVGStringList_js_1.default(PropertySymbol.illegalConstructor, this[PropertySymbol.window], {
                getAttribute: () => this.getAttribute('requiredExtensions'),
                setAttribute: (value) => this.setAttribute('requiredExtensions', value)
            });
        }
        return this[PropertySymbol.requiredExtensions];
    }
    /**
     * Returns system language.
     *
     * @returns System language.
     */
    get systemLanguage() {
        if (!this[PropertySymbol.systemLanguage]) {
            this[PropertySymbol.systemLanguage] = new SVGStringList_js_1.default(PropertySymbol.illegalConstructor, this[PropertySymbol.window], {
                getAttribute: () => this.getAttribute('systemLanguage'),
                setAttribute: (value) => this.setAttribute('systemLanguage', value)
            });
        }
        return this[PropertySymbol.systemLanguage];
    }
    /**
     * Returns target element.
     *
     * @returns Target element.
     */
    get targetElement() {
        // TODO: Implement targetElement
        return null;
    }
}
exports.default = SVGAnimationElement;
//# sourceMappingURL=SVGAnimationElement.cjs.map
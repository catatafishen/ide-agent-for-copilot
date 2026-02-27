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
const ElementEventAttributeUtility_js_1 = __importDefault(require("../element/ElementEventAttributeUtility.cjs"));
const HTMLElement_js_1 = __importDefault(require("../html-element/HTMLElement.cjs"));
const PropertySymbol = __importStar(require("../../PropertySymbol.cjs"));
/**
 * HTMLBodyElement
 *
 * @see https://developer.mozilla.org/en-US/docs/Web/API/HTMLBodyElement
 */
class HTMLBodyElement extends HTMLElement_js_1.default {
    // Events
    /* eslint-disable jsdoc/require-jsdoc */
    get onafterprint() {
        return ElementEventAttributeUtility_js_1.default.getEventListener(this, 'onafterprint');
    }
    set onafterprint(value) {
        this[PropertySymbol.propertyEventListeners].set('onafterprint', value);
    }
    get onbeforeprint() {
        return ElementEventAttributeUtility_js_1.default.getEventListener(this, 'onbeforeprint');
    }
    set onbeforeprint(value) {
        this[PropertySymbol.propertyEventListeners].set('onbeforeprint', value);
    }
    get onbeforeunload() {
        return ElementEventAttributeUtility_js_1.default.getEventListener(this, 'onbeforeunload');
    }
    set onbeforeunload(value) {
        this[PropertySymbol.propertyEventListeners].set('onbeforeunload', value);
    }
    get ongamepadconnected() {
        return ElementEventAttributeUtility_js_1.default.getEventListener(this, 'ongamepadconnected');
    }
    set ongamepadconnected(value) {
        this[PropertySymbol.propertyEventListeners].set('ongamepadconnected', value);
    }
    get ongamepaddisconnected() {
        return ElementEventAttributeUtility_js_1.default.getEventListener(this, 'ongamepaddisconnected');
    }
    set ongamepaddisconnected(value) {
        this[PropertySymbol.propertyEventListeners].set('ongamepaddisconnected', value);
    }
    get onhashchange() {
        return ElementEventAttributeUtility_js_1.default.getEventListener(this, 'onhashchange');
    }
    set onhashchange(value) {
        this[PropertySymbol.propertyEventListeners].set('onhashchange', value);
    }
    get onlanguagechange() {
        return ElementEventAttributeUtility_js_1.default.getEventListener(this, 'onlanguagechange');
    }
    set onlanguagechange(value) {
        this[PropertySymbol.propertyEventListeners].set('onlanguagechange', value);
    }
    get onmessage() {
        return ElementEventAttributeUtility_js_1.default.getEventListener(this, 'onmessage');
    }
    set onmessage(value) {
        this[PropertySymbol.propertyEventListeners].set('onmessage', value);
    }
    get onmessageerror() {
        return ElementEventAttributeUtility_js_1.default.getEventListener(this, 'onmessageerror');
    }
    set onmessageerror(value) {
        this[PropertySymbol.propertyEventListeners].set('onmessageerror', value);
    }
    get onoffline() {
        return ElementEventAttributeUtility_js_1.default.getEventListener(this, 'onoffline');
    }
    set onoffline(value) {
        this[PropertySymbol.propertyEventListeners].set('onoffline', value);
    }
    get ononline() {
        return ElementEventAttributeUtility_js_1.default.getEventListener(this, 'ononline');
    }
    set ononline(value) {
        this[PropertySymbol.propertyEventListeners].set('ononline', value);
    }
    get onpagehide() {
        return ElementEventAttributeUtility_js_1.default.getEventListener(this, 'onpagehide');
    }
    set onpagehide(value) {
        this[PropertySymbol.propertyEventListeners].set('onpagehide', value);
    }
    get onpageshow() {
        return ElementEventAttributeUtility_js_1.default.getEventListener(this, 'onpageshow');
    }
    set onpageshow(value) {
        this[PropertySymbol.propertyEventListeners].set('onpageshow', value);
    }
    get onpopstate() {
        return ElementEventAttributeUtility_js_1.default.getEventListener(this, 'onpopstate');
    }
    set onpopstate(value) {
        this[PropertySymbol.propertyEventListeners].set('onpopstate', value);
    }
    get onrejectionhandled() {
        return ElementEventAttributeUtility_js_1.default.getEventListener(this, 'onrejectionhandled');
    }
    set onrejectionhandled(value) {
        this[PropertySymbol.propertyEventListeners].set('onrejectionhandled', value);
    }
    get onstorage() {
        return ElementEventAttributeUtility_js_1.default.getEventListener(this, 'onstorage');
    }
    set onstorage(value) {
        this[PropertySymbol.propertyEventListeners].set('onstorage', value);
    }
    get onunhandledrejection() {
        return ElementEventAttributeUtility_js_1.default.getEventListener(this, 'onunhandledrejection');
    }
    set onunhandledrejection(value) {
        this[PropertySymbol.propertyEventListeners].set('onunhandledrejection', value);
    }
    get onunload() {
        return ElementEventAttributeUtility_js_1.default.getEventListener(this, 'onunload');
    }
    set onunload(value) {
        this[PropertySymbol.propertyEventListeners].set('onunload', value);
    }
}
exports.default = HTMLBodyElement;
//# sourceMappingURL=HTMLBodyElement.cjs.map
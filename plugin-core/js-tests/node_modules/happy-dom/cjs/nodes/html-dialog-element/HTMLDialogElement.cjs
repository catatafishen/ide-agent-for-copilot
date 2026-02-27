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
const Event_js_1 = __importDefault(require("../../event/Event.cjs"));
const HTMLElement_js_1 = __importDefault(require("../html-element/HTMLElement.cjs"));
const PropertySymbol = __importStar(require("../../PropertySymbol.cjs"));
const ElementEventAttributeUtility_js_1 = __importDefault(require("../element/ElementEventAttributeUtility.cjs"));
/**
 * HTML Dialog Element.
 *
 * @see https://developer.mozilla.org/en-US/docs/Web/API/HTMLDialogElement
 */
class HTMLDialogElement extends HTMLElement_js_1.default {
    // Internal properties
    [PropertySymbol.returnValue] = '';
    // Events
    /* eslint-disable jsdoc/require-jsdoc */
    get oncancel() {
        return ElementEventAttributeUtility_js_1.default.getEventListener(this, 'oncancel');
    }
    set oncancel(value) {
        this[PropertySymbol.propertyEventListeners].set('oncancel', value);
    }
    get onclose() {
        return ElementEventAttributeUtility_js_1.default.getEventListener(this, 'onclose');
    }
    set onclose(value) {
        this[PropertySymbol.propertyEventListeners].set('onclose', value);
    }
    /* eslint-enable jsdoc/require-jsdoc */
    /**
     * Returns return value.
     *
     * @returns Return value.
     */
    get returnValue() {
        return this[PropertySymbol.returnValue];
    }
    /**
     * Sets return value.
     *
     * @param value Return value.
     */
    set returnValue(value) {
        this[PropertySymbol.returnValue] = String(value);
    }
    /**
     * Sets the "open" attribute.
     *
     * @param open Open.
     */
    set open(open) {
        if (open) {
            this.setAttribute('open', '');
        }
        else {
            this.removeAttribute('open');
        }
    }
    /**
     * Returns open.
     *
     * @returns Open.
     */
    get open() {
        return this.getAttribute('open') !== null;
    }
    /**
     * Closes the dialog.
     *
     * @param [returnValue] ReturnValue.
     */
    close(returnValue) {
        const wasOpen = this.open;
        this.removeAttribute('open');
        this.returnValue = returnValue !== undefined ? String(returnValue) : '';
        if (wasOpen) {
            this.dispatchEvent(new Event_js_1.default('close'));
        }
    }
    /**
     * Shows the modal.
     */
    showModal() {
        this.setAttribute('open', '');
    }
    /**
     * Shows the dialog.
     */
    show() {
        this.setAttribute('open', '');
    }
}
exports.default = HTMLDialogElement;
//# sourceMappingURL=HTMLDialogElement.cjs.map
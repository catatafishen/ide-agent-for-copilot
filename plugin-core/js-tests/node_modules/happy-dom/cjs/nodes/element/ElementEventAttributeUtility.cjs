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
const BrowserErrorCaptureEnum_js_1 = __importDefault(require("../../browser/enums/BrowserErrorCaptureEnum.cjs"));
const WindowBrowserContext_js_1 = __importDefault(require("../../window/WindowBrowserContext.cjs"));
const PropertySymbol = __importStar(require("../../PropertySymbol.cjs"));
/**
 * ECMAScript module compiler.
 */
class ElementEventAttributeUtility {
    /**
     * Evaluates code in attribute and returns event listener.
     *
     * @param element
     * @param property Property.
     * @returns Result.
     */
    static getEventListener(element, property) {
        const cached = element[PropertySymbol.propertyEventListeners].get(property);
        if (cached) {
            return cached;
        }
        const window = element[PropertySymbol.ownerDocument][PropertySymbol.defaultView];
        const browserSettings = new WindowBrowserContext_js_1.default(window).getSettings();
        if (!browserSettings) {
            return null;
        }
        const code = element.getAttribute(property);
        if (!code) {
            return null;
        }
        let newCode = `(function anonymous($happy_dom, event) {\n//# sourceURL=${window.location.href}\n`;
        if (browserSettings &&
            !browserSettings.disableErrorCapturing &&
            browserSettings.errorCapture === BrowserErrorCaptureEnum_js_1.default.tryAndCatch) {
            newCode += 'try {\n';
        }
        newCode += code;
        if (browserSettings &&
            !browserSettings.disableErrorCapturing &&
            browserSettings.errorCapture === BrowserErrorCaptureEnum_js_1.default.tryAndCatch) {
            newCode += '\n} catch(e) { $happy_dom.dispatchError(e); }\n';
        }
        newCode += '})';
        let listener = null;
        try {
            listener = window.eval(newCode).bind(element, {
                dispatchError: window[PropertySymbol.dispatchError]
            });
        }
        catch (e) {
            const error = new window.SyntaxError(`Failed to read the '${property}' property from '${element.constructor.name}': ${e.message}`);
            if (browserSettings.disableErrorCapturing ||
                browserSettings.errorCapture !== BrowserErrorCaptureEnum_js_1.default.tryAndCatch) {
                throw error;
            }
            else {
                window[PropertySymbol.dispatchError](error);
                return null;
            }
        }
        if (listener) {
            element[PropertySymbol.propertyEventListeners].set(property, listener);
        }
        return listener;
    }
}
exports.default = ElementEventAttributeUtility;
//# sourceMappingURL=ElementEventAttributeUtility.cjs.map
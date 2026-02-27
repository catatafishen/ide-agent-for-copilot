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
const PropertySymbol = __importStar(require("../../PropertySymbol.cjs"));
const HTMLElement_js_1 = __importDefault(require("../html-element/HTMLElement.cjs"));
const Event_js_1 = __importDefault(require("../../event/Event.cjs"));
const DOMTokenList_js_1 = __importDefault(require("../../dom/DOMTokenList.cjs"));
const DOMExceptionNameEnum_js_1 = __importDefault(require("../../exception/DOMExceptionNameEnum.cjs"));
const ResourceFetch_js_1 = __importDefault(require("../../fetch/ResourceFetch.cjs"));
const WindowBrowserContext_js_1 = __importDefault(require("../../window/WindowBrowserContext.cjs"));
const Fetch_js_1 = __importDefault(require("../../fetch/Fetch.cjs"));
const BrowserErrorCaptureEnum_js_1 = __importDefault(require("../../browser/enums/BrowserErrorCaptureEnum.cjs"));
const ModuleFactory_js_1 = __importDefault(require("../../module/ModuleFactory.cjs"));
const PreloadUtility_js_1 = __importDefault(require("../../fetch/preload/PreloadUtility.cjs"));
const PreloadEntry_js_1 = __importDefault(require("../../fetch/preload/PreloadEntry.cjs"));
const ElementEventAttributeUtility_js_1 = __importDefault(require("../element/ElementEventAttributeUtility.cjs"));
/**
 * HTML Link Element.
 *
 * Reference:
 * https://developer.mozilla.org/en-US/docs/Web/API/HTMLLinkElement.
 */
class HTMLLinkElement extends HTMLElement_js_1.default {
    // Internal properties
    [PropertySymbol.sheet] = null;
    [PropertySymbol.evaluateCSS] = true;
    [PropertySymbol.relList] = null;
    #loadedStyleSheetURL = null;
    // Events
    /* eslint-disable jsdoc/require-jsdoc */
    get onerror() {
        return ElementEventAttributeUtility_js_1.default.getEventListener(this, 'onerror');
    }
    set onerror(value) {
        this[PropertySymbol.propertyEventListeners].set('onerror', value);
    }
    get onload() {
        return ElementEventAttributeUtility_js_1.default.getEventListener(this, 'onload');
    }
    set onload(value) {
        this[PropertySymbol.propertyEventListeners].set('onload', value);
    }
    /* eslint-enable jsdoc/require-jsdoc */
    /**
     * Returns sheet.
     */
    get sheet() {
        return this[PropertySymbol.sheet];
    }
    /**
     * Returns rel list.
     *
     * @returns Rel list.
     */
    get relList() {
        if (!this[PropertySymbol.relList]) {
            this[PropertySymbol.relList] = new DOMTokenList_js_1.default(PropertySymbol.illegalConstructor, this, 'rel', ['stylesheet', 'modulepreload', 'preload']);
        }
        return this[PropertySymbol.relList];
    }
    /**
     * Sets rel list.
     *
     * @param value Value.
     */
    set relList(value) {
        this.setAttribute('rel', value);
    }
    /**
     * Returns as.
     *
     * @returns As.
     */
    get as() {
        return this.getAttribute('as') || '';
    }
    /**
     * Sets as.
     *
     * @param as As.
     */
    set as(as) {
        this.setAttribute('as', as);
    }
    /**
     * Returns crossOrigin.
     *
     * @returns CrossOrigin.
     */
    get crossOrigin() {
        return this.getAttribute('crossorigin') || '';
    }
    /**
     * Sets crossOrigin.
     *
     * @param crossOrigin CrossOrigin.
     */
    set crossOrigin(crossOrigin) {
        this.setAttribute('crossorigin', crossOrigin);
    }
    /**
     * Returns href.
     *
     * @returns Href.
     */
    get href() {
        if (!this.hasAttribute('href')) {
            return '';
        }
        try {
            return new URL(this.getAttribute('href'), this[PropertySymbol.ownerDocument].location.href)
                .href;
        }
        catch (e) {
            return this.getAttribute('href');
        }
    }
    /**
     * Sets href.
     *
     * @param href Href.
     */
    set href(href) {
        this.setAttribute('href', href);
    }
    /**
     * Returns hreflang.
     *
     * @returns Hreflang.
     */
    get hreflang() {
        return this.getAttribute('hreflang') || '';
    }
    /**
     * Sets hreflang.
     *
     * @param hreflang Hreflang.
     */
    set hreflang(hreflang) {
        this.setAttribute('hreflang', hreflang);
    }
    /**
     * Returns media.
     *
     * @returns Media.
     */
    get media() {
        return this.getAttribute('media') || '';
    }
    /**
     * Sets media.
     *
     * @param media Media.
     */
    set media(media) {
        this.setAttribute('media', media);
    }
    /**
     * Returns referrerPolicy.
     *
     * @returns ReferrerPolicy.
     */
    get referrerPolicy() {
        return this.getAttribute('referrerPolicy') || '';
    }
    /**
     * Sets referrerPolicy.
     *
     * @param referrerPolicy ReferrerPolicy.
     */
    set referrerPolicy(referrerPolicy) {
        this.setAttribute('referrerPolicy', referrerPolicy);
    }
    /**
     * Returns rel.
     *
     * @returns Rel.
     */
    get rel() {
        return this.getAttribute('rel') || '';
    }
    /**
     * Sets rel.
     *
     * @param rel Rel.
     */
    set rel(rel) {
        this.setAttribute('rel', rel);
    }
    /**
     * Returns type.
     *
     * @returns Type.
     */
    get type() {
        return this.getAttribute('type') || '';
    }
    /**
     * Sets type.
     *
     * @param type Type.
     */
    set type(type) {
        this.setAttribute('type', type);
    }
    /**
     * @override
     */
    [PropertySymbol.connectedToDocument]() {
        super[PropertySymbol.connectedToDocument]();
        const rel = this.getAttribute('rel');
        const href = this.getAttribute('href');
        if (rel && href) {
            switch (rel) {
                case 'stylesheet':
                    this.#loadStyleSheet(href);
                    break;
                case 'modulepreload':
                    this.#preloadModule(href);
                    break;
                case 'preload':
                    this.#preloadResource(href);
                    break;
            }
        }
    }
    /**
     * @override
     */
    [PropertySymbol.onSetAttribute](attribute, replacedAttribute) {
        super[PropertySymbol.onSetAttribute](attribute, replacedAttribute);
        if (attribute[PropertySymbol.name] === 'rel' || attribute[PropertySymbol.name] === 'href') {
            const rel = this.getAttribute('rel');
            const href = this.getAttribute('href');
            if (rel && href) {
                switch (rel) {
                    case 'stylesheet':
                        this.#loadStyleSheet(href);
                        break;
                    case 'modulepreload':
                        this.#preloadModule(href);
                        break;
                    case 'preload':
                        this.#preloadResource(href);
                        break;
                }
            }
        }
    }
    /**
     * Preloads a module.
     *
     * @param url URL.
     */
    async #preloadModule(url) {
        const absoluteURL = new URL(url, this[PropertySymbol.ownerDocument].location.href);
        const window = this[PropertySymbol.window];
        const browserFrame = new WindowBrowserContext_js_1.default(window).getBrowserFrame();
        const browserSettings = new WindowBrowserContext_js_1.default(window).getSettings();
        if (!browserSettings ||
            !this[PropertySymbol.isConnected] ||
            browserSettings.disableJavaScriptFileLoading ||
            browserSettings.disableJavaScriptEvaluation) {
            return;
        }
        if (browserSettings.disableErrorCapturing ||
            browserSettings.errorCapture !== BrowserErrorCaptureEnum_js_1.default.tryAndCatch) {
            const module = await ModuleFactory_js_1.default.getModule(window, absoluteURL, url);
            await module.preload();
        }
        else {
            try {
                const module = await ModuleFactory_js_1.default.getModule(window, absoluteURL, url);
                await module.preload();
            }
            catch (error) {
                browserFrame.page?.console.error(error);
                window[PropertySymbol.dispatchError](error);
                return;
            }
        }
    }
    /**
     * Preloads a resource.
     *
     * @param url URL.
     */
    async #preloadResource(url) {
        const window = this[PropertySymbol.window];
        const browserFrame = new WindowBrowserContext_js_1.default(window).getBrowserFrame();
        const as = this.as;
        // Only "script", "style" and "fetch" are supported for now.
        if (!browserFrame ||
            !this[PropertySymbol.isConnected] ||
            (as !== 'script' && as !== 'style' && as !== 'fetch')) {
            return;
        }
        const browserSettings = browserFrame.page?.context?.browser?.settings;
        if (as === 'script' &&
            (browserSettings.disableJavaScriptFileLoading || browserSettings.disableJavaScriptEvaluation)) {
            return;
        }
        if (as === 'style' && browserSettings.disableCSSFileLoading) {
            return;
        }
        const absoluteURL = new URL(url, window.location.href).href;
        const preloadKey = PreloadUtility_js_1.default.getKey({
            url: absoluteURL,
            destination: as,
            mode: 'cors',
            credentialsMode: this.crossOrigin === 'use-credentials' ? 'include' : 'same-origin'
        });
        if (window.document[PropertySymbol.preloads].has(preloadKey)) {
            return;
        }
        const preloadEntry = new PreloadEntry_js_1.default();
        window.document[PropertySymbol.preloads].set(preloadKey, preloadEntry);
        const fetch = new Fetch_js_1.default({
            browserFrame,
            window,
            url: absoluteURL,
            disableSameOriginPolicy: as === 'script' || as === 'style',
            disablePreload: true,
            init: {
                credentials: this.crossOrigin === 'use-credentials' ? 'include' : 'same-origin'
            }
        });
        try {
            const response = await fetch.send();
            if (!response[PropertySymbol.buffer]) {
                await response.buffer();
            }
            preloadEntry.responseAvailable(null, response);
        }
        catch (error) {
            preloadEntry.responseAvailable(error, null);
            window.document[PropertySymbol.preloads].delete(preloadKey);
            browserFrame.page?.console?.error(`Failed to preload resource "${absoluteURL}": ${error.message}`);
        }
    }
    /**
     * Returns a URL relative to the given Location object.
     *
     * @param url URL.
     * @param rel Rel.
     */
    async #loadStyleSheet(url) {
        const window = this[PropertySymbol.window];
        const browserFrame = new WindowBrowserContext_js_1.default(window).getBrowserFrame();
        if (!browserFrame) {
            return;
        }
        const browserSettings = browserFrame.page?.context?.browser?.settings;
        if (!this[PropertySymbol.evaluateCSS] || !this[PropertySymbol.isConnected]) {
            return;
        }
        let absoluteURL;
        try {
            absoluteURL = new URL(url, window.location.href).href;
        }
        catch (error) {
            return;
        }
        if (this.#loadedStyleSheetURL === absoluteURL) {
            return;
        }
        if (browserSettings && browserSettings.disableCSSFileLoading) {
            if (browserSettings.handleDisabledFileLoadingAsSuccess) {
                this.dispatchEvent(new Event_js_1.default('load'));
            }
            else {
                const error = new window.DOMException(`Failed to load external stylesheet "${absoluteURL}". CSS file loading is disabled.`, DOMExceptionNameEnum_js_1.default.notSupportedError);
                browserFrame.page?.console.error(error);
                this.dispatchEvent(new Event_js_1.default('error'));
            }
            return;
        }
        const resourceFetch = new ResourceFetch_js_1.default(window);
        const readyStateManager = window[PropertySymbol.readyStateManager];
        this.#loadedStyleSheetURL = absoluteURL;
        readyStateManager.startTask();
        let code = null;
        let error = null;
        try {
            code = await resourceFetch.fetch(absoluteURL, 'style', {
                credentials: this.crossOrigin === 'use-credentials' ? 'include' : 'same-origin'
            });
        }
        catch (e) {
            error = e;
        }
        readyStateManager.endTask();
        if (error) {
            browserFrame.page?.console.error(error);
            this.dispatchEvent(new Event_js_1.default('error'));
        }
        else {
            const styleSheet = new this[PropertySymbol.ownerDocument][PropertySymbol.window].CSSStyleSheet();
            styleSheet.replaceSync(code);
            this[PropertySymbol.sheet] = styleSheet;
            // Computed style cache is affected by all mutations.
            const document = this[PropertySymbol.ownerDocument];
            if (document) {
                for (const item of document[PropertySymbol.affectsComputedStyleCache]) {
                    item.result = null;
                }
                document[PropertySymbol.affectsComputedStyleCache] = [];
            }
            this.dispatchEvent(new Event_js_1.default('load'));
        }
    }
}
exports.default = HTMLLinkElement;
//# sourceMappingURL=HTMLLinkElement.cjs.map
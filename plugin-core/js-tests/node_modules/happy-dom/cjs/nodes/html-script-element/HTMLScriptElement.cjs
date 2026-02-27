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
const HTMLElement_js_1 = __importDefault(require("../html-element/HTMLElement.cjs"));
const PropertySymbol = __importStar(require("../../PropertySymbol.cjs"));
const Event_js_1 = __importDefault(require("../../event/Event.cjs"));
const WindowBrowserContext_js_1 = __importDefault(require("../../window/WindowBrowserContext.cjs"));
const BrowserErrorCaptureEnum_js_1 = __importDefault(require("../../browser/enums/BrowserErrorCaptureEnum.cjs"));
const DOMExceptionNameEnum_js_1 = __importDefault(require("../../exception/DOMExceptionNameEnum.cjs"));
const ResourceFetch_js_1 = __importDefault(require("../../fetch/ResourceFetch.cjs"));
const ECMAScriptModule_js_1 = __importDefault(require("../../module/ECMAScriptModule.cjs"));
const ModuleFactory_js_1 = __importDefault(require("../../module/ModuleFactory.cjs"));
const DOMTokenList_js_1 = __importDefault(require("../../dom/DOMTokenList.cjs"));
const ElementEventAttributeUtility_js_1 = __importDefault(require("../element/ElementEventAttributeUtility.cjs"));
/**
 * HTML Script Element.
 *
 * Reference:
 * https://developer.mozilla.org/en-US/docs/Web/API/HTMLScriptElement.
 */
class HTMLScriptElement extends HTMLElement_js_1.default {
    // Internal properties
    [PropertySymbol.evaluateScript] = true;
    [PropertySymbol.blocking] = null;
    // Private properties
    #loadedScriptURL = null;
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
     * Returns blocking.
     */
    get blocking() {
        if (!this[PropertySymbol.blocking]) {
            this[PropertySymbol.blocking] = new DOMTokenList_js_1.default(PropertySymbol.illegalConstructor, this, 'blocking');
        }
        return this[PropertySymbol.blocking];
    }
    /**
     * Sets blocking.
     *
     * @param value Value.
     */
    set blocking(value) {
        this.setAttribute('blocking', value);
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
     * Returns fetch priority.
     *
     * @returns Fetch priority.
     */
    get fetchPriority() {
        const fetchPriority = this.getAttribute('fetchpriority');
        switch (fetchPriority) {
            case 'high':
            case 'low':
            case 'normal':
                return fetchPriority;
            default:
                return 'auto';
        }
    }
    /**
     * Sets fetch priority.
     *
     * @param fetchPriority Fetch priority.
     */
    set fetchPriority(fetchPriority) {
        this.setAttribute('fetchpriority', fetchPriority);
    }
    /**
     * Returns noModule.
     *
     * @returns NoModule.
     */
    get noModule() {
        return this.getAttribute('nomodule') !== null;
    }
    /**
     * Sets noModule.
     *
     * @param noModule NoModule.
     */
    set noModule(noModule) {
        if (noModule) {
            this.setAttribute('nomodule', '');
        }
        else {
            this.removeAttribute('nomodule');
        }
    }
    /**
     * Returns integrity.
     *
     * @returns Integrity.
     */
    get integrity() {
        return this.getAttribute('integrity') || '';
    }
    /**
     * Sets integrity.
     *
     * @param integrity Integrity.
     */
    set integrity(integrity) {
        this.setAttribute('integrity', integrity);
    }
    /**
     * Returns referrerPolicy.
     *
     * @returns ReferrerPolicy.
     */
    get referrerPolicy() {
        const referrerPolicy = this.getAttribute('referrerpolicy');
        switch (referrerPolicy) {
            case 'no-referrer':
            case 'no-referrer-when-downgrade':
            case 'same-origin':
            case 'origin':
            case 'strict-origin':
            case 'origin-when-cross-origin':
            case 'strict-origin-when-cross-origin':
            case 'unsafe-url':
                return referrerPolicy;
            default:
                return '';
        }
    }
    /**
     * Sets referrerPolicy.
     *
     * @param referrerPolicy ReferrerPolicy.
     */
    set referrerPolicy(referrerPolicy) {
        this.setAttribute('referrerpolicy', referrerPolicy);
    }
    /**
     * Returns source.
     *
     * @returns Source.
     */
    get src() {
        if (!this.hasAttribute('src')) {
            return '';
        }
        try {
            return new URL(this.getAttribute('src'), this[PropertySymbol.ownerDocument].location.href)
                .href;
        }
        catch (e) {
            return this.getAttribute('src');
        }
    }
    /**
     * Sets source.
     *
     * @param src Source.
     */
    set src(src) {
        this.setAttribute('src', src);
    }
    /**
     * Returns charset.
     *
     * @returns Charset.
     */
    get charset() {
        return this.getAttribute('charset') || '';
    }
    /**
     * Sets charset.
     *
     * @param charset Charset.
     */
    set charset(charset) {
        this.setAttribute('charset', charset);
    }
    /**
     * Returns lang.
     *
     * @returns Lang.
     */
    get lang() {
        return this.getAttribute('lang') || '';
    }
    /**
     * Sets lang.
     *
     * @param lang Lang.
     */
    set lang(lang) {
        this.setAttribute('lang', lang);
    }
    /**
     * Returns async.
     *
     * @returns Async.
     */
    get async() {
        return this.getAttribute('async') !== null;
    }
    /**
     * Sets async.
     *
     * @param async Async.
     */
    set async(async) {
        if (!async) {
            this.removeAttribute('async');
        }
        else {
            this.setAttribute('async', '');
        }
    }
    /**
     * Returns defer.
     *
     * @returns Defer.
     */
    get defer() {
        return this.getAttribute('defer') !== null;
    }
    /**
     * Sets defer.
     *
     * @param defer Defer.
     */
    set defer(defer) {
        if (!defer) {
            this.removeAttribute('defer');
        }
        else {
            this.setAttribute('defer', '');
        }
    }
    /**
     * Returns text.
     *
     * @returns Text.
     */
    get text() {
        return this.textContent;
    }
    /**
     * Sets text.
     *
     * @param text Text.
     */
    set text(text) {
        this.textContent = text;
    }
    /**
     * @override
     */
    [PropertySymbol.cloneNode](deep = false) {
        return super[PropertySymbol.cloneNode](deep);
    }
    /**
     * @override
     */
    [PropertySymbol.connectedToDocument]() {
        const browserSettings = new WindowBrowserContext_js_1.default(this[PropertySymbol.window]).getSettings();
        super[PropertySymbol.connectedToDocument]();
        if (this[PropertySymbol.evaluateScript]) {
            const src = this.getAttribute('src');
            if (src !== null) {
                if (this.getAttribute('type') === 'module') {
                    this.#loadModule(src);
                }
                else {
                    this.#loadScript(src);
                }
            }
            else if (browserSettings && !browserSettings.disableJavaScriptEvaluation) {
                const source = this.textContent;
                const type = this.getAttribute('type');
                if (source) {
                    if (type === 'module') {
                        this.#evaluateModule(source);
                    }
                    else if (type === 'importmap') {
                        this.#evaluateImportMap(source);
                    }
                    else if (type === null ||
                        type === 'application/x-ecmascript' ||
                        type === 'application/x-javascript' ||
                        type.startsWith('text/javascript')) {
                        this.#evaluateScript(source);
                    }
                }
            }
        }
    }
    /**
     * @override
     */
    [PropertySymbol.onSetAttribute](attribute, replacedAttribute) {
        super[PropertySymbol.onSetAttribute](attribute, replacedAttribute);
        if (attribute[PropertySymbol.name] === 'src' &&
            attribute[PropertySymbol.value] !== null &&
            this[PropertySymbol.isConnected]) {
            if (this.getAttribute('type') === 'module') {
                this.#loadModule(attribute[PropertySymbol.value]);
            }
            else {
                this.#loadScript(attribute[PropertySymbol.value]);
            }
        }
    }
    /**
     * Evaluates a module.
     *
     * @param source Source.
     */
    async #evaluateModule(source) {
        const url = this[PropertySymbol.ownerDocument].location;
        const window = this[PropertySymbol.window];
        const browserSettings = new WindowBrowserContext_js_1.default(window).getSettings();
        const browserFrame = new WindowBrowserContext_js_1.default(window).getBrowserFrame();
        if (!browserFrame) {
            return;
        }
        const module = new ECMAScriptModule_js_1.default(window, url, source);
        const readyStateManager = window[PropertySymbol.readyStateManager];
        readyStateManager.startTask();
        if (browserSettings.disableErrorCapturing ||
            browserSettings.errorCapture !== BrowserErrorCaptureEnum_js_1.default.tryAndCatch) {
            await module.evaluate();
        }
        else {
            try {
                await module.evaluate();
            }
            catch (error) {
                window[PropertySymbol.dispatchError](error);
                return;
            }
        }
        readyStateManager.endTask();
        this.dispatchEvent(new Event_js_1.default('load'));
    }
    /**
     * Evaluates an import map.
     *
     * @param source Source.
     */
    async #evaluateImportMap(source) {
        const window = this[PropertySymbol.window];
        const browserSettings = new WindowBrowserContext_js_1.default(window).getSettings();
        const browserFrame = new WindowBrowserContext_js_1.default(window).getBrowserFrame();
        if (!browserFrame || window[PropertySymbol.moduleImportMap]) {
            return;
        }
        let json;
        if (browserSettings.disableErrorCapturing ||
            browserSettings.errorCapture !== BrowserErrorCaptureEnum_js_1.default.tryAndCatch) {
            json = JSON.parse(source);
        }
        else {
            try {
                json = JSON.parse(source);
            }
            catch (error) {
                window[PropertySymbol.dispatchError](error);
                return;
            }
        }
        if (json.imports || json.scopes) {
            const importMap = {
                imports: [],
                scopes: []
            };
            if (json.imports) {
                for (const key of Object.keys(json.imports)) {
                    importMap.imports.push({
                        from: key,
                        to: json.imports[key]
                    });
                }
            }
            if (json.scopes) {
                for (const scopeKey of Object.keys(json.scopes)) {
                    const scope = {
                        scope: scopeKey,
                        rules: []
                    };
                    for (const importKey of Object.keys(json.scopes[scopeKey])) {
                        const value = json.scopes[scopeKey][importKey];
                        scope.rules.push({
                            from: importKey,
                            to: value
                        });
                    }
                    importMap.scopes.push(scope);
                }
            }
            window[PropertySymbol.moduleImportMap] = importMap;
        }
    }
    /**
     * Evaluates a script.
     *
     * @param source Source.
     */
    #evaluateScript(source) {
        const window = this[PropertySymbol.window];
        const browserSettings = new WindowBrowserContext_js_1.default(window).getSettings();
        if (!browserSettings) {
            return;
        }
        this[PropertySymbol.ownerDocument][PropertySymbol.currentScript] = this;
        const code = `//# sourceURL=${this[PropertySymbol.ownerDocument].location.href}\n` + source;
        if (browserSettings.disableErrorCapturing ||
            browserSettings.errorCapture !== BrowserErrorCaptureEnum_js_1.default.tryAndCatch) {
            window.eval(code);
        }
        else {
            try {
                window.eval(code);
            }
            catch (error) {
                window[PropertySymbol.dispatchError](error);
            }
        }
        this[PropertySymbol.ownerDocument][PropertySymbol.currentScript] = null;
    }
    /**
     * Loads a module.
     *
     * @param url URL.
     */
    async #loadModule(url) {
        const window = this[PropertySymbol.window];
        const browserFrame = new WindowBrowserContext_js_1.default(window).getBrowserFrame();
        const browserSettings = new WindowBrowserContext_js_1.default(window).getSettings();
        if (!browserSettings) {
            return;
        }
        if (!url || !this[PropertySymbol.isConnected] || this.getAttribute('type') !== 'module') {
            return;
        }
        if (browserSettings &&
            (browserSettings.disableJavaScriptFileLoading || browserSettings.disableJavaScriptEvaluation)) {
            if (browserSettings.handleDisabledFileLoadingAsSuccess) {
                this.dispatchEvent(new Event_js_1.default('load'));
            }
            else {
                const error = new window.DOMException(`Failed to load module "${url}". JavaScript file loading is disabled.`, DOMExceptionNameEnum_js_1.default.notSupportedError);
                browserFrame.page?.console.error(error);
                this.dispatchEvent(new Event_js_1.default('error'));
            }
            return;
        }
        const readyStateManager = window[PropertySymbol.readyStateManager];
        readyStateManager.startTask();
        // TODO: What to do with "referrerPolicy" and "crossOrigin" for modules?
        // @see https://github.com/w3c/webappsec-referrer-policy/issues/111
        if (browserSettings.disableErrorCapturing ||
            browserSettings.errorCapture !== BrowserErrorCaptureEnum_js_1.default.tryAndCatch) {
            const module = await ModuleFactory_js_1.default.getModule(window, window.location, url);
            await module.evaluate();
        }
        else {
            try {
                const module = await ModuleFactory_js_1.default.getModule(window, window.location, url);
                await module.evaluate();
            }
            catch (error) {
                browserFrame.page?.console.error(error);
                this.dispatchEvent(new Event_js_1.default('error'));
                readyStateManager.endTask();
                return;
            }
        }
        readyStateManager.endTask();
        this.dispatchEvent(new Event_js_1.default('load'));
    }
    /**
     * Returns a URL relative to the given Location object.
     *
     * @param url URL.
     */
    async #loadScript(url) {
        const window = this[PropertySymbol.window];
        const browserFrame = new WindowBrowserContext_js_1.default(window).getBrowserFrame();
        if (!browserFrame) {
            return;
        }
        const browserSettings = browserFrame.page?.context?.browser?.settings;
        const type = this.getAttribute('type');
        if (!url ||
            !this[PropertySymbol.isConnected] ||
            (type !== null &&
                type !== 'application/x-ecmascript' &&
                type !== 'application/x-javascript' &&
                !type.startsWith('text/javascript'))) {
            return;
        }
        let absoluteURL;
        try {
            absoluteURL = new URL(url, window.location.href);
        }
        catch (error) {
            return;
        }
        const absoluteURLString = absoluteURL.toString();
        if (this.#loadedScriptURL === absoluteURLString) {
            return;
        }
        if (browserSettings &&
            (browserSettings.disableJavaScriptFileLoading || browserSettings.disableJavaScriptEvaluation)) {
            if (browserSettings.handleDisabledFileLoadingAsSuccess) {
                this.dispatchEvent(new Event_js_1.default('load'));
            }
            else {
                const error = new window.DOMException(`Failed to load script "${absoluteURL}". JavaScript file loading is disabled.`, DOMExceptionNameEnum_js_1.default.notSupportedError);
                browserFrame.page?.console.error(error);
                this.dispatchEvent(new Event_js_1.default('error'));
            }
            return;
        }
        this.#loadedScriptURL = absoluteURLString;
        const resourceFetch = new ResourceFetch_js_1.default(window);
        const async = this.getAttribute('async') !== null || this.getAttribute('defer') !== null;
        let code = null;
        if (async) {
            const readyStateManager = window[PropertySymbol.readyStateManager];
            readyStateManager.startTask();
            try {
                code = await resourceFetch.fetch(absoluteURLString, 'script', {
                    credentials: this.crossOrigin === 'use-credentials' ? 'include' : 'same-origin',
                    referrerPolicy: this.referrerPolicy
                });
            }
            catch (error) {
                browserFrame.page?.console.error(error);
                this.dispatchEvent(new Event_js_1.default('error'));
                return;
            }
            readyStateManager.endTask();
        }
        else {
            try {
                code = resourceFetch.fetchSync(absoluteURLString, 'script', {
                    credentials: this.crossOrigin === 'use-credentials' ? 'include' : 'same-origin',
                    referrerPolicy: this.referrerPolicy
                });
            }
            catch (error) {
                browserFrame.page?.console.error(error);
                this.dispatchEvent(new Event_js_1.default('error'));
                return;
            }
        }
        this[PropertySymbol.ownerDocument][PropertySymbol.currentScript] = this;
        code = '//# sourceURL=' + absoluteURL + '\n' + code;
        if (browserSettings.disableErrorCapturing ||
            browserSettings.errorCapture !== BrowserErrorCaptureEnum_js_1.default.tryAndCatch) {
            this[PropertySymbol.window].eval(code);
        }
        else {
            try {
                this[PropertySymbol.window].eval(code);
            }
            catch (error) {
                this[PropertySymbol.ownerDocument][PropertySymbol.currentScript] = null;
                window[PropertySymbol.dispatchError](error);
                return;
            }
        }
        this[PropertySymbol.ownerDocument][PropertySymbol.currentScript] = null;
        this.dispatchEvent(new Event_js_1.default('load'));
    }
    /**
     * Returns true if the given type is supported.
     *
     * @param type Type.
     * @returns True if the given type is supported.
     */
    static supports(type) {
        switch (type) {
            case 'classic':
            case 'module':
            case 'importmap':
                return true;
            case 'speculationrules':
            default:
                return false;
        }
    }
}
exports.default = HTMLScriptElement;
//# sourceMappingURL=HTMLScriptElement.cjs.map
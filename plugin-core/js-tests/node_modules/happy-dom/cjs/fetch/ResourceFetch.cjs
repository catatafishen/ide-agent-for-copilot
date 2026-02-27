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
const URL_js_1 = __importDefault(require("../url/URL.cjs"));
const Fetch_js_1 = __importDefault(require("./Fetch.cjs"));
const SyncFetch_js_1 = __importDefault(require("./SyncFetch.cjs"));
const WindowBrowserContext_js_1 = __importDefault(require("../window/WindowBrowserContext.cjs"));
const PreloadUtility_js_1 = __importDefault(require("./preload/PreloadUtility.cjs"));
const PropertySymbol = __importStar(require("../PropertySymbol.cjs"));
/**
 * Helper class for performing fetch of resources.
 */
class ResourceFetch {
    window;
    /**
     * Constructor.
     *
     * @param window Window.
     */
    constructor(window) {
        this.window = window;
    }
    /**
     * Returns resource data asynchronously.
     *
     * @param url URL.
     * @param destination Destination.
     * @param [options]
     * @param [options.credentials] Credentials.
     * @param options.referrerPolicy
     * @returns Response.
     */
    async fetch(url, destination, options) {
        const browserFrame = new WindowBrowserContext_js_1.default(this.window).getBrowserFrame();
        // Preloaded resource
        if (destination === 'script' || destination === 'style') {
            const preloadKey = PreloadUtility_js_1.default.getKey({
                url: String(url),
                destination,
                mode: 'cors',
                credentialsMode: options.credentials || 'same-origin'
            });
            const preloadEntry = this.window.document[PropertySymbol.preloads].get(preloadKey);
            if (preloadEntry) {
                this.window.document[PropertySymbol.preloads].delete(preloadKey);
                const response = preloadEntry.response || (await preloadEntry.onResponseAvailable());
                if (!response.ok) {
                    throw new this.window.DOMException(`Failed to perform request to "${new URL_js_1.default(url, this.window.location.href).href}". Status ${preloadEntry.response.status} ${preloadEntry.response.statusText}.`);
                }
                return preloadEntry.response[PropertySymbol.buffer].toString();
            }
        }
        const fetch = new Fetch_js_1.default({
            browserFrame,
            window: this.window,
            url,
            disableSameOriginPolicy: destination === 'script' || destination === 'style',
            disablePreload: true,
            init: {
                credentials: options?.credentials,
                referrerPolicy: options?.referrerPolicy
            }
        });
        const response = await fetch.send();
        if (!response.ok) {
            throw new this.window.DOMException(`Failed to perform request to "${new URL_js_1.default(url, this.window.location.href).href}". Status ${response.status} ${response.statusText}.`);
        }
        return await response.text();
    }
    /**
     * Returns resource data synchronously.
     *
     * @param url URL.
     * @param destination Destination.
     * @param [options] Options.
     * @param [options.credentials] Credentials.
     * @param [options.referrerPolicy] Referrer policy.
     * @returns Response.
     */
    fetchSync(url, destination, options) {
        const browserFrame = new WindowBrowserContext_js_1.default(this.window).getBrowserFrame();
        // Preloaded resource
        if (destination === 'script' || destination === 'style') {
            const preloadKey = PreloadUtility_js_1.default.getKey({
                url: String(url),
                destination,
                mode: 'cors',
                credentialsMode: options.credentials || 'same-origin'
            });
            const preloadEntry = this.window.document[PropertySymbol.preloads].get(preloadKey);
            // We will only use this if the fetch for the resource is complete as it is async and this request is sync.
            if (preloadEntry && preloadEntry.response) {
                this.window.document[PropertySymbol.preloads].delete(preloadKey);
                const response = preloadEntry.response;
                if (!response.ok) {
                    throw new this.window.DOMException(`Failed to perform request to "${new URL_js_1.default(url, this.window.location.href).href}". Status ${preloadEntry.response.status} ${preloadEntry.response.statusText}.`);
                }
                return preloadEntry.response[PropertySymbol.buffer].toString();
            }
        }
        const fetch = new SyncFetch_js_1.default({
            browserFrame,
            window: this.window,
            url,
            disableSameOriginPolicy: true,
            init: {
                credentials: options?.credentials,
                referrerPolicy: options?.referrerPolicy
            }
        });
        const response = fetch.send();
        if (!response.ok) {
            throw new this.window.DOMException(`Failed to perform request to "${new URL_js_1.default(url, this.window.location.href).href}". Status ${response.status} ${response.statusText}.`);
        }
        return response.body.toString();
    }
}
exports.default = ResourceFetch;
//# sourceMappingURL=ResourceFetch.cjs.map
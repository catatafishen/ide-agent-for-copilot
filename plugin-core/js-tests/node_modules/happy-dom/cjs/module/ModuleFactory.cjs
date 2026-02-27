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
const url_1 = require("url");
const PropertySymbol = __importStar(require("../PropertySymbol.cjs"));
const CSSModule_js_1 = __importDefault(require("./CSSModule.cjs"));
const JSONModule_js_1 = __importDefault(require("./JSONModule.cjs"));
const UnresolvedModule_js_1 = __importDefault(require("./UnresolvedModule.cjs"));
const WindowBrowserContext_js_1 = __importDefault(require("../window/WindowBrowserContext.cjs"));
const ResourceFetch_js_1 = __importDefault(require("../fetch/ResourceFetch.cjs"));
const ECMAScriptModule_js_1 = __importDefault(require("./ECMAScriptModule.cjs"));
/**
 * Module factory.
 */
class ModuleFactory {
    /**
     * Fetches the source code of the module from the given URL and creates a new module instance.
     *
     * @param window Window.
     * @param parentURL Parent URL.
     * @param url Module URL.
     * @param [options] Options.
     * @param [options.with] Options.
     * @param [options.with.type] Module type.
     */
    static async getModule(window, parentURL, url, options) {
        const absoluteURL = this.getURL(window, parentURL, url);
        const absoluteURLString = absoluteURL.href;
        const type = options?.with?.type || 'esm';
        if (type !== 'esm' && type !== 'css' && type !== 'json') {
            throw new window.TypeError(`Failed to import module "${absoluteURL}" from "${parentURL}": Unkown type "${type}"`);
        }
        const cached = window[PropertySymbol.modules][type].get(absoluteURLString);
        if (cached) {
            if (cached instanceof UnresolvedModule_js_1.default) {
                await new Promise((resolve, reject) => {
                    cached.addResolveListener(resolve, reject);
                });
                return window[PropertySymbol.modules][type].get(absoluteURLString);
            }
            return cached;
        }
        const browserFrame = new WindowBrowserContext_js_1.default(window).getBrowserFrame();
        if (!browserFrame) {
            throw new window.TypeError(`Failed to import module "${absoluteURL}" from "${parentURL}": Window is closed and is no longer attached to a frame`);
        }
        const unresolvedModule = new UnresolvedModule_js_1.default(window, absoluteURL);
        window[PropertySymbol.modules][type].set(absoluteURLString, unresolvedModule);
        const resourceFetch = new ResourceFetch_js_1.default(window);
        let source;
        try {
            source = await resourceFetch.fetch(absoluteURL, 'module');
        }
        catch (error) {
            unresolvedModule.resolve(error);
            throw error;
        }
        let module;
        switch (type) {
            case 'json':
                module = new JSONModule_js_1.default(window, absoluteURL, source);
                break;
            case 'css':
                module = new CSSModule_js_1.default(window, absoluteURL, source);
                break;
            case 'esm':
                module = new ECMAScriptModule_js_1.default(window, absoluteURL, source);
                break;
        }
        window[PropertySymbol.modules][type].set(absoluteURLString, module);
        unresolvedModule.resolve();
        return module;
    }
    /**
     * Returns module URL based on parent URL and the import map.
     *
     * @param window Window.
     * @param parentURL Parent URL.
     * @param url Module URL.
     */
    static getURL(window, parentURL, url) {
        const parentURLString = parentURL.href;
        const absoluteURL = new url_1.URL(url, parentURLString);
        const absoluteURLString = absoluteURL.href;
        const importMap = window[PropertySymbol.moduleImportMap];
        if (!importMap) {
            return absoluteURL;
        }
        if (importMap.scopes) {
            for (const scope of importMap.scopes) {
                if (parentURLString.includes(scope.scope)) {
                    for (const rule of scope.rules) {
                        if (absoluteURLString.startsWith(rule.from)) {
                            return new url_1.URL(rule.to + absoluteURLString.replace(rule.from, ''));
                        }
                    }
                }
            }
        }
        if (importMap.imports) {
            for (const rule of importMap.imports) {
                if (absoluteURLString.startsWith(rule.from)) {
                    return new url_1.URL(rule.to + absoluteURLString.replace(rule.from, ''));
                }
            }
        }
        return absoluteURL;
    }
}
exports.default = ModuleFactory;
//# sourceMappingURL=ModuleFactory.cjs.map
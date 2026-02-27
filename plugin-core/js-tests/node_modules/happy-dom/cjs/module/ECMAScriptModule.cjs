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
const ECMAScriptModuleCompiler_js_1 = __importDefault(require("./ECMAScriptModuleCompiler.cjs"));
const PropertySymbol = __importStar(require("../PropertySymbol.cjs"));
const WindowBrowserContext_js_1 = __importDefault(require("../window/WindowBrowserContext.cjs"));
const ModuleFactory_js_1 = __importDefault(require("./ModuleFactory.cjs"));
const EMPTY_COMPILED_RESULT = { imports: [], execute: () => { } };
/**
 * ECMAScript module.
 */
class ECMAScriptModule {
    url;
    [PropertySymbol.window];
    #source;
    #preloaded = false;
    #compiled = null;
    #exports = null;
    /**
     * Constructor.
     *
     * @param window Window.
     * @param url Module URL.
     * @param source Source code.
     */
    constructor(window, url, source) {
        this[PropertySymbol.window] = window;
        this.url = url;
        this.#source = source;
    }
    /**
     * Compiles and evaluates the module.
     *
     * @returns Module exports.
     */
    async evaluate() {
        if (this.#exports) {
            return this.#exports;
        }
        const compiled = this.#compile();
        const modulePromises = [];
        const window = this[PropertySymbol.window];
        const browserFrame = new WindowBrowserContext_js_1.default(window).getBrowserFrame();
        if (!browserFrame) {
            return {};
        }
        for (const moduleImport of compiled.imports) {
            modulePromises.push(ModuleFactory_js_1.default.getModule(window, this.url, moduleImport.url, {
                with: { type: moduleImport.type }
            }));
        }
        const modules = await Promise.all(modulePromises);
        const imports = new Map();
        for (const module of modules) {
            imports.set(module.url.href, await module.evaluate());
        }
        const exports = {};
        this.#exports = exports;
        compiled.execute({
            dispatchError: window[PropertySymbol.dispatchError].bind(window),
            dynamicImport: this.#import.bind(this),
            imports,
            exports
        });
        return exports;
    }
    /**
     * Compiles and preloads the module and its imports.
     *
     * @returns Promise.
     */
    async preload() {
        if (this.#preloaded) {
            return;
        }
        this.#preloaded = true;
        const compiled = this.#compile();
        const modulePromises = [];
        const window = this[PropertySymbol.window];
        const browserFrame = new WindowBrowserContext_js_1.default(window).getBrowserFrame();
        if (!browserFrame) {
            return;
        }
        for (const moduleImport of compiled.imports) {
            modulePromises.push(ModuleFactory_js_1.default.getModule(window, this.url, moduleImport.url, {
                with: { type: moduleImport.type }
            }));
        }
        const modules = await Promise.all(modulePromises);
        const promises = [];
        for (const module of modules) {
            promises.push(module.preload());
        }
        await Promise.all(promises);
    }
    /**
     * Compiles the module.
     */
    #compile() {
        if (this.#compiled) {
            return this.#compiled;
        }
        // In case of an error, the compiled module will be empty.
        this.#compiled = EMPTY_COMPILED_RESULT;
        const compiler = new ECMAScriptModuleCompiler_js_1.default(this[PropertySymbol.window]);
        this.#compiled = compiler.compile(this.url.href, this.#source);
        return this.#compiled;
    }
    /**
     * Imports a module.
     *
     * @param url URL.
     * @param [options] Options.
     * @param [options.with] With.
     * @param [options.with.type] Type.
     */
    async #import(url, options) {
        const window = this[PropertySymbol.window];
        const browserFrame = new WindowBrowserContext_js_1.default(window).getBrowserFrame();
        if (!browserFrame) {
            return;
        }
        const asyncTaskManager = browserFrame[PropertySymbol.asyncTaskManager];
        const taskID = asyncTaskManager?.startTask();
        const module = await ModuleFactory_js_1.default.getModule(window, this.url, url, options);
        const exports = await module.evaluate();
        asyncTaskManager.endTask(taskID);
        return exports;
    }
}
exports.default = ECMAScriptModule;
//# sourceMappingURL=ECMAScriptModule.cjs.map
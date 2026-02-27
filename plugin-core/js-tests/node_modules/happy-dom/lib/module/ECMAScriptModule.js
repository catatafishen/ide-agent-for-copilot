import ECMAScriptModuleCompiler from './ECMAScriptModuleCompiler.js';
import * as PropertySymbol from '../PropertySymbol.js';
import WindowBrowserContext from '../window/WindowBrowserContext.js';
import ModuleFactory from './ModuleFactory.js';
const EMPTY_COMPILED_RESULT = { imports: [], execute: () => { } };
/**
 * ECMAScript module.
 */
export default class ECMAScriptModule {
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
        const browserFrame = new WindowBrowserContext(window).getBrowserFrame();
        if (!browserFrame) {
            return {};
        }
        for (const moduleImport of compiled.imports) {
            modulePromises.push(ModuleFactory.getModule(window, this.url, moduleImport.url, {
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
        const browserFrame = new WindowBrowserContext(window).getBrowserFrame();
        if (!browserFrame) {
            return;
        }
        for (const moduleImport of compiled.imports) {
            modulePromises.push(ModuleFactory.getModule(window, this.url, moduleImport.url, {
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
        const compiler = new ECMAScriptModuleCompiler(this[PropertySymbol.window]);
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
        const browserFrame = new WindowBrowserContext(window).getBrowserFrame();
        if (!browserFrame) {
            return;
        }
        const asyncTaskManager = browserFrame[PropertySymbol.asyncTaskManager];
        const taskID = asyncTaskManager?.startTask();
        const module = await ModuleFactory.getModule(window, this.url, url, options);
        const exports = await module.evaluate();
        asyncTaskManager.endTask(taskID);
        return exports;
    }
}
//# sourceMappingURL=ECMAScriptModule.js.map
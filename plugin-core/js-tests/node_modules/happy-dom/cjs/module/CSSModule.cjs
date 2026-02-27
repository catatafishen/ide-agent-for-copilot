"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
/**
 * CSS module.
 */
class CSSModule {
    url;
    #window;
    #source;
    #exports = null;
    /**
     * Constructor.
     *
     * @param window Window.
     * @param url Module URL.
     * @param source Source code.
     */
    constructor(window, url, source) {
        this.#window = window;
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
        const styleSheet = new this.#window.CSSStyleSheet();
        styleSheet.replaceSync(this.#source);
        this.#exports = { default: styleSheet };
        return this.#exports;
    }
    /**
     * Compiles and preloads the module and its imports.
     *
     * @returns Promise.
     */
    async preload() {
        await this.evaluate();
    }
}
exports.default = CSSModule;
//# sourceMappingURL=CSSModule.cjs.map
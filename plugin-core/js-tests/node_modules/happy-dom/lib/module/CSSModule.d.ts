import BrowserWindow from '../window/BrowserWindow.js';
import { URL } from 'url';
import IModule from './IModule.js';
import CSSStyleSheet from '../css/CSSStyleSheet.js';
/**
 * CSS module.
 */
export default class CSSModule implements IModule {
    #private;
    readonly url: URL;
    /**
     * Constructor.
     *
     * @param window Window.
     * @param url Module URL.
     * @param source Source code.
     */
    constructor(window: BrowserWindow, url: URL, source: string);
    /**
     * Compiles and evaluates the module.
     *
     * @returns Module exports.
     */
    evaluate(): Promise<{
        default: CSSStyleSheet;
    }>;
    /**
     * Compiles and preloads the module and its imports.
     *
     * @returns Promise.
     */
    preload(): Promise<void>;
}
//# sourceMappingURL=CSSModule.d.ts.map
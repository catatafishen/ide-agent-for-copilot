import BrowserWindow from '../window/BrowserWindow.cjs';
import { URL } from 'url';
import IModule from './IModule.cjs';
import * as PropertySymbol from '../PropertySymbol.cjs';
import Location from '../location/Location.cjs';
/**
 * ECMAScript module.
 */
export default class ECMAScriptModule implements IModule {
    #private;
    readonly url: URL;
    readonly [PropertySymbol.window]: BrowserWindow;
    /**
     * Constructor.
     *
     * @param window Window.
     * @param url Module URL.
     * @param source Source code.
     */
    constructor(window: BrowserWindow, url: URL | Location, source: string);
    /**
     * Compiles and evaluates the module.
     *
     * @returns Module exports.
     */
    evaluate(): Promise<{
        [key: string]: any;
    } | null>;
    /**
     * Compiles and preloads the module and its imports.
     *
     * @returns Promise.
     */
    preload(): Promise<void>;
}
//# sourceMappingURL=ECMAScriptModule.d.ts.map
import BrowserWindow from '../window/BrowserWindow.cjs';
import { URL } from 'url';
import IModule from './IModule.cjs';
/**
 * JSON module.
 */
export default class JSONModule implements IModule {
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
        default: object;
    }>;
    /**
     * Compiles and preloads the module and its imports.
     *
     * @returns Promise.
     */
    preload(): Promise<void>;
}
//# sourceMappingURL=JSONModule.d.ts.map
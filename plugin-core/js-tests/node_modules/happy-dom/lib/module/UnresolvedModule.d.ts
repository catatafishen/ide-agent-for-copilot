import BrowserWindow from '../window/BrowserWindow.js';
import { URL } from 'url';
import IModule from './IModule.js';
/**
 * CSS module.
 */
export default class UnresolvedModule implements IModule {
    #private;
    readonly url: URL;
    /**
     * Constructor.
     *
     * @param window Window.
     * @param url Module URL.
     */
    constructor(window: BrowserWindow, url: URL);
    /**
     * Compiles and evaluates the module.
     *
     * @returns Module exports.
     */
    evaluate(): Promise<null>;
    /**
     * Compiles and preloads the module and its imports.
     *
     * @returns Promise.
     */
    preload(): Promise<void>;
    /**
     * Add a hook to be called when the module is resolved.
     *
     * @param resolve Resolve.
     * @param reject Reject.
     */
    addResolveListener(resolve: (value: unknown) => void, reject: (error: Error) => void): void;
    /**
     * Resolves the module.
     *
     * @param [error] Error.
     */
    resolve(error?: Error): void;
}
//# sourceMappingURL=UnresolvedModule.d.ts.map
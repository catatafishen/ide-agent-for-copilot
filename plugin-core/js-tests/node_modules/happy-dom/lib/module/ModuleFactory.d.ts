import { URL } from 'url';
import IModule from './IModule.js';
import BrowserWindow from '../window/BrowserWindow.js';
import Location from '../location/Location.js';
/**
 * Module factory.
 */
export default class ModuleFactory {
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
    static getModule(window: BrowserWindow, parentURL: URL | Location, url: string, options?: {
        with?: {
            type?: string;
        };
    }): Promise<IModule>;
    /**
     * Returns module URL based on parent URL and the import map.
     *
     * @param window Window.
     * @param parentURL Parent URL.
     * @param url Module URL.
     */
    private static getURL;
}
//# sourceMappingURL=ModuleFactory.d.ts.map
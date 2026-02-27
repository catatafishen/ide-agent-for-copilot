import { URL } from 'url';
import BrowserWindow from '../window/BrowserWindow.cjs';
import Location from '../location/Location.cjs';
/**
 * Module URL utility.
 */
export default class ModuleURLUtility {
    /**
     * Returns module URL based on parent URL and the import map.
     *
     * @param window Window.
     * @param parentURL Parent URL.
     * @param url Module URL.
     */
    static getURL(window: BrowserWindow, parentURL: URL | Location | string, url: string): URL;
}
//# sourceMappingURL=ModuleURLUtility.d.ts.map
import BrowserWindow from '../window/BrowserWindow.cjs';
import URL from '../url/URL.cjs';
import IRequestCredentials from './types/IRequestCredentials.cjs';
import IRequestReferrerPolicy from './types/IRequestReferrerPolicy.cjs';
/**
 * Helper class for performing fetch of resources.
 */
export default class ResourceFetch {
    private window;
    /**
     * Constructor.
     *
     * @param window Window.
     */
    constructor(window: BrowserWindow);
    /**
     * Returns resource data asynchronously.
     *
     * @param url URL.
     * @param destination Destination.
     * @param [options]
     * @param [options.credentials] Credentials.
     * @param options.referrerPolicy
     * @returns Response.
     */
    fetch(url: string | URL, destination: 'script' | 'style' | 'module', options?: {
        credentials?: IRequestCredentials;
        referrerPolicy?: IRequestReferrerPolicy;
    }): Promise<string>;
    /**
     * Returns resource data synchronously.
     *
     * @param url URL.
     * @param destination Destination.
     * @param [options] Options.
     * @param [options.credentials] Credentials.
     * @param [options.referrerPolicy] Referrer policy.
     * @returns Response.
     */
    fetchSync(url: string, destination: 'script' | 'style' | 'module', options?: {
        credentials?: IRequestCredentials;
        referrerPolicy?: IRequestReferrerPolicy;
    }): string;
}
//# sourceMappingURL=ResourceFetch.d.ts.map
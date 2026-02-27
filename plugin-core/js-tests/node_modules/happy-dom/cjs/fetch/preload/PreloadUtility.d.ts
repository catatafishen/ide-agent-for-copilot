import IRequestCredentials from '../types/IRequestCredentials.cjs';
import IRequestMode from '../types/IRequestMode.cjs';
/**
 * Utility for preloading resources.
 *
 * @see https://html.spec.whatwg.org/multipage/links.html#link-type-preload
 */
export default class PreloadUtility {
    /**
     * Returns a key for a preload entry.
     *
     * @param options Options.
     * @param options.url URL.
     * @param options.destination Destination.
     * @param options.mode Mode.
     * @param options.credentialsMode Credentials mode.
     * @returns Key.
     */
    static getKey(options: {
        url: string;
        destination: string;
        mode: IRequestMode;
        credentialsMode: IRequestCredentials;
    }): string;
}
//# sourceMappingURL=PreloadUtility.d.ts.map
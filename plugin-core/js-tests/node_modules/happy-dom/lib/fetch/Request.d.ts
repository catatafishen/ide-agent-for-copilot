import * as PropertySymbol from '../PropertySymbol.js';
import IRequestInit from './types/IRequestInit.js';
import { URL } from 'url';
import IRequestInfo from './types/IRequestInfo.js';
import Headers from './Headers.js';
import AbortSignal from './AbortSignal.js';
import { ReadableStream } from 'stream/web';
import Blob from '../file/Blob.js';
import IRequestReferrerPolicy from './types/IRequestReferrerPolicy.js';
import IRequestRedirect from './types/IRequestRedirect.js';
import IRequestCredentials from './types/IRequestCredentials.js';
import FormData from '../form-data/FormData.js';
import BrowserWindow from '../window/BrowserWindow.js';
import IRequestMode from './types/IRequestMode.js';
/**
 * Fetch request.
 *
 * Based on:
 * https://github.com/node-fetch/node-fetch/blob/main/src/request.js
 *
 * @see https://fetch.spec.whatwg.org/#request-class
 */
export default class Request implements Request {
    protected [PropertySymbol.window]: BrowserWindow;
    [PropertySymbol.method]: string;
    [PropertySymbol.body]: ReadableStream | null;
    [PropertySymbol.mode]: IRequestMode;
    [PropertySymbol.headers]: Headers;
    [PropertySymbol.redirect]: IRequestRedirect;
    [PropertySymbol.referrerPolicy]: IRequestReferrerPolicy;
    [PropertySymbol.signal]: AbortSignal;
    [PropertySymbol.bodyUsed]: boolean;
    [PropertySymbol.credentials]: IRequestCredentials;
    [PropertySymbol.aborted]: boolean;
    [PropertySymbol.contentLength]: number | null;
    [PropertySymbol.contentType]: string | null;
    [PropertySymbol.referrer]: '' | 'no-referrer' | 'client' | URL;
    [PropertySymbol.url]: URL;
    [PropertySymbol.bodyBuffer]: Buffer | null;
    /**
     * Constructor.
     *
     * @param input Input.
     * @param [init] Init.
     */
    constructor(input: IRequestInfo, init?: IRequestInit);
    /**
     * Returns method.
     *
     * @returns Method.
     */
    get method(): string;
    /**
     * Returns body.
     *
     * @returns Body.
     */
    get body(): ReadableStream | null;
    /**
     * Returns mode.
     *
     * @returns Mode.
     */
    get mode(): IRequestMode;
    /**
     * Returns headers.
     *
     * @returns Headers.
     */
    get headers(): Headers;
    /**
     * Returns redirect.
     *
     * @returns Redirect.
     */
    get redirect(): IRequestRedirect;
    /**
     * Returns referrer policy.
     *
     * @returns Referrer policy.
     */
    get referrerPolicy(): IRequestReferrerPolicy;
    /**
     * Returns signal.
     *
     * @returns Signal.
     */
    get signal(): AbortSignal;
    /**
     * Returns body used.
     *
     * @returns Body used.
     */
    get bodyUsed(): boolean;
    /**
     * Returns credentials.
     *
     * @returns Credentials.
     */
    get credentials(): IRequestCredentials;
    /**
     * Returns referrer.
     *
     * @returns Referrer.
     */
    get referrer(): string;
    /**
     * Returns URL.
     *
     * @returns URL.
     */
    get url(): string;
    /**
     * Returns string tag.
     *
     * @returns String tag.
     */
    get [Symbol.toStringTag](): string;
    /**
     * Returns array buffer.
     *
     * @returns Array buffer.
     */
    arrayBuffer(): Promise<ArrayBuffer>;
    /**
     * Returns blob.
     *
     * @returns Blob.
     */
    blob(): Promise<Blob>;
    /**
     * Returns buffer.
     *
     * @returns Buffer.
     */
    buffer(): Promise<Buffer>;
    /**
     * Returns text.
     *
     * @returns Text.
     */
    text(): Promise<string>;
    /**
     * Returns json.
     *
     * @returns JSON.
     */
    json(): Promise<string>;
    /**
     * Returns FormData.
     *
     * @returns FormData.
     */
    formData(): Promise<FormData>;
    /**
     * Clones request.
     *
     * @returns Clone.
     */
    clone(): Request;
}
//# sourceMappingURL=Request.d.ts.map
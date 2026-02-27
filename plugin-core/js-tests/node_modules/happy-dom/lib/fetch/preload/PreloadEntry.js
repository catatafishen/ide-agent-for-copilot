/**
 * Preload entry.
 *
 * @see https://html.spec.whatwg.org/multipage/links.html#preload-entry
 */
export default class PreloadEntry {
    integrityMetadata = null;
    response = null;
    error = null;
    #callback = null;
    /**
     * On response available.
     *
     * @returns Response.
     */
    onResponseAvailable() {
        return new Promise((resolve, reject) => {
            this.#callback = { resolve, reject };
        });
    }
    /**
     * Response available.
     *
     * @param error
     * @param response
     */
    responseAvailable(error, response) {
        this.response = response;
        this.error = error;
        if (!this.#callback) {
            return;
        }
        if (error) {
            this.#callback.reject(error);
        }
        else {
            this.#callback.resolve(response);
        }
    }
}
//# sourceMappingURL=PreloadEntry.js.map
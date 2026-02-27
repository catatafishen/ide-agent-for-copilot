export default class MessageMeta extends HTMLElement {
    private _init = false;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
    }
}

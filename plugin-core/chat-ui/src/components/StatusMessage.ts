export default class StatusMessage extends HTMLElement {
    static get observedAttributes(): string[] {
        return ['type', 'message'];
    }

    private _init = false;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this._render();
    }

    private _render(): void {
        const type = this.getAttribute('type') || 'info';
        const msg = this.getAttribute('message') || '';
        this.className = 'status-row ' + type;
        const icon = type === 'error' ? '❌' : 'ℹ';
        this.textContent = icon + ' ' + msg;
    }

    attributeChangedCallback(): void {
        if (this._init) this._render();
    }
}

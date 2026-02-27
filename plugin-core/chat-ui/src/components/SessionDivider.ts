import {escHtml} from '../helpers';

export default class SessionDivider extends HTMLElement {
    static get observedAttributes(): string[] {
        return ['timestamp'];
    }

    private _init = false;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this.classList.add('session-sep');
        this.setAttribute('role', 'separator');
        this._render();
    }

    private _render(): void {
        const ts = this.getAttribute('timestamp') || '';
        this.setAttribute('aria-label', 'New session started ' + ts);
        this.innerHTML = `<span class="session-sep-line"></span><span class="session-sep-label"><span aria-hidden="true">📅 </span>New session — ${escHtml(ts)}</span><span class="session-sep-line"></span>`;
    }

    attributeChangedCallback(): void {
        if (this._init) this._render();
    }
}

import {escHtml} from '../helpers';

export default class SessionDivider extends HTMLElement {
    static get observedAttributes(): string[] {
        return ['timestamp', 'agent'];
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
        const agent = this.getAttribute('agent') || '';
        const label = agent ? `New session — ${escHtml(ts)} · ${escHtml(agent)}` : `New session — ${escHtml(ts)}`;
        this.setAttribute('aria-label', label);
        this.innerHTML = `<span class="session-sep-line"></span><span class="session-sep-label">${label}</span><span class="session-sep-line"></span>`;
    }

    attributeChangedCallback(): void {
        if (this._init) this._render();
    }
}

export default class LoadMore extends HTMLElement {
    static get observedAttributes(): string[] {
        return ['count', 'loading'];
    }

    private _init = false;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this.classList.add('load-more-banner');
        this.setAttribute('role', 'button');
        this.setAttribute('tabindex', '0');
        this.setAttribute('aria-label', 'Load earlier messages');
        this._render();
        this.onclick = () => {
            if (!this.hasAttribute('loading')) {
                this.setAttribute('loading', '');
                this.dispatchEvent(new CustomEvent('load-more', {bubbles: true}));
            }
        };
        this.onkeydown = (e: KeyboardEvent) => {
            if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                this.click();
            }
        };
    }

    private _render(): void {
        const count = this.getAttribute('count') || '?';
        const loading = this.hasAttribute('loading');
        this.innerHTML = `<span class="load-more-text">${loading ? 'Loading...' : '▲ Load earlier messages (' + count + ' more) — click or scroll up'}</span>`;
    }

    attributeChangedCallback(): void {
        if (this._init) this._render();
    }
}

export default class LoadMore extends HTMLElement {
    static get observedAttributes(): string[] {
        return ['count', 'loading'];
    }

    private _init = false;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this.classList.add('load-more-banner');
        this._render();
        this.onclick = () => {
            if (!this.hasAttribute('loading')) {
                this.setAttribute('loading', '');
                this.dispatchEvent(new CustomEvent('load-more', { bubbles: true }));
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

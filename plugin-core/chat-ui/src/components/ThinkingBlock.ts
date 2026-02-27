export default class ThinkingBlock extends HTMLElement {
    private _init = false;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this.classList.add('thinking-section');
        this.innerHTML = `<div class="thinking-content"></div>`;
    }

    get contentEl(): Element | null {
        return this.querySelector('.thinking-content');
    }

    appendText(text: string): void {
        const el = this.contentEl;
        if (el) el.textContent += text;
    }

    finalize(): void {
        this.removeAttribute('active');
    }
}

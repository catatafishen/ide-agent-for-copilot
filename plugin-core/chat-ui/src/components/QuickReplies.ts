export default class QuickReplies extends HTMLElement {
    static get observedAttributes(): string[] {
        return ['disabled'];
    }

    private _init = false;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this.classList.add('quick-replies');
    }

    set options(arr: string[]) {
        this.innerHTML = '';
        (arr || []).forEach(text => {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'quick-reply-btn';
            btn.textContent = text;
            btn.onclick = () => {
                if (this.hasAttribute('disabled')) return;
                this.setAttribute('disabled', '');
                this.dispatchEvent(new CustomEvent('quick-reply', {detail: {text}, bubbles: true}));
            };
            this.appendChild(btn);
        });
    }

    attributeChangedCallback(name: string): void {
        if (name === 'disabled') this.classList.toggle('disabled', this.hasAttribute('disabled'));
    }
}

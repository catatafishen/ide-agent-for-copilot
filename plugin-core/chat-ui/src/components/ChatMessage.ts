export default class ChatMessage extends HTMLElement {
    static get observedAttributes(): string[] {
        return ['type', 'timestamp'];
    }

    private _init = false;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        const type = this.getAttribute('type') || 'agent';
        this.classList.add(type === 'user' ? 'prompt-row' : 'agent-row');
    }

    attributeChangedCallback(name: string, _oldVal: string | null, newVal: string | null): void {
        if (name === 'type' && this._init) {
            this.classList.remove('prompt-row', 'agent-row');
            this.classList.add(newVal === 'user' ? 'prompt-row' : 'agent-row');
        }
    }
}

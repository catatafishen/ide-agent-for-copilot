import { escHtml } from '../helpers';

export default class ToolSection extends HTMLElement {
    private _init = false;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this.classList.add('tool-section', 'turn-hidden');
        this.innerHTML = `
            <div class="tool-params"></div>
            <div class="tool-result">Running...</div>`;
    }

    set params(val: string) {
        const el = this.querySelector('.tool-params');
        if (el) el.innerHTML = `<pre class="tool-params-code"><code>${escHtml(val)}</code></pre>`;
    }

    set result(val: string) {
        const el = this.querySelector('.tool-result');
        if (el) el.innerHTML = val;
    }

    updateStatus(_status: string): void { /* status tracked on chip only */ }
}

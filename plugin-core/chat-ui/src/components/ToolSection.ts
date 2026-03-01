import { escHtml } from '../helpers';

export default class ToolSection extends HTMLElement {
    private _init = false;
    private _startTime = 0;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this._startTime = Date.now();
        this.classList.add('tool-section', 'turn-hidden');
        this.innerHTML = `
            <div class="tool-params"></div>
            <div class="tool-result">Running...</div>
            <div class="tool-runtime"></div>`;
        // Attribute may have been set before connectedCallback — apply it now
        const p = this.getAttribute('params');
        if (p) this.params = p;
    }

    set params(val: string) {
        const el = this.querySelector('.tool-params');
        if (el) el.innerHTML = `<div class="tool-result-label">Input:</div><pre class="tool-params-code"><code>${escHtml(val)}</code></pre>`;
    }

    set result(val: string) {
        const el = this.querySelector('.tool-result');
        if (el) el.innerHTML = val;
        this._showRuntime();
    }

    private _showRuntime(): void {
        if (!this._startTime) return;
        const el = this.querySelector('.tool-runtime');
        if (!el) return;
        const ms = Date.now() - this._startTime;
        const label = ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;
        el.textContent = `⏱ ${label}`;
    }

    updateStatus(_status: string): void { /* status tracked on chip only */ }
}

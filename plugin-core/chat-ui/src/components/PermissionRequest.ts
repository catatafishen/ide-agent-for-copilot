import {escHtml} from '../helpers';

// Keys to prefer for the single-line preview under the tool name
const PREVIEW_KEY_ORDER = ['path', 'file', 'file1', 'symbol', 'query', 'name', 'command', 'target'];

export default class PermissionRequest extends HTMLElement {
    private _init = false;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this._render();
    }

    private _render(): void {
        const reqId = this.getAttribute('req-id') || '';
        const toolName = this.getAttribute('tool-name') || 'Unknown Tool';
        const argsJson = this.getAttribute('args-json') || '{}';

        let args: Record<string, unknown> = {};
        try { args = JSON.parse(argsJson); } catch { /* ignore parse errors */ }

        this.classList.add('permission-request');

        // Header: "🔐 Use Write File?"
        const header = document.createElement('div');
        header.className = 'perm-header';
        header.innerHTML = `<span class="perm-icon">🔐</span>\u00a0Use <strong>${escHtml(toolName)}</strong>?`;
        this.appendChild(header);

        // Preview: show the most salient arg value inline
        const previewKey = PREVIEW_KEY_ORDER.find(k => args[k] !== undefined);
        if (previewKey) {
            const val = String(args[previewKey]);
            const preview = document.createElement('div');
            preview.className = 'perm-preview';
            preview.textContent = val.length > 80 ? val.slice(0, 77) + '\u2026' : val;
            this.appendChild(preview);
        }

        // Collapsible parameters section
        const entries = Object.entries(args);
        if (entries.length > 0) {
            const details = document.createElement('details');
            details.className = 'perm-details';
            const summary = document.createElement('summary');
            summary.textContent = 'Parameters';
            details.appendChild(summary);

            entries.forEach(([k, v]) => {
                const row = document.createElement('div');
                row.className = 'perm-arg-row';
                const valStr = typeof v === 'string' ? v : JSON.stringify(v, null, 2);
                if (valStr.length > 120) {
                    // Long value: nested collapsible
                    const kEl = document.createElement('span');
                    kEl.className = 'perm-key';
                    kEl.textContent = k + ':';
                    row.appendChild(kEl);
                    const valDetails = document.createElement('details');
                    valDetails.className = 'perm-val-details';
                    const valSummary = document.createElement('summary');
                    valSummary.className = 'perm-val-summary';
                    valSummary.textContent = valStr.slice(0, 80) + '\u2026';
                    valDetails.appendChild(valSummary);
                    const pre = document.createElement('pre');
                    pre.className = 'perm-val-pre';
                    pre.textContent = valStr;
                    valDetails.appendChild(pre);
                    row.appendChild(valDetails);
                } else {
                    row.innerHTML = `<span class="perm-key">${escHtml(k)}:</span> <span class="perm-val">${escHtml(valStr)}</span>`;
                }
                details.appendChild(row);
            });

            this.appendChild(details);
        }

        // Action buttons
        const actions = document.createElement('div');
        actions.className = 'perm-actions';

        const allowBtn = document.createElement('button');
        allowBtn.type = 'button';
        allowBtn.className = 'quick-reply-btn perm-allow';
        allowBtn.textContent = 'Allow';
        allowBtn.onclick = () => this._respond(reqId, true);

        const denyBtn = document.createElement('button');
        denyBtn.type = 'button';
        denyBtn.className = 'quick-reply-btn perm-deny';
        denyBtn.textContent = 'Deny';
        denyBtn.onclick = () => this._respond(reqId, false);

        actions.appendChild(allowBtn);
        actions.appendChild(denyBtn);
        this.appendChild(actions);
    }

    private _respond(reqId: string, allowed: boolean): void {
        this.querySelectorAll('button').forEach(b => ((b as HTMLButtonElement).disabled = true));
        this.classList.add('resolved');
        const result = document.createElement('div');
        result.className = 'perm-result ' + (allowed ? 'perm-allowed' : 'perm-denied');
        result.textContent = allowed ? '\u2713 Allowed' : '\u2717 Denied';
        const actions = this.querySelector('.perm-actions');
        if (actions) actions.replaceWith(result);
        (globalThis as any)._bridge?.permissionResponse(`${reqId}:${allowed}`);
    }
}

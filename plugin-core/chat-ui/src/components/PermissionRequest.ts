/**
 * Permission request actions element — rendered below the question bubble.
 * Contains Allow/Deny buttons, replaced with result text on resolve.
 * The chip + tool-section are created by ChatController (same as regular tool calls).
 */
export default class PermissionRequest extends HTMLElement {
    private _init = false;

    static get observedAttributes(): string[] {
        return ['resolved'];
    }

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this._render();
    }

    private _render(): void {
        const reqId = this.getAttribute('req-id') || '';
        this.className = 'perm-actions';

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

        this.appendChild(allowBtn);
        this.appendChild(denyBtn);
    }

    private _respond(reqId: string, allowed: boolean): void {
        this.querySelectorAll('button').forEach(b => (b as HTMLButtonElement).disabled = true);

        // Update chip status
        const chipId = 'perm-' + reqId;
        const chip = document.querySelector(`[data-chip-for="${chipId}"]`);
        if (chip) chip.setAttribute('status', allowed ? 'complete' : 'failed');

        // Replace buttons with result text
        const result = document.createElement('div');
        result.className = 'perm-result ' + (allowed ? 'perm-allowed' : 'perm-denied');
        result.textContent = allowed ? '\u2713 Allowed' : '\u2717 Denied';
        this.replaceChildren(result);

        (globalThis as any)._bridge?.permissionResponse(`${reqId}:${allowed}`);
    }
}

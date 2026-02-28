import {escHtml} from '../helpers';
import {toolCategory} from '../toolDisplayName';

/**
 * Permission request rendered as an agent-style message bubble.
 * Shows a question text + a tool chip (with hourglass while pending).
 * Clicking the chip expands a tool-section showing parameters.
 * Allow/Deny buttons styled as quick-reply pills.
 */
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
        try {
            args = JSON.parse(argsJson);
        } catch { /* ignore parse errors */
        }

        this.classList.add('permission-request', 'agent-row');

        // Agent-style bubble with question
        const bubble = document.createElement('div');
        bubble.className = 'agent-bubble perm-bubble';

        const question = document.createElement('div');
        question.className = 'perm-question';
        question.innerHTML = `\u{1F510} Can I use <strong>${escHtml(toolName)}</strong>?`;
        bubble.appendChild(question);

        // Tool chip — reuses existing chip styling with hourglass status
        const chipRow = document.createElement('div');
        chipRow.className = 'perm-chip-row';

        const sectionId = 'perm-section-' + reqId;
        const section = document.createElement('tool-section') as HTMLElement;
        section.id = sectionId;
        section.setAttribute('title', toolName);
        section.setAttribute('params', argsJson);
        section.classList.add('turn-hidden');

        const chip = document.createElement('tool-chip') as HTMLElement;
        const cat = toolCategory(toolName);
        chip.setAttribute('label', toolName);
        chip.setAttribute('status', 'running');
        chip.classList.add(`cat-${cat}`);
        chip.dataset.chipFor = sectionId;
        (chip as any).linkSection(section);

        chipRow.appendChild(chip);
        bubble.appendChild(chipRow);
        bubble.appendChild(section);

        // Allow / Deny buttons
        const actions = document.createElement('div');
        actions.className = 'perm-actions';

        const allowBtn = document.createElement('button');
        allowBtn.type = 'button';
        allowBtn.className = 'quick-reply-btn perm-allow';
        allowBtn.textContent = 'Allow';
        allowBtn.onclick = () => this._respond(reqId, true, chip);

        const denyBtn = document.createElement('button');
        denyBtn.type = 'button';
        denyBtn.className = 'quick-reply-btn perm-deny';
        denyBtn.textContent = 'Deny';
        denyBtn.onclick = () => this._respond(reqId, false, chip);

        actions.appendChild(allowBtn);
        actions.appendChild(denyBtn);
        bubble.appendChild(actions);

        this.appendChild(bubble);
    }

    private _respond(reqId: string, allowed: boolean, chip: HTMLElement): void {
        this.querySelectorAll('button').forEach(b => ((b as HTMLButtonElement).disabled = true));
        this.classList.add('resolved');

        // Update chip status
        chip.setAttribute('status', allowed ? 'complete' : 'failed');

        // Replace buttons with result text
        const result = document.createElement('div');
        result.className = 'perm-result ' + (allowed ? 'perm-allowed' : 'perm-denied');
        result.textContent = allowed ? '\u2713 Allowed' : '\u2717 Denied';
        const actions = this.querySelector('.perm-actions');
        if (actions) actions.replaceWith(result);

        (globalThis as any)._bridge?.permissionResponse(`${reqId}:${allowed}`);
    }
}

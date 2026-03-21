import {escHtml} from '../helpers';
import {toolDisplayName} from '../toolDisplayName';

/**
 * External (non-MCP) tools that are considered safe and should not show warning badge.
 * These are useful agent built-in tools that don't pose security concerns.
 */
const SAFE_EXTERNAL_TOOLS = new Set([
    // OpenCode / Claude agent tools - read/search operations
    'todowrite', 'TodoWrite',
    'codesearch', 'CodeSearch',
    'webfetch', 'WebFetch',
    'websearch', 'WebSearch',
    'skill', 'Skill',
    'task', 'Task',
    // Built-in CLI read-only tools
    'view', 'View',
    'Read',
    'grep', 'Grep',
    'glob', 'Glob',
]);

export default class ToolChip extends HTMLElement {
    static get observedAttributes(): string[] {
        return ['label', 'status', 'expanded', 'kind', 'external'];
    }

    private _init = false;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this.classList.add('turn-chip', 'tool');
        this.setAttribute('role', 'button');
        this.setAttribute('tabindex', '0');
        this.setAttribute('aria-expanded', 'false');
        this._render();
        this.onclick = (e) => {
            e.stopPropagation();
            this._showPopup();
        };
        this.onkeydown = (e) => {
            if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                this._showPopup();
            }
        };
    }

    private _render(): void {
        const rawLabel = this.getAttribute('label') || '';
        const status = this.getAttribute('status') || 'running';
        const kind = this.getAttribute('kind') || 'other';
        const isExternal = this.getAttribute('external') === 'true';
        const paramsStr = this.dataset.params || undefined;
        const display = toolDisplayName(rawLabel, paramsStr);
        const truncated = display.length > 50 ? display.substring(0, 47) + '\u2026' : display;
        // Remove any previous kind/status class and apply current one
        this.className = this.className.replaceAll(/\bkind-\S+/g, '').replaceAll(/\bstatus-\S+/g, '').trim();
        this.classList.add('turn-chip', 'tool', `kind-${kind}`, `status-${status}`);
        if (isExternal) this.classList.add('external-tool');
        let iconHtml = '';
        if (status === 'running') iconHtml = '<span class="chip-spinner"></span> ';
        if (status === 'pending') iconHtml = '<span class="chip-spinner"></span> ';
        // Status-based badge
        let statusBadge = '';
        if (status === 'unverified') statusBadge = '<span class="unverified-badge" title="Tool call was requested but not handled by our MCP">⚠</span> ';
        if (status === 'orphan') statusBadge = '<span class="orphan-badge" title="Tool called by another connected client">?</span> ';
        // Always update failed class based on current status
        this.classList.toggle('failed', status === 'failed');
        // Add external badge for non-MCP tools, unless it's a known safe tool
        const baseToolName = rawLabel.split(' — ')[0].trim();
        const showWarning = isExternal && !SAFE_EXTERNAL_TOOLS.has(baseToolName);
        const externalBadge = showWarning ? '<span class="external-badge" title="Built-in agent tool (not from MCP plugin)">⚠</span> ' : '';
        this.innerHTML = iconHtml + statusBadge + externalBadge + escHtml(truncated);
        if (display.length > 50) this.dataset.tip = display;
        else if (rawLabel !== display) this.dataset.tip = rawLabel;
        if (this.dataset.tip) this.setAttribute('title', this.dataset.tip);
    }

    private _showPopup(): void {
        const id = this.dataset.chipFor || '';
        if (id && globalThis._bridge?.showToolPopup) {
            globalThis._bridge.showToolPopup(id);
        }
    }

    attributeChangedCallback(name: string): void {
        if (!this._init) return;
        if (name === 'status' || name === 'kind') this._render();
    }
}

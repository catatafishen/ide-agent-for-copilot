import {collapseAllChips, escHtml} from '../helpers';
import {toolCategory, toolDisplayName} from '../toolDisplayName';

export default class ToolChip extends HTMLElement {
    static get observedAttributes(): string[] {
        return ['label', 'status', 'expanded'];
    }

    private _init = false;
    _linkedSection: HTMLElement | null = null;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this.classList.add('turn-chip', 'tool');
        this.style.cursor = 'pointer';
        this.setAttribute('role', 'button');
        this.setAttribute('tabindex', '0');
        this.setAttribute('aria-expanded', 'false');
        this._render();
        this.onclick = (e) => {
            e.stopPropagation();
            this._toggleExpand();
        };
        this.onkeydown = (e) => {
            if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                this._toggleExpand();
            }
        };
    }

    private _render(): void {
        const rawLabel = this.getAttribute('label') || '';
        const status = this.getAttribute('status') || 'running';
        this._resolveLink();
        const paramsStr = this._linkedSection?.getAttribute('params') || undefined;
        const display = toolDisplayName(rawLabel, paramsStr);
        const cat = toolCategory(rawLabel);
        const truncated = display.length > 50 ? display.substring(0, 47) + '\u2026' : display;
        // Remove any previous category class and apply current one
        this.className = this.className.replace(/\bcat-\S+/g, '').trim();
        this.classList.add('turn-chip', 'tool', `cat-${cat}`);
        let iconHtml = '';
        if (status === 'running') iconHtml = '<span class="chip-spinner"></span> ';
        else if (status === 'failed') this.classList.add('failed');
        this.innerHTML = iconHtml + escHtml(truncated);
        if (display.length > 50) this.dataset.tip = display;
        else if (rawLabel !== display) this.dataset.tip = rawLabel;
        if (this.dataset.tip) this.setAttribute('title', this.dataset.tip);
    }

    private _resolveLink(): void {
        if (!this._linkedSection && this.dataset.chipFor) {
            this._linkedSection = document.getElementById(this.dataset.chipFor);
        }
    }

    private _toggleExpand(): void {
        this._resolveLink();
        const section = this._linkedSection;
        if (!section) return;
        collapseAllChips(this.closest('chat-message'), this);
        if (section.classList.contains('turn-hidden')) {
            section.classList.remove('turn-hidden');
            section.classList.add('chip-expanded');
            this.style.opacity = '0.5';
            this.setAttribute('aria-expanded', 'true');
        } else {
            this.style.opacity = '1';
            section.classList.add('collapsing');
            setTimeout(() => {
                section.classList.remove('collapsing', 'chip-expanded');
                section.classList.add('turn-hidden');
            }, 250);
            this.setAttribute('aria-expanded', 'false');
        }
    }

    linkSection(section: HTMLElement): void {
        this._linkedSection = section;
    }

    attributeChangedCallback(name: string): void {
        if (!this._init) return;
        if (name === 'status') this._render();
    }
}

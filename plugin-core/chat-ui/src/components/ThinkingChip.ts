import {collapseAllChips} from '../helpers';

export default class ThinkingChip extends HTMLElement {
    static get observedAttributes(): string[] {
        return ['status'];
    }

    private _init = false;
    _linkedSection: HTMLElement | null = null;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this.classList.add('turn-chip');
        this.style.cursor = 'pointer';
        this._render();
        this.onclick = (e) => {
            e.stopPropagation();
            this._toggleExpand();
        };
    }

    private _render(): void {
        const status = this.getAttribute('status') || 'complete';
        if (status === 'running') this.innerHTML = '<span class="chip-spinner"></span> \uD83D\uDCAD Thinking\u2026';
        else this.textContent = '\uD83D\uDCAD Thought';
    }

    attributeChangedCallback(name: string): void {
        if (!this._init) return;
        if (name === 'status') this._render();
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
        } else {
            this.style.opacity = '1';
            section.classList.add('collapsing');
            setTimeout(() => {
                section.classList.remove('collapsing', 'chip-expanded');
                section.classList.add('turn-hidden');
            }, 250);
        }
    }

    linkSection(section: HTMLElement): void {
        this._linkedSection = section;
    }
}

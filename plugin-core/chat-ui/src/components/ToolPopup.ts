/**
 * Singleton floating popup for tool call details.
 * Anchored near the clicked chip — no inline layout shift.
 */
let instance: ToolPopup | null = null;

export function showToolPopup(chip: HTMLElement, section: HTMLElement): void {
    if (!instance) {
        instance = document.createElement('tool-popup') as ToolPopup;
        document.body.appendChild(instance);
    }
    instance.show(chip, section);
}

export function dismissToolPopup(): void {
    instance?.dismiss();
}

export function isToolPopupVisibleFor(chip: HTMLElement): boolean {
    return instance?.isVisibleFor(chip) ?? false;
}

export default class ToolPopup extends HTMLElement {
    private _init = false;
    private _activeChip: HTMLElement | null = null;
    private _onClickOutside = (e: MouseEvent) => this._handleClickOutside(e);
    private _onKeyDown = (e: KeyboardEvent) => this._handleKeyDown(e);
    private _onScroll = () => this._reposition();

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this.classList.add('tool-popup', 'tool-popup-hidden');
        this.innerHTML = `
            <div class="tool-popup-header">
                <span class="tool-popup-title"></span>
                <span class="tool-popup-close" role="button" tabindex="0" aria-label="Close">\u00d7</span>
            </div>
            <div class="tool-popup-body">
                <div class="tool-popup-params"></div>
                <div class="tool-popup-result"></div>
            </div>`;
        this.querySelector('.tool-popup-close')!.addEventListener('click', () => this.dismiss());
    }

    show(chip: HTMLElement, section: HTMLElement): void {
        // If clicking the same chip, toggle off
        if (this._activeChip === chip && !this.classList.contains('tool-popup-hidden')) {
            this.dismiss();
            return;
        }

        // Deactivate previous chip
        if (this._activeChip) {
            this._activeChip.style.opacity = '1';
            this._activeChip.setAttribute('aria-expanded', 'false');
        }

        // Populate content from the linked section
        const title = section.getAttribute('title') || '';
        this.querySelector('.tool-popup-title')!.textContent = title;

        const paramsEl = section.querySelector('.tool-params');
        const resultEl = section.querySelector('.tool-result');
        const popupParams = this.querySelector('.tool-popup-params')!;
        const popupResult = this.querySelector('.tool-popup-result')!;

        popupParams.innerHTML = paramsEl?.innerHTML || '';
        popupResult.innerHTML = resultEl?.innerHTML || '';

        // Apply kind color class from chip
        this.className = 'tool-popup';
        const kindClass = Array.from(chip.classList).find(c => c.startsWith('kind-'));
        if (kindClass) this.classList.add(kindClass);
        if (section.classList.contains('failed')) this.classList.add('failed');

        // Activate
        this._activeChip = chip;
        chip.style.opacity = '0.5';
        chip.setAttribute('aria-expanded', 'true');

        this.classList.remove('tool-popup-hidden');
        this._reposition();

        // Listeners
        setTimeout(() => {
            document.addEventListener('click', this._onClickOutside, true);
            document.addEventListener('keydown', this._onKeyDown, true);
            const scroller = document.querySelector('chat-container');
            scroller?.addEventListener('scroll', this._onScroll, {passive: true});
        }, 0);
    }

    dismiss(): void {
        if (this._activeChip) {
            this._activeChip.style.opacity = '1';
            this._activeChip.setAttribute('aria-expanded', 'false');
            this._activeChip = null;
        }
        this.classList.add('tool-popup-hidden');
        document.removeEventListener('click', this._onClickOutside, true);
        document.removeEventListener('keydown', this._onKeyDown, true);
        const scroller = document.querySelector('chat-container');
        scroller?.removeEventListener('scroll', this._onScroll);
    }

    isVisibleFor(chip: HTMLElement): boolean {
        return this._activeChip === chip && !this.classList.contains('tool-popup-hidden');
    }

    private _reposition(): void {
        if (!this._activeChip || this.classList.contains('tool-popup-hidden')) return;
        const chipRect = this._activeChip.getBoundingClientRect();
        const popupHeight = this.offsetHeight;
        const viewportH = window.innerHeight;

        // Horizontal: align left edge with chip, but clamp to viewport
        let left = chipRect.left;
        const maxLeft = window.innerWidth - this.offsetWidth - 8;
        if (left > maxLeft) left = maxLeft;
        if (left < 8) left = 8;

        // Vertical: prefer above the chip, fall back to below
        let top: number;
        if (chipRect.top - popupHeight - 6 > 8) {
            top = chipRect.top - popupHeight - 6;
        } else if (chipRect.bottom + popupHeight + 6 < viewportH) {
            top = chipRect.bottom + 6;
        } else {
            top = Math.max(8, viewportH - popupHeight - 8);
        }

        this.style.left = left + 'px';
        this.style.top = top + 'px';
    }

    private _handleClickOutside(e: MouseEvent): void {
        if (this.contains(e.target as Node)) return;
        if (this._activeChip?.contains(e.target as Node)) return;
        this.dismiss();
    }

    private _handleKeyDown(e: KeyboardEvent): void {
        if (e.key === 'Escape') {
            e.preventDefault();
            this.dismiss();
        }
    }
}

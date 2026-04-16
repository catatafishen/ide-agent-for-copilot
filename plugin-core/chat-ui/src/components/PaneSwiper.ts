/**
 * `<pane-swiper>` — Two-pane horizontal container with touch swipe
 * and programmatic switching. The left pane holds the file viewer,
 * the right pane holds the chat.
 *
 * Both panes are always mounted — switching is purely visual via
 * CSS transform on the inner track.
 */

const SWIPE_THRESHOLD = 50;   // px minimum to trigger pane switch
const VELOCITY_THRESHOLD = 0.3; // px/ms — fast flick overrides distance

export class PaneSwiper extends HTMLElement {
    private _track!: HTMLElement;
    private _leftPane!: HTMLElement;
    private _rightPane!: HTMLElement;
    private _dots!: HTMLElement;

    /** 0 = file viewer (left), 1 = chat (right) */
    private _activeIndex = 1;

    // Touch tracking
    private _startX = 0;
    private _startY = 0;
    private _startTime = 0;
    private _tracking = false;
    private _horizontal = false;
    private _currentOffset = 0;
    private _scrollerEl: HTMLElement | null = null;

    connectedCallback(): void {
        this.innerHTML = `
            <div class="ps-track">
                <div class="ps-pane ps-left"></div>
                <div class="ps-pane ps-right"></div>
            </div>
            <div class="ps-dots">
                <span class="ps-dot" data-index="0"></span>
                <span class="ps-dot active" data-index="1"></span>
            </div>`;
        this._track = this.querySelector('.ps-track') as HTMLElement;
        this._leftPane = this.querySelector('.ps-left') as HTMLElement;
        this._rightPane = this.querySelector('.ps-right') as HTMLElement;
        this._dots = this.querySelector('.ps-dots') as HTMLElement;

        this._applyTransform(false);

        // Touch events for swipe
        this.addEventListener('touchstart', this._onTouchStart.bind(this), {passive: true});
        this.addEventListener('touchmove', this._onTouchMove.bind(this), {passive: false});
        this.addEventListener('touchend', this._onTouchEnd.bind(this), {passive: true});

        // Dot click
        this._dots.addEventListener('click', (e) => {
            const dot = (e.target as HTMLElement).closest('.ps-dot') as HTMLElement | null;
            if (dot?.dataset.index) this.switchTo(Number(dot.dataset.index));
        });
    }

    get leftPane(): HTMLElement {
        return this._leftPane;
    }

    get rightPane(): HTMLElement {
        return this._rightPane;
    }

    get activeIndex(): number {
        return this._activeIndex;
    }

    /** Switch to pane by index (0=left/files, 1=right/chat). */
    switchTo(index: number): void {
        if (index < 0 || index > 1) return;
        this._activeIndex = index;
        this._applyTransform(true);
        this._updateDots();
        this.dispatchEvent(new CustomEvent('pane-changed', {detail: {index}}));
    }

    private _applyTransform(animate: boolean): void {
        this._track.style.transition = animate ? 'transform 0.3s cubic-bezier(0.4, 0, 0.2, 1)' : 'none';
        this._track.style.transform = `translateX(${-this._activeIndex * 50}%)`;
    }

    private _updateDots(): void {
        this._dots.querySelectorAll('.ps-dot').forEach((dot, i) => {
            dot.classList.toggle('active', i === this._activeIndex);
        });
    }

    // ── Touch handling ──────────────────────────────────────────────────

    private _onTouchStart(e: TouchEvent): void {
        if (e.touches.length !== 1) return;
        this._startX = e.touches[0].clientX;
        this._startY = e.touches[0].clientY;
        this._startTime = Date.now();
        this._tracking = true;
        this._horizontal = false;
        this._currentOffset = 0;
        this._scrollerEl = this._findHorizontalScroller(e.target as HTMLElement | null);
    }

    private _onTouchMove(e: TouchEvent): void {
        if (!this._tracking || e.touches.length !== 1) return;
        const dx = e.touches[0].clientX - this._startX;
        const dy = e.touches[0].clientY - this._startY;

        // Lock direction after first significant movement
        if (!this._horizontal && Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > 10) {
            this._tracking = false; // Vertical scroll — let it pass through
            return;
        }
        if (Math.abs(dx) > 10) {
            this._horizontal = true;
        }
        if (!this._horizontal) return;

        // Inside a horizontally scrollable element — defer to native scroll
        // unless already scrolled all the way in the swipe direction
        if (this._scrollerEl) {
            const el = this._scrollerEl;
            const atLeftEdge = el.scrollLeft <= 0;
            const atRightEdge = el.scrollLeft + el.clientWidth >= el.scrollWidth - 1;

            if (dx > 0 && !atLeftEdge) {
                // Swiping right but scroller can still scroll left → native scroll
                this._tracking = false;
                return;
            }
            if (dx < 0 && !atRightEdge) {
                // Swiping left but scroller can still scroll right → native scroll
                this._tracking = false;
                return;
            }
            // At boundary in swipe direction — take over as pane swipe
            this._scrollerEl = null;
        }

        e.preventDefault();

        // Clamp: don't drag past the edges
        const paneWidth = this.offsetWidth;
        const baseOffset = -this._activeIndex * paneWidth;
        let targetOffset = baseOffset + dx;
        targetOffset = Math.max(targetOffset, -paneWidth); // don't go past right
        targetOffset = Math.min(targetOffset, 0);           // don't go past left

        const pct = (targetOffset / (paneWidth * 2)) * 100;
        this._track.style.transition = 'none';
        this._track.style.transform = `translateX(${pct}%)`;
        this._currentOffset = dx;
    }

    private _onTouchEnd(): void {
        if (!this._tracking || !this._horizontal) {
            this._tracking = false;
            return;
        }
        this._tracking = false;

        const elapsed = Date.now() - this._startTime;
        const velocity = Math.abs(this._currentOffset) / Math.max(elapsed, 1);
        const dx = this._currentOffset;

        if (dx > SWIPE_THRESHOLD || (dx > 20 && velocity > VELOCITY_THRESHOLD)) {
            // Swiped right → show left pane (files)
            if (this._activeIndex > 0) this._activeIndex--;
        } else if (dx < -SWIPE_THRESHOLD || (dx < -20 && velocity > VELOCITY_THRESHOLD)) {
            // Swiped left → show right pane (chat)
            if (this._activeIndex < 1) this._activeIndex++;
        }
        this._applyTransform(true);
        this._updateDots();
        this.dispatchEvent(new CustomEvent('pane-changed', {detail: {index: this._activeIndex}}));
    }

    /** Find the nearest ancestor with horizontal overflow, or null. */
    private _findHorizontalScroller(target: HTMLElement | null): HTMLElement | null {
        let el = target;
        while (el && el !== this) {
            if (el.scrollWidth > el.clientWidth + 1) return el;
            el = el.parentElement;
        }
        return null;
    }
}

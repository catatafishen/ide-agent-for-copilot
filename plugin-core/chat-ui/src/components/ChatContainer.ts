export default class ChatContainer extends HTMLElement {
    private _init = false;
    private _autoScroll = true;
    private _messages!: HTMLDivElement;
    private _scrollRAF: number | null = null;
    private _observer!: MutationObserver;
    private _copyObs!: MutationObserver;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this._autoScroll = true;
        this._messages = document.createElement('div');
        this._messages.id = 'messages';
        this.appendChild(this._messages);

        window.addEventListener('scroll', () => {
            this._autoScroll = (window.innerHeight + window.scrollY >= document.body.scrollHeight - 20);
        });

        // Auto-scroll when children change (debounced via rAF)
        this._observer = new MutationObserver(() => {
            if (!this._scrollRAF) {
                this._scrollRAF = requestAnimationFrame(() => {
                    this._scrollRAF = null;
                    this.scrollIfNeeded();
                });
            }
        });
        this._observer.observe(this._messages, { childList: true, subtree: true, characterData: true });

        // Copy-button observer
        this._copyObs = new MutationObserver(() => {
            this._messages.querySelectorAll('pre:not([data-copy-btn]):not(.streaming)').forEach(pre => {
                (pre as HTMLElement).dataset.copyBtn = '1';
                const btn = document.createElement('button');
                btn.className = 'copy-btn';
                btn.textContent = 'Copy';
                btn.onclick = () => {
                    const code = pre.querySelector('code');
                    navigator.clipboard.writeText(code ? code.textContent! : pre.textContent!).then(() => {
                        btn.textContent = 'Copied!';
                        setTimeout(() => btn.textContent = 'Copy', 1500);
                    });
                };
                pre.appendChild(btn);
            });
        });
        this._copyObs.observe(this._messages, { childList: true, subtree: true });
    }

    get messages(): HTMLDivElement {
        return this._messages;
    }

    scrollIfNeeded(): void {
        if (this._autoScroll) {
            window.scrollTo(0, document.body.scrollHeight);
        }
    }

    forceScroll(): void {
        this._autoScroll = true;
        window.scrollTo(0, document.body.scrollHeight);
    }

    disconnectedCallback(): void {
        this._observer?.disconnect();
        this._copyObs?.disconnect();
    }
}

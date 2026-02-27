/**
 * Chat Console V2 — Web Components
 *
 * Pure custom elements (light DOM) for the chat panel.
 * Bridge wired by Kotlin: window._bridge = { openFile, openUrl, setCursor, loadMore, quickReply }
 */

/* ── Helpers ─────────────────────────────────────────── */

function b64(s) {
    const r = atob(s);
    const b = new Uint8Array(r.length);
    for (let i = 0; i < r.length; i++) b[i] = r.codePointAt(i);
    return new TextDecoder().decode(b);
}

/* ── <chat-container> ────────────────────────────────── */

class ChatContainer extends HTMLElement {
    connectedCallback() {
        if (this._init) return;
        this._init = true;
        this._autoScroll = true;
        this._messages = document.createElement('div');
        this._messages.id = 'messages';
        this.appendChild(this._messages);

        window.addEventListener('scroll', () => {
            this._autoScroll = (window.innerHeight + window.scrollY >= document.body.scrollHeight - 20);
        });

        // Auto-scroll when children change (debounced to avoid per-chunk flicker)
        this._scrollRAF = null;
        this._observer = new MutationObserver(() => {
            if (!this._scrollRAF) {
                this._scrollRAF = requestAnimationFrame(() => {
                    this._scrollRAF = null;
                    this.scrollIfNeeded();
                });
            }
        });
        this._observer.observe(this._messages, {childList: true, subtree: true, characterData: true});

        // Copy-button observer
        this._copyObs = new MutationObserver(() => {
            this._messages.querySelectorAll('pre:not([data-copy-btn]):not(.streaming)').forEach(pre => {
                pre.dataset.copyBtn = '1';
                const btn = document.createElement('button');
                btn.className = 'copy-btn';
                btn.textContent = 'Copy';
                btn.onclick = () => {
                    const code = pre.querySelector('code');
                    navigator.clipboard.writeText(code ? code.textContent : pre.textContent).then(() => {
                        btn.textContent = 'Copied!';
                        setTimeout(() => btn.textContent = 'Copy', 1500);
                    });
                };
                pre.appendChild(btn);
            });
        });
        this._copyObs.observe(this._messages, {childList: true, subtree: true});
    }

    get messages() {
        return this._messages;
    }

    scrollIfNeeded() {
        if (this._autoScroll) {
            window.scrollTo(0, document.body.scrollHeight);
        }
    }

    forceScroll() {
        this._autoScroll = true;
        window.scrollTo(0, document.body.scrollHeight);
    }

    disconnectedCallback() {
        this._observer?.disconnect();
        this._copyObs?.disconnect();
    }
}

customElements.define('chat-container', ChatContainer);

/* ── <chat-message> ──────────────────────────────────── */

class ChatMessage extends HTMLElement {
    static get observedAttributes() {
        return ['type', 'timestamp'];
    }

    connectedCallback() {
        if (this._init) return;
        this._init = true;
        const type = this.getAttribute('type') || 'agent';
        this.classList.add(type === 'user' ? 'prompt-row' : 'agent-row');
    }

    attributeChangedCallback(name, oldVal, newVal) {
        if (name === 'type' && this._init) {
            this.classList.remove('prompt-row', 'agent-row');
            this.classList.add(newVal === 'user' ? 'prompt-row' : 'agent-row');
        }
    }
}

customElements.define('chat-message', ChatMessage);

/* ── <message-bubble> ────────────────────────────────── */

class MessageBubble extends HTMLElement {
    static get observedAttributes() {
        return ['streaming', 'type'];
    }

    connectedCallback() {
        if (this._init) return;
        this._init = true;
        const parent = this.closest('chat-message');
        const isUser = parent?.getAttribute('type') === 'user';
        this.classList.add(isUser ? 'prompt-bubble' : 'agent-bubble');

        this.setAttribute('tabindex', '0');
        this.setAttribute('role', 'button');
        this.onclick = (e) => {
            if (e.target.closest('a,.turn-chip')) return;
            _collapseAllChips(parent);
            const meta = parent?.querySelector('message-meta');
            if (meta) meta.classList.toggle('show');
        };

        if (this.hasAttribute('streaming')) this._setupStreaming();
    }

    _setupStreaming() {
        if (!this._pre) {
            this._pre = document.createElement('pre');
            this._pre.className = 'streaming';
            this.innerHTML = '';
            this.appendChild(this._pre);
        }
    }

    appendStreamingText(text) {
        if (!this._pre) this._setupStreaming();
        this._pre.textContent += text;
    }

    finalize(html) {
        this.removeAttribute('streaming');
        this._pre = null;
        this.innerHTML = html;
        this.classList.remove('streaming-bubble');
    }

    set content(val) {
        this.innerHTML = val;
    }

    attributeChangedCallback(name) {
        if (!this._init) return;
        if (name === 'streaming' && this.hasAttribute('streaming')) this._setupStreaming();
        if (name === 'pending' && this.hasAttribute('pending')) this._setupPending();
    }
}

customElements.define('message-bubble', MessageBubble);

/* ── <message-meta> ──────────────────────────────────── */

class MessageMeta extends HTMLElement {
    connectedCallback() {
        if (this._init) return;
        this._init = true;
        this.classList.add('meta');
    }
}

customElements.define('message-meta', MessageMeta);

/* ── <thinking-block> ────────────────────────────────── */

class ThinkingBlock extends HTMLElement {
    connectedCallback() {
        if (this._init) return;
        this._init = true;
        this.classList.add('thinking-section');
        this.innerHTML = `<div class="thinking-content"></div>`;
    }

    get contentEl() {
        return this.querySelector('.thinking-content');
    }

    appendText(text) {
        const el = this.contentEl;
        if (el) el.textContent += text;
    }

    finalize() {
        this.removeAttribute('active');
    }
}

customElements.define('thinking-block', ThinkingBlock);

/* ── <tool-section> ──────────────────────────────────── */

class ToolSection extends HTMLElement {
    connectedCallback() {
        if (this._init) return;
        this._init = true;
        this.classList.add('tool-section', 'turn-hidden');
        this.innerHTML = `
            <div class="tool-params"></div>
            <div class="tool-result">Running...</div>`;
    }

    set params(val) {
        const el = this.querySelector('.tool-params');
        if (el) el.innerHTML = `<pre class="tool-params-code"><code>${this._esc(val)}</code></pre>`;
    }

    set result(val) {
        const el = this.querySelector('.tool-result');
        if (el) el.innerHTML = val;
    }

    updateStatus(_status) { /* status tracked on chip only */
    }

    _esc(s) {
        return s ? s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;') : '';
    }
}

customElements.define('tool-section', ToolSection);

/* ── Chip helpers ────────────────────────────────────── */

function _collapseAllChips(container, except) {
    if (!container) return;
    container.querySelectorAll('tool-chip, thinking-chip, subagent-chip').forEach(chip => {
        if (chip === except) return;
        const section = chip._linkedSection;
        if (!section || section.classList.contains('turn-hidden')) return;
        chip.style.opacity = '1';
        section.classList.add('turn-hidden');
        section.classList.remove('chip-expanded', 'collapsing', 'collapsed');
    });
}

/* ── <tool-chip> ─────────────────────────────────────── */

class ToolChip extends HTMLElement {
    static get observedAttributes() {
        return ['label', 'status', 'expanded'];
    }

    connectedCallback() {
        if (this._init) return;
        this._init = true;
        this.classList.add('turn-chip', 'tool');
        this.style.cursor = 'pointer';
        this._render();
        this.onclick = (e) => {
            e.stopPropagation();
            this._toggleExpand();
        };
    }

    _render() {
        const label = this.getAttribute('label') || '';
        const status = this.getAttribute('status') || 'running';
        const display = label.length > 50 ? label.substring(0, 47) + '\u2026' : label;
        let iconHtml = '';
        if (status === 'running') iconHtml = '<span class="chip-spinner"></span> ';
        else if (status === 'failed') this.classList.add('failed');
        this.innerHTML = iconHtml + this._esc(display);
        if (label.length > 50) this.dataset.tip = label;
    }

    _toggleExpand() {
        const section = this._linkedSection;
        if (!section) return;
        _collapseAllChips(this.closest('chat-message'), this);
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

    linkSection(section) {
        this._linkedSection = section;
    }

    attributeChangedCallback(name) {
        if (!this._init) return;
        if (name === 'status') {
            this._render();
        }
    }

    _esc(s) {
        return s ? s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;') : '';
    }
}

customElements.define('tool-chip', ToolChip);

/* ── <thinking-chip> ─────────────────────────────────── */

class ThinkingChip extends HTMLElement {
    static get observedAttributes() {
        return ['status'];
    }

    connectedCallback() {
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

    _render() {
        const status = this.getAttribute('status') || 'complete';
        if (status === 'running') this.innerHTML = '<span class="chip-spinner"></span> \uD83D\uDCAD Thinking\u2026';
        else this.textContent = '\uD83D\uDCAD Thought';
    }

    attributeChangedCallback(name) {
        if (!this._init) return;
        if (name === 'status') this._render();
    }

    _toggleExpand() {
        const section = this._linkedSection;
        if (!section) return;
        _collapseAllChips(this.closest('chat-message'), this);
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

    linkSection(section) {
        this._linkedSection = section;
    }
}

customElements.define('thinking-chip', ThinkingChip);

/* ── <subagent-chip> ─────────────────────────────────── */

class SubagentChip extends HTMLElement {
    static get observedAttributes() {
        return ['label', 'status', 'color-index'];
    }

    connectedCallback() {
        if (this._init) return;
        this._init = true;
        const ci = this.getAttribute('color-index') || '0';
        this.classList.add('turn-chip', 'subagent', 'subagent-c' + ci);
        this.style.cursor = 'pointer';
        this._render();
        this.onclick = (e) => {
            e.stopPropagation();
            this._toggleExpand();
        };
    }

    _render() {
        const label = this.getAttribute('label') || '';
        const status = this.getAttribute('status') || 'running';
        const display = label.length > 50 ? label.substring(0, 47) + '\u2026' : label;
        let html = '';
        if (status === 'running') html = '<span class="chip-spinner"></span> ';
        else if (status === 'failed') this.classList.add('failed');
        html += (label.length > 50 ? '<span>' + display + '</span>' : display);
        this.innerHTML = html;
    }

    _toggleExpand() {
        const section = this._linkedSection;
        if (!section) return;
        _collapseAllChips(this.closest('chat-message'), this);
        if (section.classList.contains('turn-hidden')) {
            section.classList.remove('turn-hidden', 'collapsed');
            section.classList.add('chip-expanded');
            this.style.opacity = '0.5';
        } else {
            this.style.opacity = '1';
            section.classList.add('turn-hidden', 'collapsed');
            section.classList.remove('chip-expanded');
        }
    }

    linkSection(section) {
        this._linkedSection = section;
    }

    attributeChangedCallback(name) {
        if (!this._init) return;
        if (name === 'status' || name === 'label') this._render();
    }
}

customElements.define('subagent-chip', SubagentChip);

/* ── <quick-replies> ─────────────────────────────────── */

class QuickReplies extends HTMLElement {
    static get observedAttributes() {
        return ['disabled'];
    }

    connectedCallback() {
        if (this._init) return;
        this._init = true;
        this.classList.add('quick-replies');
    }

    set options(arr) {
        this.innerHTML = '';
        (arr || []).forEach(text => {
            const btn = document.createElement('span');
            btn.className = 'quick-reply-btn';
            btn.textContent = text;
            btn.onclick = () => {
                if (this.hasAttribute('disabled')) return;
                this.setAttribute('disabled', '');
                this.dispatchEvent(new CustomEvent('quick-reply', {detail: {text}, bubbles: true}));
            };
            this.appendChild(btn);
        });
    }

    attributeChangedCallback(name) {
        if (name === 'disabled') this.classList.toggle('disabled', this.hasAttribute('disabled'));
    }
}

customElements.define('quick-replies', QuickReplies);

/* ── <status-message> ────────────────────────────────── */

class StatusMessage extends HTMLElement {
    static get observedAttributes() {
        return ['type', 'message'];
    }

    connectedCallback() {
        if (this._init) return;
        this._init = true;
        this._render();
    }

    _render() {
        const type = this.getAttribute('type') || 'info';
        const msg = this.getAttribute('message') || '';
        this.className = 'status-row ' + type;
        const icon = type === 'error' ? '❌' : 'ℹ';
        this.textContent = icon + ' ' + msg;
    }

    attributeChangedCallback() {
        if (this._init) this._render();
    }
}

customElements.define('status-message', StatusMessage);

/* ── <session-divider> ───────────────────────────────── */

class SessionDivider extends HTMLElement {
    static get observedAttributes() {
        return ['timestamp'];
    }

    connectedCallback() {
        if (this._init) return;
        this._init = true;
        this.classList.add('session-sep');
        this._render();
    }

    _render() {
        const ts = this.getAttribute('timestamp') || '';
        this.innerHTML = `<span class="session-sep-line"></span><span class="session-sep-label">New session 📅 ${this._esc(ts)}</span><span class="session-sep-line"></span>`;
    }

    attributeChangedCallback() {
        if (this._init) this._render();
    }

    _esc(s) {
        return s ? s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;') : '';
    }
}

customElements.define('session-divider', SessionDivider);

/* ── <load-more> ─────────────────────────────────────── */

class LoadMore extends HTMLElement {
    static get observedAttributes() {
        return ['count', 'loading'];
    }

    connectedCallback() {
        if (this._init) return;
        this._init = true;
        this.classList.add('load-more-banner');
        this._render();
        this.onclick = () => {
            if (!this.hasAttribute('loading')) {
                this.setAttribute('loading', '');
                this.dispatchEvent(new CustomEvent('load-more', {bubbles: true}));
            }
        };
    }

    _render() {
        const count = this.getAttribute('count') || '?';
        const loading = this.hasAttribute('loading');
        this.innerHTML = `<span class="load-more-text">${loading ? 'Loading...' : '▲ Load earlier messages (' + count + ' more) — click or scroll up'}</span>`;
    }

    attributeChangedCallback() {
        if (this._init) this._render();
    }
}

customElements.define('load-more', LoadMore);

/* ── Global event handlers ───────────────────────────── */

// Link interception
document.addEventListener('click', e => {
    let el = e.target;
    while (el && el.tagName !== 'A') el = el.parentElement;
    if (!el?.getAttribute('href')) return;
    const href = el.getAttribute('href');
    if (href.startsWith('openfile://')) {
        e.preventDefault();
        globalThis._bridge?.openFile(href);
    } else if (href.startsWith('http://') || href.startsWith('https://')) {
        e.preventDefault();
        globalThis._bridge?.openUrl(href);
    }
});

// Cursor management
let _lastCursor = '';
document.addEventListener('mouseover', e => {
    const el = e.target;
    let c = 'default';
    if (el.closest('a,.turn-chip,.chip-close,.prompt-ctx-chip,.quick-reply-btn')) c = 'pointer';
    else if (el.closest('p,pre,code,li,td,th,.thinking-content,.streaming')) c = 'text';
    if (c !== _lastCursor) {
        _lastCursor = c;
        globalThis._bridge?.setCursor(c);
    }
});

/* ── ChatController (Kotlin→JS bridge) ───────────────── */

const ChatController = {
    _msgs() {
        return document.querySelector('#messages');
    },
    _container() {
        return document.querySelector('chat-container');
    },
    _thinkingCounter: 0,
    _ctx: {},

    // ── Context-based state (keyed by turnId-agentId) ─────

    _getCtx(turnId, agentId) {
        const key = turnId + '-' + agentId;
        if (!this._ctx[key]) {
            this._ctx[key] = {
                msg: null, meta: null,
                textBubble: null,
                thinkingBlock: null,
            };
        }
        return this._ctx[key];
    },

    /** Ensures a single chat-message exists for this (turnId, agentId). */
    _ensureMsg(turnId, agentId) {
        const ctx = this._getCtx(turnId, agentId);
        if (!ctx.msg) {
            const msg = document.createElement('chat-message');
            msg.setAttribute('type', 'agent');
            const meta = document.createElement('message-meta');
            const now = new Date();
            const ts = String(now.getHours()).padStart(2, '0') + ':' + String(now.getMinutes()).padStart(2, '0');
            const tsSpan = document.createElement('span');
            tsSpan.className = 'ts';
            tsSpan.textContent = ts;
            meta.appendChild(tsSpan);
            msg.appendChild(meta);
            this._msgs().appendChild(msg);
            ctx.msg = msg;
            ctx.meta = meta;
        }
        return ctx;
    },

    _collapseThinkingFor(ctx) {
        if (!ctx?.thinkingBlock) return;
        ctx.thinkingBlock.removeAttribute('active');
        ctx.thinkingBlock.removeAttribute('expanded');
        ctx.thinkingBlock.classList.add('turn-hidden');
        ctx.thinkingBlock = null;
        ctx.thinkingMsg = null;
    },

    /** Start a new chat-message for the next content in this (turnId, agentId). */
    newSegment(turnId, agentId) {
        const ctx = this._getCtx(turnId, agentId);
        if (ctx.textBubble) {
            ctx.textBubble.removeAttribute('streaming');
            const p = ctx.textBubble.querySelector('.pending');
            if (p) p.remove();
        }
        this._collapseThinkingFor(ctx);
        ctx.msg = null;
        ctx.meta = null;
        ctx.textBubble = null;
    },

    // ── Public API ─────────────────────────────────────────────

    addUserMessage(text, timestamp, ctxChipsHtml) {
        const msg = document.createElement('chat-message');
        msg.setAttribute('type', 'user');
        const meta = document.createElement('message-meta');
        meta.innerHTML = '<span class="ts">' + timestamp + '</span>' + (ctxChipsHtml || '');
        msg.appendChild(meta);
        const bubble = document.createElement('message-bubble');
        bubble.setAttribute('type', 'user');
        bubble.textContent = text;
        msg.appendChild(bubble);
        this._msgs().appendChild(msg);
        this._container()?.forceScroll();
    },

    appendAgentText(turnId, agentId, text) {
        const ctx = this._getCtx(turnId, agentId);
        this._collapseThinkingFor(ctx);
        if (!ctx.textBubble) {
            if (!text.trim()) return;
            const c = this._ensureMsg(turnId, agentId);
            const bubble = document.createElement('message-bubble');
            bubble.setAttribute('streaming', '');
            c.msg.appendChild(bubble);
            c.textBubble = bubble;
        }
        ctx.textBubble.appendStreamingText(text);
        this._container()?.scrollIfNeeded();
    },

    finalizeAgentText(turnId, agentId, encodedHtml) {
        const ctx = this._getCtx(turnId, agentId);
        if (!ctx.textBubble && !encodedHtml) return;
        if (encodedHtml) {
            if (ctx.textBubble) {
                ctx.textBubble.finalize(b64(encodedHtml));
            } else {
                const c = this._ensureMsg(turnId, agentId);
                const bubble = document.createElement('message-bubble');
                c.msg.appendChild(bubble);
                bubble.finalize(b64(encodedHtml));
            }
        } else if (ctx.textBubble) {
            ctx.textBubble.remove();
            // Clean up the msg if nothing else remains
            if (ctx.msg && !ctx.msg.querySelector('message-bubble, tool-section, thinking-block')) {
                ctx.msg.remove();
                ctx.msg = null;
                ctx.meta = null;
            }
        }
        ctx.textBubble = null;
        this._container()?.scrollIfNeeded();
    },

    addThinkingText(turnId, agentId, text) {
        const ctx = this._ensureMsg(turnId, agentId);
        if (!ctx.thinkingBlock) {
            this._thinkingCounter++;
            const el = document.createElement('thinking-block');
            el.id = 'think-v2-' + this._thinkingCounter;
            el.setAttribute('active', '');
            el.setAttribute('expanded', '');
            ctx.msg.appendChild(el);
            ctx.thinkingBlock = el;
            const chip = document.createElement('thinking-chip');
            chip.setAttribute('status', 'thinking');
            chip.linkSection(el);
            ctx.meta.appendChild(chip);
            ctx.meta.classList.add('show');
        }
        ctx.thinkingBlock.appendText(text);
        this._container()?.scrollIfNeeded();
    },

    collapseThinking(turnId, agentId) {
        const ctx = this._getCtx(turnId, agentId);
        this._collapseThinkingFor(ctx);
    },

    addToolCall(turnId, agentId, id, title, paramsJson) {
        const ctx = this._ensureMsg(turnId, agentId);
        this._collapseThinkingFor(ctx);
        const section = document.createElement('tool-section');
        section.id = id;
        section.setAttribute('title', title);
        if (paramsJson) section.setAttribute('params', paramsJson);
        ctx.msg.appendChild(section);
        const chip = document.createElement('tool-chip');
        chip.setAttribute('label', title);
        chip.setAttribute('status', 'running');
        chip.dataset.chipFor = id;
        chip.linkSection(section);
        ctx.meta.appendChild(chip);
        ctx.meta.classList.add('show');
        this._container()?.scrollIfNeeded();
    },

    updateToolCall(id, status, resultHtml) {
        const section = document.getElementById(id);
        if (section) {
            const resultDiv = section.querySelector('.tool-result');
            if (resultDiv) {
                resultDiv.innerHTML = (typeof resultHtml === 'string') ? resultHtml : 'Completed';
            }
            if (status === 'failed') section.classList.add('failed');
        }
        const chip = document.querySelector('[data-chip-for="' + id + '"]');
        if (chip) chip.setAttribute('status', status === 'failed' ? 'failed' : 'complete');
    },

    addSubAgent(turnId, agentId, sectionId, displayName, colorIndex, promptText) {
        const ctx = this._ensureMsg(turnId, agentId);
        this._collapseThinkingFor(ctx);
        ctx.textBubble = null;
        const chip = document.createElement('subagent-chip');
        chip.setAttribute('label', displayName);
        chip.setAttribute('status', 'running');
        chip.setAttribute('color-index', String(colorIndex));
        chip.dataset.chipFor = 'sa-' + sectionId;
        ctx.meta.appendChild(chip);
        ctx.meta.classList.add('show');
        const promptBubble = document.createElement('message-bubble');
        promptBubble.innerHTML = '<span class="subagent-prefix subagent-c' + colorIndex + '">@' + this._esc(displayName) + '</span> ' + this._esc(promptText || '');
        ctx.msg.appendChild(promptBubble);
        const msg = document.createElement('chat-message');
        msg.setAttribute('type', 'agent');
        msg.id = 'sa-' + sectionId;
        msg.classList.add('subagent-indent', 'subagent-c' + colorIndex);
        const meta = document.createElement('message-meta');
        const now = new Date();
        const ts = String(now.getHours()).padStart(2, '0') + ':' + String(now.getMinutes()).padStart(2, '0');
        const tsSpan = document.createElement('span');
        tsSpan.className = 'ts';
        tsSpan.textContent = ts;
        meta.appendChild(tsSpan);
        msg.appendChild(meta);
        const resultBubble = document.createElement('message-bubble');
        resultBubble.id = 'result-' + sectionId;
        resultBubble.classList.add('subagent-result');
        msg.appendChild(resultBubble);
        this._msgs().appendChild(msg);
        chip.linkSection(msg);
        this._container()?.scrollIfNeeded();
    },

    updateSubAgent(sectionId, status, resultHtml) {
        const el = document.getElementById('result-' + sectionId);
        if (el) {
            el.innerHTML = resultHtml || (status === 'completed' ? 'Completed' : '<span style="color:var(--error)">\u2716 Failed</span>');
        }
        const chip = document.querySelector('[data-chip-for="sa-' + sectionId + '"]');
        if (chip) chip.setAttribute('status', status === 'failed' ? 'failed' : 'complete');
        this._container()?.scrollIfNeeded();
    },

    /** Add a tool call chip+section to a sub-agent's result message. */
    addSubAgentToolCall(subAgentDomId, toolDomId, title, paramsJson) {
        const msg = document.getElementById('sa-' + subAgentDomId);
        if (!msg) return;
        const meta = msg.querySelector('message-meta');
        const section = document.createElement('tool-section');
        section.id = toolDomId;
        section.setAttribute('title', title);
        if (paramsJson) section.setAttribute('params', paramsJson);
        const resultBubble = msg.querySelector('.subagent-result');
        if (resultBubble) msg.insertBefore(section, resultBubble);
        else msg.appendChild(section);
        const chip = document.createElement('tool-chip');
        chip.setAttribute('label', title);
        chip.setAttribute('status', 'running');
        chip.dataset.chipFor = toolDomId;
        chip.linkSection(section);
        if (meta) {
            meta.appendChild(chip);
            meta.classList.add('show');
        }
        this._container()?.scrollIfNeeded();
    },

    addError(message) {
        const el = document.createElement('status-message');
        el.setAttribute('type', 'error');
        el.setAttribute('message', message);
        this._msgs().appendChild(el);
        this._container()?.scrollIfNeeded();
    },

    addInfo(message) {
        const el = document.createElement('status-message');
        el.setAttribute('type', 'info');
        el.setAttribute('message', message);
        this._msgs().appendChild(el);
        this._container()?.scrollIfNeeded();
    },

    addSessionSeparator(timestamp) {
        const el = document.createElement('session-divider');
        el.setAttribute('timestamp', timestamp);
        this._msgs().appendChild(el);
    },

    showPlaceholder(text) {
        this.clear();
        this._msgs().innerHTML = '<div class="placeholder">' + this._esc(text) + '</div>';
    },

    clear() {
        this._msgs().innerHTML = '';
        this._ctx = {};
        this._thinkingCounter = 0;
    },

    finalizeTurn(turnId, statsJson) {
        const ctx = this._ctx[turnId + '-main'];
        if (ctx?.textBubble && !ctx.textBubble.textContent?.trim()) {
            ctx.textBubble.remove();
        }
        let meta = ctx?.meta;
        if (!meta) {
            const rows = this._msgs().querySelectorAll('chat-message[type="agent"]:not(.subagent-indent)');
            if (rows.length) meta = rows[rows.length - 1].querySelector('message-meta');
        }
        if (statsJson && meta) {
            const stats = typeof statsJson === 'string' ? JSON.parse(statsJson) : statsJson;
            if (stats.model) {
                const chip = document.createElement('span');
                chip.className = 'turn-chip stats';
                chip.textContent = stats.mult || '1x';
                chip.dataset.tip = stats.model;
                meta.appendChild(chip);
                meta.classList.add('show');
            }
        }
        if (ctx) {
            ctx.thinkingBlock = null;
            ctx.textBubble = null;
        }
        this._container()?.scrollIfNeeded();
        this._trimMessages();
    },

    showQuickReplies(options) {
        this.disableQuickReplies();
        if (!options?.length) return;
        const el = document.createElement('quick-replies');
        el.options = options;
        this._msgs().appendChild(el);
        this._container()?.scrollIfNeeded();
    },

    disableQuickReplies() {
        document.querySelectorAll('quick-replies:not([disabled])').forEach(el => el.setAttribute('disabled', ''));
    },

    setPromptStats(model, multiplier) {
        const rows = document.querySelectorAll('.prompt-row');
        const row = rows[rows.length - 1];
        if (!row) return;
        let meta = row.querySelector('message-meta');
        if (!meta) {
            meta = document.createElement('message-meta');
            row.insertBefore(meta, row.firstChild);
        }
        meta.classList.add('show');
        const chip = document.createElement('span');
        chip.className = 'turn-chip stats';
        chip.textContent = multiplier;
        chip.dataset.tip = model;
        meta.appendChild(chip);
    },

    restoreBatch(encodedHtml) {
        const html = b64(encodedHtml);
        const temp = document.createElement('div');
        temp.innerHTML = html;
        const msgs = this._msgs();
        const first = msgs.firstChild;
        while (temp.firstChild) {
            if (first) msgs.insertBefore(temp.firstChild, first);
            else msgs.appendChild(temp.firstChild);
        }
    },

    showLoadMore(count) {
        let el = document.querySelector('load-more');
        if (!el) {
            el = document.createElement('load-more');
            this._msgs().insertBefore(el, this._msgs().firstChild);
        }
        el.setAttribute('count', String(count));
        el.removeAttribute('loading');
    },

    removeLoadMore() {
        document.querySelector('load-more')?.remove();
    },

    _trimMessages() {
        const msgs = this._msgs();
        if (!msgs) return;
        const rows = msgs.querySelectorAll('chat-message,thinking-block,tool-section,status-message');
        if (rows.length > 100) for (let i = 0; i < rows.length - 100; i++) rows[i].remove();
    },

    _esc(s) {
        return s ? s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;') : '';
    },
};
window.ChatController = ChatController;

// Quick-reply bridge: listen for custom events and forward to Kotlin
document.addEventListener('quick-reply', e => {
    globalThis._bridge?.quickReply(e.detail.text);
});
document.addEventListener('load-more', () => {
    globalThis._bridge?.loadMore();
});

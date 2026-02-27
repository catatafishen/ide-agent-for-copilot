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

        // Auto-scroll when children change
        this._observer = new MutationObserver(() => this.scrollIfNeeded());
        this._observer.observe(this._messages, {childList: true, subtree: true, characterData: true});

        // Copy-button observer
        this._copyObs = new MutationObserver(() => {
            this._messages.querySelectorAll('pre:not([data-copy-btn])').forEach(pre => {
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
                pre.parentElement.insertBefore(btn, pre.nextElementSibling);
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
            requestAnimationFrame(() => {
                document.body.style.opacity = '0.999';
                requestAnimationFrame(() => document.body.style.opacity = '1');
            });
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
        return ['streaming', 'pending', 'type'];
    }

    connectedCallback() {
        if (this._init) return;
        this._init = true;
        const parent = this.closest('chat-message');
        const isUser = parent?.getAttribute('type') === 'user';
        this.classList.add(isUser ? 'prompt-bubble' : 'agent-bubble');

        if (isUser) {
            this.setAttribute('tabindex', '0');
            this.setAttribute('role', 'button');
            this.onclick = () => {
                const meta = parent?.querySelector('message-meta');
                if (meta) meta.classList.toggle('show');
            };
        }

        if (this.hasAttribute('streaming')) this._setupStreaming();
        if (this.hasAttribute('pending')) this._setupPending();
    }

    _setupStreaming() {
        if (!this._pre) {
            this._pre = document.createElement('pre');
            this._pre.className = 'streaming';
            this.innerHTML = '';
            this.appendChild(this._pre);
        }
    }

    _setupPending() {
        if (!this.querySelector('.agent-pending')) {
            const span = document.createElement('span');
            span.className = 'agent-pending';
            span.textContent = 'Working\u2026';
            this.appendChild(span);
        }
    }

    appendStreamingText(text) {
        if (!this._pre) this._setupStreaming();
        this._pre.textContent += text;
    }

    finalize(html) {
        this.removeAttribute('streaming');
        this.removeAttribute('pending');
        this._pre = null;
        this.innerHTML = html;
        this.classList.remove('streaming-bubble');
    }

    removePending() {
        const p = this.querySelector('.agent-pending');
        if (p) p.remove();
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
    static get observedAttributes() {
        return ['expanded', 'active'];
    }

    connectedCallback() {
        if (this._init) return;
        this._init = true;
        this.classList.add('collapse-section', 'thinking-section');
        if (!this.hasAttribute('expanded')) this.classList.add('collapsed');

        const isActive = this.hasAttribute('active');
        this.innerHTML = `
            <div class="collapse-header" tabindex="0" role="button" aria-expanded="${this.hasAttribute('expanded')}">
                <span class="collapse-icon${isActive ? ' thinking-pulse' : ''}">💭</span>
                <span class="collapse-label">${isActive ? 'Thinking...' : 'Thought process'}</span>
                <span class="caret">${this.hasAttribute('expanded') ? '▾' : '▸'}</span>
            </div>
            <div class="collapse-content"></div>`;

        this.querySelector('.collapse-header').onclick = () => this.toggle();
    }

    get contentEl() {
        return this.querySelector('.collapse-content');
    }

    appendText(text) {
        const el = this.contentEl;
        if (el) el.textContent += text;
    }

    toggle() {
        const expanded = this.classList.toggle('collapsed');
        const isOpen = !this.classList.contains('collapsed');
        this.classList.toggle('open', isOpen);
        this.querySelector('.caret').textContent = isOpen ? '▾' : '▸';
        this.querySelector('.collapse-header')?.setAttribute('aria-expanded', String(isOpen));
    }

    finalize() {
        this.removeAttribute('active');
        const icon = this.querySelector('.collapse-icon');
        if (icon) icon.classList.remove('thinking-pulse');
        const label = this.querySelector('.collapse-label');
        if (label) label.textContent = 'Thought process';
        if (!this.classList.contains('collapsed')) {
            this.classList.add('collapsed');
            this.querySelector('.caret').textContent = '▸';
        }
    }
}

customElements.define('thinking-block', ThinkingBlock);

/* ── <tool-section> ──────────────────────────────────── */

class ToolSection extends HTMLElement {
    static get observedAttributes() {
        return ['title', 'status'];
    }

    connectedCallback() {
        if (this._init) return;
        this._init = true;
        this.classList.add('collapse-section', 'tool-section', 'turn-hidden', 'collapsed');
        const title = this.getAttribute('title') || 'Tool Call';
        this.innerHTML = `
            <div class="collapse-header" tabindex="0" role="button" aria-expanded="false">
                <span class="collapse-icon tool-spinner"></span>
                <span class="collapse-label">${this._esc(title)}</span>
                <span class="caret">▸</span>
            </div>
            <div class="collapse-content">
                <div class="tool-params"></div>
                <div class="tool-result">Running...</div>
            </div>`;
        this.querySelector('.collapse-header').onclick = () => {
            this.classList.toggle('collapsed');
            const isOpen = !this.classList.contains('collapsed');
            this.classList.toggle('open', isOpen);
            this.querySelector('.caret').textContent = isOpen ? '▾' : '▸';
        };
    }

    set params(val) {
        const el = this.querySelector('.tool-params');
        if (el) el.innerHTML = `<pre class="tool-params-code"><code>${this._esc(val)}</code></pre>`;
    }

    set result(val) {
        const el = this.querySelector('.tool-result');
        if (el) el.innerHTML = val;
    }

    updateStatus(status) {
        this.setAttribute('status', status);
        const icon = this.querySelector('.collapse-icon');
        if (!icon) return;
        icon.classList.remove('tool-spinner');
        if (status === 'failed') {
            icon.textContent = '✖';
            icon.style.color = 'var(--error)';
        } else {
            icon.textContent = '✓';
            icon.style.color = 'var(--agent)';
        }
    }

    _esc(s) {
        return s ? s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;') : '';
    }
}

customElements.define('tool-section', ToolSection);

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
        if (section.classList.contains('turn-hidden')) {
            // Place section right before the agent-row containing this chip
            const agentRow = this.closest('.agent-row');
            if (agentRow?.parentElement) {
                agentRow.parentElement.insertBefore(section, agentRow);
            }
            section.classList.remove('turn-hidden', 'collapsed');
            section.classList.add('chip-expanded');
            this.style.opacity = '0.5';
            this._ensureClose(section);
        } else {
            this._close(section);
        }
    }

    _ensureClose(section) {
        const cc = section.querySelector('.collapse-content');
        if (cc && !cc.querySelector('.chip-close')) {
            const btn = document.createElement('span');
            btn.className = 'chip-close';
            btn.textContent = '\u2715';
            btn.onclick = (e) => {
                e.stopPropagation();
                this._close(section);
            };
            cc.insertBefore(btn, cc.firstChild);
        }
    }

    _close(section) {
        this.style.opacity = '1';
        const btn = section.querySelector('.chip-close');
        if (btn) btn.remove();
        section.classList.add('collapsing');
        setTimeout(() => {
            section.classList.remove('collapsing');
            section.classList.add('turn-hidden', 'collapsed');
            section.classList.remove('chip-expanded');
        }, 250);
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
        return ['expanded'];
    }

    connectedCallback() {
        if (this._init) return;
        this._init = true;
        this.classList.add('turn-chip');
        this.style.cursor = 'pointer';
        this.textContent = '\uD83D\uDCAD Thought';
        this.onclick = (e) => {
            e.stopPropagation();
            this._toggleExpand();
        };
    }

    _toggleExpand() {
        const section = this._linkedSection;
        if (!section) return;
        if (section.classList.contains('turn-hidden')) {
            const agentRow = this.closest('.agent-row');
            if (agentRow?.parentElement) agentRow.parentElement.insertBefore(section, agentRow);
            section.classList.remove('turn-hidden', 'collapsed');
            section.classList.add('chip-expanded');
            this.style.opacity = '0.5';
            const cc = section.querySelector('.collapse-content');
            if (cc && !cc.querySelector('.chip-close')) {
                const btn = document.createElement('span');
                btn.className = 'chip-close';
                btn.textContent = '\u2715';
                btn.onclick = (e) => {
                    e.stopPropagation();
                    this.style.opacity = '1';
                    btn.remove();
                    section.classList.add('collapsing');
                    setTimeout(() => {
                        section.classList.remove('collapsing');
                        section.classList.add('turn-hidden', 'collapsed');
                        section.classList.remove('chip-expanded');
                    }, 250);
                };
                cc.insertBefore(btn, cc.firstChild);
            }
        } else {
            this.style.opacity = '1';
            section.querySelector('.chip-close')?.remove();
            section.classList.add('turn-hidden', 'collapsed');
            section.classList.remove('chip-expanded');
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
        if (section.classList.contains('turn-hidden')) {
            const agentRow = this.closest('.agent-row');
            if (agentRow?.parentElement) agentRow.parentElement.insertBefore(section, agentRow);
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

/* ── <subagent-block> ────────────────────────────────── */

class SubagentBlock extends HTMLElement {
    static get observedAttributes() {
        return ['color-index', 'agent-name'];
    }

    connectedCallback() {
        if (this._init) return;
        this._init = true;
        const ci = this.getAttribute('color-index') || '0';
        this.classList.add('subagent-c' + ci);
    }

    attributeChangedCallback(name, oldVal, newVal) {
        if (name === 'color-index' && this._init) {
            this.className = this.className.replace(/subagent-c\d/, 'subagent-c' + newVal);
        }
    }
}

customElements.define('subagent-block', SubagentBlock);

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
    if (el.closest('a,.collapse-header,.turn-chip,.chip-close,.prompt-ctx-chip,.quick-reply-btn')) c = 'pointer';
    else if (el.closest('p,pre,code,li,td,th,.collapse-content,.streaming')) c = 'text';
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
    _currentAgentMsg: null,
    _currentBubble: null,
    _currentThinking: null,
    _thinkingCounter: 0,
    _pendingMeta: null,

    addUserMessage(text, timestamp, ctxChipsHtml) {
        this._finalizeAgent();
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

    _ensureAgentMessage() {
        if (this._currentAgentMsg) return;
        const msg = document.createElement('chat-message');
        msg.setAttribute('type', 'agent');
        const meta = document.createElement('message-meta');
        this._pendingMeta = meta;
        msg.appendChild(meta);
        const bubble = document.createElement('message-bubble');
        bubble.setAttribute('streaming', '');
        msg.appendChild(bubble);
        this._currentAgentMsg = msg;
        this._currentBubble = bubble;
        this._msgs().appendChild(msg);
    },

    appendAgentText(text) {
        this._collapseThinkingInternal();
        if (!this._currentBubble && !text.trim()) return;
        this._ensureAgentMessage();
        this._currentBubble.appendStreamingText(text);
        this._container()?.scrollIfNeeded();
    },

    finalizeAgentText(encodedHtml) {
        if (!this._currentBubble) return;
        if (encodedHtml) {
            this._currentBubble.finalize(b64(encodedHtml));
        } else {
            this._currentAgentMsg?.remove();
        }
        this._currentBubble = null;
        this._container()?.scrollIfNeeded();
    },

    _finalizeAgent() {
        this._currentBubble = null;
        this._currentAgentMsg = null;
        this._pendingMeta = null;
        this._collapseThinkingInternal();
    },

    addThinkingText(text) {
        if (!this._currentThinking) {
            this._thinkingCounter++;
            const el = document.createElement('thinking-block');
            el.id = 'think-v2-' + this._thinkingCounter;
            el.setAttribute('active', '');
            el.setAttribute('expanded', '');
            this._currentThinking = el;
            if (this._currentAgentMsg) {
                this._msgs().insertBefore(el, this._currentAgentMsg);
            } else {
                this._msgs().appendChild(el);
            }
        }
        this._currentThinking.appendText(text);
        this._container()?.scrollIfNeeded();
    },

    collapseThinking() {
        this._collapseThinkingInternal();
    },

    _collapseThinkingInternal() {
        if (!this._currentThinking) return;
        const el = this._currentThinking;
        this._currentThinking = null;
        el.finalize();
        this._ensureAgentMessage();
        const chip = document.createElement('thinking-chip');
        chip.linkSection(el);
        this._pendingMeta?.appendChild(chip);
        this._pendingMeta?.classList.add('show');
        el.classList.add('turn-hidden');
    },

    addToolCall(sectionId, displayName, paramsJson) {
        this._collapseThinkingInternal();
        this._ensureAgentMessage();
        const section = document.createElement('tool-section');
        section.id = sectionId;
        section.setAttribute('title', displayName);
        if (paramsJson) section.params = paramsJson;
        this._msgs().insertBefore(section, this._currentAgentMsg);
        const chip = document.createElement('tool-chip');
        chip.setAttribute('label', displayName);
        chip.setAttribute('status', 'running');
        chip.dataset.chipFor = sectionId;
        chip.linkSection(section);
        this._pendingMeta?.appendChild(chip);
        this._pendingMeta?.classList.add('show');
        this._container()?.scrollIfNeeded();
    },

    updateToolCall(sectionId, status, resultHtml) {
        const section = document.getElementById(sectionId);
        if (section) {
            section.updateStatus(status);
            if (resultHtml) section.result = resultHtml;
        }
        const chip = document.querySelector('[data-chip-for="' + sectionId + '"]');
        if (chip) chip.setAttribute('status', status === 'failed' ? 'failed' : 'complete');
    },

    addSubAgent(sectionId, displayName, colorIndex, promptText) {
        this._collapseThinkingInternal();
        this._ensureAgentMessage();
        const block = document.createElement('subagent-block');
        block.id = 'sa-' + sectionId;
        block.setAttribute('color-index', String(colorIndex));
        const promptRow = document.createElement('div');
        promptRow.classList.add('agent-row');
        const promptBubble = document.createElement('div');
        promptBubble.classList.add('agent-bubble');
        promptBubble.innerHTML = '<span class="subagent-prefix">@' + this._esc(displayName) + '</span> ' + this._esc(promptText || '');
        promptRow.appendChild(promptBubble);
        block.appendChild(promptRow);
        const resultRow = document.createElement('div');
        resultRow.classList.add('agent-row');
        const resultMeta = document.createElement('message-meta');
        resultMeta.id = 'meta-sa-' + sectionId;
        resultRow.appendChild(resultMeta);
        const resultBubble = document.createElement('div');
        resultBubble.classList.add('subagent-bubble');
        resultBubble.id = 'result-' + sectionId;
        resultBubble.innerHTML = '<span class="subagent-pending">Working\u2026</span>';
        resultRow.appendChild(resultBubble);
        block.appendChild(resultRow);
        this._msgs().insertBefore(block, this._currentAgentMsg);
        const chip = document.createElement('subagent-chip');
        chip.setAttribute('label', displayName);
        chip.setAttribute('status', 'running');
        chip.setAttribute('color-index', String(colorIndex));
        chip.dataset.chipFor = 'sa-' + sectionId;
        chip.linkSection(block);
        this._pendingMeta?.appendChild(chip);
        this._pendingMeta?.classList.add('show');
        this._container()?.scrollIfNeeded();
    },

    updateSubAgent(sectionId, status, resultHtml) {
        const el = document.getElementById('result-' + sectionId);
        if (el) el.innerHTML = resultHtml || (status === 'completed' ? 'Completed' : '<span style="color:var(--error)">\u2716 Failed</span>');
        const chip = document.querySelector('[data-chip-for="sa-' + sectionId + '"]');
        if (chip) chip.setAttribute('status', status === 'failed' ? 'failed' : 'complete');
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
        this._finalizeAgent();
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
        this._currentBubble = null;
        this._currentAgentMsg = null;
        this._pendingMeta = null;
        this._currentThinking = null;
        this._thinkingCounter = 0;
    },

    finalizeTurn(statsJson) {
        if (this._currentBubble) this._currentBubble.removePending();
        if (statsJson && this._pendingMeta) {
            const stats = typeof statsJson === 'string' ? JSON.parse(statsJson) : statsJson;
            if (stats.model) {
                const chip = document.createElement('span');
                chip.className = 'turn-chip stats';
                chip.textContent = stats.mult || '1x';
                chip.dataset.tip = stats.model;
                this._pendingMeta.appendChild(chip);
                this._pendingMeta.classList.add('show');
            }
        }
        this._currentAgentMsg = null;
        this._currentBubble = null;
        this._pendingMeta = null;
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
        const rows = msgs.querySelectorAll('chat-message,thinking-block,tool-section,subagent-block,status-message');
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

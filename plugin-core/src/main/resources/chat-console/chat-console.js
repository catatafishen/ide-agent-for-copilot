/**
 * Chat Console Panel JavaScript
 *
 * Bridge functions are wired up by Kotlin via window._bridge before this script loads:
 * window._bridge.openFile(href) - open a file link
 * window._bridge.setCursor(type) - set cursor type
 * window._bridge.loadMore() - load earlier messages
 */

let autoScroll = true;
window.addEventListener('scroll', function () {
    autoScroll = (window.innerHeight + window.scrollY >= document.body.scrollHeight - 20);
});

function scrollIfNeeded() {
    if (autoScroll) window.scrollTo(0, document.body.scrollHeight);
}

function b64(s) {
    const r = atob(s);
    const b = new Uint8Array(r.length);
    for (let i = 0; i < r.length; i++) b[i] = r.codePointAt(i);
    return new TextDecoder().decode(b);
}

globalThis._loadingMore = false;

function loadMore() {
    if (globalThis._loadingMore) return;
    globalThis._loadingMore = true;
    const s = document.getElementById('load-more-sentinel');
    if (s) {
        const t = s.querySelector('.load-more-text');
        if (t) t.textContent = 'Loading...';
    }
    globalThis._bridge.loadMore();
}

function _toggleSection(id) {
    const el = document.getElementById(id);
    if (!el) return;
    if (el.dataset.chipOwned && !el.classList.contains('collapsed')) {
        el.classList.add('turn-hidden', 'collapsed');
        el.classList.remove('chip-expanded', 'open');
        const hdr = el.querySelector('.collapse-header');
        if (hdr) hdr.setAttribute('aria-expanded', 'false');
        const btn = el.querySelector('.chip-close');
        if (btn) btn.remove();
        const chip = document.querySelector('[data-chip-for="' + id + '"]');
        if (chip) chip.style.opacity = '1';
        return;
    }
    el.classList.toggle('collapsed');
    const isOpen = !el.classList.contains('collapsed');
    el.classList.toggle('open', isOpen);
    const hdr = el.querySelector('.collapse-header');
    if (hdr) hdr.setAttribute('aria-expanded', String(isOpen));
    el.querySelector('.caret').textContent = el.classList.contains('collapsed') ? '\u25B8' : '\u25BE';
}

function toggleTool(id) {
    _toggleSection(id);
}

function toggleThinking(id) {
    _toggleSection(id);
}

function toggleMeta(el) {
    const row = el.parentElement;
    const m = row ? row.querySelector('.meta') : null;
    if (m) m.classList.toggle('show');
}

function animateCollapse(el, callback) {
    el.classList.add('collapsing');
    setTimeout(function () {
        el.classList.remove('collapsing');
        callback();
    }, 250);
}

function _findNextAgentMeta(el) {
    let sib = el.nextElementSibling;
    while (sib) {
        if (sib.classList.contains('agent-row')) {
            return sib.querySelector('.meta');
        }
        sib = sib.nextElementSibling;
    }
    return null;
}

function _closeChipSection(el, chip) {
    chip.style.opacity = '1';
    const btn = el.querySelector('.chip-close');
    if (btn) btn.remove();
    animateCollapse(el, function () {
        el.classList.add('turn-hidden', 'collapsed');
        el.classList.remove('chip-expanded');
    });
}

function _ensureCloseButton(el, chip) {
    const cc = el.querySelector('.collapse-content');
    if (cc && !cc.querySelector('.chip-close')) {
        const btn = document.createElement('span');
        btn.className = 'chip-close';
        btn.textContent = '\u2715';
        btn.onclick = function (e) {
            e.stopPropagation();
            _closeChipSection(el, chip);
        };
        cc.insertBefore(btn, cc.firstChild);
    }
}

function _attachChipToggle(chip, el) {
    chip.onclick = function (ev) {
        ev.stopPropagation();
        if (el.classList.contains('turn-hidden')) {
            el.classList.remove('turn-hidden', 'collapsed');
            el.classList.add('chip-expanded');
            chip.style.opacity = '0.5';
            _ensureCloseButton(el, chip);
        } else {
            _closeChipSection(el, chip);
        }
    };
}

function _createChipForElement(el) {
    const chip = document.createElement('span');
    if (el.classList.contains('thinking-section')) {
        chip.className = 'turn-chip';
        chip.textContent = '\uD83D\uDCAD Thought';
    } else if (el.classList.contains('tool-section')) {
        const lbl = el.querySelector('.collapse-label');
        const icon = el.querySelector('.collapse-icon');
        const failed = icon?.style.color === 'red';
        chip.className = 'turn-chip tool' + (failed ? ' failed' : '');
        chip.textContent = lbl ? lbl.textContent : 'Tool';
    } else if (el.classList.contains('context-section')) {
        chip.className = 'turn-chip ctx';
        const n = el.querySelectorAll('.ctx-file').length;
        chip.textContent = '\uD83D\uDCCE ' + n + ' file' + (n === 1 ? '' : 's');
    } else if (el.classList.contains('status-row')) {
        if (el.classList.contains('error')) {
            chip.className = 'turn-chip err';
            chip.textContent = '\u274C Error';
        } else {
            chip.className = 'turn-chip';
            chip.textContent = '\u2139\uFE0F Info';
        }
    }
    chip.style.cursor = 'pointer';
    return chip;
}

function finalizeTurn(stats) {
    const items = document.querySelectorAll(
        '.thinking-section:not(.turn-hidden),' +
        '.tool-section:not(.turn-hidden),' +
        '.context-section:not(.turn-hidden),' +
        '.status-row:not(.turn-hidden)'
    );
    const lastBubbles = document.querySelectorAll('.agent-bubble');
    const lastBubble = lastBubbles.length > 0 ? lastBubbles[lastBubbles.length - 1] : null;
    const lastRow = lastBubble ? lastBubble.parentElement : null;
    const lastMeta = lastRow ? lastRow.querySelector('.meta') : null;
    items.forEach(function (el) {
        const targetMeta = _findNextAgentMeta(el) || lastMeta;
        if (!targetMeta) return;
        const chip = _createChipForElement(el);
        chip.dataset.chipFor = el.id || '';
        _attachChipToggle(chip, el);
        el.dataset.chipOwned = '1';
        targetMeta.appendChild(chip);
        targetMeta.classList.add('show');
        animateCollapse(el, function () {
            el.classList.add('turn-hidden');
        });
    });
    if (stats?.mult) {
        const existing = document.getElementById('turn-stats');
        if (existing) {
            existing.textContent = stats.mult;
            const short = stats.model.split('/').pop().substring(0, 30);
            existing.dataset.tip = short + ' \u00B7 ' + stats.tools + ' tool' + (stats.tools === 1 ? '' : 's');
            existing.removeAttribute('id');
        }
    }
    scrollIfNeeded();
}

function collapseToolToChip(elId) {
    const el = document.getElementById(elId);
    if (!el || el.dataset.chipOwned) return;
    const targetMeta = _findNextAgentMeta(el);
    if (!targetMeta) {
        el.dataset.pendingCollapse = '1';
        return;
    }
    _doCollapseToolToChip(el, elId, targetMeta);
}

function _doCollapseToolToChip(el, elId, targetMeta) {
    const lbl = el.querySelector('.collapse-label');
    const icon = el.querySelector('.collapse-icon');
    const failed = icon?.style.color === 'red';
    const chip = document.createElement('span');
    // Detect sub-agent sections by their color class
    const saMatch = el.className.match(/subagent-\w+/);
    if (saMatch) {
        chip.className = 'turn-chip subagent ' + saMatch[0] + (failed ? ' failed' : '');
    } else {
        chip.className = 'turn-chip tool' + (failed ? ' failed' : '');
    }
    const fullText = (lbl ? lbl.textContent : 'Tool');
    chip.textContent = fullText.length > 50 ? fullText.substring(0, 47) + '\u2026' : fullText;
    if (fullText.length > 50) chip.dataset.tip = fullText;
    el.dataset.chipOwned = '1';
    chip.dataset.chipFor = elId;
    chip.style.cursor = 'pointer';
    _attachChipToggle(chip, el);
    targetMeta.appendChild(chip);
    targetMeta.classList.add('show');
    animateCollapse(el, function () {
        el.classList.add('turn-hidden');
    });
    scrollIfNeeded();
}

function collapseThinkingToChip(elId) {
    const el = document.getElementById(elId);
    if (!el || el.dataset.chipOwned) return;
    const targetMeta = _findNextAgentMeta(el);
    if (!targetMeta) {
        el.dataset.pendingCollapse = '1';
        return;
    }
    _doCollapseThinkingToChip(el, elId, targetMeta);
}

function _doCollapseThinkingToChip(el, elId, targetMeta) {
    const chip = document.createElement('span');
    chip.className = 'turn-chip';
    chip.textContent = '\uD83D\uDCAD Thought';
    el.dataset.chipOwned = '1';
    chip.dataset.chipFor = elId;
    chip.style.cursor = 'pointer';
    _attachChipToggle(chip, el);
    targetMeta.appendChild(chip);
    targetMeta.classList.add('show');
    animateCollapse(el, function () {
        el.classList.add('turn-hidden');
    });
    scrollIfNeeded();
}

function collapsePendingTools() {
    const pending = document.querySelectorAll('.tool-section[data-pending-collapse], .thinking-section[data-pending-collapse]');
    pending.forEach(function (el) {
        const targetMeta = _findNextAgentMeta(el);
        if (!targetMeta) return;
        delete el.dataset.pendingCollapse;
        if (el.classList.contains('thinking-section')) {
            _doCollapseThinkingToChip(el, el.id, targetMeta);
        } else {
            _doCollapseToolToChip(el, el.id, targetMeta);
        }
    });
}


// noinspection SpellCheckingInspection
document.addEventListener('click', function (e) {
    let el = e.target;
    while (el && el.tagName !== 'A') el = el.parentElement;
    if (!el?.getAttribute('href')) return;
    const href = el.getAttribute('href');
    if (href.indexOf('openfile://') === 0) {
        e.preventDefault();
        globalThis._bridge.openFile(href);
    } else if (href.indexOf('http://') === 0 || href.indexOf('https://') === 0) {
        e.preventDefault();
        globalThis._bridge.openUrl(href);
    }
});

let _lastCursor = '';
document.addEventListener('mouseover', function (e) {
    const el = e.target;
    let c = 'default';
    if (el.closest('a,.collapse-header,.turn-chip,.chip-close,.prompt-ctx-chip,.quick-reply-btn')) c = 'pointer';
    else if (el.closest('p,pre,code,li,td,th,.collapse-content,.streaming')) c = 'text';
    if (c !== _lastCursor) {
        _lastCursor = c;
        globalThis._bridge.setCursor(c);
    }
});

function showQuickReplies(options) {
    disableQuickReplies();
    const c = document.getElementById('container');
    if (!c || !options?.length) return;
    const div = document.createElement('div');
    div.className = 'quick-replies';
    div.id = 'active-quick-replies';
    options.forEach(function (text) {
        const btn = document.createElement('span');
        btn.className = 'quick-reply-btn';
        btn.textContent = text;
        btn.onclick = function () {
            if (div.classList.contains('disabled')) return;
            div.classList.add('disabled');
            globalThis._bridge.quickReply(text);
        };
        div.appendChild(btn);
    });
    const ind = document.getElementById('processing-ind');
    if (ind) ind.before(div);
    else c.appendChild(div);
    scrollIfNeeded();
}

function disableQuickReplies() {
    const all = document.querySelectorAll('.quick-replies:not(.disabled)');
    all.forEach(function (el) {
        el.classList.add('disabled');
    });
}

/* --- Code block copy buttons --- */
function _handleCopyClick(btn, pre) {
    const code = pre.querySelector('code');
    const text = code ? code.textContent : pre.textContent;
    navigator.clipboard.writeText(text).then(function () {
        btn.textContent = 'Copied!';
        setTimeout(function () { btn.textContent = 'Copy'; }, 1500);
    });
}

const _copyObserver = new MutationObserver(function () {
    document.querySelectorAll('pre:not([data-copy-btn])').forEach(function (pre) {
        pre.dataset.copyBtn = '1';
        const btn = document.createElement('button');
        btn.className = 'copy-btn';
        btn.textContent = 'Copy';
        btn.tabIndex = 0;
        btn.setAttribute('role', 'button');
        btn.onclick = function () { _handleCopyClick(btn, pre); };
        // Insert button as a sibling after the pre element instead of as a child
        // This prevents the button text from appearing in the code content during streaming
        pre.parentElement.insertBefore(btn, pre.nextElementSibling);
    });
});
_copyObserver.observe(document.body, {childList: true, subtree: true});

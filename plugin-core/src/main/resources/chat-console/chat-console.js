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
    for (let i = 0; i < r.length; i++) b[i] = r.charCodeAt(i);
    return new TextDecoder().decode(b);
}

window._loadingMore = false;

function loadMore() {
    if (window._loadingMore) return;
    window._loadingMore = true;
    const s = document.getElementById('load-more-sentinel');
    if (s) {
        const t = s.querySelector('.load-more-text');
        if (t) t.textContent = 'Loading...';
    }
    window._bridge.loadMore();
}

function _toggleSection(id) {
    const el = document.getElementById(id);
    if (!el) return;
    if (el.dataset.chipOwned && !el.classList.contains('collapsed')) {
        el.classList.add('turn-hidden', 'collapsed');
        el.classList.remove('chip-expanded');
        const btn = el.querySelector('.chip-close');
        if (btn) btn.remove();
        const chip = document.querySelector('[data-chip-for="' + id + '"]');
        if (chip) chip.style.opacity = '1';
        return;
    }
    el.classList.toggle('collapsed');
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
        let targetMeta = null;
        let sib = el.nextElementSibling;
        while (sib) {
            if (sib.classList.contains('agent-row')) {
                targetMeta = sib.querySelector('.meta');
                break;
            }
            sib = sib.nextElementSibling;
        }
        if (!targetMeta) targetMeta = lastMeta;
        if (!targetMeta) return;
        const chip = document.createElement('span');
        const elId = el.id || '';
        if (el.classList.contains('thinking-section')) {
            chip.className = 'turn-chip';
            chip.textContent = '\uD83D\uDCAD Thought';
        } else if (el.classList.contains('tool-section')) {
            const lbl = el.querySelector('.collapse-label');
            const icon = el.querySelector('.collapse-icon');
            const failed = icon && icon.style.color === 'red';
            chip.className = 'turn-chip tool' + (failed ? ' failed' : '');
            chip.textContent = lbl ? lbl.textContent : 'Tool';
        } else if (el.classList.contains('context-section')) {
            chip.className = 'turn-chip ctx';
            const n = el.querySelectorAll('.ctx-file').length;
            chip.textContent = '\uD83D\uDCCE ' + n + ' file' + (n !== 1 ? 's' : '');
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
        chip.dataset.chipFor = elId;
        chip.onclick = function (ev) {
            ev.stopPropagation();
            if (el.classList.contains('turn-hidden')) {
                el.classList.remove('turn-hidden', 'collapsed');
                el.classList.add('chip-expanded');
                chip.style.opacity = '0.5';
                const cc = el.querySelector('.collapse-content');
                if (cc && !cc.querySelector('.chip-close')) {
                    const btn = document.createElement('span');
                    btn.className = 'chip-close';
                    btn.textContent = '\u2715';
                    btn.onclick = function (e) {
                        e.stopPropagation();
                        el.classList.add('turn-hidden', 'collapsed');
                        el.classList.remove('chip-expanded');
                        chip.style.opacity = '1';
                        const b2 = el.querySelector('.chip-close');
                        if (b2) b2.remove();
                    };
                    cc.insertBefore(btn, cc.firstChild);
                }
            } else {
                el.classList.add('turn-hidden', 'collapsed');
                el.classList.remove('chip-expanded');
                chip.style.opacity = '1';
                const btn2 = el.querySelector('.chip-close');
                if (btn2) btn2.remove();
            }
        };
        el.classList.add('turn-hidden');
        el.dataset.chipOwned = '1';
        targetMeta.appendChild(chip);
        targetMeta.classList.add('show');
    });
    if (stats && stats.mult) {
        const existing = document.getElementById('turn-stats');
        if (existing) {
            existing.textContent = stats.mult;
            const short = stats.model.split('/').pop().substring(0, 30);
            existing.setAttribute('data-tip', short + ' \u00B7 ' + stats.tools + ' tool' + (stats.tools !== 1 ? 's' : ''));
            existing.removeAttribute('id');
        }
    }
    scrollIfNeeded();
}

function collapseToolToChip(elId) {
    const el = document.getElementById(elId);
    if (!el || el.dataset.chipOwned) return;
    let targetMeta = null;
    let sib = el.nextElementSibling;
    while (sib) {
        if (sib.classList.contains('agent-row')) {
            targetMeta = sib.querySelector('.meta');
            break;
        }
        sib = sib.nextElementSibling;
    }
    if (!targetMeta) {
        el.dataset.pendingCollapse = '1';
        return;
    }
    _doCollapseToolToChip(el, elId, targetMeta);
}

function _doCollapseToolToChip(el, elId, targetMeta) {
    const lbl = el.querySelector('.collapse-label');
    const icon = el.querySelector('.collapse-icon');
    const failed = icon && icon.style.color === 'red';
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
    if (fullText.length > 50) chip.setAttribute('data-tip', fullText);
    el.classList.add('turn-hidden');
    el.dataset.chipOwned = '1';
    chip.dataset.chipFor = elId;
    chip.style.cursor = 'pointer';

    function closeSection() {
        el.classList.add('turn-hidden', 'collapsed');
        el.classList.remove('chip-expanded');
        chip.style.opacity = '1';
        const btn = el.querySelector('.chip-close');
        if (btn) btn.remove();
    }

    chip.onclick = function (ev) {
        ev.stopPropagation();
        if (el.classList.contains('turn-hidden')) {
            el.classList.remove('turn-hidden', 'collapsed');
            el.classList.add('chip-expanded');
            chip.style.opacity = '0.5';
            const cc = el.querySelector('.collapse-content');
            if (cc && !cc.querySelector('.chip-close')) {
                const btn = document.createElement('span');
                btn.className = 'chip-close';
                btn.textContent = '\u2715';
                btn.onclick = function (e) {
                    e.stopPropagation();
                    closeSection();
                };
                cc.insertBefore(btn, cc.firstChild);
            }
        } else {
            closeSection();
        }
    };
    targetMeta.appendChild(chip);
    targetMeta.classList.add('show');
    scrollIfNeeded();
}

function collapseThinkingToChip(elId) {
    const el = document.getElementById(elId);
    if (!el || el.dataset.chipOwned) return;
    let targetMeta = null;
    let sib = el.nextElementSibling;
    while (sib) {
        if (sib.classList.contains('agent-row')) {
            targetMeta = sib.querySelector('.meta');
            break;
        }
        sib = sib.nextElementSibling;
    }
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
    el.classList.add('turn-hidden');
    el.dataset.chipOwned = '1';
    chip.dataset.chipFor = elId;
    chip.style.cursor = 'pointer';

    function closeSection() {
        el.classList.add('turn-hidden', 'collapsed');
        el.classList.remove('chip-expanded');
        chip.style.opacity = '1';
        const btn = el.querySelector('.chip-close');
        if (btn) btn.remove();
    }

    chip.onclick = function (ev) {
        ev.stopPropagation();
        if (el.classList.contains('turn-hidden')) {
            el.classList.remove('turn-hidden', 'collapsed');
            el.classList.add('chip-expanded');
            chip.style.opacity = '0.5';
            const cc = el.querySelector('.collapse-content');
            if (cc && !cc.querySelector('.chip-close')) {
                const btn = document.createElement('span');
                btn.className = 'chip-close';
                btn.textContent = '\u2715';
                btn.onclick = function (e) {
                    e.stopPropagation();
                    closeSection();
                };
                cc.insertBefore(btn, cc.firstChild);
            }
        } else {
            closeSection();
        }
    };
    targetMeta.appendChild(chip);
    targetMeta.classList.add('show');
    scrollIfNeeded();
}

function collapsePendingTools() {
    const pending = document.querySelectorAll('.tool-section[data-pending-collapse], .thinking-section[data-pending-collapse]');
    pending.forEach(function (el) {
        let targetMeta = null;
        let sib = el.nextElementSibling;
        while (sib) {
            if (sib.classList.contains('agent-row')) {
                targetMeta = sib.querySelector('.meta');
                break;
            }
            sib = sib.nextElementSibling;
        }
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
    if (!el || !el.getAttribute('href')) return;
    var href = el.getAttribute('href');
    if (href.indexOf('openfile://') === 0) {
        e.preventDefault();
        window._bridge.openFile(href);
    } else if (href.indexOf('http://') === 0 || href.indexOf('https://') === 0) {
        e.preventDefault();
        window._bridge.openUrl(href);
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
        window._bridge.setCursor(c);
    }
});

function showQuickReplies(options) {
    disableQuickReplies();
    var c = document.getElementById('container');
    if (!c || !options || !options.length) return;
    var div = document.createElement('div');
    div.className = 'quick-replies';
    div.id = 'active-quick-replies';
    options.forEach(function (text) {
        var btn = document.createElement('span');
        btn.className = 'quick-reply-btn';
        btn.textContent = text;
        btn.onclick = function () {
            if (div.classList.contains('disabled')) return;
            div.classList.add('disabled');
            window._bridge.quickReply(text);
        };
        div.appendChild(btn);
    });
    var ind = document.getElementById('processing-ind');
    if (ind) c.insertBefore(div, ind);
    else c.appendChild(div);
    scrollIfNeeded();
}

function disableQuickReplies() {
    var all = document.querySelectorAll('.quick-replies:not(.disabled)');
    all.forEach(function (el) { el.classList.add('disabled'); });
}

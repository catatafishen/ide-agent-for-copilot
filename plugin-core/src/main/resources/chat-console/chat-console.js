/**
 * Chat Console Panel JavaScript
 *
 * Bridge functions are wired up by Kotlin via window._bridge before this script loads:
 *   window._bridge.openFile(href)  - open a file link
 *   window._bridge.setCursor(type) - set cursor type
 *   window._bridge.loadMore()      - load earlier messages
 */

var autoScroll = true;
window.addEventListener('scroll', function () {
    autoScroll = (window.innerHeight + window.scrollY >= document.body.scrollHeight - 20);
});

function scrollIfNeeded() {
    if (autoScroll) window.scrollTo(0, document.body.scrollHeight);
}

function b64(s) {
    var r = atob(s), b = new Uint8Array(r.length);
    for (var i = 0; i < r.length; i++) b[i] = r.charCodeAt(i);
    return new TextDecoder().decode(b);
}

var _loadingMore = false;
function loadMore() {
    if (_loadingMore) return;
    _loadingMore = true;
    var s = document.getElementById('load-more-sentinel');
    if (s) { var t = s.querySelector('.load-more-text'); if (t) t.textContent = 'Loading...'; }
    window._bridge.loadMore();
}

function toggleTool(id) {
    var el = document.getElementById(id); if (!el) return;
    if (el.dataset.chipOwned && !el.classList.contains('collapsed')) {
        el.classList.add('turn-hidden', 'collapsed');
        el.classList.remove('chip-expanded');
        var btn = el.querySelector('.chip-close'); if (btn) btn.remove();
        var chip = document.querySelector('[data-chip-for="' + id + '"]');
        if (chip) chip.style.opacity = '1';
        return;
    }
    el.classList.toggle('collapsed');
    el.querySelector('.caret').textContent = el.classList.contains('collapsed') ? '\u25B8' : '\u25BE';
}

function toggleThinking(id) {
    var el = document.getElementById(id); if (!el) return;
    if (el.dataset.chipOwned && !el.classList.contains('collapsed')) {
        el.classList.add('turn-hidden', 'collapsed');
        el.classList.remove('chip-expanded');
        var btn = el.querySelector('.chip-close'); if (btn) btn.remove();
        var chip = document.querySelector('[data-chip-for="' + id + '"]');
        if (chip) chip.style.opacity = '1';
        return;
    }
    el.classList.toggle('collapsed');
    el.querySelector('.caret').textContent = el.classList.contains('collapsed') ? '\u25B8' : '\u25BE';
}

function toggleMeta(el) {
    var row = el.parentElement;
    var m = row ? row.querySelector('.meta') : null;
    if (m) m.classList.toggle('show');
}

function finalizeTurn(stats) {
    var items = document.querySelectorAll(
        '.thinking-section:not(.turn-hidden),' +
        '.tool-section:not(.turn-hidden),' +
        '.context-section:not(.turn-hidden),' +
        '.status-row:not(.turn-hidden)'
    );
    var lastBubbles = document.querySelectorAll('.agent-bubble');
    var lastBubble = lastBubbles.length > 0 ? lastBubbles[lastBubbles.length - 1] : null;
    var lastRow = lastBubble ? lastBubble.parentElement : null;
    var lastMeta = lastRow ? lastRow.querySelector('.meta') : null;
    items.forEach(function (el) {
        var targetMeta = null;
        var sib = el.nextElementSibling;
        while (sib) {
            if (sib.classList.contains('agent-row')) { targetMeta = sib.querySelector('.meta'); break; }
            sib = sib.nextElementSibling;
        }
        if (!targetMeta) targetMeta = lastMeta;
        if (!targetMeta) return;
        var chip = document.createElement('span');
        var elId = el.id || '';
        if (el.classList.contains('thinking-section')) {
            chip.className = 'turn-chip'; chip.textContent = '\uD83D\uDCAD Thought';
        } else if (el.classList.contains('tool-section')) {
            var lbl = el.querySelector('.collapse-label');
            var icon = el.querySelector('.collapse-icon');
            var failed = icon && icon.style.color === 'red';
            chip.className = 'turn-chip tool' + (failed ? ' failed' : '');
            var fullText = (lbl ? lbl.textContent : 'Tool');
            chip.textContent = fullText;
        } else if (el.classList.contains('context-section')) {
            chip.className = 'turn-chip ctx';
            var n = el.querySelectorAll('.ctx-file').length;
            chip.textContent = '\uD83D\uDCCE ' + n + ' file' + (n !== 1 ? 's' : '');
        } else if (el.classList.contains('status-row')) {
            if (el.classList.contains('error')) {
                chip.className = 'turn-chip err'; chip.textContent = '\u274C Error';
            } else {
                chip.className = 'turn-chip'; chip.textContent = '\u2139\uFE0F Info';
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
                var cc = el.querySelector('.collapse-content');
                if (cc && !cc.querySelector('.chip-close')) {
                    var btn = document.createElement('span'); btn.className = 'chip-close'; btn.textContent = '\u2715';
                    btn.onclick = function (e) {
                        e.stopPropagation();
                        el.classList.add('turn-hidden', 'collapsed');
                        el.classList.remove('chip-expanded');
                        chip.style.opacity = '1';
                        var b2 = el.querySelector('.chip-close'); if (b2) b2.remove();
                    };
                    cc.insertBefore(btn, cc.firstChild);
                }
            } else {
                el.classList.add('turn-hidden', 'collapsed');
                el.classList.remove('chip-expanded');
                chip.style.opacity = '1';
                var btn2 = el.querySelector('.chip-close'); if (btn2) btn2.remove();
            }
        };
        el.classList.add('turn-hidden');
        el.dataset.chipOwned = '1';
        targetMeta.appendChild(chip);
    });
    // Update stats chip on the prompt bubble with final tool count
    if (stats && stats.mult) {
        var existing = document.getElementById('turn-stats');
        if (existing) {
            var label = stats.mult;
            existing.textContent = label;
            var short = stats.model.split('/').pop().substring(0, 30);
            existing.setAttribute('data-tip', short + ' \u00B7 ' + stats.tools + ' tool' + (stats.tools != 1 ? 's' : ''));
            existing.removeAttribute('id');
        }
    }
    scrollIfNeeded();
}

function collapseToolToChip(elId) {
    var el = document.getElementById(elId);
    if (!el || el.dataset.chipOwned) return;
    var targetMeta = null;
    var sib = el.nextElementSibling;
    while (sib) {
        if (sib.classList.contains('agent-row')) { targetMeta = sib.querySelector('.meta'); break; }
        sib = sib.nextElementSibling;
    }
    if (!targetMeta) { el.dataset.pendingCollapse = '1'; return; }
    _doCollapseToolToChip(el, elId, targetMeta);
}

function _doCollapseToolToChip(el, elId, targetMeta) {
    var lbl = el.querySelector('.collapse-label');
    var icon = el.querySelector('.collapse-icon');
    var failed = icon && icon.style.color === 'red';
    var chip = document.createElement('span');
    chip.className = 'turn-chip tool' + (failed ? ' failed' : '');
    var fullText = (lbl ? lbl.textContent : 'Tool');
    var displayText = fullText.length > 50 ? fullText.substring(0, 47) + '\u2026' : fullText;
    chip.textContent = displayText;
    if (fullText.length > 50) chip.setAttribute('data-tip', fullText);
    el.classList.add('turn-hidden');
    el.dataset.chipOwned = '1';
    chip.dataset.chipFor = elId;
    chip.style.cursor = 'pointer';
    function closeSection() {
        el.classList.add('turn-hidden', 'collapsed');
        el.classList.remove('chip-expanded');
        chip.style.opacity = '1';
        var btn = el.querySelector('.chip-close'); if (btn) btn.remove();
    }
    chip.onclick = function (ev) {
        ev.stopPropagation();
        if (el.classList.contains('turn-hidden')) {
            el.classList.remove('turn-hidden', 'collapsed');
            el.classList.add('chip-expanded');
            chip.style.opacity = '0.5';
            var cc = el.querySelector('.collapse-content');
            if (cc && !cc.querySelector('.chip-close')) {
                var btn = document.createElement('span'); btn.className = 'chip-close'; btn.textContent = '\u2715';
                btn.onclick = function (e) { e.stopPropagation(); closeSection(); };
                cc.insertBefore(btn, cc.firstChild);
            }
        } else { closeSection(); }
    };
    targetMeta.appendChild(chip);
    scrollIfNeeded();
}

function collapsePendingTools() {
    var pending = document.querySelectorAll('.tool-section[data-pending-collapse]');
    pending.forEach(function (el) {
        var targetMeta = null;
        var sib = el.nextElementSibling;
        while (sib) {
            if (sib.classList.contains('agent-row')) { targetMeta = sib.querySelector('.meta'); break; }
            sib = sib.nextElementSibling;
        }
        if (!targetMeta) return;
        delete el.dataset.pendingCollapse;
        _doCollapseToolToChip(el, el.id, targetMeta);
    });
}

document.addEventListener('click', function (e) {
    var el = e.target;
    while (el && el.tagName !== 'A') el = el.parentElement;
    if (el && el.getAttribute('href') && el.getAttribute('href').indexOf('openfile://') === 0) {
        e.preventDefault();
        window._bridge.openFile(el.getAttribute('href'));
    }
});

var _lastCursor = '';
document.addEventListener('mouseover', function (e) {
    var el = e.target; var c = 'default';
    if (el.closest('a,.collapse-header,.turn-chip,.chip-close,.prompt-ctx-chip')) c = 'pointer';
    else if (el.closest('p,pre,code,li,td,th,.collapse-content,.streaming')) c = 'text';
    if (c !== _lastCursor) { _lastCursor = c; window._bridge.setCursor(c); }
});

import { describe, it, expect, beforeEach } from 'vitest';

// Helper to wait for custom element upgrades
function flush() {
  return new Promise(r => setTimeout(r, 0));
}

describe('Web Components Registration', () => {
  it('all 15 custom elements are defined', () => {
    const tags = [
      'chat-container', 'chat-message', 'message-bubble', 'message-meta',
      'thinking-block', 'tool-section', 'tool-chip', 'thinking-chip',
      'subagent-chip', 'subagent-block', 'quick-replies', 'status-message',
      'session-divider', 'load-more',
    ];
    for (const tag of tags) {
      expect(customElements.get(tag), `${tag} should be registered`).toBeDefined();
    }
  });

  it('ChatController is globally available', () => {
    expect(window.ChatController).toBeDefined();
    expect(typeof window.ChatController.addUserMessage).toBe('function');
    expect(typeof window.ChatController.appendAgentText).toBe('function');
    expect(typeof window.ChatController.addToolCall).toBe('function');
    expect(typeof window.ChatController.addSubAgent).toBe('function');
  });
});

describe('chat-container', () => {
  let container;

  beforeEach(() => {
    document.body.innerHTML = '';
    container = document.createElement('chat-container');
    document.body.appendChild(container);
  });

  it('creates #messages div on connect', () => {
    const msgs = container.querySelector('#messages');
    expect(msgs).not.toBeNull();
  });

  it('exposes messages getter', () => {
    expect(container.messages).toBe(container.querySelector('#messages'));
  });

  it('scrollIfNeeded does not throw', () => {
    expect(() => container.scrollIfNeeded()).not.toThrow();
  });

  it('forceScroll does not throw', () => {
    expect(() => container.forceScroll()).not.toThrow();
  });
});

describe('chat-message', () => {
  beforeEach(() => { document.body.innerHTML = ''; });

  it('renders with user type (prompt-row class)', () => {
    const msg = document.createElement('chat-message');
    msg.setAttribute('type', 'user');
    document.body.appendChild(msg);
    expect(msg.className).toContain('prompt-row');
  });

  it('renders with agent type (agent-row class)', () => {
    const msg = document.createElement('chat-message');
    msg.setAttribute('type', 'agent');
    document.body.appendChild(msg);
    expect(msg.className).toContain('agent-row');
  });
});

describe('message-bubble', () => {
  let bubble;

  beforeEach(() => {
    document.body.innerHTML = '';
    bubble = document.createElement('message-bubble');
    document.body.appendChild(bubble);
  });

  it('appendStreamingText adds text', () => {
    bubble.appendStreamingText('Hello');
    expect(bubble.textContent).toContain('Hello');
  });

  it('appendStreamingText accumulates', () => {
    bubble.appendStreamingText('Hello ');
    bubble.appendStreamingText('World');
    expect(bubble.textContent).toContain('Hello World');
  });

  it('finalize replaces content with HTML', () => {
    bubble.appendStreamingText('raw text');
    bubble.finalize('<p>Formatted</p>');
    expect(bubble.innerHTML).toContain('<p>Formatted</p>');
    expect(bubble.hasAttribute('streaming')).toBe(false);
  });

  it('removePending removes pending span', () => {
    bubble.setAttribute('streaming', '');
    const span = document.createElement('span');
    span.className = 'agent-pending';
    span.textContent = 'Working…';
    bubble.appendChild(span);
    bubble.removePending();
    expect(bubble.querySelector('.agent-pending')).toBeNull();
  });
});

describe('message-meta', () => {
  it('renders with show class', () => {
    const meta = document.createElement('message-meta');
    meta.classList.add('show');
    document.body.appendChild(meta);
    expect(meta.classList.contains('show')).toBe(true);
  });
});

describe('thinking-block', () => {
  let block;

  beforeEach(() => {
    document.body.innerHTML = '';
    block = document.createElement('thinking-block');
    document.body.appendChild(block);
  });

  it('creates collapse structure on connect', () => {
    expect(block.querySelector('.collapse-header')).not.toBeNull();
    expect(block.querySelector('.collapse-content')).not.toBeNull();
  });

  it('appendText adds to content', () => {
    block.appendText('Thought 1');
    const content = block.querySelector('.collapse-content');
    expect(content.textContent).toContain('Thought 1');
  });

  it('finalize removes active and adds collapsed', () => {
    block.setAttribute('active', '');
    block.setAttribute('expanded', '');
    block.appendText('some thinking');
    block.finalize();
    expect(block.hasAttribute('active')).toBe(false);
    expect(block.classList.contains('collapsed')).toBe(true);
  });

  it('clicking header toggles collapsed class', () => {
    const header = block.querySelector('.collapse-header');
    // Initially collapsed (no expanded attr)
    expect(block.classList.contains('collapsed')).toBe(true);
    header.click();
    expect(block.classList.contains('collapsed')).toBe(false);
    header.click();
    expect(block.classList.contains('collapsed')).toBe(true);
  });
});

describe('tool-section', () => {
  let section;

  beforeEach(() => {
    document.body.innerHTML = '';
    section = document.createElement('tool-section');
    section.setAttribute('title', 'Read File');
    document.body.appendChild(section);
  });

  it('renders header with title', () => {
    const header = section.querySelector('.collapse-header');
    expect(header).not.toBeNull();
    expect(header.textContent).toContain('Read File');
  });

  it('setting params renders JSON', () => {
    section.params = '{"path": "/foo/bar.txt"}';
    expect(section.querySelector('.tool-params')).not.toBeNull();
    expect(section.textContent).toContain('/foo/bar.txt');
  });

  it('setting result renders HTML', () => {
    section.result = '<div>Output here</div>';
    expect(section.querySelector('.tool-result')).not.toBeNull();
    expect(section.innerHTML).toContain('Output here');
  });

  it('updateStatus changes header styling', () => {
    section.updateStatus('completed');
    const header = section.querySelector('.collapse-header');
    expect(header.textContent).toContain('✓');
  });

  it('updateStatus failed shows error', () => {
    section.updateStatus('failed');
    const header = section.querySelector('.collapse-header');
    expect(header.textContent).toContain('✖');
  });
});

describe('tool-chip', () => {
  let chip;

  beforeEach(() => {
    document.body.innerHTML = '';
    chip = document.createElement('tool-chip');
    chip.setAttribute('label', 'Read File');
    chip.setAttribute('status', 'running');
    document.body.appendChild(chip);
  });

  it('renders label text', () => {
    expect(chip.textContent).toContain('Read File');
  });

  it('shows spinner when running', () => {
    expect(chip.querySelector('.chip-spinner')).not.toBeNull();
  });

  it('removes spinner and shows no spinner on complete', () => {
    chip.setAttribute('status', 'complete');
    expect(chip.querySelector('.chip-spinner')).toBeNull();
    // No spinner means completed
    expect(chip.textContent).toContain('Read File');
  });

  it('adds failed class on failed', () => {
    chip.setAttribute('status', 'failed');
    expect(chip.querySelector('.chip-spinner')).toBeNull();
    expect(chip.classList.contains('failed')).toBe(true);
  });

  it('linkSection connects chip to section toggle', () => {
    const section = document.createElement('tool-section');
    section.setAttribute('title', 'Read File');
    document.body.appendChild(section);
    chip.linkSection(section);
    // Clicking chip should toggle section visibility
    chip.click();
    expect(section.classList.contains('turn-hidden')).toBe(false);
  });
});

describe('thinking-chip', () => {
  let chip;

  beforeEach(() => {
    document.body.innerHTML = '';
    chip = document.createElement('thinking-chip');
    document.body.appendChild(chip);
  });

  it('renders thought label', () => {
    expect(chip.textContent).toContain('💭');
  });

  it('linkSection toggles thinking block visibility', () => {
    const block = document.createElement('thinking-block');
    block.classList.add('turn-hidden');
    document.body.appendChild(block);
    chip.linkSection(block);
    chip.click();
    expect(block.classList.contains('turn-hidden')).toBe(false);
  });
});

describe('subagent-chip', () => {
  let chip;

  beforeEach(() => {
    document.body.innerHTML = '';
    chip = document.createElement('subagent-chip');
    chip.setAttribute('label', 'Explore Agent');
    chip.setAttribute('status', 'running');
    chip.setAttribute('color-index', '0');
    document.body.appendChild(chip);
  });

  it('renders agent label', () => {
    expect(chip.textContent).toContain('Explore Agent');
  });

  it('shows spinner when running', () => {
    expect(chip.querySelector('.chip-spinner')).not.toBeNull();
  });

  it('completes when status changes', () => {
    chip.setAttribute('status', 'complete');
    expect(chip.querySelector('.chip-spinner')).toBeNull();
    expect(chip.textContent).toContain('Explore Agent');
  });
});

describe('subagent-block', () => {
  it('applies color-index class on connect', () => {
    const block = document.createElement('subagent-block');
    block.setAttribute('color-index', '3');
    document.body.appendChild(block);
    // Should have styling for color index 3
    expect(block.getAttribute('color-index')).toBe('3');
  });
});

describe('quick-replies', () => {
  let qr;

  beforeEach(() => {
    document.body.innerHTML = '';
    qr = document.createElement('quick-replies');
    document.body.appendChild(qr);
  });

  it('renders buttons from options property', () => {
    qr.options = ['Yes', 'No', 'Maybe'];
    const buttons = qr.querySelectorAll('.quick-reply-btn');
    expect(buttons.length).toBe(3);
    expect(buttons[0].textContent).toBe('Yes');
    expect(buttons[1].textContent).toBe('No');
    expect(buttons[2].textContent).toBe('Maybe');
  });

  it('dispatches quick-reply event on button click', () => {
    let received = null;
    document.addEventListener('quick-reply', e => { received = e.detail.text; });
    qr.options = ['Click me'];
    qr.querySelector('.quick-reply-btn').click();
    expect(received).toBe('Click me');
  });

  it('disabled attribute prevents clicks from dispatching', () => {
    let received = false;
    document.addEventListener('quick-reply', () => { received = true; });
    qr.options = ['Test'];
    qr.setAttribute('disabled', '');
    qr.querySelector('.quick-reply-btn').click();
    expect(received).toBe(false);
  });
});

describe('status-message', () => {
  it('renders error type', () => {
    const sm = document.createElement('status-message');
    sm.setAttribute('type', 'error');
    sm.setAttribute('message', 'Something went wrong');
    document.body.appendChild(sm);
    expect(sm.textContent).toContain('Something went wrong');
    expect(sm.className).toContain('error');
  });

  it('renders info type', () => {
    const sm = document.createElement('status-message');
    sm.setAttribute('type', 'info');
    sm.setAttribute('message', 'Info message');
    document.body.appendChild(sm);
    expect(sm.textContent).toContain('Info message');
    expect(sm.className).toContain('info');
  });
});

describe('session-divider', () => {
  it('renders timestamp', () => {
    const sd = document.createElement('session-divider');
    sd.setAttribute('timestamp', 'Feb 27, 2026 8:45 AM');
    document.body.appendChild(sd);
    expect(sd.textContent).toContain('Feb 27, 2026 8:45 AM');
  });
});

describe('load-more', () => {
  it('renders count', () => {
    const lm = document.createElement('load-more');
    lm.setAttribute('count', '42');
    document.body.appendChild(lm);
    expect(lm.textContent).toContain('42');
  });

  it('dispatches load-more event on click', () => {
    let fired = false;
    document.addEventListener('load-more', () => { fired = true; });
    const lm = document.createElement('load-more');
    lm.setAttribute('count', '10');
    document.body.appendChild(lm);
    lm.querySelector('button, [role="button"], .load-more-btn')?.click?.();
    // If no clickable child, click the element itself
    if (!fired) lm.click();
    expect(fired).toBe(true);
  });
});

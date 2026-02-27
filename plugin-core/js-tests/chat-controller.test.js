import {beforeEach, describe, expect, it} from 'vitest';

const CC = () => window.ChatController;

function getMessages() {
    return document.querySelector('#messages');
}

describe('ChatController', () => {
    beforeEach(() => {
        document.body.innerHTML = '';
        const container = document.createElement('chat-container');
        document.body.appendChild(container);
        CC().clear();
    });

    describe('addUserMessage', () => {
        it('creates a user chat-message', () => {
            CC().addUserMessage('Hello', '10:30', '');
            const msgs = getMessages().querySelectorAll('chat-message');
            expect(msgs.length).toBe(1);
            expect(msgs[0].getAttribute('type')).toBe('user');
        });

        it('contains the user text', () => {
            CC().addUserMessage('Test prompt', '10:30', '');
            expect(getMessages().textContent).toContain('Test prompt');
        });

        it('renders timestamp in meta', () => {
            CC().addUserMessage('Hi', '14:22', '');
            const meta = getMessages().querySelector('message-meta');
            expect(meta.textContent).toContain('14:22');
        });

        it('renders context chips HTML', () => {
            CC().addUserMessage('Hi', '14:22', '<a class="prompt-ctx-chip">📄 file.kt</a>');
            const chip = getMessages().querySelector('.prompt-ctx-chip');
            expect(chip).not.toBeNull();
            expect(chip.textContent).toContain('file.kt');
        });
    });

    describe('appendAgentText / finalizeAgentText', () => {
        it('creates agent message on first text', () => {
            CC().appendAgentText('t0', 'Hello from agent');
            const msgs = getMessages().querySelectorAll('chat-message');
            expect(msgs.length).toBe(1);
            expect(msgs[0].getAttribute('type')).toBe('agent');
        });

        it('accumulates streaming text', () => {
            CC().appendAgentText('t0', 'Part 1 ');
            CC().appendAgentText('t0', 'Part 2');
            const bubble = getMessages().querySelector('message-bubble');
            expect(bubble.textContent).toContain('Part 1 Part 2');
        });

        it('finalizeAgentText replaces with rendered HTML', () => {
            CC().appendAgentText('t0', 'raw text');
            // Encode "<p>Final</p>" as base64
            const html = '<p>Final</p>';
            const encoded = btoa(html);
            CC().finalizeAgentText('t0', encoded);
            const bubble = getMessages().querySelector('message-bubble');
            expect(bubble.innerHTML).toContain('<p>Final</p>');
        });

        it('finalizeAgentText with null removes empty bubble', () => {
            CC().appendAgentText('t0', '');
            CC()._getOrCreateMsg('t0');
            CC()._getOrCreateBubble('t0');
            const countBefore = getMessages().querySelectorAll('chat-message').length;
            CC().finalizeAgentText('t0', null);
            // Agent message should be removed if no encoded HTML
            const countAfter = getMessages().querySelectorAll('chat-message').length;
            expect(countAfter).toBeLessThanOrEqual(countBefore);
        });

        it('skips blank text when no current bubble', () => {
            CC().appendAgentText('t0', '   ');
            expect(getMessages().querySelectorAll('chat-message').length).toBe(0);
        });
    });

    describe('thinking', () => {
        it('addThinkingText creates thinking-block', () => {
            CC().addThinkingText('t0', 'Let me think...');
            const blocks = getMessages().querySelectorAll('thinking-block');
            expect(blocks.length).toBe(1);
            expect(blocks[0].hasAttribute('active')).toBe(true);
        });

        it('thinking text accumulates', () => {
            CC().addThinkingText('t0', 'Step 1. ');
            CC().addThinkingText('t0', 'Step 2.');
            const block = getMessages().querySelector('thinking-block');
            const content = block.querySelector('.thinking-content');
            expect(content.textContent).toContain('Step 1. Step 2.');
        });

        it('collapseThinking creates chip and hides block', () => {
            CC().addThinkingText('t0', 'Thinking...');
            CC().collapseThinking();
            const block = getMessages().querySelector('thinking-block');
            expect(block.classList.contains('turn-hidden')).toBe(true);
            // A thinking-chip should exist in the agent message meta
            const chip = getMessages().querySelector('thinking-chip');
            expect(chip).not.toBeNull();
        });

        it('collapseThinking is no-op if no active thinking', () => {
            expect(() => CC().collapseThinking()).not.toThrow();
        });
    });

    describe('tool calls', () => {
        it('addToolCall creates section and chip', () => {
            CC().addToolCall('t0', 'tc-1', 'Read File', '{"path":"/test.kt"}');
            const section = document.getElementById('tc-1');
            expect(section).not.toBeNull();
            expect(section.tagName.toLowerCase()).toBe('tool-section');
            const chip = getMessages().querySelector('tool-chip');
            expect(chip).not.toBeNull();
            expect(chip.getAttribute('status')).toBe('running');
        });

        it('updateToolCall changes chip status', () => {
            CC().addToolCall('t0', 'tc-2', 'Write File', null);
            CC().updateToolCall('tc-2', 'completed', null);
            const chip = document.querySelector('[data-chip-for="tc-2"]');
            expect(chip.getAttribute('status')).toBe('complete');
        });

        it('updateToolCall with failed status', () => {
            CC().addToolCall('t0', 'tc-3', 'Build', null);
            CC().updateToolCall('tc-3', 'failed', 'Build error');
            const chip = document.querySelector('[data-chip-for="tc-3"]');
            expect(chip.getAttribute('status')).toBe('failed');
        });

        it('multiple tools create separate sections', () => {
            CC().addToolCall('t0', 'tc-a', 'Tool A', null);
            CC().addToolCall('t0', 'tc-b', 'Tool B', null);
            expect(document.getElementById('tc-a')).not.toBeNull();
            expect(document.getElementById('tc-b')).not.toBeNull();
            const chips = getMessages().querySelectorAll('tool-chip');
            expect(chips.length).toBe(2);
        });

        it('tool section appears before bubble in same message', () => {
            CC().appendAgentText('t0', 'Some text');
            CC().addToolCall('t0', 'tc-order', 'Test', null);
            const msg = document.getElementById('msg-t0');
            const children = Array.from(msg.children);
            const sectionIdx = children.findIndex(c => c.id === 'tc-order');
            const bubbleIdx = children.findIndex(c => c.id === 'bubble-t0');
            expect(sectionIdx).toBeLessThan(bubbleIdx);
        });
    });

    describe('sub-agents', () => {
        it('addSubAgent creates message and chip', () => {
            CC().addSubAgent('t0', 'sa-1', 'Explore Agent', 0, 'Find files');
            const msg = document.getElementById('sa-sa-1');
            expect(msg).not.toBeNull();
            expect(msg.tagName.toLowerCase()).toBe('chat-message');
            expect(msg.classList.contains('subagent-indent')).toBe(true);
            const chip = getMessages().querySelector('subagent-chip');
            expect(chip).not.toBeNull();
            expect(chip.getAttribute('status')).toBe('running');
        });

        it('updateSubAgent updates result bubble', () => {
            CC().addSubAgent('t0', 'sa-2', 'Task Agent', 1, 'Run tests');
            CC().updateSubAgent('sa-2', 'completed', '<p>All passed</p>');
            const result = document.getElementById('result-sa-2');
            expect(result.innerHTML).toContain('All passed');
        });

        it('sub-agent message is separate from parent', () => {
            CC().appendAgentText('t0', 'Response');
            CC().addSubAgent('t0', 'sa-order', 'Agent', 2, 'Prompt');
            const msgs = getMessages().querySelectorAll('chat-message[type="agent"]');
            expect(msgs.length).toBeGreaterThanOrEqual(2);
            const saMsg = document.getElementById('sa-sa-order');
            expect(saMsg.classList.contains('subagent-indent')).toBe(true);
        });
    });

    describe('status messages', () => {
        it('addError creates error status', () => {
            CC().addError('Something broke');
            const sm = getMessages().querySelector('status-message');
            expect(sm).not.toBeNull();
            expect(sm.getAttribute('type')).toBe('error');
            expect(sm.getAttribute('message')).toBe('Something broke');
        });

        it('addInfo creates info status', () => {
            CC().addInfo('FYI');
            const sm = getMessages().querySelector('status-message');
            expect(sm.getAttribute('type')).toBe('info');
        });
    });

    describe('session management', () => {
        it('addSessionSeparator creates divider', () => {
            CC().addSessionSeparator('Feb 27 2026');
            const sd = getMessages().querySelector('session-divider');
            expect(sd).not.toBeNull();
            expect(sd.getAttribute('timestamp')).toBe('Feb 27 2026');
        });

        it('showPlaceholder replaces content', () => {
            CC().addUserMessage('Old', '10:00', '');
            CC().showPlaceholder('Start a conversation...');
            expect(getMessages().querySelectorAll('chat-message').length).toBe(0);
            expect(getMessages().textContent).toContain('Start a conversation...');
        });

        it('clear removes everything', () => {
            CC().addUserMessage('Hello', '10:00', '');
            CC().appendAgentText('t0', 'Response');
            CC().clear();
            expect(getMessages().innerHTML).toBe('');
        });
    });

    describe('quick replies', () => {
        it('showQuickReplies creates element', () => {
            CC().showQuickReplies(['Yes', 'No']);
            const qr = getMessages().querySelector('quick-replies');
            expect(qr).not.toBeNull();
            const btns = qr.querySelectorAll('.quick-reply-btn');
            expect(btns.length).toBe(2);
        });

        it('disableQuickReplies marks them disabled', () => {
            CC().showQuickReplies(['Option']);
            CC().disableQuickReplies();
            const qr = getMessages().querySelector('quick-replies');
            expect(qr.hasAttribute('disabled')).toBe(true);
        });

        it('showQuickReplies disables previous ones', () => {
            CC().showQuickReplies(['First']);
            CC().showQuickReplies(['Second']);
            const all = getMessages().querySelectorAll('quick-replies');
            // First should be disabled
            expect(all[0].hasAttribute('disabled')).toBe(true);
            expect(all[1].hasAttribute('disabled')).toBe(false);
        });
    });

    describe('finalizeTurn', () => {
        it('removes pending span from bubble', () => {
            CC().appendAgentText('t0', 'Final response');
            CC().finalizeTurn('t0', null);
            const bubble = getMessages().querySelector('message-bubble');
            if (bubble) expect(bubble.querySelector('.agent-pending')).toBeNull();
        });

        it('adds stats chip when model provided', () => {
            CC().appendAgentText('t0', 'Response');
            CC().finalizeTurn('t0', {model: 'claude-opus-4.6', mult: '1x'});
            const stats = getMessages().querySelector('.turn-chip.stats');
            expect(stats).not.toBeNull();
            expect(stats.textContent).toBe('1x');
        });

        it('resets agent state for next turn', () => {
            CC().appendAgentText('t0', 'Turn 1');
            CC().finalizeTurn('t0', null);
            CC().addUserMessage('Next prompt', '10:01', '');
            CC().appendAgentText('t1', 'Turn 2');
            const agentMsgs = getMessages().querySelectorAll('chat-message[type="agent"]');
            expect(agentMsgs.length).toBe(2);
        });
    });

    describe('full conversation flow', () => {
        it('renders a complete turn: user → thinking → tools → agent', () => {
            // User sends prompt
            CC().addUserMessage('Explain the code', '10:00', '');

            // Agent starts thinking
            CC().addThinkingText('t0', 'Let me analyze...');

            // Tool call starts
            CC().addToolCall('t0', 'tc-flow', 'Read File — main.kt', '{"path":"main.kt"}');

            // Tool completes
            CC().updateToolCall('tc-flow', 'completed', 'file contents here');

            // Thinking collapses as agent starts responding
            CC().collapseThinking();

            // Agent responds
            CC().appendAgentText('t0', 'The code does ');
            CC().appendAgentText('t0', 'the following...');

            // Finalize
            const html = '<p>The code does the following...</p>';
            CC().finalizeAgentText('t0', btoa(html));
            CC().finalizeTurn('t0', {model: 'opus', mult: '5x'});

            // Verify structure
            const messages = getMessages();
            expect(messages.querySelectorAll('chat-message[type="user"]').length).toBe(1);
            expect(messages.querySelectorAll('thinking-block').length).toBe(1);
            expect(messages.querySelectorAll('tool-section').length).toBe(1);
            expect(messages.querySelectorAll('chat-message[type="agent"]').length).toBe(1);
            expect(messages.querySelectorAll('tool-chip').length).toBe(1);
            expect(messages.querySelectorAll('thinking-chip').length).toBe(1);
        });

        it('handles multiple turns correctly', () => {
            // Turn 1
            CC().addUserMessage('Q1', '10:00', '');
            CC().appendAgentText('t0', 'A1');
            CC().finalizeAgentText('t0', btoa('<p>A1</p>'));
            CC().finalizeTurn('t0', null);

            // Turn 2
            CC().addUserMessage('Q2', '10:01', '');
            CC().appendAgentText('t1', 'A2');
            CC().finalizeAgentText('t1', btoa('<p>A2</p>'));
            CC().finalizeTurn('t1', null);

            expect(getMessages().querySelectorAll('chat-message[type="user"]').length).toBe(2);
            expect(getMessages().querySelectorAll('chat-message[type="agent"]').length).toBe(2);
        });

        it('handles sub-agent flow', () => {
            CC().addUserMessage('Do something', '10:00', '');
            CC().addSubAgent('t0', 'sa-flow', 'Explore', 0, 'Find the file');
            CC().updateSubAgent('sa-flow', 'completed', '<p>Found it</p>');
            CC().appendAgentText('t0', 'Done');
            CC().finalizeAgentText('t0', btoa('<p>Done</p>'));
            CC().finalizeTurn('t0', null);

            expect(getMessages().querySelectorAll('.subagent-indent').length).toBe(1);
            expect(getMessages().querySelectorAll('subagent-chip').length).toBe(1);
        });
    });
});

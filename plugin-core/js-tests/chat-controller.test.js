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
            CC().appendAgentText('s0', 'Hello from agent');
            const msgs = getMessages().querySelectorAll('chat-message');
            expect(msgs.length).toBe(1);
            expect(msgs[0].getAttribute('type')).toBe('agent');
        });

        it('accumulates streaming text', () => {
            CC().appendAgentText('s0', 'Part 1 ');
            CC().appendAgentText('s0', 'Part 2');
            const bubble = getMessages().querySelector('message-bubble');
            expect(bubble.textContent).toContain('Part 1 Part 2');
        });

        it('finalizeAgentText replaces with rendered HTML', () => {
            CC().appendAgentText('s0', 'raw text');
            const html = '<p>Final</p>';
            const encoded = btoa(html);
            CC().finalizeAgentText('s0', encoded);
            const bubble = getMessages().querySelector('message-bubble');
            expect(bubble.innerHTML).toContain('<p>Final</p>');
        });

        it('finalizeAgentText with null removes empty bubble', () => {
            CC().appendAgentText('s0', '');
            CC()._getOrCreateMsg('s0');
            CC()._getOrCreateBubble('s0');
            const countBefore = getMessages().querySelectorAll('chat-message').length;
            CC().finalizeAgentText('s0', null);
            const countAfter = getMessages().querySelectorAll('chat-message').length;
            expect(countAfter).toBeLessThanOrEqual(countBefore);
        });

        it('skips blank text when no current bubble', () => {
            CC().appendAgentText('s0', '   ');
            expect(getMessages().querySelectorAll('chat-message').length).toBe(0);
        });

        it('different segment IDs create separate messages', () => {
            CC().appendAgentText('s0', 'First segment');
            CC().finalizeAgentText('s0', btoa('<p>First</p>'));
            CC().appendAgentText('s1', 'Second segment');
            CC().finalizeAgentText('s1', btoa('<p>Second</p>'));
            const msgs = getMessages().querySelectorAll('chat-message[type="agent"]');
            expect(msgs.length).toBe(2);
        });
    });

    describe('thinking', () => {
        it('addThinkingText creates thinking-block', () => {
            CC().addThinkingText('s0', 'Let me think...');
            const blocks = getMessages().querySelectorAll('thinking-block');
            expect(blocks.length).toBe(1);
            expect(blocks[0].hasAttribute('active')).toBe(true);
        });

        it('thinking text accumulates', () => {
            CC().addThinkingText('s0', 'Step 1. ');
            CC().addThinkingText('s0', 'Step 2.');
            const block = getMessages().querySelector('thinking-block');
            const content = block.querySelector('.thinking-content');
            expect(content.textContent).toContain('Step 1. Step 2.');
        });

        it('collapseThinking creates chip and hides block', () => {
            CC().addThinkingText('s0', 'Thinking...');
            CC().collapseThinking();
            const block = getMessages().querySelector('thinking-block');
            expect(block.classList.contains('turn-hidden')).toBe(true);
            const chip = getMessages().querySelector('thinking-chip');
            expect(chip).not.toBeNull();
        });

        it('collapseThinking is no-op if no active thinking', () => {
            expect(() => CC().collapseThinking()).not.toThrow();
        });
    });

    describe('tool calls', () => {
        it('addToolCall creates section and chip', () => {
            CC().addToolCall('s0', 'tc-1', 'Read File', '{"path":"/test.kt"}');
            const section = document.getElementById('tc-1');
            expect(section).not.toBeNull();
            const chip = getMessages().querySelector('tool-chip');
            expect(chip).not.toBeNull();
            expect(chip.getAttribute('label')).toBe('Read File');
        });

        it('updateToolCall updates status', () => {
            CC().addToolCall('s0', 'tc-2', 'Search', '{}');
            CC().updateToolCall('tc-2', 'completed', 'Found 5 results');
            const chip = getMessages().querySelector('tool-chip');
            expect(chip.getAttribute('status')).toBe('complete');
        });

        it('each tool call gets its own segment message', () => {
            CC().addToolCall('s0', 'tc-a', 'Tool A', '{}');
            CC().addToolCall('s1', 'tc-b', 'Tool B', '{}');
            const msgs = getMessages().querySelectorAll('chat-message[type="agent"]');
            expect(msgs.length).toBe(2);
            // Each message has its own chip
            msgs.forEach(msg => {
                expect(msg.querySelector('tool-chip')).not.toBeNull();
            });
        });
    });

    describe('sub-agents', () => {
        it('addSubAgent creates chip and indented message', () => {
            CC().addSubAgent('s0', 'sa-1', 'Explore Agent', 0, 'Find the file');
            const chip = getMessages().querySelector('subagent-chip');
            expect(chip).not.toBeNull();
            expect(chip.getAttribute('label')).toBe('Explore Agent');
            const indent = getMessages().querySelector('.subagent-indent');
            expect(indent).not.toBeNull();
        });

        it('updateSubAgent sets result and status', () => {
            CC().addSubAgent('s0', 'sa-2', 'Task Agent', 1, 'Do the thing');
            CC().updateSubAgent('sa-2', 'completed', '<p>Done</p>');
            const result = document.getElementById('result-sa-2');
            expect(result.innerHTML).toContain('Done');
            const chip = getMessages().querySelector('subagent-chip');
            expect(chip.getAttribute('status')).toBe('complete');
        });
    });

    describe('status messages', () => {
        it('addError creates error status', () => {
            CC().addError('Something broke');
            const el = getMessages().querySelector('status-message');
            expect(el).not.toBeNull();
            expect(el.getAttribute('type')).toBe('error');
        });

        it('addInfo creates info status', () => {
            CC().addInfo('All good');
            const el = getMessages().querySelector('status-message');
            expect(el.getAttribute('type')).toBe('info');
        });
    });

    describe('session separator', () => {
        it('creates session-divider element', () => {
            CC().addSessionSeparator('2024-01-15 10:30');
            const div = getMessages().querySelector('session-divider');
            expect(div).not.toBeNull();
            expect(div.getAttribute('timestamp')).toBe('2024-01-15 10:30');
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
            expect(all[0].hasAttribute('disabled')).toBe(true);
            expect(all[1].hasAttribute('disabled')).toBe(false);
        });
    });

    describe('finalizeTurn', () => {
        it('removes pending span from bubble', () => {
            CC().appendAgentText('s0', 'Final response');
            CC().finalizeTurn('s0', null);
            const bubble = getMessages().querySelector('message-bubble');
            if (bubble) expect(bubble.querySelector('.agent-pending')).toBeNull();
        });

        it('adds stats chip when model provided', () => {
            CC().appendAgentText('s0', 'Response');
            CC().finalizeTurn('s0', {model: 'claude-opus-4.6', mult: '1x'});
            const stats = getMessages().querySelector('.turn-chip.stats');
            expect(stats).not.toBeNull();
            expect(stats.textContent).toBe('1x');
        });

        it('resets agent state for next turn', () => {
            CC().appendAgentText('s0', 'Turn 1');
            CC().finalizeTurn('s0', null);
            CC().addUserMessage('Next prompt', '10:01', '');
            CC().appendAgentText('s1', 'Turn 2');
            const agentMsgs = getMessages().querySelectorAll('chat-message[type="agent"]');
            expect(agentMsgs.length).toBe(2);
        });
    });

    describe('full conversation flow', () => {
        it('renders a complete turn: user → thinking → tools → agent', () => {
            // User sends prompt
            CC().addUserMessage('Explain the code', '10:00', '');

            // Agent starts thinking (own segment)
            CC().addThinkingText('s0', 'Let me analyze...');

            // Tool call starts (own segment)
            CC().addToolCall('s1', 'tc-flow', 'Read File — main.kt', '{"path":"main.kt"}');

            // Tool completes
            CC().updateToolCall('tc-flow', 'completed', 'file contents here');

            // Thinking collapses as agent starts responding
            CC().collapseThinking();

            // Agent responds (own segment)
            CC().appendAgentText('s2', 'The code does ');
            CC().appendAgentText('s2', 'the following...');

            // Finalize
            const html = '<p>The code does the following...</p>';
            CC().finalizeAgentText('s2', btoa(html));
            CC().finalizeTurn('s2', {model: 'opus', mult: '5x'});

            // Verify structure — each content type in own segment message
            const messages = getMessages();
            expect(messages.querySelectorAll('chat-message[type="user"]').length).toBe(1);
            expect(messages.querySelectorAll('thinking-block').length).toBe(1);
            expect(messages.querySelectorAll('tool-section').length).toBe(1);
            // 3 agent messages: thinking, tool, text (each in own segment)
            expect(messages.querySelectorAll('chat-message[type="agent"]').length).toBe(3);
            expect(messages.querySelectorAll('tool-chip').length).toBe(1);
            expect(messages.querySelectorAll('thinking-chip').length).toBe(1);
        });

        it('handles multiple turns correctly', () => {
            // Turn 1
            CC().addUserMessage('Q1', '10:00', '');
            CC().appendAgentText('s0', 'A1');
            CC().finalizeAgentText('s0', btoa('<p>A1</p>'));
            CC().finalizeTurn('s0', null);

            // Turn 2
            CC().addUserMessage('Q2', '10:01', '');
            CC().appendAgentText('s1', 'A2');
            CC().finalizeAgentText('s1', btoa('<p>A2</p>'));
            CC().finalizeTurn('s1', null);

            expect(getMessages().querySelectorAll('chat-message[type="user"]').length).toBe(2);
            expect(getMessages().querySelectorAll('chat-message[type="agent"]').length).toBe(2);
        });

        it('handles sub-agent flow', () => {
            CC().addUserMessage('Do something', '10:00', '');
            CC().addSubAgent('s0', 'sa-flow', 'Explore', 0, 'Find the file');
            CC().updateSubAgent('sa-flow', 'completed', '<p>Found it</p>');
            CC().appendAgentText('s1', 'Done');
            CC().finalizeAgentText('s1', btoa('<p>Done</p>'));
            CC().finalizeTurn('s1', null);

            expect(getMessages().querySelectorAll('.subagent-indent').length).toBe(1);
            expect(getMessages().querySelectorAll('subagent-chip').length).toBe(1);
        });

        it('text after tool call goes to new segment, not overwriting', () => {
            // First text segment
            CC().appendAgentText('s0', 'Before tool');
            CC().finalizeAgentText('s0', btoa('<p>Before tool</p>'));

            // Tool call in its own segment
            CC().addToolCall('s1', 'tc-mid', 'Search', '{}');
            CC().updateToolCall('tc-mid', 'completed', 'results');

            // Second text segment — must NOT overwrite first
            CC().appendAgentText('s2', 'After tool');
            CC().finalizeAgentText('s2', btoa('<p>After tool</p>'));

            const bubbles = getMessages().querySelectorAll('message-bubble');
            expect(bubbles.length).toBe(2);
            expect(bubbles[0].innerHTML).toContain('Before tool');
            expect(bubbles[1].innerHTML).toContain('After tool');
        });
    });
});

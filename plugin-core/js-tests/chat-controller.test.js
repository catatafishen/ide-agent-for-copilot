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
            CC().appendAgentText('t0', 'main', 'Hello from agent');
            const msgs = getMessages().querySelectorAll('chat-message');
            expect(msgs.length).toBe(1);
            expect(msgs[0].getAttribute('type')).toBe('agent');
        });

        it('accumulates streaming text', () => {
            CC().appendAgentText('t0', 'main', 'Part 1 ');
            CC().appendAgentText('t0', 'main', 'Part 2');
            const bubble = getMessages().querySelector('message-bubble');
            expect(bubble.textContent).toContain('Part 1 Part 2');
        });

        it('finalizeAgentText replaces with rendered HTML', () => {
            CC().appendAgentText('t0', 'main', 'raw text');
            const html = '<p>Final</p>';
            const encoded = btoa(html);
            CC().finalizeAgentText('t0', 'main', encoded);
            const bubble = getMessages().querySelector('message-bubble');
            expect(bubble.innerHTML).toContain('<p>Final</p>');
        });

        it('finalizeAgentText with null removes empty bubble', () => {
            CC().appendAgentText('t0', 'main', '');
            const countBefore = getMessages().querySelectorAll('chat-message').length;
            CC().finalizeAgentText('t0', 'main', null);
            const countAfter = getMessages().querySelectorAll('chat-message').length;
            expect(countAfter).toBeLessThanOrEqual(countBefore);
        });

        it('skips blank text when no current bubble', () => {
            CC().appendAgentText('t0', 'main', '   ');
            expect(getMessages().querySelectorAll('chat-message').length).toBe(0);
        });

        it('different turns create separate messages', () => {
            CC().appendAgentText('t0', 'main', 'First turn');
            CC().finalizeAgentText('t0', 'main', btoa('<p>First</p>'));
            CC().appendAgentText('t1', 'main', 'Second turn');
            CC().finalizeAgentText('t1', 'main', btoa('<p>Second</p>'));
            const msgs = getMessages().querySelectorAll('chat-message[type="agent"]');
            expect(msgs.length).toBe(2);
        });
    });

    describe('thinking', () => {
        it('addThinkingText creates thinking-block', () => {
            CC().addThinkingText('t0', 'main', 'Let me think...');
            const blocks = getMessages().querySelectorAll('thinking-block');
            expect(blocks.length).toBe(1);
            expect(blocks[0].hasAttribute('active')).toBe(true);
        });

        it('thinking text accumulates', () => {
            CC().addThinkingText('t0', 'main', 'Step 1. ');
            CC().addThinkingText('t0', 'main', 'Step 2.');
            const block = getMessages().querySelector('thinking-block');
            const content = block.querySelector('.thinking-content');
            expect(content.textContent).toContain('Step 1. Step 2.');
        });

        it('collapseThinking creates chip and hides block', () => {
            CC().addThinkingText('t0', 'main', 'Thinking...');
            CC().collapseThinking('t0', 'main');
            const block = getMessages().querySelector('thinking-block');
            expect(block.classList.contains('turn-hidden')).toBe(true);
            const chip = getMessages().querySelector('thinking-chip');
            expect(chip).not.toBeNull();
        });

        it('collapseThinking is no-op if no active thinking', () => {
            CC().collapseThinking('t0', 'main');
            expect(getMessages().querySelectorAll('thinking-chip').length).toBe(0);
        });
    });

    describe('tool calls', () => {
        it('addToolCall creates tool section with chip', () => {
            CC().addToolCall('t0', 'main', 'tc-1', 'Search — query', '{"q":"test"}');
            const chip = getMessages().querySelector('tool-chip');
            expect(chip).not.toBeNull();
            expect(chip.textContent).toContain('Search');
        });

        it('updateToolCall updates status', () => {
            CC().addToolCall('t0', 'main', 'tc-1', 'Read File', '{}');
            CC().updateToolCall('tc-1', 'completed', 'file contents');
            const chip = getMessages().querySelector('tool-chip');
            expect(chip.getAttribute('status')).toBe('completed');
        });

        it('multiple tool calls in same turn share message', () => {
            CC().addToolCall('t0', 'main', 'tc-1', 'Tool A', '{}');
            CC().addToolCall('t0', 'main', 'tc-2', 'Tool B', '{}');
            const agentMsgs = getMessages().querySelectorAll('chat-message[type="agent"]');
            expect(agentMsgs.length).toBe(1);
            const chips = agentMsgs[0].querySelectorAll('tool-chip');
            expect(chips.length).toBe(2);
        });
    });

    describe('sub-agents', () => {
        it('addSubAgent creates section with chip', () => {
            CC().addSubAgent('t0', 'main', 'sa-1', 'Explore', 0, 'Find the file');
            const chip = getMessages().querySelector('subagent-chip');
            expect(chip).not.toBeNull();
            expect(chip.textContent).toContain('Explore');
        });

        it('updateSubAgent sets status and result', () => {
            CC().addSubAgent('t0', 'main', 'sa-1', 'Task Agent', 1, 'Do something');
            CC().updateSubAgent('sa-1', 'completed', '<p>Done</p>');
            const chip = getMessages().querySelector('subagent-chip');
            expect(chip.getAttribute('status')).toBe('completed');
        });
    });

    describe('clear / showPlaceholder', () => {
        it('clear removes all messages', () => {
            CC().addUserMessage('Hello', '10:00', '');
            CC().appendAgentText('t0', 'main', 'Response');
            CC().clear();
            expect(getMessages().querySelectorAll('chat-message').length).toBe(0);
        });

        it('showPlaceholder shows placeholder text', () => {
            CC().showPlaceholder('Loading...');
            const ph = getMessages().querySelector('#placeholder');
            expect(ph.textContent).toContain('Loading...');
        });
    });

    describe('quick replies', () => {
        it('showQuickReplies adds buttons', () => {
            CC().showQuickReplies(['Yes', 'No']);
            const btns = getMessages().querySelectorAll('.quick-reply-btn');
            expect(btns.length).toBe(2);
        });

        it('disableQuickReplies marks buttons disabled except selected', () => {
            CC().showQuickReplies(['A', 'B']);
            CC().disableQuickReplies(0);
            const all = getMessages().querySelectorAll('.quick-reply-btn');
            expect(all[0].hasAttribute('disabled')).toBe(true);
            expect(all[1].hasAttribute('disabled')).toBe(false);
        });
    });

    describe('finalizeTurn', () => {
        it('removes pending span from bubble', () => {
            CC().appendAgentText('t0', 'main', 'Final response');
            CC().finalizeTurn('t0', null);
            const bubble = getMessages().querySelector('message-bubble');
            if (bubble) expect(bubble.querySelector('.agent-pending')).toBeNull();
        });

        it('adds stats chip when model provided', () => {
            CC().appendAgentText('t0', 'main', 'Response');
            CC().finalizeTurn('t0', {model: 'claude-opus-4.6', mult: '1x'});
            const stats = getMessages().querySelector('.turn-chip.stats');
            expect(stats).not.toBeNull();
            expect(stats.textContent).toBe('1x');
        });

        it('resets agent state for next turn', () => {
            CC().appendAgentText('t0', 'main', 'Turn 1');
            CC().finalizeTurn('t0', null);
            CC().addUserMessage('Next prompt', '10:01', '');
            CC().appendAgentText('t1', 'main', 'Turn 2');
            const agentMsgs = getMessages().querySelectorAll('chat-message[type="agent"]');
            expect(agentMsgs.length).toBe(2);
        });
    });

    describe('full conversation flow', () => {
        it('renders a complete turn: user → thinking → tools → agent', () => {
            CC().addUserMessage('Explain the code', '10:00', '');

            CC().addThinkingText('t0', 'main', 'Let me analyze...');

            CC().addToolCall('t0', 'main', 'tc-flow', 'Read File — main.kt', '{"path":"main.kt"}');
            CC().updateToolCall('tc-flow', 'completed', 'file contents here');

            CC().collapseThinking('t0', 'main');

            CC().appendAgentText('t0', 'main', 'The code does ');
            CC().appendAgentText('t0', 'main', 'the following...');

            const html = '<p>The code does the following...</p>';
            CC().finalizeAgentText('t0', 'main', btoa(html));
            CC().finalizeTurn('t0', {model: 'opus', mult: '5x'});

            const messages = getMessages();
            expect(messages.querySelectorAll('chat-message[type="user"]').length).toBe(1);
            expect(messages.querySelectorAll('thinking-block').length).toBe(1);
            expect(messages.querySelectorAll('tool-section').length).toBe(1);
            expect(messages.querySelectorAll('chat-message[type="agent"]').length).toBeGreaterThanOrEqual(1);
            expect(messages.querySelectorAll('tool-chip').length).toBe(1);
            expect(messages.querySelectorAll('thinking-chip').length).toBe(1);
        });

        it('handles multiple turns correctly', () => {
            CC().addUserMessage('Q1', '10:00', '');
            CC().appendAgentText('t0', 'main', 'A1');
            CC().finalizeAgentText('t0', 'main', btoa('<p>A1</p>'));
            CC().finalizeTurn('t0', null);

            CC().addUserMessage('Q2', '10:01', '');
            CC().appendAgentText('t1', 'main', 'A2');
            CC().finalizeAgentText('t1', 'main', btoa('<p>A2</p>'));
            CC().finalizeTurn('t1', null);

            expect(getMessages().querySelectorAll('chat-message[type="user"]').length).toBe(2);
            expect(getMessages().querySelectorAll('chat-message[type="agent"]').length).toBe(2);
        });

        it('handles sub-agent flow', () => {
            CC().addUserMessage('Do something', '10:00', '');
            CC().addSubAgent('t0', 'main', 'sa-flow', 'Explore', 0, 'Find the file');
            CC().updateSubAgent('sa-flow', 'completed', '<p>Found it</p>');
            CC().appendAgentText('t0', 'main', 'Done');
            CC().finalizeAgentText('t0', 'main', btoa('<p>Done</p>'));
            CC().finalizeTurn('t0', null);

            expect(getMessages().querySelectorAll('.subagent-indent').length).toBe(1);
            expect(getMessages().querySelectorAll('subagent-chip').length).toBe(1);
        });

        it('text after tool call goes to new bubble, not overwriting', () => {
            CC().appendAgentText('t0', 'main', 'Before tool');
            CC().finalizeAgentText('t0', 'main', btoa('<p>Before tool</p>'));

            CC().addToolCall('t0', 'main', 'tc-mid', 'Search', '{}');
            CC().updateToolCall('tc-mid', 'completed', 'results');

            CC().appendAgentText('t0', 'main', 'After tool');
            CC().finalizeAgentText('t0', 'main', btoa('<p>After tool</p>'));

            const bubbles = getMessages().querySelectorAll('message-bubble');
            expect(bubbles.length).toBe(2);
            expect(bubbles[0].innerHTML).toContain('Before tool');
            expect(bubbles[1].innerHTML).toContain('After tool');
        });
    });
});

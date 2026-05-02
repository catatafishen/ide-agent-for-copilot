export class TodoView extends HTMLElement {
    private _content!: HTMLElement;
    private _empty!: HTMLElement;
    private _pollTimer: number | null = null;
    private _lastContent: string | null = null;

    connectedCallback(): void {
        this.innerHTML = `
            <div class="tv-container">
                <div class="tv-empty">No plan file yet. The agent creates plan.md during complex tasks.</div>
                <div class="tv-content"></div>
            </div>`;
        this._content = this.querySelector('.tv-content')!;
        this._empty = this.querySelector('.tv-empty')!;
        this.refresh();
        this._pollTimer = window.setInterval(() => this.refresh(), 2000);
    }

    disconnectedCallback(): void {
        if (this._pollTimer) clearInterval(this._pollTimer);
    }

    async refresh(): Promise<void> {
        try {
            const resp = await fetch('/plan');
            if (!resp.ok) return;
            const data = await resp.json() as { content: string | null };
            if (data.content === this._lastContent) return;
            this._lastContent = data.content;

            if (!data.content) {
                this._empty.style.display = '';
                this._content.style.display = 'none';
                return;
            }
            this._empty.style.display = 'none';
            this._content.style.display = '';
            this._content.innerHTML = this._renderMarkdown(data.content);
        } catch (e) {
            // Silently ignore
        }
    }

    private _renderMarkdown(md: string): string {
        // Simple markdown rendering: headings, checkboxes, code blocks, bold, italic, links
        const lines = md.split('\n');
        const out: string[] = [];
        let inCodeBlock = false;

        for (const line of lines) {
            if (line.startsWith('```')) {
                if (inCodeBlock) {
                    out.push('</code></pre>');
                    inCodeBlock = false;
                } else {
                    out.push('<pre><code>');
                    inCodeBlock = true;
                }
                continue;
            }
            if (inCodeBlock) {
                out.push(this._esc(line) + '\n');
                continue;
            }

            // Headings
            const headingMatch = line.match(/^(#{1,6})\s+(.*)/);
            if (headingMatch) {
                const level = headingMatch[1].length;
                out.push(`<h${level}>${this._inline(headingMatch[2])}</h${level}>`);
                continue;
            }

            // Checkboxes
            const checkMatch = line.match(/^(\s*[-*])\s+\[([ xX])]\s+(.*)/);
            if (checkMatch) {
                const checked = checkMatch[2] !== ' ';
                const cls = checked ? 'tv-done' : 'tv-pending';
                out.push(`<div class="tv-check ${cls}">${checked ? '☑' : '☐'} ${this._inline(checkMatch[3])}</div>`);
                continue;
            }

            // List items
            const listMatch = line.match(/^(\s*[-*])\s+(.*)/);
            if (listMatch) {
                out.push(`<div class="tv-li">• ${this._inline(listMatch[2])}</div>`);
                continue;
            }

            // Empty lines
            if (line.trim() === '') {
                out.push('<br>');
                continue;
            }

            // Regular paragraph
            out.push(`<p>${this._inline(line)}</p>`);
        }

        if (inCodeBlock) out.push('</code></pre>');
        return out.join('\n');
    }

    private _inline(text: string): string {
        let s = this._esc(text);
        // Bold
        s = s.replaceAll(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
        // Italic
        s = s.replaceAll(/\*(.+?)\*/g, '<em>$1</em>');
        // Inline code
        s = s.replaceAll(/`(.+?)`/g, '<code>$1</code>');
        return s;
    }

    private _esc(s: string): string {
        return s.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
    }
}

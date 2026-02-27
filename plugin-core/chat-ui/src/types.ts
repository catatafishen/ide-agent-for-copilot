export interface Bridge {
    openFile(href: string): void;
    openUrl(href: string): void;
    setCursor(cursor: string): void;
    loadMore(): void;
    quickReply(text: string): void;
}

export interface TurnContext {
    msg: HTMLElement | null;
    meta: HTMLElement | null;
    details: HTMLElement | null;
    textBubble: HTMLElement | null;
    thinkingBlock: HTMLElement | null;
}

declare global {
    interface Window {
        _bridge?: Bridge;
        ChatController: typeof import('./ChatController').default;
    }
    // eslint-disable-next-line no-var
    var _bridge: Bridge | undefined;
}

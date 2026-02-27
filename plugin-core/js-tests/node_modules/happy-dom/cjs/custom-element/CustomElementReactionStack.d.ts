import BrowserWindow from '../window/BrowserWindow.cjs';
import Element from '../nodes/element/Element.cjs';
/**
 * Custom element reaction stack.
 *
 * @see https://html.spec.whatwg.org/multipage/custom-elements.html#custom-element-reactions-stack
 */
export default class CustomElementReactionStack {
    private window;
    /**
     * Constructor.
     *
     * @param window Window.
     */
    constructor(window: BrowserWindow);
    /**
     * Enqueues a custom element reaction.
     *
     * @see https://html.spec.whatwg.org/multipage/custom-elements.html#enqueue-a-custom-element-callback-reaction
     * @see https://html.spec.whatwg.org/multipage/custom-elements.html#enqueue-an-element-on-the-appropriate-element-queue
     * @param element Element.
     * @param callbackName Callback name.
     * @param [args] Arguments.
     */
    enqueueReaction(element: Element, callbackName: string, args?: any[]): void;
}
//# sourceMappingURL=CustomElementReactionStack.d.ts.map
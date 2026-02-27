import SVGElement from '../svg-element/SVGElement.cjs';
import SVGStringList from '../../svg/SVGStringList.cjs';
import * as PropertySymbol from '../../PropertySymbol.cjs';
import Event from '../../event/Event.cjs';
/**
 * SVG Animation Element.
 *
 * @see https://developer.mozilla.org/en-US/docs/Web/API/SVGAnimationElement
 */
export default class SVGAnimationElement extends SVGElement {
    [PropertySymbol.requiredExtensions]: SVGStringList | null;
    [PropertySymbol.systemLanguage]: SVGStringList | null;
    get onbegin(): ((event: Event) => void) | null;
    set onbegin(value: ((event: Event) => void) | null);
    get onend(): ((event: Event) => void) | null;
    set onend(value: ((event: Event) => void) | null);
    get onrepeat(): ((event: Event) => void) | null;
    set onrepeat(value: ((event: Event) => void) | null);
    /**
     * Returns required extensions.
     *
     * @returns Required extensions.
     */
    get requiredExtensions(): SVGStringList;
    /**
     * Returns system language.
     *
     * @returns System language.
     */
    get systemLanguage(): SVGStringList;
    /**
     * Returns target element.
     *
     * @returns Target element.
     */
    get targetElement(): SVGElement | null;
}
//# sourceMappingURL=SVGAnimationElement.d.ts.map
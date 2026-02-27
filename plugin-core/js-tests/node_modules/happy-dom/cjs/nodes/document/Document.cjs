"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const Element_js_1 = __importDefault(require("../element/Element.cjs"));
const PropertySymbol = __importStar(require("../../PropertySymbol.cjs"));
const Node_js_1 = __importDefault(require("../node/Node.cjs"));
const NodeIterator_js_1 = __importDefault(require("../../tree-walker/NodeIterator.cjs"));
const TreeWalker_js_1 = __importDefault(require("../../tree-walker/TreeWalker.cjs"));
const Event_js_1 = __importDefault(require("../../event/Event.cjs"));
const DOMImplementation_js_1 = __importDefault(require("../../dom-implementation/DOMImplementation.cjs"));
const NamespaceURI_js_1 = __importDefault(require("../../config/NamespaceURI.cjs"));
const DocumentType_js_1 = __importDefault(require("../document-type/DocumentType.cjs"));
const ParentNodeUtility_js_1 = __importDefault(require("../parent-node/ParentNodeUtility.cjs"));
const QuerySelector_js_1 = __importDefault(require("../../query-selector/QuerySelector.cjs"));
const HTMLCollection_js_1 = __importDefault(require("../element/HTMLCollection.cjs"));
const DocumentReadyStateEnum_js_1 = __importDefault(require("./DocumentReadyStateEnum.cjs"));
const Selection_js_1 = __importDefault(require("../../selection/Selection.cjs"));
const VisibilityStateEnum_js_1 = __importDefault(require("./VisibilityStateEnum.cjs"));
const NodeTypeEnum_js_1 = __importDefault(require("../node/NodeTypeEnum.cjs"));
const CookieStringUtility_js_1 = __importDefault(require("../../cookie/urilities/CookieStringUtility.cjs"));
const url_1 = require("url");
const HTMLElementConfig_js_1 = __importDefault(require("../../config/HTMLElementConfig.cjs"));
const WindowBrowserContext_js_1 = __importDefault(require("../../window/WindowBrowserContext.cjs"));
const NodeFactory_js_1 = __importDefault(require("../NodeFactory.cjs"));
const SVGElementConfig_js_1 = __importDefault(require("../../config/SVGElementConfig.cjs"));
const StringUtility_js_1 = __importDefault(require("../../utilities/StringUtility.cjs"));
const HTMLParser_js_1 = __importDefault(require("../../html-parser/HTMLParser.cjs"));
const DOMExceptionNameEnum_js_1 = __importDefault(require("../../exception/DOMExceptionNameEnum.cjs"));
const PROCESSING_INSTRUCTION_TARGET_REGEXP = /^[a-z][a-z0-9-]+$/;
/**
 * Document.
 */
class Document extends Node_js_1.default {
    // Internal properties
    [PropertySymbol.children] = null;
    [PropertySymbol.activeElement] = null;
    [PropertySymbol.nextActiveElement] = null;
    [PropertySymbol.currentScript] = null;
    [PropertySymbol.rootNode] = this;
    [PropertySymbol.isFirstWrite] = true;
    [PropertySymbol.isFirstWriteAfterOpen] = false;
    [PropertySymbol.nodeType] = NodeTypeEnum_js_1.default.documentNode;
    [PropertySymbol.isConnected] = true;
    [PropertySymbol.adoptedStyleSheets] = [];
    [PropertySymbol.implementation] = new DOMImplementation_js_1.default(this);
    [PropertySymbol.readyState] = DocumentReadyStateEnum_js_1.default.interactive;
    [PropertySymbol.referrer] = '';
    [PropertySymbol.defaultView] = null;
    [PropertySymbol.forms] = null;
    [PropertySymbol.affectsComputedStyleCache] = [];
    [PropertySymbol.ownerDocument] = null;
    [PropertySymbol.elementIdMap] = new Map();
    [PropertySymbol.contentType] = 'text/html';
    [PropertySymbol.xmlProcessingInstruction] = null;
    [PropertySymbol.preloads] = new Map();
    [PropertySymbol.propertyEventListeners] = new Map();
    // Private properties
    #selection = null;
    // Events
    /* eslint-disable jsdoc/require-jsdoc */
    get onreadystatechange() {
        return this[PropertySymbol.propertyEventListeners].get('onreadystatechange') ?? null;
    }
    set onreadystatechange(value) {
        this[PropertySymbol.propertyEventListeners].set('onreadystatechange', value);
    }
    get onpointerlockchange() {
        return this[PropertySymbol.propertyEventListeners].get('onpointerlockchange') ?? null;
    }
    set onpointerlockchange(value) {
        this[PropertySymbol.propertyEventListeners].set('onpointerlockchange', value);
    }
    get onpointerlockerror() {
        return this[PropertySymbol.propertyEventListeners].get('onpointerlockerror') ?? null;
    }
    set onpointerlockerror(value) {
        this[PropertySymbol.propertyEventListeners].set('onpointerlockerror', value);
    }
    get onbeforecopy() {
        return this[PropertySymbol.propertyEventListeners].get('onbeforecopy') ?? null;
    }
    set onbeforecopy(value) {
        this[PropertySymbol.propertyEventListeners].set('onbeforecopy', value);
    }
    get onbeforecut() {
        return this[PropertySymbol.propertyEventListeners].get('onbeforecut') ?? null;
    }
    set onbeforecut(value) {
        this[PropertySymbol.propertyEventListeners].set('onbeforecut', value);
    }
    get onbeforepaste() {
        return this[PropertySymbol.propertyEventListeners].get('onbeforepaste') ?? null;
    }
    set onbeforepaste(value) {
        this[PropertySymbol.propertyEventListeners].set('onbeforepaste', value);
    }
    get onfreeze() {
        return this[PropertySymbol.propertyEventListeners].get('onfreeze') ?? null;
    }
    set onfreeze(value) {
        this[PropertySymbol.propertyEventListeners].set('onfreeze', value);
    }
    get onprerenderingchange() {
        return this[PropertySymbol.propertyEventListeners].get('onprerenderingchange') ?? null;
    }
    set onprerenderingchange(value) {
        this[PropertySymbol.propertyEventListeners].set('onprerenderingchange', value);
    }
    get onresume() {
        return this[PropertySymbol.propertyEventListeners].get('onresume') ?? null;
    }
    set onresume(value) {
        this[PropertySymbol.propertyEventListeners].set('onresume', value);
    }
    get onsearch() {
        return this[PropertySymbol.propertyEventListeners].get('onsearch') ?? null;
    }
    set onsearch(value) {
        this[PropertySymbol.propertyEventListeners].set('onsearch', value);
    }
    get onvisibilitychange() {
        return this[PropertySymbol.propertyEventListeners].get('onvisibilitychange') ?? null;
    }
    set onvisibilitychange(value) {
        this[PropertySymbol.propertyEventListeners].set('onvisibilitychange', value);
    }
    get onfullscreenchange() {
        return this[PropertySymbol.propertyEventListeners].get('onfullscreenchange') ?? null;
    }
    set onfullscreenchange(value) {
        this[PropertySymbol.propertyEventListeners].set('onfullscreenchange', value);
    }
    get onfullscreenerror() {
        return this[PropertySymbol.propertyEventListeners].get('onfullscreenerror') ?? null;
    }
    set onfullscreenerror(value) {
        this[PropertySymbol.propertyEventListeners].set('onfullscreenerror', value);
    }
    get onwebkitfullscreenchange() {
        return this[PropertySymbol.propertyEventListeners].get('onwebkitfullscreenchange') ?? null;
    }
    set onwebkitfullscreenchange(value) {
        this[PropertySymbol.propertyEventListeners].set('onwebkitfullscreenchange', value);
    }
    get onwebkitfullscreenerror() {
        return this[PropertySymbol.propertyEventListeners].get('onwebkitfullscreenerror') ?? null;
    }
    set onwebkitfullscreenerror(value) {
        this[PropertySymbol.propertyEventListeners].set('onwebkitfullscreenerror', value);
    }
    get onbeforexrselect() {
        return this[PropertySymbol.propertyEventListeners].get('onbeforexrselect') ?? null;
    }
    set onbeforexrselect(value) {
        this[PropertySymbol.propertyEventListeners].set('onbeforexrselect', value);
    }
    get onabort() {
        return this[PropertySymbol.propertyEventListeners].get('onabort') ?? null;
    }
    set onabort(value) {
        this[PropertySymbol.propertyEventListeners].set('onabort', value);
    }
    get onbeforeinput() {
        return this[PropertySymbol.propertyEventListeners].get('onbeforeinput') ?? null;
    }
    set onbeforeinput(value) {
        this[PropertySymbol.propertyEventListeners].set('onbeforeinput', value);
    }
    get onbeforematch() {
        return this[PropertySymbol.propertyEventListeners].get('onbeforematch') ?? null;
    }
    set onbeforematch(value) {
        this[PropertySymbol.propertyEventListeners].set('onbeforematch', value);
    }
    get onbeforetoggle() {
        return this[PropertySymbol.propertyEventListeners].get('onbeforetoggle') ?? null;
    }
    set onbeforetoggle(value) {
        this[PropertySymbol.propertyEventListeners].set('onbeforetoggle', value);
    }
    get onblur() {
        return this[PropertySymbol.propertyEventListeners].get('onblur') ?? null;
    }
    set onblur(value) {
        this[PropertySymbol.propertyEventListeners].set('onblur', value);
    }
    get oncancel() {
        return this[PropertySymbol.propertyEventListeners].get('oncancel') ?? null;
    }
    set oncancel(value) {
        this[PropertySymbol.propertyEventListeners].set('oncancel', value);
    }
    get oncanplay() {
        return this[PropertySymbol.propertyEventListeners].get('oncanplay') ?? null;
    }
    set oncanplay(value) {
        this[PropertySymbol.propertyEventListeners].set('oncanplay', value);
    }
    get oncanplaythrough() {
        return this[PropertySymbol.propertyEventListeners].get('oncanplaythrough') ?? null;
    }
    set oncanplaythrough(value) {
        this[PropertySymbol.propertyEventListeners].set('oncanplaythrough', value);
    }
    get onchange() {
        return this[PropertySymbol.propertyEventListeners].get('onchange') ?? null;
    }
    set onchange(value) {
        this[PropertySymbol.propertyEventListeners].set('onchange', value);
    }
    get onclick() {
        return this[PropertySymbol.propertyEventListeners].get('onclick') ?? null;
    }
    set onclick(value) {
        this[PropertySymbol.propertyEventListeners].set('onclick', value);
    }
    get onclose() {
        return this[PropertySymbol.propertyEventListeners].get('onclose') ?? null;
    }
    set onclose(value) {
        this[PropertySymbol.propertyEventListeners].set('onclose', value);
    }
    get oncontentvisibilityautostatechange() {
        return (this[PropertySymbol.propertyEventListeners].get('oncontentvisibilityautostatechange') ?? null);
    }
    set oncontentvisibilityautostatechange(value) {
        this[PropertySymbol.propertyEventListeners].set('oncontentvisibilityautostatechange', value);
    }
    get oncontextlost() {
        return this[PropertySymbol.propertyEventListeners].get('oncontextlost') ?? null;
    }
    set oncontextlost(value) {
        this[PropertySymbol.propertyEventListeners].set('oncontextlost', value);
    }
    get oncontextmenu() {
        return this[PropertySymbol.propertyEventListeners].get('oncontextmenu') ?? null;
    }
    set oncontextmenu(value) {
        this[PropertySymbol.propertyEventListeners].set('oncontextmenu', value);
    }
    get oncontextrestored() {
        return this[PropertySymbol.propertyEventListeners].get('oncontextrestored') ?? null;
    }
    set oncontextrestored(value) {
        this[PropertySymbol.propertyEventListeners].set('oncontextrestored', value);
    }
    get oncuechange() {
        return this[PropertySymbol.propertyEventListeners].get('oncuechange') ?? null;
    }
    set oncuechange(value) {
        this[PropertySymbol.propertyEventListeners].set('oncuechange', value);
    }
    get ondblclick() {
        return this[PropertySymbol.propertyEventListeners].get('ondblclick') ?? null;
    }
    set ondblclick(value) {
        this[PropertySymbol.propertyEventListeners].set('ondblclick', value);
    }
    get ondrag() {
        return this[PropertySymbol.propertyEventListeners].get('ondrag') ?? null;
    }
    set ondrag(value) {
        this[PropertySymbol.propertyEventListeners].set('ondrag', value);
    }
    get ondragend() {
        return this[PropertySymbol.propertyEventListeners].get('ondragend') ?? null;
    }
    set ondragend(value) {
        this[PropertySymbol.propertyEventListeners].set('ondragend', value);
    }
    get ondragenter() {
        return this[PropertySymbol.propertyEventListeners].get('ondragenter') ?? null;
    }
    set ondragenter(value) {
        this[PropertySymbol.propertyEventListeners].set('ondragenter', value);
    }
    get ondragleave() {
        return this[PropertySymbol.propertyEventListeners].get('ondragleave') ?? null;
    }
    set ondragleave(value) {
        this[PropertySymbol.propertyEventListeners].set('ondragleave', value);
    }
    get ondragover() {
        return this[PropertySymbol.propertyEventListeners].get('ondragover') ?? null;
    }
    set ondragover(value) {
        this[PropertySymbol.propertyEventListeners].set('ondragover', value);
    }
    get ondragstart() {
        return this[PropertySymbol.propertyEventListeners].get('ondragstart') ?? null;
    }
    set ondragstart(value) {
        this[PropertySymbol.propertyEventListeners].set('ondragstart', value);
    }
    get ondrop() {
        return this[PropertySymbol.propertyEventListeners].get('ondrop') ?? null;
    }
    set ondrop(value) {
        this[PropertySymbol.propertyEventListeners].set('ondrop', value);
    }
    get ondurationchange() {
        return this[PropertySymbol.propertyEventListeners].get('ondurationchange') ?? null;
    }
    set ondurationchange(value) {
        this[PropertySymbol.propertyEventListeners].set('ondurationchange', value);
    }
    get onemptied() {
        return this[PropertySymbol.propertyEventListeners].get('onemptied') ?? null;
    }
    set onemptied(value) {
        this[PropertySymbol.propertyEventListeners].set('onemptied', value);
    }
    get onended() {
        return this[PropertySymbol.propertyEventListeners].get('onended') ?? null;
    }
    set onended(value) {
        this[PropertySymbol.propertyEventListeners].set('onended', value);
    }
    get onerror() {
        return this[PropertySymbol.propertyEventListeners].get('onerror') ?? null;
    }
    set onerror(value) {
        this[PropertySymbol.propertyEventListeners].set('onerror', value);
    }
    get onfocus() {
        return this[PropertySymbol.propertyEventListeners].get('onfocus') ?? null;
    }
    set onfocus(value) {
        this[PropertySymbol.propertyEventListeners].set('onfocus', value);
    }
    get onformdata() {
        return this[PropertySymbol.propertyEventListeners].get('onformdata') ?? null;
    }
    set onformdata(value) {
        this[PropertySymbol.propertyEventListeners].set('onformdata', value);
    }
    get oninput() {
        return this[PropertySymbol.propertyEventListeners].get('oninput') ?? null;
    }
    set oninput(value) {
        this[PropertySymbol.propertyEventListeners].set('oninput', value);
    }
    get oninvalid() {
        return this[PropertySymbol.propertyEventListeners].get('oninvalid') ?? null;
    }
    set oninvalid(value) {
        this[PropertySymbol.propertyEventListeners].set('oninvalid', value);
    }
    get onkeydown() {
        return this[PropertySymbol.propertyEventListeners].get('onkeydown') ?? null;
    }
    set onkeydown(value) {
        this[PropertySymbol.propertyEventListeners].set('onkeydown', value);
    }
    get onkeypress() {
        return this[PropertySymbol.propertyEventListeners].get('onkeypress') ?? null;
    }
    set onkeypress(value) {
        this[PropertySymbol.propertyEventListeners].set('onkeypress', value);
    }
    get onkeyup() {
        return this[PropertySymbol.propertyEventListeners].get('onkeyup') ?? null;
    }
    set onkeyup(value) {
        this[PropertySymbol.propertyEventListeners].set('onkeyup', value);
    }
    get onload() {
        return this[PropertySymbol.propertyEventListeners].get('onload') ?? null;
    }
    set onload(value) {
        this[PropertySymbol.propertyEventListeners].set('onload', value);
    }
    get onloadeddata() {
        return this[PropertySymbol.propertyEventListeners].get('onloadeddata') ?? null;
    }
    set onloadeddata(value) {
        this[PropertySymbol.propertyEventListeners].set('onloadeddata', value);
    }
    get onloadedmetadata() {
        return this[PropertySymbol.propertyEventListeners].get('onloadedmetadata') ?? null;
    }
    set onloadedmetadata(value) {
        this[PropertySymbol.propertyEventListeners].set('onloadedmetadata', value);
    }
    get onloadstart() {
        return this[PropertySymbol.propertyEventListeners].get('onloadstart') ?? null;
    }
    set onloadstart(value) {
        this[PropertySymbol.propertyEventListeners].set('onloadstart', value);
    }
    get onmousedown() {
        return this[PropertySymbol.propertyEventListeners].get('onmousedown') ?? null;
    }
    set onmousedown(value) {
        this[PropertySymbol.propertyEventListeners].set('onmousedown', value);
    }
    get onmouseenter() {
        return this[PropertySymbol.propertyEventListeners].get('onmouseenter') ?? null;
    }
    set onmouseenter(value) {
        this[PropertySymbol.propertyEventListeners].set('onmouseenter', value);
    }
    get onmouseleave() {
        return this[PropertySymbol.propertyEventListeners].get('onmouseleave') ?? null;
    }
    set onmouseleave(value) {
        this[PropertySymbol.propertyEventListeners].set('onmouseleave', value);
    }
    get onmousemove() {
        return this[PropertySymbol.propertyEventListeners].get('onmousemove') ?? null;
    }
    set onmousemove(value) {
        this[PropertySymbol.propertyEventListeners].set('onmousemove', value);
    }
    get onmouseout() {
        return this[PropertySymbol.propertyEventListeners].get('onmouseout') ?? null;
    }
    set onmouseout(value) {
        this[PropertySymbol.propertyEventListeners].set('onmouseout', value);
    }
    get onmouseover() {
        return this[PropertySymbol.propertyEventListeners].get('onmouseover') ?? null;
    }
    set onmouseover(value) {
        this[PropertySymbol.propertyEventListeners].set('onmouseover', value);
    }
    get onmouseup() {
        return this[PropertySymbol.propertyEventListeners].get('onmouseup') ?? null;
    }
    set onmouseup(value) {
        this[PropertySymbol.propertyEventListeners].set('onmouseup', value);
    }
    get onmousewheel() {
        return this[PropertySymbol.propertyEventListeners].get('onmousewheel') ?? null;
    }
    set onmousewheel(value) {
        this[PropertySymbol.propertyEventListeners].set('onmousewheel', value);
    }
    get onpause() {
        return this[PropertySymbol.propertyEventListeners].get('onpause') ?? null;
    }
    set onpause(value) {
        this[PropertySymbol.propertyEventListeners].set('onpause', value);
    }
    get onplay() {
        return this[PropertySymbol.propertyEventListeners].get('onplay') ?? null;
    }
    set onplay(value) {
        this[PropertySymbol.propertyEventListeners].set('onplay', value);
    }
    get onplaying() {
        return this[PropertySymbol.propertyEventListeners].get('onplaying') ?? null;
    }
    set onplaying(value) {
        this[PropertySymbol.propertyEventListeners].set('onplaying', value);
    }
    get onprogress() {
        return this[PropertySymbol.propertyEventListeners].get('onprogress') ?? null;
    }
    set onprogress(value) {
        this[PropertySymbol.propertyEventListeners].set('onprogress', value);
    }
    get onratechange() {
        return this[PropertySymbol.propertyEventListeners].get('onratechange') ?? null;
    }
    set onratechange(value) {
        this[PropertySymbol.propertyEventListeners].set('onratechange', value);
    }
    get onreset() {
        return this[PropertySymbol.propertyEventListeners].get('onreset') ?? null;
    }
    set onreset(value) {
        this[PropertySymbol.propertyEventListeners].set('onreset', value);
    }
    get onresize() {
        return this[PropertySymbol.propertyEventListeners].get('onresize') ?? null;
    }
    set onresize(value) {
        this[PropertySymbol.propertyEventListeners].set('onresize', value);
    }
    get onscroll() {
        return this[PropertySymbol.propertyEventListeners].get('onscroll') ?? null;
    }
    set onscroll(value) {
        this[PropertySymbol.propertyEventListeners].set('onscroll', value);
    }
    get onsecuritypolicyviolation() {
        return this[PropertySymbol.propertyEventListeners].get('onsecuritypolicyviolation') ?? null;
    }
    set onsecuritypolicyviolation(value) {
        this[PropertySymbol.propertyEventListeners].set('onsecuritypolicyviolation', value);
    }
    get onseeked() {
        return this[PropertySymbol.propertyEventListeners].get('onseeked') ?? null;
    }
    set onseeked(value) {
        this[PropertySymbol.propertyEventListeners].set('onseeked', value);
    }
    get onseeking() {
        return this[PropertySymbol.propertyEventListeners].get('onseeking') ?? null;
    }
    set onseeking(value) {
        this[PropertySymbol.propertyEventListeners].set('onseeking', value);
    }
    get onselect() {
        return this[PropertySymbol.propertyEventListeners].get('onselect') ?? null;
    }
    set onselect(value) {
        this[PropertySymbol.propertyEventListeners].set('onselect', value);
    }
    get onslotchange() {
        return this[PropertySymbol.propertyEventListeners].get('onslotchange') ?? null;
    }
    set onslotchange(value) {
        this[PropertySymbol.propertyEventListeners].set('onslotchange', value);
    }
    get onstalled() {
        return this[PropertySymbol.propertyEventListeners].get('onstalled') ?? null;
    }
    set onstalled(value) {
        this[PropertySymbol.propertyEventListeners].set('onstalled', value);
    }
    get onsubmit() {
        return this[PropertySymbol.propertyEventListeners].get('onsubmit') ?? null;
    }
    set onsubmit(value) {
        this[PropertySymbol.propertyEventListeners].set('onsubmit', value);
    }
    get onsuspend() {
        return this[PropertySymbol.propertyEventListeners].get('onsuspend') ?? null;
    }
    set onsuspend(value) {
        this[PropertySymbol.propertyEventListeners].set('onsuspend', value);
    }
    get ontimeupdate() {
        return this[PropertySymbol.propertyEventListeners].get('ontimeupdate') ?? null;
    }
    set ontimeupdate(value) {
        this[PropertySymbol.propertyEventListeners].set('ontimeupdate', value);
    }
    get ontoggle() {
        return this[PropertySymbol.propertyEventListeners].get('ontoggle') ?? null;
    }
    set ontoggle(value) {
        this[PropertySymbol.propertyEventListeners].set('ontoggle', value);
    }
    get onvolumechange() {
        return this[PropertySymbol.propertyEventListeners].get('onvolumechange') ?? null;
    }
    set onvolumechange(value) {
        this[PropertySymbol.propertyEventListeners].set('onvolumechange', value);
    }
    get onwaiting() {
        return this[PropertySymbol.propertyEventListeners].get('onwaiting') ?? null;
    }
    set onwaiting(value) {
        this[PropertySymbol.propertyEventListeners].set('onwaiting', value);
    }
    get onwebkitanimationend() {
        return this[PropertySymbol.propertyEventListeners].get('onwebkitanimationend') ?? null;
    }
    set onwebkitanimationend(value) {
        this[PropertySymbol.propertyEventListeners].set('onwebkitanimationend', value);
    }
    get onwebkitanimationiteration() {
        return this[PropertySymbol.propertyEventListeners].get('onwebkitanimationiteration') ?? null;
    }
    set onwebkitanimationiteration(value) {
        this[PropertySymbol.propertyEventListeners].set('onwebkitanimationiteration', value);
    }
    get onwebkitanimationstart() {
        return this[PropertySymbol.propertyEventListeners].get('onwebkitanimationstart') ?? null;
    }
    set onwebkitanimationstart(value) {
        this[PropertySymbol.propertyEventListeners].set('onwebkitanimationstart', value);
    }
    get onwebkittransitionend() {
        return this[PropertySymbol.propertyEventListeners].get('onwebkittransitionend') ?? null;
    }
    set onwebkittransitionend(value) {
        this[PropertySymbol.propertyEventListeners].set('onwebkittransitionend', value);
    }
    get onwheel() {
        return this[PropertySymbol.propertyEventListeners].get('onwheel') ?? null;
    }
    set onwheel(value) {
        this[PropertySymbol.propertyEventListeners].set('onwheel', value);
    }
    get onauxclick() {
        return this[PropertySymbol.propertyEventListeners].get('onauxclick') ?? null;
    }
    set onauxclick(value) {
        this[PropertySymbol.propertyEventListeners].set('onauxclick', value);
    }
    get ongotpointercapture() {
        return this[PropertySymbol.propertyEventListeners].get('ongotpointercapture') ?? null;
    }
    set ongotpointercapture(value) {
        this[PropertySymbol.propertyEventListeners].set('ongotpointercapture', value);
    }
    get onlostpointercapture() {
        return this[PropertySymbol.propertyEventListeners].get('onlostpointercapture') ?? null;
    }
    set onlostpointercapture(value) {
        this[PropertySymbol.propertyEventListeners].set('onlostpointercapture', value);
    }
    get onpointerdown() {
        return this[PropertySymbol.propertyEventListeners].get('onpointerdown') ?? null;
    }
    set onpointerdown(value) {
        this[PropertySymbol.propertyEventListeners].set('onpointerdown', value);
    }
    get onpointermove() {
        return this[PropertySymbol.propertyEventListeners].get('onpointermove') ?? null;
    }
    set onpointermove(value) {
        this[PropertySymbol.propertyEventListeners].set('onpointermove', value);
    }
    get onpointerrawupdate() {
        return this[PropertySymbol.propertyEventListeners].get('onpointerrawupdate') ?? null;
    }
    set onpointerrawupdate(value) {
        this[PropertySymbol.propertyEventListeners].set('onpointerrawupdate', value);
    }
    get onpointerup() {
        return this[PropertySymbol.propertyEventListeners].get('onpointerup') ?? null;
    }
    set onpointerup(value) {
        this[PropertySymbol.propertyEventListeners].set('onpointerup', value);
    }
    get onpointercancel() {
        return this[PropertySymbol.propertyEventListeners].get('onpointercancel') ?? null;
    }
    set onpointercancel(value) {
        this[PropertySymbol.propertyEventListeners].set('onpointercancel', value);
    }
    get onpointerover() {
        return this[PropertySymbol.propertyEventListeners].get('onpointerover') ?? null;
    }
    set onpointerover(value) {
        this[PropertySymbol.propertyEventListeners].set('onpointerover', value);
    }
    get onpointerout() {
        return this[PropertySymbol.propertyEventListeners].get('onpointerout') ?? null;
    }
    set onpointerout(value) {
        this[PropertySymbol.propertyEventListeners].set('onpointerout', value);
    }
    get onpointerenter() {
        return this[PropertySymbol.propertyEventListeners].get('onpointerenter') ?? null;
    }
    set onpointerenter(value) {
        this[PropertySymbol.propertyEventListeners].set('onpointerenter', value);
    }
    get onpointerleave() {
        return this[PropertySymbol.propertyEventListeners].get('onpointerleave') ?? null;
    }
    set onpointerleave(value) {
        this[PropertySymbol.propertyEventListeners].set('onpointerleave', value);
    }
    get onselectstart() {
        return this[PropertySymbol.propertyEventListeners].get('onselectstart') ?? null;
    }
    set onselectstart(value) {
        this[PropertySymbol.propertyEventListeners].set('onselectstart', value);
    }
    get onselectionchange() {
        return this[PropertySymbol.propertyEventListeners].get('onselectionchange') ?? null;
    }
    set onselectionchange(value) {
        this[PropertySymbol.propertyEventListeners].set('onselectionchange', value);
    }
    get onanimationend() {
        return this[PropertySymbol.propertyEventListeners].get('onanimationend') ?? null;
    }
    set onanimationend(value) {
        this[PropertySymbol.propertyEventListeners].set('onanimationend', value);
    }
    get onanimationiteration() {
        return this[PropertySymbol.propertyEventListeners].get('onanimationiteration') ?? null;
    }
    set onanimationiteration(value) {
        this[PropertySymbol.propertyEventListeners].set('onanimationiteration', value);
    }
    get onanimationstart() {
        return this[PropertySymbol.propertyEventListeners].get('onanimationstart') ?? null;
    }
    set onanimationstart(value) {
        this[PropertySymbol.propertyEventListeners].set('onanimationstart', value);
    }
    get ontransitionrun() {
        return this[PropertySymbol.propertyEventListeners].get('ontransitionrun') ?? null;
    }
    set ontransitionrun(value) {
        this[PropertySymbol.propertyEventListeners].set('ontransitionrun', value);
    }
    get ontransitionstart() {
        return this[PropertySymbol.propertyEventListeners].get('ontransitionstart') ?? null;
    }
    set ontransitionstart(value) {
        this[PropertySymbol.propertyEventListeners].set('ontransitionstart', value);
    }
    get ontransitionend() {
        return this[PropertySymbol.propertyEventListeners].get('ontransitionend') ?? null;
    }
    set ontransitionend(value) {
        this[PropertySymbol.propertyEventListeners].set('ontransitionend', value);
    }
    get ontransitioncancel() {
        return this[PropertySymbol.propertyEventListeners].get('ontransitioncancel') ?? null;
    }
    set ontransitioncancel(value) {
        this[PropertySymbol.propertyEventListeners].set('ontransitioncancel', value);
    }
    get oncopy() {
        return this[PropertySymbol.propertyEventListeners].get('oncopy') ?? null;
    }
    set oncopy(value) {
        this[PropertySymbol.propertyEventListeners].set('oncopy', value);
    }
    get oncut() {
        return this[PropertySymbol.propertyEventListeners].get('oncut') ?? null;
    }
    set oncut(value) {
        this[PropertySymbol.propertyEventListeners].set('oncut', value);
    }
    get onpaste() {
        return this[PropertySymbol.propertyEventListeners].get('onpaste') ?? null;
    }
    set onpaste(value) {
        this[PropertySymbol.propertyEventListeners].set('onpaste', value);
    }
    get onscrollend() {
        return this[PropertySymbol.propertyEventListeners].get('onscrollend') ?? null;
    }
    set onscrollend(value) {
        this[PropertySymbol.propertyEventListeners].set('onscrollend', value);
    }
    get onscrollsnapchange() {
        return this[PropertySymbol.propertyEventListeners].get('onscrollsnapchange') ?? null;
    }
    set onscrollsnapchange(value) {
        this[PropertySymbol.propertyEventListeners].set('onscrollsnapchange', value);
    }
    get onscrollsnapchanging() {
        return this[PropertySymbol.propertyEventListeners].get('onscrollsnapchanging') ?? null;
    }
    set onscrollsnapchanging(value) {
        this[PropertySymbol.propertyEventListeners].set('onscrollsnapchanging', value);
    }
    /* eslint-enable jsdoc/require-jsdoc */
    /**
     * Returns adopted style sheets.
     *
     * @returns Adopted style sheets.
     */
    get adoptedStyleSheets() {
        return this[PropertySymbol.adoptedStyleSheets];
    }
    /**
     * Sets adopted style sheets.
     *
     * @param value Adopted style sheets.
     */
    set adoptedStyleSheets(value) {
        this[PropertySymbol.adoptedStyleSheets] = value;
    }
    /**
     * Returns DOM implementation.
     *
     * @returns DOM implementation.
     */
    get implementation() {
        return this[PropertySymbol.implementation];
    }
    /**
     * Returns document ready state.
     *
     * @returns Document ready state.
     */
    get readyState() {
        return this[PropertySymbol.readyState];
    }
    /**
     * Returns referrer.
     *
     * @returns Referrer.
     */
    get referrer() {
        return this[PropertySymbol.referrer];
    }
    /**
     * Returns default view.
     *
     * @returns Default view.
     */
    get defaultView() {
        return this[PropertySymbol.defaultView];
    }
    /**
     * Returns document children.
     */
    get children() {
        if (!this[PropertySymbol.children]) {
            const elements = this[PropertySymbol.elementArray];
            this[PropertySymbol.children] = new HTMLCollection_js_1.default(PropertySymbol.illegalConstructor, () => elements);
        }
        return this[PropertySymbol.children];
    }
    /**
     * Returns character set.
     *
     * @deprecated
     * @returns Character set.
     */
    get charset() {
        return this.characterSet;
    }
    /**
     * Returns character set.
     *
     * @returns Character set.
     */
    get characterSet() {
        const charset = QuerySelector_js_1.default.querySelector(this, 'meta[charset]')?.getAttributeNS(null, 'charset');
        return charset ? charset : 'UTF-8';
    }
    /**
     * Returns title.
     *
     * @returns Title.
     */
    get title() {
        const element = ParentNodeUtility_js_1.default.getElementByTagName(this, 'title');
        if (element) {
            return element.text.trim();
        }
        return '';
    }
    /**
     * Returns set title.
     *
     */
    set title(title) {
        const element = ParentNodeUtility_js_1.default.getElementByTagName(this, 'title');
        if (element) {
            element.textContent = title;
        }
        else {
            const newElement = this.createElement('title');
            newElement.textContent = title;
            this.head.appendChild(newElement);
        }
    }
    /**
     * Returns a collection of all area elements and a elements in a document with a value for the href attribute.
     */
    get links() {
        return QuerySelector_js_1.default.querySelectorAll(this, 'a[href],area[href]');
    }
    /**
     * Returns a collection of all form elements in a document.
     */
    get forms() {
        if (!this[PropertySymbol.forms]) {
            this[PropertySymbol.forms] = (ParentNodeUtility_js_1.default.getElementsByTagName(this, 'form'));
        }
        return this[PropertySymbol.forms];
    }
    /**
     * Last element child.
     *
     * @returns Element.
     */
    get childElementCount() {
        return this[PropertySymbol.elementArray].length;
    }
    /**
     * First element child.
     *
     * @returns Element.
     */
    get firstElementChild() {
        return this[PropertySymbol.elementArray][0] ?? null;
    }
    /**
     * Last element child.
     *
     * @returns Element.
     */
    get lastElementChild() {
        const children = this[PropertySymbol.elementArray];
        return children[children.length - 1] ?? null;
    }
    /**
     * Returns cookie string.
     *
     * @returns Cookie.
     */
    get cookie() {
        const browserFrame = new WindowBrowserContext_js_1.default(this[PropertySymbol.window]).getBrowserFrame();
        if (!browserFrame) {
            return '';
        }
        return CookieStringUtility_js_1.default.cookiesToString(browserFrame.page.context.cookieContainer.getCookies(new url_1.URL(this[PropertySymbol.window].location.href), true));
    }
    /**
     * Sets a cookie string.
     *
     * @param cookie Cookie string.
     */
    set cookie(cookie) {
        const browserFrame = new WindowBrowserContext_js_1.default(this[PropertySymbol.window]).getBrowserFrame();
        if (!browserFrame) {
            return;
        }
        browserFrame.page.context.cookieContainer.addCookies([
            CookieStringUtility_js_1.default.stringToCookie(new url_1.URL(this[PropertySymbol.window].location.href), cookie)
        ]);
    }
    /**
     * Node name.
     *
     * @returns Node name.
     */
    get nodeName() {
        return '#document';
    }
    /**
     * Returns <html> element.
     *
     * @returns Element.
     */
    get documentElement() {
        return this[PropertySymbol.elementArray][0] ?? null;
    }
    /**
     * Returns document type element.
     *
     * @returns Document type.
     */
    get doctype() {
        for (const node of this[PropertySymbol.nodeArray]) {
            if (node instanceof DocumentType_js_1.default) {
                return node;
            }
        }
        return null;
    }
    /**
     * Returns <body> element.
     *
     * @returns Element.
     */
    get body() {
        const documentElement = this.documentElement;
        return documentElement
            ? ParentNodeUtility_js_1.default.getElementByTagName(documentElement, 'body')
            : null;
    }
    /**
     * Returns <head> element.
     *
     * @returns Element.
     */
    get head() {
        const documentElement = this.documentElement;
        return documentElement
            ? ParentNodeUtility_js_1.default.getElementByTagName(documentElement, 'head')
            : null;
    }
    /**
     * Returns CSS style sheets.
     *
     * @returns CSS style sheets.
     */
    get styleSheets() {
        const styles = (QuerySelector_js_1.default.querySelectorAll(this, 'link[rel="stylesheet"][href],style'));
        const styleSheets = [];
        for (const style of styles) {
            const sheet = style.sheet;
            if (sheet) {
                styleSheets.push(sheet);
            }
        }
        return styleSheets;
    }
    /**
     * Returns active element.
     *
     * @returns Active element.
     */
    get activeElement() {
        if (this[PropertySymbol.activeElement] &&
            !this[PropertySymbol.activeElement][PropertySymbol.isConnected]) {
            this[PropertySymbol.activeElement] = null;
        }
        if (this[PropertySymbol.activeElement] &&
            this[PropertySymbol.activeElement] instanceof Element_js_1.default) {
            let rootNode = (this[PropertySymbol.activeElement].getRootNode());
            let activeElement = this[PropertySymbol.activeElement];
            while (rootNode !== this) {
                activeElement = rootNode.host;
                rootNode = activeElement ? activeElement.getRootNode() : this;
            }
            return activeElement;
        }
        return this[PropertySymbol.activeElement] || this.body || this.documentElement || null;
    }
    /**
     * Returns scrolling element.
     *
     * @returns Scrolling element.
     */
    get scrollingElement() {
        return this.documentElement;
    }
    /**
     * Returns location.
     *
     * @returns Location.
     */
    get location() {
        return this[PropertySymbol.window].location;
    }
    /**
     * Returns scripts.
     *
     * @returns Scripts.
     */
    get scripts() {
        return this.getElementsByTagName('script');
    }
    /**
     * Returns base URI.
     *
     * @override
     * @returns Base URI.
     */
    get baseURI() {
        const element = ParentNodeUtility_js_1.default.getElementByTagName(this, 'base');
        if (element) {
            return element.href;
        }
        return this[PropertySymbol.window].location.href;
    }
    /**
     * Returns URL.
     *
     * @returns URL of the current document.
     * */
    get URL() {
        return this[PropertySymbol.window].location.href;
    }
    /**
     * Returns document URI.
     *
     * @returns URL of the current document.
     * */
    get documentURI() {
        return this.URL;
    }
    /**
     * Returns domain.
     *
     * @returns Domain.
     * */
    get domain() {
        return this[PropertySymbol.window].location.hostname;
    }
    /**
     * Returns document visibility state.
     *
     * @returns the visibility state of the current document.
     * */
    get visibilityState() {
        if (this.defaultView) {
            return VisibilityStateEnum_js_1.default.visible;
        }
        return VisibilityStateEnum_js_1.default.hidden;
    }
    /**
     * Returns document hidden state.
     *
     * @returns the hidden state of the current document.
     * */
    get hidden() {
        if (this.defaultView) {
            return false;
        }
        return true;
    }
    /**
     * Gets the currently executing script element.
     *
     * @returns the currently executing script element.
     */
    get currentScript() {
        return this[PropertySymbol.currentScript];
    }
    /**
     * Returns content type.
     *
     * @returns Content type.
     */
    get contentType() {
        return this[PropertySymbol.contentType];
    }
    /**
     * Inserts a set of Node objects or DOMString objects after the last child of the ParentNode. DOMString objects are inserted as equivalent Text nodes.
     *
     * @param nodes List of Node or DOMString.
     */
    append(...nodes) {
        ParentNodeUtility_js_1.default.append(this, ...nodes);
    }
    /**
     * Inserts a set of Node objects or DOMString objects before the first child of the ParentNode. DOMString objects are inserted as equivalent Text nodes.
     *
     * @param nodes List of Node or DOMString.
     */
    prepend(...nodes) {
        ParentNodeUtility_js_1.default.prepend(this, ...nodes);
    }
    /**
     * Replaces the existing children of a node with a specified new set of children.
     *
     * @param nodes List of Node or DOMString.
     */
    replaceChildren(...nodes) {
        ParentNodeUtility_js_1.default.replaceChildren(this, ...nodes);
    }
    /**
     * Query CSS selector to find matching elments.
     *
     * @param selector CSS selector.
     * @returns Matching elements.
     */
    querySelectorAll(selector) {
        return QuerySelector_js_1.default.querySelectorAll(this, selector);
    }
    /**
     * Query CSS Selector to find matching node.
     *
     * @param selector CSS selector.
     * @returns Matching element.
     */
    querySelector(selector) {
        return QuerySelector_js_1.default.querySelector(this, selector);
    }
    /**
     * Returns true if the command is supported.
     * @deprecated
     * @param _ Command.
     * @returns True if the command is supported, false otherwise.
     */
    queryCommandSupported(_) {
        if (!arguments.length) {
            throw new this[PropertySymbol.window].TypeError("Failed to execute 'queryCommandSupported' on 'Document': 1 argument required, but only 0 present.");
        }
        return true;
    }
    /**
     * Returns an elements by class name.
     *
     * @param className Tag name.
     * @returns Matching element.
     */
    getElementsByClassName(className) {
        return ParentNodeUtility_js_1.default.getElementsByClassName(this, className);
    }
    /**
     * Returns an elements by tag name.
     *
     * @param tagName Tag name.
     * @returns Matching element.
     */
    getElementsByTagName(tagName) {
        return ParentNodeUtility_js_1.default.getElementsByTagName(this, tagName);
    }
    /**
     * Returns an elements by tag name and namespace.
     *
     * @param namespaceURI Namespace URI.
     * @param tagName Tag name.
     * @returns Matching element.
     */
    getElementsByTagNameNS(namespaceURI, tagName) {
        return ParentNodeUtility_js_1.default.getElementsByTagNameNS(this, namespaceURI, tagName);
    }
    /**
     * Returns an element by ID.
     *
     * @param id ID.
     * @returns Matching element.
     */
    getElementById(id) {
        return ParentNodeUtility_js_1.default.getElementById(this, id);
    }
    /**
     * Returns an element by Name.
     *
     * @returns Matching element.
     * @param name
     */
    getElementsByName(name) {
        return QuerySelector_js_1.default.querySelectorAll(this, `[name="${name}"]`);
    }
    /**
     * Replaces the document HTML with new HTML.
     *
     * @param html HTML.
     */
    write(html) {
        if (this[PropertySymbol.isFirstWrite] || this[PropertySymbol.isFirstWriteAfterOpen]) {
            if (this[PropertySymbol.isFirstWrite]) {
                if (!this[PropertySymbol.isFirstWriteAfterOpen]) {
                    this.open();
                }
                this[PropertySymbol.isFirstWrite] = false;
            }
            const { documentElement, head, body } = this;
            if (!documentElement || !head || !body) {
                this.open();
            }
            this[PropertySymbol.isFirstWrite] = false;
            this[PropertySymbol.isFirstWriteAfterOpen] = false;
            new HTMLParser_js_1.default(this[PropertySymbol.window], {
                evaluateScripts: true
            }).parse(html, this);
        }
        else {
            new HTMLParser_js_1.default(this[PropertySymbol.window], {
                evaluateScripts: true
            }).parse(html, this.body);
        }
    }
    /**
     * Opens the document.
     *
     * @returns Document.
     */
    open() {
        this[PropertySymbol.isFirstWriteAfterOpen] = true;
        for (const eventType of this[PropertySymbol.listeners].bubbling.keys()) {
            const listeners = this[PropertySymbol.listeners].bubbling.get(eventType);
            if (listeners) {
                for (const listener of listeners) {
                    this.removeEventListener(eventType, listener);
                }
            }
        }
        for (const eventType of this[PropertySymbol.listeners].capturing.keys()) {
            const listeners = this[PropertySymbol.listeners].capturing.get(eventType);
            if (listeners) {
                for (const listener of listeners) {
                    this.removeEventListener(eventType, listener);
                }
            }
        }
        const childNodes = this[PropertySymbol.nodeArray];
        while (childNodes.length) {
            this.removeChild(childNodes[0]);
        }
        // Default document elements
        const doctype = this[PropertySymbol.implementation].createDocumentType('html', '', '');
        const documentElement = this.createElement('html');
        const bodyElement = this.createElement('body');
        const headElement = this.createElement('head');
        this.appendChild(doctype);
        this.appendChild(documentElement);
        documentElement.appendChild(headElement);
        documentElement.appendChild(bodyElement);
        return this;
    }
    /**
     * Closes the document.
     */
    close() { }
    /**
     * Creates an element.
     *
     * @param qualifiedName Tag name.
     * @param [options] Options.
     * @param [options.is] Tag name of a custom element previously defined via customElements.define().
     * @returns Element.
     */
    createElement(qualifiedName, options) {
        return (this.createElementNS(NamespaceURI_js_1.default.html, StringUtility_js_1.default.asciiLowerCase(String(qualifiedName)), options));
    }
    /**
     * Creates an element with the specified namespace URI and qualified name.
     *
     * @param namespaceURI Namespace URI.
     * @param qualifiedName Tag name.
     * @param [options] Options.
     * @param [options.is] Tag name of a custom element previously defined via customElements.define().
     * @returns Element.
     */
    createElementNS(namespaceURI, qualifiedName, options) {
        const window = this[PropertySymbol.window];
        qualifiedName = String(qualifiedName);
        if (!qualifiedName) {
            throw new window.DOMException("Failed to execute 'createElementNS' on 'Document': The qualified name provided is empty.");
        }
        const parts = qualifiedName.split(':');
        const localName = parts[1] ?? parts[0];
        const prefix = parts[1] ? parts[0] : null;
        switch (namespaceURI) {
            case NamespaceURI_js_1.default.svg:
                const config = SVGElementConfig_js_1.default[qualifiedName.toLowerCase()];
                const svgElementClass = config && config.localName === qualifiedName
                    ? window[config.className]
                    : window.SVGElement;
                const svgElement = NodeFactory_js_1.default.createNode(this, svgElementClass);
                svgElement[PropertySymbol.tagName] = qualifiedName;
                svgElement[PropertySymbol.localName] = localName;
                svgElement[PropertySymbol.prefix] = prefix;
                svgElement[PropertySymbol.namespaceURI] = namespaceURI;
                svgElement[PropertySymbol.isValue] = options && options.is ? String(options.is) : null;
                return svgElement;
            case NamespaceURI_js_1.default.html:
                // Custom HTML element
                // If a polyfill is used, [PropertySymbol.registry] may be undefined
                const customElementDefinition = window.customElements[PropertySymbol.registry]?.get(options && options.is ? String(options.is) : qualifiedName);
                if (customElementDefinition) {
                    const element = new customElementDefinition.elementClass();
                    element[PropertySymbol.tagName] = StringUtility_js_1.default.asciiUpperCase(qualifiedName);
                    element[PropertySymbol.localName] = localName;
                    element[PropertySymbol.prefix] = prefix;
                    element[PropertySymbol.namespaceURI] = namespaceURI;
                    element[PropertySymbol.isValue] = options && options.is ? String(options.is) : null;
                    return element;
                }
                const elementClass = HTMLElementConfig_js_1.default[qualifiedName]
                    ? window[HTMLElementConfig_js_1.default[qualifiedName].className]
                    : null;
                // Known HTML element
                if (elementClass) {
                    const element = NodeFactory_js_1.default.createNode(this, elementClass);
                    element[PropertySymbol.tagName] = StringUtility_js_1.default.asciiUpperCase(qualifiedName);
                    element[PropertySymbol.localName] = localName;
                    element[PropertySymbol.prefix] = prefix;
                    element[PropertySymbol.namespaceURI] = namespaceURI;
                    element[PropertySymbol.isValue] = options && options.is ? String(options.is) : null;
                    return element;
                }
                // Unknown HTML element (if it has an hyphen in the name, it is a custom element that hasn't been defined yet)
                const unknownElementClass = qualifiedName.includes('-')
                    ? window.HTMLElement
                    : window.HTMLUnknownElement;
                const unknownElement = NodeFactory_js_1.default.createNode(this, unknownElementClass);
                unknownElement[PropertySymbol.tagName] = StringUtility_js_1.default.asciiUpperCase(qualifiedName);
                unknownElement[PropertySymbol.localName] = localName;
                unknownElement[PropertySymbol.prefix] = prefix;
                unknownElement[PropertySymbol.namespaceURI] = namespaceURI;
                unknownElement[PropertySymbol.isValue] = options && options.is ? String(options.is) : null;
                return unknownElement;
            default:
                const element = NodeFactory_js_1.default.createNode(this, Element_js_1.default);
                element[PropertySymbol.tagName] = qualifiedName;
                element[PropertySymbol.localName] = localName;
                element[PropertySymbol.prefix] = prefix;
                element[PropertySymbol.namespaceURI] = namespaceURI;
                element[PropertySymbol.isValue] = options && options.is ? String(options.is) : null;
                return element;
        }
    }
    /* eslint-enable jsdoc/valid-types */
    /**
     * Creates a text node.
     *
     * @param [data] Text data.
     * @returns Text node.
     */
    createTextNode(data) {
        if (arguments.length < 1) {
            throw new this[PropertySymbol.window].TypeError(`Failed to execute 'createTextNode' on 'Document': 1 argument required, but only ${arguments.length} present.`);
        }
        // We should use the NodeFactory and not the class constructor, so that owner document will be this document
        return NodeFactory_js_1.default.createNode(this, this[PropertySymbol.window].Text, String(data));
    }
    /**
     * Creates a comment node.
     *
     * @param [data] Text data.
     * @returns Text node.
     */
    createComment(data) {
        if (arguments.length < 1) {
            throw new this[PropertySymbol.window].TypeError(`Failed to execute 'createComment' on 'Document': 1 argument required, but only ${arguments.length} present.`);
        }
        // We should use the NodeFactory and not the class constructor, so that owner document will be this document
        return NodeFactory_js_1.default.createNode(this, this[PropertySymbol.window].Comment, String(data));
    }
    /**
     * Creates a document fragment.
     *
     * @returns Document fragment.
     */
    createDocumentFragment() {
        // We should use the NodeFactory and not the class constructor, so that owner document will be this document
        return NodeFactory_js_1.default.createNode(this, this[PropertySymbol.window].DocumentFragment);
    }
    /**
     * Creates a node iterator.
     *
     * @param root Root.
     * @param [whatToShow] What to show.
     * @param [filter] Filter.
     */
    createNodeIterator(root, whatToShow = -1, filter = null) {
        return new NodeIterator_js_1.default(root, whatToShow, filter);
    }
    /**
     * Creates a Tree Walker.
     *
     * @param root Root.
     * @param [whatToShow] What to show.
     * @param [filter] Filter.
     */
    createTreeWalker(root, whatToShow = -1, filter = null) {
        return new TreeWalker_js_1.default(root, whatToShow, filter);
    }
    /**
     * Creates an event.
     *
     * @deprecated
     * @param type Type.
     * @returns Event.
     */
    createEvent(type) {
        if (typeof this[PropertySymbol.window][type] === 'function') {
            return new this[PropertySymbol.window][type]('init');
        }
        return new Event_js_1.default('init');
    }
    /**
     * Creates an Attr node.
     *
     * @param qualifiedName Name.
     * @returns Attribute.
     */
    createAttribute(qualifiedName) {
        // We should use the NodeFactory and not the class constructor, so that owner document will be this document
        const attribute = NodeFactory_js_1.default.createNode(this, this[PropertySymbol.window].Attr);
        const name = StringUtility_js_1.default.asciiLowerCase(qualifiedName);
        const parts = name.split(':');
        attribute[PropertySymbol.name] = name;
        attribute[PropertySymbol.localName] = parts[1] ?? name;
        attribute[PropertySymbol.prefix] = parts[1] ? parts[0] : null;
        return attribute;
    }
    /**
     * Creates a namespaced Attr node.
     *
     * @param namespaceURI Namespace URI.
     * @param qualifiedName Qualified name.
     * @returns Element.
     */
    createAttributeNS(namespaceURI, qualifiedName) {
        // We should use the NodeFactory and not the class constructor, so that owner document will be this document
        const attribute = NodeFactory_js_1.default.createNode(this, this[PropertySymbol.window].Attr);
        const parts = qualifiedName.split(':');
        attribute[PropertySymbol.namespaceURI] = namespaceURI;
        attribute[PropertySymbol.name] = qualifiedName;
        attribute[PropertySymbol.localName] = parts[1] ?? qualifiedName;
        attribute[PropertySymbol.prefix] = parts[1] ? parts[0] : null;
        if (!namespaceURI && attribute[PropertySymbol.prefix]) {
            throw new this[PropertySymbol.window].DOMException(`Failed to execute 'createAttributeNS' on 'Document': The namespace URI provided ('${namespaceURI || ''}') is not valid for the qualified name provided ('${qualifiedName}').`, DOMExceptionNameEnum_js_1.default.namespaceError);
        }
        return attribute;
    }
    /**
     * Imports a node.
     *
     * @param node Node to import.
     * @param [deep=false] Set to "true" if the clone should be deep.
     */
    importNode(node, deep = false) {
        if (!(node instanceof Node_js_1.default)) {
            throw new this[PropertySymbol.window].DOMException('Parameter 1 was not of type Node.');
        }
        const clone = node.cloneNode(deep);
        this.#importNode(clone);
        return clone;
    }
    /**
     * Creates a range.
     *
     * @returns Range.
     */
    createRange() {
        return new this[PropertySymbol.window].Range();
    }
    /**
     * Adopts a node.
     *
     * @param node Node to adopt.
     * @returns Adopted node.
     */
    adoptNode(node) {
        if (!(node instanceof Node_js_1.default)) {
            throw new this[PropertySymbol.window].DOMException('Parameter 1 was not of type Node.');
        }
        const adopted = node[PropertySymbol.parentNode]
            ? node[PropertySymbol.parentNode].removeChild(node)
            : node;
        const document = this;
        Object.defineProperty(adopted, 'ownerDocument', { value: document });
        return adopted;
    }
    /**
     * Returns selection.
     *
     * @returns Selection.
     */
    getSelection() {
        if (!this.#selection) {
            this.#selection = new Selection_js_1.default(this);
        }
        return this.#selection;
    }
    /**
     * Returns a boolean value indicating whether the document or any element inside the document has focus.
     *
     * @returns "true" if the document has focus.
     */
    hasFocus() {
        return !!this.activeElement;
    }
    /**
     * Creates a Processing Instruction node.
     *
     * @param target Target.
     * @param data Data.
     * @returns ProcessingInstruction.
     */
    createProcessingInstruction(target, data) {
        if (arguments.length < 2) {
            throw new this[PropertySymbol.window].TypeError(`Failed to execute 'createProcessingInstruction' on 'Document': 2 arguments required, but only ${arguments.length} present.`);
        }
        target = String(target);
        data = String(data);
        if (!target || !PROCESSING_INSTRUCTION_TARGET_REGEXP.test(target)) {
            throw new this[PropertySymbol.window].DOMException(`Failed to execute 'createProcessingInstruction' on 'Document': The target provided ('${target}') is not a valid name.`);
        }
        if (data.includes('?>')) {
            throw new this[PropertySymbol.window].DOMException(`Failed to execute 'createProcessingInstruction' on 'Document': The data provided ('?>') contains '?>'`);
        }
        // We should use the NodeFactory and not the class constructor, so that owner document will be this document
        const element = NodeFactory_js_1.default.createNode(this, this[PropertySymbol.window].ProcessingInstruction);
        element[PropertySymbol.data] = data;
        element[PropertySymbol.target] = target;
        return element;
    }
    /**
     * Get element at a given point.
     *
     * @param _x horizontal coordinate
     * @param _y vertical coordinate
     * @returns Always returns null since Happy DOM does not render elements.
     */
    elementFromPoint(_x, _y) {
        return null;
    }
    /**
     * Imports a node.
     *
     * @param node Node.
     */
    #importNode(node) {
        node[PropertySymbol.ownerDocument] = this;
        for (const child of node[PropertySymbol.nodeArray]) {
            this.#importNode(child);
        }
    }
}
exports.default = Document;
//# sourceMappingURL=Document.cjs.map
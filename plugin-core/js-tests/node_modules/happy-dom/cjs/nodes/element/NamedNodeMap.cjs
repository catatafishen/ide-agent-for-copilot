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
const PropertySymbol = __importStar(require("../../PropertySymbol.cjs"));
const DOMException_js_1 = __importDefault(require("../../exception/DOMException.cjs"));
const DOMExceptionNameEnum_js_1 = __importDefault(require("../../exception/DOMExceptionNameEnum.cjs"));
const NamespaceURI_js_1 = __importDefault(require("../../config/NamespaceURI.cjs"));
const StringUtility_js_1 = __importDefault(require("../../utilities/StringUtility.cjs"));
/**
 * Named Node Map.
 *
 * @see https://developer.mozilla.org/en-US/docs/Web/API/NamedNodeMap
 */
class NamedNodeMap {
    // Items by attribute namespaceURI
    [PropertySymbol.itemsByNamespaceURI] = new Map();
    // Items by attribute name
    [PropertySymbol.itemsByName] = new Map();
    // All items
    [PropertySymbol.items] = new Map();
    /**
     * Constructor.
     *
     * @param ownerElement Owner element.
     */
    constructor(ownerElement) {
        this[PropertySymbol.ownerElement] = ownerElement;
    }
    /**
     * Returns length.
     *
     * @returns Length.
     */
    get length() {
        return this[PropertySymbol.items].size;
    }
    /**
     * Returns string.
     *
     * @returns string.
     */
    get [Symbol.toStringTag]() {
        return 'NamedNodeMap';
    }
    /**
     * Returns string.
     *
     * @returns string.
     */
    toString() {
        return '[object NamedNodeMap]';
    }
    /**
     * Iterator.
     *
     * @returns Iterator.
     */
    [Symbol.iterator]() {
        return this[PropertySymbol.items].values();
    }
    /**
     * Returns item by index.
     *
     * @param index Index.
     */
    item(index) {
        const items = Array.from(this[PropertySymbol.items].values());
        return index >= 0 && items[index] ? items[index] : null;
    }
    /**
     * Returns named item.
     *
     * @param name Name.
     * @returns Item.
     */
    getNamedItem(name) {
        name = String(name);
        if (this[PropertySymbol.ownerElement][PropertySymbol.namespaceURI] === NamespaceURI_js_1.default.html &&
            this[PropertySymbol.ownerElement][PropertySymbol.ownerDocument][PropertySymbol.contentType] === 'text/html') {
            return this[PropertySymbol.itemsByName].get(StringUtility_js_1.default.asciiLowerCase(name))?.[0] || null;
        }
        return this[PropertySymbol.itemsByName].get(name)?.[0] || null;
    }
    /**
     * Returns item by name and namespace.
     *
     * @param namespace Namespace.
     * @param localName Local name of the attribute.
     * @returns Item.
     */
    getNamedItemNS(namespace, localName) {
        const item = this[PropertySymbol.itemsByNamespaceURI].get(`${namespace || ''}:${localName}`);
        // It seems like an item cant have a prefix without a namespaceURI
        // E.g. element.setAttribute('ns1:key', 'value1');
        // expect(element.attributes.getNamedItemNS(null, 'key')).toBeNull();
        if (item && (!item[PropertySymbol.prefix] || item[PropertySymbol.namespaceURI])) {
            return item;
        }
        return null;
    }
    /**
     * Sets named item.
     *
     * @param item Item.
     * @returns Replaced item.
     */
    setNamedItem(item) {
        return this[PropertySymbol.setNamedItem](item);
    }
    /**
     * Adds a new namespaced item.
     *
     * @alias setNamedItem()
     * @param item Item.
     * @returns Replaced item.
     */
    setNamedItemNS(item) {
        return this[PropertySymbol.setNamedItem](item);
    }
    /**
     * Removes an item.
     *
     * @throws DOMException
     * @param name Name of item.
     * @returns Removed item.
     */
    removeNamedItem(name) {
        const item = this.getNamedItem(name);
        if (!item) {
            throw new DOMException_js_1.default(`Failed to execute 'removeNamedItem' on 'NamedNodeMap': No item with name '${name}' was found.`, DOMExceptionNameEnum_js_1.default.notFoundError);
        }
        this[PropertySymbol.removeNamedItem](item);
        return item;
    }
    /**
     * Removes a namespaced item.
     *
     * @param namespace Namespace.
     * @param localName Local name of the item.
     * @returns Removed item.
     */
    removeNamedItemNS(namespace, localName) {
        const item = this.getNamedItemNS(namespace, localName);
        if (!item) {
            throw new DOMException_js_1.default(`Failed to execute 'removeNamedItemNS' on 'NamedNodeMap': No item with name '${localName}' in namespace '${namespace}' was found.`, DOMExceptionNameEnum_js_1.default.notFoundError);
        }
        this[PropertySymbol.removeNamedItem](item);
        return item;
    }
    /**
     * Sets named item.
     *
     * @param item Item.
     * @param [ignoreListeners] Ignore listeners.
     * @returns Replaced item.
     */
    [PropertySymbol.setNamedItem](item, ignoreListeners = false) {
        if (item[PropertySymbol.ownerElement] !== null &&
            item[PropertySymbol.ownerElement] !== this[PropertySymbol.ownerElement]) {
            throw new this[PropertySymbol.ownerElement][PropertySymbol.window].DOMException('The attribute is in use.', DOMExceptionNameEnum_js_1.default.inUseAttributeError);
        }
        item[PropertySymbol.ownerElement] = this[PropertySymbol.ownerElement];
        const replacedItem = this.getNamedItemNS(item[PropertySymbol.namespaceURI], item[PropertySymbol.localName]) ||
            null;
        const itemsByName = this[PropertySymbol.itemsByName].get(item[PropertySymbol.name]);
        if (replacedItem === item) {
            return item;
        }
        this[PropertySymbol.itemsByNamespaceURI].set(`${item[PropertySymbol.namespaceURI] || ''}:${item[PropertySymbol.localName]}`, item);
        this[PropertySymbol.items].set(`${item[PropertySymbol.namespaceURI] || ''}:${item[PropertySymbol.name]}`, item);
        if (!itemsByName?.length) {
            this[PropertySymbol.itemsByName].set(item[PropertySymbol.name], [item]);
        }
        else {
            const index = itemsByName.indexOf(replacedItem);
            if (index !== -1) {
                itemsByName.splice(index, 1);
            }
            itemsByName.push(item);
        }
        if (!ignoreListeners) {
            this[PropertySymbol.ownerElement][PropertySymbol.onSetAttribute](item, replacedItem);
        }
        return replacedItem;
    }
    /**
     * Removes named item.
     *
     * @param item Item.
     * @param ignoreListeners
     */
    [PropertySymbol.removeNamedItem](item, ignoreListeners = false) {
        item[PropertySymbol.ownerElement] = null;
        this[PropertySymbol.itemsByNamespaceURI].delete(`${item[PropertySymbol.namespaceURI] || ''}:${item[PropertySymbol.localName]}`);
        this[PropertySymbol.items].delete(`${item[PropertySymbol.namespaceURI] || ''}:${item[PropertySymbol.name]}`);
        const itemsByName = this[PropertySymbol.itemsByName].get(item[PropertySymbol.name]);
        if (itemsByName?.length) {
            const index = itemsByName.indexOf(item);
            if (index !== -1) {
                itemsByName.splice(index, 1);
            }
            if (!itemsByName.length) {
                this[PropertySymbol.itemsByName].delete(item[PropertySymbol.name]);
            }
        }
        if (!ignoreListeners) {
            this[PropertySymbol.ownerElement][PropertySymbol.onRemoveAttribute](item);
        }
    }
}
exports.default = NamedNodeMap;
//# sourceMappingURL=NamedNodeMap.cjs.map
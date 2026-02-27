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
const Node_js_1 = __importDefault(require("../nodes/node/Node.cjs"));
const PropertySymbol = __importStar(require("../PropertySymbol.cjs"));
const NodeFilterMask_js_1 = __importDefault(require("./NodeFilterMask.cjs"));
const DOMException_js_1 = __importDefault(require("../exception/DOMException.cjs"));
const NodeFilter_js_1 = __importDefault(require("./NodeFilter.cjs"));
var TraverseChildrenTypeEnum;
(function (TraverseChildrenTypeEnum) {
    TraverseChildrenTypeEnum["first"] = "first";
    TraverseChildrenTypeEnum["last"] = "last";
})(TraverseChildrenTypeEnum || (TraverseChildrenTypeEnum = {}));
var TraverseSiblingsTypeEnum;
(function (TraverseSiblingsTypeEnum) {
    TraverseSiblingsTypeEnum["next"] = "next";
    TraverseSiblingsTypeEnum["previous"] = "previous";
})(TraverseSiblingsTypeEnum || (TraverseSiblingsTypeEnum = {}));
/**
 * The TreeWalker object represents the nodes of a document subtree and a position within them.
 *
 * @see https://developer.mozilla.org/en-US/docs/Web/API/TreeWalker
 * @see https://dom.spec.whatwg.org/#interface-treewalker
 */
class TreeWalker {
    root = null;
    whatToShow = -1;
    filter = null;
    currentNode = null;
    /**
     * Constructor.
     *
     * @param root Root.
     * @param [whatToShow] What to show.
     * @param [filter] Filter.
     */
    constructor(root, whatToShow = -1, filter = null) {
        if (!(root instanceof Node_js_1.default)) {
            throw new DOMException_js_1.default('Parameter 1 was not of type Node.');
        }
        this.root = root;
        this.whatToShow = whatToShow;
        this.filter = filter;
        this.currentNode = root;
    }
    /**
     * Moves the current Node to the first visible ancestor node in the document order, and returns the found node. It also moves the current node to this one. If no such node exists, or if it is before that the root node defined at the object construction, returns null and the current node is not changed.
     *
     * @returns Current node.
     */
    parentNode() {
        let node = this.currentNode;
        while (node !== null && node !== this.root) {
            node = node.parentNode;
            if (node !== null && this[PropertySymbol.filterNode](node) === NodeFilter_js_1.default.FILTER_ACCEPT) {
                this.currentNode = node;
                return this.currentNode;
            }
        }
        return null;
    }
    /**
     * Moves the current Node to the first visible child of the current node, and returns the found child. It also moves the current node to this child. If no such child exists, returns null and the current node is not changed.
     *
     * @returns Current node.
     */
    firstChild() {
        return this.#traverseChildren(TraverseChildrenTypeEnum.first);
    }
    /**
     * Moves the current Node to the last visible child of the current node, and returns the found child. It also moves the current node to this child. If no such child exists, null is returned and the current node is not changed.
     *
     * @returns Current node.
     */
    lastChild() {
        return this.#traverseChildren(TraverseChildrenTypeEnum.last);
    }
    /**
     * Moves the current Node to its next sibling, if any, and returns the found sibling. If there is no such node, null is returned and the current node is not changed.
     *
     * @returns Current node.
     */
    nextSibling() {
        return this.#traverseSiblings(TraverseSiblingsTypeEnum.next);
    }
    /**
     * Moves the current Node to its previous sibling, if any, and returns the found sibling. If there is no such node, return null and the current node is not changed.
     *
     * @returns Current node.
     */
    previousSibling() {
        return this.#traverseSiblings(TraverseSiblingsTypeEnum.previous);
    }
    /**
     * Moves the current Node to the previous visible node in the document order, and returns the found node. It also moves the current node to this one. If no such node exists, or if it is before that the root node defined at the object construction, returns null and the current node is not changed.
     *
     * @returns Current node.
     */
    previousNode() {
        let node = this.currentNode;
        while (node !== this.root) {
            let sibling = node.previousSibling;
            while (sibling !== null) {
                let node = sibling;
                let result = this[PropertySymbol.filterNode](node);
                while (result !== NodeFilter_js_1.default.FILTER_REJECT && node[PropertySymbol.nodeArray].length) {
                    node = node.lastChild;
                    result = this[PropertySymbol.filterNode](node);
                }
                if (result === NodeFilter_js_1.default.FILTER_ACCEPT) {
                    this.currentNode = node;
                    return node;
                }
                sibling = node.previousSibling;
            }
            if (node === this.root || node.parentNode === null) {
                return null;
            }
            node = node.parentNode;
            if (this[PropertySymbol.filterNode](node) === NodeFilter_js_1.default.FILTER_ACCEPT) {
                this.currentNode = node;
                return node;
            }
        }
        return null;
    }
    /**
     * Moves the current Node to the next visible node in the document order.
     *
     * @returns Current node.
     */
    nextNode() {
        let node = this.currentNode;
        let result = NodeFilter_js_1.default.FILTER_ACCEPT;
        while (true) {
            while (result !== NodeFilter_js_1.default.FILTER_REJECT && node[PropertySymbol.nodeArray].length) {
                node = node.firstChild;
                result = this[PropertySymbol.filterNode](node);
                if (result === NodeFilter_js_1.default.FILTER_ACCEPT) {
                    this.currentNode = node;
                    return node;
                }
            }
            while (node !== null) {
                if (node === this.root) {
                    return null;
                }
                const sibling = node.nextSibling;
                if (sibling !== null) {
                    node = sibling;
                    break;
                }
                node = node.parentNode;
            }
            if (node === null) {
                return null;
            }
            result = this[PropertySymbol.filterNode](node);
            if (result === NodeFilter_js_1.default.FILTER_ACCEPT) {
                this.currentNode = node;
                return node;
            }
        }
    }
    /**
     * Filters a node.
     *
     * Based on solution:
     * https://gist.github.com/shawndumas/1132009.
     *
     * @param node Node.
     * @returns Child nodes.
     */
    [PropertySymbol.filterNode](node) {
        const mask = NodeFilterMask_js_1.default[node.nodeType];
        if (mask && (this.whatToShow & mask) == 0) {
            return NodeFilter_js_1.default.FILTER_SKIP;
        }
        if (typeof this.filter === 'function') {
            return this.filter(node);
        }
        if (this.filter) {
            return this.filter.acceptNode(node);
        }
        return NodeFilter_js_1.default.FILTER_ACCEPT;
    }
    /**
     * Traverses children.
     *
     * @param type Type.
     * @returns Node.
     */
    #traverseChildren(type) {
        let node = this.currentNode;
        node = type === TraverseChildrenTypeEnum.first ? node.firstChild : node.lastChild;
        while (node !== null) {
            const result = this[PropertySymbol.filterNode](node);
            if (result === NodeFilter_js_1.default.FILTER_ACCEPT) {
                this.currentNode = node;
                return node;
            }
            if (result === NodeFilter_js_1.default.FILTER_SKIP) {
                const child = type === TraverseChildrenTypeEnum.first ? node.firstChild : node.lastChild;
                if (child !== null) {
                    node = child;
                    continue;
                }
            }
            while (node !== null) {
                const sibling = type === TraverseChildrenTypeEnum.first ? node.nextSibling : node.previousSibling;
                if (sibling !== null) {
                    node = sibling;
                    break;
                }
                const parent = node.parentNode;
                if (parent === null || parent === this.root || parent === this.currentNode) {
                    return null;
                }
                node = parent;
            }
        }
        return null;
    }
    /**
     * Traverses siblings.
     *
     * @param type Type.
     * @returns Node.
     */
    #traverseSiblings(type) {
        let node = this.currentNode;
        if (node === this.root) {
            return null;
        }
        while (true) {
            let sibling = type === TraverseSiblingsTypeEnum.next ? node.nextSibling : node.previousSibling;
            while (sibling !== null) {
                const node = sibling;
                const result = this[PropertySymbol.filterNode](node);
                if (result === NodeFilter_js_1.default.FILTER_ACCEPT) {
                    this.currentNode = node;
                    return node;
                }
                sibling = type === TraverseSiblingsTypeEnum.next ? node.firstChild : node.lastChild;
                if (result === NodeFilter_js_1.default.FILTER_REJECT || sibling === null) {
                    sibling =
                        type === TraverseSiblingsTypeEnum.next ? node.nextSibling : node.previousSibling;
                }
            }
            node = node.parentNode;
            if (node === null || node === this.root) {
                return null;
            }
            if (this[PropertySymbol.filterNode](node) === NodeFilter_js_1.default.FILTER_ACCEPT) {
                return null;
            }
        }
    }
}
exports.default = TreeWalker;
//# sourceMappingURL=TreeWalker.cjs.map
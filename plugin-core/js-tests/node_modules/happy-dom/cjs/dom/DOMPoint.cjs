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
const DOMPointReadOnly_js_1 = __importDefault(require("./DOMPointReadOnly.cjs"));
const PropertySymbol = __importStar(require("../PropertySymbol.cjs"));
/**
 * DOM Point.
 *
 * @see https://developer.mozilla.org/en-US/docs/Web/API/DOMPoint
 */
class DOMPoint extends DOMPointReadOnly_js_1.default {
    /**
     * Sets x.
     *
     * @param value X.
     */
    set x(value) {
        this[PropertySymbol.x] = value;
    }
    /**
     * Returns x.
     *
     * @returns X.
     */
    get x() {
        return this[PropertySymbol.x];
    }
    /**
     * Sets y.
     *
     * @param value Y.
     */
    set y(value) {
        this[PropertySymbol.y] = value;
    }
    /**
     * Returns y.
     *
     * @returns Y.
     */
    get y() {
        return this[PropertySymbol.y];
    }
    /**
     * Sets z.
     *
     * @param value Z.
     */
    set z(value) {
        this[PropertySymbol.z] = value;
    }
    /**
     * Returns z.
     *
     * @returns Z.
     */
    get z() {
        return this[PropertySymbol.z];
    }
    /**
     * Sets w.
     *
     * @param value W.
     */
    set w(value) {
        this[PropertySymbol.w] = value;
    }
    /**
     * Returns w.
     *
     * @returns W.
     */
    get w() {
        return this[PropertySymbol.w];
    }
}
exports.default = DOMPoint;
//# sourceMappingURL=DOMPoint.cjs.map
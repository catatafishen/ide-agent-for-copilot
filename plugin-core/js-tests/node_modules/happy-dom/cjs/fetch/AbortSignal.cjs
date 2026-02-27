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
const EventTarget_js_1 = __importDefault(require("../event/EventTarget.cjs"));
const PropertySymbol = __importStar(require("../PropertySymbol.cjs"));
const Event_js_1 = __importDefault(require("../event/Event.cjs"));
const DOMExceptionNameEnum_js_1 = __importDefault(require("../exception/DOMExceptionNameEnum.cjs"));
/**
 * AbortSignal.
 *
 * @see https://developer.mozilla.org/en-US/docs/Web/API/AbortSignal
 */
class AbortSignal extends EventTarget_js_1.default {
    // Internal properties
    [PropertySymbol.aborted] = false;
    [PropertySymbol.reason] = undefined;
    // Events
    onabort = null;
    /**
     * Constructor.
     */
    constructor() {
        super();
        if (!this[PropertySymbol.window]) {
            throw new TypeError(`Failed to construct 'AbortSignal': Illegal constructor`);
        }
    }
    /**
     * Return a default description for the AbortSignal class.
     */
    get [Symbol.toStringTag]() {
        return 'AbortSignal';
    }
    /**
     * Returns true if the signal has been aborted.
     *
     * @returns True if the signal has been aborted.
     */
    get aborted() {
        return this[PropertySymbol.aborted];
    }
    /**
     * Setter for aborted. Value will be ignored as the property is read-only.
     *
     * @param _value Aborted.
     */
    set aborted(_value) {
        // Do nothing
    }
    /**
     * Returns the reason the signal was aborted.
     *
     * @returns Reason.
     */
    get reason() {
        return this[PropertySymbol.reason];
    }
    /**
     * Setter for reason. Value will be ignored as the property is read-only.
     *
     * @param _value Reason.
     */
    set reason(_value) {
        // Do nothing
    }
    /**
     * Aborts the signal.
     *
     * @param [reason] Reason.
     */
    [PropertySymbol.abort](reason) {
        if (this.aborted) {
            return;
        }
        this[PropertySymbol.reason] =
            reason !== undefined
                ? reason
                : new this[PropertySymbol.window].DOMException('signal is aborted without reason', DOMExceptionNameEnum_js_1.default.abortError);
        this[PropertySymbol.aborted] = true;
        this.dispatchEvent(new Event_js_1.default('abort'));
    }
    /**
     * Throws an "AbortError" if the signal has been aborted.
     */
    throwIfAborted() {
        if (this.aborted) {
            throw this.reason;
        }
    }
    /**
     * Returns an AbortSignal instance that has been set as aborted.
     *
     * @param [reason] Reason.
     * @returns AbortSignal instance.
     */
    static abort(reason) {
        const signal = new this();
        signal[PropertySymbol.reason] =
            reason !== undefined
                ? reason
                : new this[PropertySymbol.window].DOMException('signal is aborted without reason', DOMExceptionNameEnum_js_1.default.abortError);
        signal[PropertySymbol.aborted] = true;
        return signal;
    }
    /**
     * Returns an AbortSignal that will automatically abort after a specified
     * time.
     *
     * @param [time] Time in milliseconds.
     * @returns AbortSignal instance.
     */
    static timeout(time) {
        const window = this[PropertySymbol.window];
        const signal = new this();
        window.setTimeout(() => {
            signal[PropertySymbol.abort](new window.DOMException('signal timed out', DOMExceptionNameEnum_js_1.default.timeoutError));
        }, time);
        return signal;
    }
    /**
     * Takes an iterable of abort signals and returns an AbortSignal that is
     * aborted when any of the input iterable abort signals are aborted.
     *
     * The abort reason will be set to the reason of the first signal that is
     * aborted. If any of the given abort signals are already aborted then so will
     * be the returned AbortSignal.
     *
     * @param [signals] Iterable of abort signals.
     * @returns AbortSignal instance.
     */
    static any(signals) {
        for (const signal of signals) {
            if (signal[PropertySymbol.aborted]) {
                return this.abort(signal[PropertySymbol.reason]);
            }
        }
        const anySignal = new this();
        const handlers = new Map();
        const stopListening = () => {
            for (const signal of signals) {
                signal.removeEventListener('abort', handlers.get(signal));
            }
        };
        for (const signal of signals) {
            const handler = () => {
                stopListening();
                anySignal[PropertySymbol.abort](signal[PropertySymbol.reason]);
            };
            handlers.set(signal, handler);
            signal.addEventListener('abort', handler);
        }
        return anySignal;
    }
}
exports.default = AbortSignal;
//# sourceMappingURL=AbortSignal.cjs.map
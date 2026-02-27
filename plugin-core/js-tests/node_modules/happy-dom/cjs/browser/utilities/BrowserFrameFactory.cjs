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
Object.defineProperty(exports, "__esModule", { value: true });
const PropertySymbol = __importStar(require("../../PropertySymbol.cjs"));
/**
 * Browser frame factory.
 */
class BrowserFrameFactory {
    /**
     * Creates a new frame.
     *
     * @param parentFrame Parent frame.
     * @returns Frame.
     */
    static createChildFrame(parentFrame) {
        const frame = new parentFrame.constructor(parentFrame.page);
        frame.parentFrame = parentFrame;
        parentFrame.childFrames.push(frame);
        return frame;
    }
    /**
     * Aborts all ongoing operations and destroys the frame.
     *
     * @param frame Frame.
     */
    static destroyFrame(frame) {
        const exceptionObserver = frame.page?.context?.browser?.[PropertySymbol.exceptionObserver];
        // Using Promise instead of async/await to prevent usage of a microtask
        return new Promise((resolve, reject) => {
            if (!frame.window) {
                resolve();
                return;
            }
            if (frame.parentFrame) {
                const index = frame.parentFrame.childFrames.indexOf(frame);
                if (index !== -1) {
                    frame.parentFrame.childFrames.splice(index, 1);
                }
            }
            if (!frame.childFrames.length) {
                frame[PropertySymbol.asyncTaskManager]
                    .destroy()
                    .then(() => {
                    if (exceptionObserver && frame.window) {
                        exceptionObserver.disconnect(frame.window);
                    }
                    frame.page = null;
                    frame.window = null;
                    frame[PropertySymbol.openerFrame] = null;
                    frame[PropertySymbol.openerWindow] = null;
                    resolve();
                })
                    .catch((error) => reject(error));
                if (frame.window) {
                    frame.window[PropertySymbol.destroy]();
                }
                return;
            }
            Promise.all(frame.childFrames.slice().map((childFrame) => this.destroyFrame(childFrame)))
                .then(() => {
                frame[PropertySymbol.asyncTaskManager]
                    .destroy()
                    .then(() => {
                    if (exceptionObserver && frame.window) {
                        exceptionObserver.disconnect(frame.window);
                    }
                    frame.page = null;
                    frame.window = null;
                    frame[PropertySymbol.openerFrame] = null;
                    frame[PropertySymbol.openerWindow] = null;
                    resolve();
                })
                    .catch((error) => reject(error));
                if (frame.window) {
                    frame.window[PropertySymbol.destroy]();
                }
            })
                .catch((error) => reject(error));
        });
    }
}
exports.default = BrowserFrameFactory;
//# sourceMappingURL=BrowserFrameFactory.cjs.map
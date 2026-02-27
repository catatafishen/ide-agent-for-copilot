import BrowserWindow from '../window/BrowserWindow.cjs';
import IECMAScriptModuleCompiledResult from './IECMAScriptModuleCompiledResult.cjs';
/**
 * ECMAScript module compiler.
 */
export default class ECMAScriptModuleCompiler {
    readonly window: BrowserWindow;
    /**
     * Constructor.
     *
     * @param window Window.
     * @param url Module URL.
     */
    constructor(window: BrowserWindow);
    /**
     * Compiles code and returns imports and compiled code.
     *
     * @param moduleURL Module URL.
     * @param code Code.
     * @returns Result.
     */
    compile(moduleURL: string, code: string): IECMAScriptModuleCompiledResult;
    /**
     * Remove multiline comments.
     *
     * @param code Code.
     * @returns Code without multiline comments.
     */
    private removeMultilineComments;
}
//# sourceMappingURL=ECMAScriptModuleCompiler.d.ts.map
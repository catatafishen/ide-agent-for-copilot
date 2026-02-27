import IModuleImportMapRule from './IModuleImportMapRule.cjs';
import IModuleImportMapScope from './IModuleImportMapScope.cjs';
/**
 * @see https://html.spec.whatwg.org/multipage/webappapis.html#import-map
 */
export default interface IModuleImportMap {
    imports: IModuleImportMapRule[];
    scopes: IModuleImportMapScope[];
}
//# sourceMappingURL=IModuleImportMap.d.ts.map
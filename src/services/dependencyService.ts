import { posix } from 'node:path';
import type { ProjectFile, ProjectGraph } from '../types.js';

const JS_EXTENSIONS = [
  '.ts',
  '.tsx',
  '.js',
  '.jsx',
  '.mts',
  '.cts',
  '.mjs',
  '.cjs',
];
const JS_IMPORT =
  /\b(?:import\s+(?:(?:[^'";()]+?)\bfrom\s+)?|export\s+[^'";()]+?\bfrom\s+|(?:require|import)\s*\(\s*)['"]([^'"\r\n]+)['"]/g;
const SYMBOL_DECL = /\b(?:class|interface|object|struct|enum|record)\s+(\w+)/g;
const MODULE_PATTERNS = {
  '.kt': /^\s*import\s+([\w.]+)/gm,
  '.kts': /^\s*import\s+([\w.]+)/gm,
  '.cs': /^\s*using\s+([\w.]+)\s*;/gm,
  '.py': /^\s*(?:from\s+([\w.]+)\s+import|import\s+([\w.]+))/gm,
} as const;

function resolveJavaScript(
  path: string,
  files: Set<string>,
): string | undefined {
  const aliases: Record<string, string[]> = {
    '.js': ['.ts', '.tsx'],
    '.jsx': ['.tsx'],
    '.mjs': ['.mts'],
    '.cjs': ['.cts'],
  };
  const ext = posix.extname(path);
  const candidates = [
    path,
    ...(aliases[ext] ?? []).map((alias) => path.slice(0, -ext.length) + alias),
    ...JS_EXTENSIONS.map((suffix) => path + suffix),
    ...JS_EXTENSIONS.map((suffix) => posix.join(path, 'index' + suffix)),
  ];
  return candidates.find((candidate) => files.has(candidate));
}

function matchesModule(
  path: string,
  module: string,
  extensions: string[],
): boolean {
  return (
    extensions.some(
      (ext) => path === module + ext || path.endsWith('/' + module + ext),
    ) ||
    path.includes('/' + module + '/') ||
    path.startsWith(module + '/')
  );
}

export class DependencyService {
  buildGraph(files: ProjectFile[]): ProjectGraph {
    const pathSet = new Set(files.map((file) => file.path));
    const edges = new Map<string, string[]>();
    const symbols = new Map<string, Set<string>>();
    const missing = new Map<string, string[]>();
    const referenced = new Set<string>();

    for (const file of files) {
      for (const [, name] of file.content.matchAll(SYMBOL_DECL)) {
        const declarations = symbols.get(name) ?? new Set<string>();
        declarations.add(file.path);
        symbols.set(name, declarations);
      }

      const dependencies = new Set<string>();
      const unresolved = new Set<string>();
      this.extractImports(file, pathSet, dependencies, unresolved);
      edges.set(file.path, [...dependencies]);
      for (const dependency of dependencies) referenced.add(dependency);
      if (unresolved.size) missing.set(file.path, [...unresolved]);
    }

    return {
      edges: Object.fromEntries(edges),
      cycles: this.detectCycles(edges),
      orphans: files
        .map((file) => file.path)
        .filter((path) => !edges.get(path)?.length && !referenced.has(path)),
      duplicateSymbols: Object.fromEntries(
        Array.from(symbols, ([name, paths]): [string, string[]] => [
          name,
          [...paths],
        ]).filter(([, declarations]) => declarations.length > 1),
      ),
      missing: Object.fromEntries(missing),
    };
  }

  private extractImports(
    file: ProjectFile,
    files: Set<string>,
    dependencies: Set<string>,
    unresolved: Set<string>,
  ): void {
    const extension = posix.extname(file.path).toLowerCase();
    if (JS_EXTENSIONS.includes(extension)) {
      for (const [, module] of file.content.matchAll(JS_IMPORT)) {
        const relative =
          module === '.' ||
          module === '..' ||
          module.startsWith('./') ||
          module.startsWith('../');
        const candidate = relative
          ? posix.join(posix.dirname(file.path), module)
          : module;
        const resolved = resolveJavaScript(candidate, files);
        if (resolved) {
          dependencies.add(resolved);
        } else if (relative) {
          unresolved.add(module);
        } else if (
          !module.startsWith('.') &&
          !module.startsWith('/') &&
          !module.includes(':')
        ) {
          for (const path of files) {
            if (matchesModule(path, module, JS_EXTENSIONS))
              dependencies.add(path);
          }
        }
      }
      return;
    }

    const pattern = MODULE_PATTERNS[extension as keyof typeof MODULE_PATTERNS];
    if (!pattern) return;
    const extensions = extension === '.kts' ? ['.kt', '.kts'] : [extension];
    for (const [, from, single] of file.content.matchAll(pattern)) {
      const module = (from || single).replace(/\./g, '/');
      for (const path of files) {
        if (matchesModule(path, module, extensions)) dependencies.add(path);
      }
    }
  }

  private detectCycles(edges: Map<string, string[]>): string[][] {
    const cycles: string[][] = [];
    const visited = new Set<string>();
    const stack: string[] = [];
    const active = new Map<string, number>();

    const visit = (path: string): void => {
      const start = active.get(path);
      if (start !== undefined) {
        cycles.push([...stack.slice(start), path]);
        return;
      }
      if (visited.has(path)) return;

      visited.add(path);
      active.set(path, stack.length);
      stack.push(path);
      for (const dependency of edges.get(path) ?? []) visit(dependency);
      stack.pop();
      active.delete(path);
    };

    for (const path of edges.keys()) visit(path);
    return cycles;
  }
}

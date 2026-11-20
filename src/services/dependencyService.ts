import { ProjectFile, ProjectGraph } from '../types.js';

const KOTLIN_IMPORT = /^\s*import\s+([\w.]+)/gm;
const CSHARP_USING = /^\s*using\s+([\w.]+)\s*;/gm;
const JS_IMPORT =
  /(?:import\s+.*?from\s+['"]([^'"]+)['"]|require\(['"]([^'"]+)['"]\))/g;
const PY_IMPORT = /^\s*(?:from\s+([\w.]+)\s+import|import\s+([\w.]+))/gm;
const SYMBOL_DECL = /\b(?:class|interface|object|struct|enum|record)\s+(\w+)/g;

export class DependencyService {
  buildGraph(files: ProjectFile[]): ProjectGraph {
    const graph: ProjectGraph = {
      edges: {},
      cycles: [],
      orphans: [],
      duplicateSymbols: {},
      missing: {}
    };

    const pathSet = new Set(files.map(f => f.path));
    const symbolMap: Record<string, string[]> = {};

    // 1. Collect symbols
    for (const f of files) {
      const matches = f.content.matchAll(SYMBOL_DECL);
      for (const m of matches) {
        const sym = m[1];
        if (!symbolMap[sym]) symbolMap[sym] = [];
        if (!symbolMap[sym].includes(f.path)) symbolMap[sym].push(f.path);
      }
    }

    // 2. Build edges
    for (const f of files) {
      const deps = new Set<string>();
      this.extractImports(f, pathSet, deps);
      graph.edges[f.path] = [...deps];
    }

    // 3. Cycles
    graph.cycles = this.detectCycles(graph.edges);

    // 4. Orphans
    const hasOut = new Set(
      Object.entries(graph.edges)
        .filter(([, v]) => v.length > 0)
        .map(([k]) => k)
    );
    const referenced = new Set(
      Object.values(graph.edges).flat()
    );
    graph.orphans = files
      .map(f => f.path)
      .filter(p => !hasOut.has(p) && !referenced.has(p));

    // 5. Duplicates
    for (const [sym, paths] of Object.entries(symbolMap)) {
      if (paths.length > 1) graph.duplicateSymbols[sym] = paths;
    }

    console.log(
      `[graph] ${files.length} files, ` +
        `${Object.values(graph.edges).reduce((s, v) => s + v.length, 0)} edges, ` +
        `${graph.cycles.length} cycles, ${graph.orphans.length} orphans, ` +
        `${Object.keys(graph.duplicateSymbols).length} dups`
    );

    return graph;
  }

  private extractImports(
    file: ProjectFile,
    pathSet: Set<string>,
    deps: Set<string>
  ): void {
    const ext = file.path.split('.').pop()?.toLowerCase() || '';
    const content = file.content;

    switch (ext) {
      case 'kt':
      case 'kts':
        for (const m of content.matchAll(KOTLIN_IMPORT)) {
          this.matchToFile(m[1].replace(/\./g, '/'), pathSet, deps);
        }
        break;
      case 'cs':
        for (const m of content.matchAll(CSHARP_USING)) {
          this.matchToFile(m[1].replace(/\./g, '/'), pathSet, deps);
        }
        break;
      case 'js':
      case 'ts':
      case 'jsx':
      case 'tsx':
        for (const m of content.matchAll(JS_IMPORT)) {
          const imp = (m[1] || m[2] || '').replace(/^[./]+/, '');
          this.matchToFile(imp, pathSet, deps);
        }
        break;
      case 'py':
        for (const m of content.matchAll(PY_IMPORT)) {
          const imp = (m[1] || m[2] || '').replace(/\./g, '/');
          this.matchToFile(imp, pathSet, deps);
        }
        break;
    }
  }

  private matchToFile(
    importPath: string,
    pathSet: Set<string>,
    deps: Set<string>
  ): void {
    if (!importPath) return;
    for (const p of pathSet) {
      if (
        p.endsWith(importPath + '.kt') ||
        p.endsWith(importPath + '.cs') ||
        p.endsWith(importPath + '.js') ||
        p.endsWith(importPath + '.ts') ||
        p.endsWith(importPath + '.py') ||
        p.endsWith(importPath + '.tsx') ||
        p.includes('/' + importPath + '/')
      ) {
        deps.add(p);
      }
    }
  }

  private detectCycles(edges: Record<string, string[]>): string[][] {
    const cycles: string[][] = [];
    const visited = new Set<string>();
    const stack: string[] = [];
    const onStack = new Set<string>();

    const dfs = (node: string): void => {
      if (onStack.has(node)) {
        const idx = stack.indexOf(node);
        if (idx >= 0) cycles.push(stack.slice(idx));
        return;
      }
      if (visited.has(node)) return;
      visited.add(node);
      stack.push(node);
      onStack.add(node);
      for (const n of edges[node] || []) dfs(n);
      stack.pop();
      onStack.delete(node);
    };

    for (const n of Object.keys(edges)) dfs(n);
    return cycles;
  }
}

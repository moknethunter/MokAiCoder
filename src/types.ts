export interface ProjectFile {
  path: string;
  content: string;
  size: number;
}

export interface ProjectGraph {
  edges: Record<string, string[]>;
  cycles: string[][];
  orphans: string[];
  duplicateSymbols: Record<string, string[]>;
  missing: Record<string, string[]>;
}

export interface ScanRequest {
  branch?: string;
}

export interface ScanResponse {
  fileCount: number;
  edgeCount: number;
  cycleCount: number;
  orphanCount: number;
  duplicateCount: number;
  totalBytes: number;
  graph: ProjectGraph;
  files: string[];
}

import { randomUUID } from 'node:crypto';
import type { ProjectFile, ProjectGraph } from '../types.js';

interface ScanSnapshot {
  id: string;
  files: Map<string, ProjectFile>;
  graph: ProjectGraph;
  expiresAt: number;
}

export class ProjectCache {
  private snapshot: ScanSnapshot | undefined;

  constructor(private readonly ttlMs = 15 * 60 * 1000) {}

  get current(): ScanSnapshot | undefined {
    if (this.snapshot && this.snapshot.expiresAt <= Date.now()) this.clear();
    return this.snapshot;
  }

  set(files: ProjectFile[], graph: ProjectGraph): string {
    const id = randomUUID();
    this.snapshot = {
      id,
      files: new Map(files.map((file) => [file.path, file])),
      graph,
      expiresAt: Date.now() + this.ttlMs,
    };
    return id;
  }

  clear(): void {
    this.snapshot = undefined;
  }
}

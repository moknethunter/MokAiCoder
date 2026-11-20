import { ProjectFile, ProjectGraph } from '../types.js';

export class ProjectCache {
  files: ProjectFile[] = [];
  graph: ProjectGraph | null = null;
  scannedAt: Date = new Date(0);
  branch: string = '';

  get isEmpty(): boolean {
    return this.files.length === 0;
  }
}

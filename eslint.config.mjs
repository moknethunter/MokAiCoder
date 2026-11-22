import js from '@eslint/js';
import { defineConfig } from 'eslint/config';
import globals from 'globals';
import typescript from 'typescript-eslint';

export default defineConfig([
  {
    files: ['src/**/*.ts'],
    extends: [js.configs.recommended, typescript.configs.recommended],
  },
  {
    files: ['test/**/*.js'],
    extends: [js.configs.recommended],
    languageOptions: { globals: globals.node },
  },
  {
    files: ['web/**/*.js'],
    extends: [js.configs.recommended],
    languageOptions: { globals: globals.browser },
  },
]);

import js from '@eslint/js'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import globals from 'globals'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  {
    ignores: ['dist', 'node_modules', 'src/api/esquema.d.ts', 'playwright-report', 'test-results'],
  },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: { ecmaVersion: 2023, globals: globals.browser },
    plugins: { 'react-hooks': reactHooks, 'react-refresh': reactRefresh },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      // Prohibido llamar a fetch a mano: todo pasa por el cliente generado (src/api/cliente.ts).
      'no-restricted-globals': [
        'error',
        { name: 'fetch', message: 'Usa el cliente de API generado (src/api/cliente.ts).' },
      ],
    },
  },
  {
    // El árbol de rutas declara componentes pequeños junto a su ruta.
    files: ['src/rutas.tsx'],
    rules: { 'react-refresh/only-export-components': 'off' },
  },
  {
    files: ['src/api/cliente.ts'],
    rules: { 'no-restricted-globals': 'off' },
  },
)

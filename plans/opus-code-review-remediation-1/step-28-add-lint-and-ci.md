# Step 28 — Fix the lint script and add CI

**Phase:** 4 — Features and cleanup
**Severity:** Medium (report: M16)
**Files:** `frontend/package.json`, new `frontend/eslint.config.js`,
new `.github/workflows/ci.yml`
**Depends on:** step-03 (CI runs `npm ci`, which needs the tracked lock file)

## Problem

Two gaps.

1. `frontend/package.json` declares `"lint": "eslint ."`, but eslint is not in
   `devDependencies` and there is no config file. Running `npm run lint` fails.
   `AGENTS.md` does not mention the command, so nobody has noticed.

2. `.github/` contains only modernize hook scripts — **no workflows**. Nothing builds or
   tests on push. The backend tests are good and the frontend has 27 title-parsing tests;
   none of them gate anything.

## Change

### 1. Add eslint

```bash
cd frontend
npm install -D eslint @eslint/js typescript-eslint eslint-plugin-react-hooks eslint-plugin-react-refresh globals
```

Create `frontend/eslint.config.js`:

```js
import js from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['dist', 'node_modules', '*.tsbuildinfo'] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      // The pages use `catch (err: any)` to read err.message off Axios errors.
      // Worth tightening later; not worth blocking CI on today.
      '@typescript-eslint/no-explicit-any': 'warn',
    },
  },
);
```

Expect `react-hooks/exhaustive-deps` to flag the effects touched in steps 08 and 11. Those
have explicit `eslint-disable-next-line` comments with a stated reason — that is the
correct treatment. Do **not** silence the rule globally.

### 2. Add the CI workflow

Create `.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: ['**']
  pull_request:
    branches: [main]

jobs:
  backend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: temurin
          cache: maven
      - name: Build and test
        working-directory: backend
        run: mvn -B verify

  frontend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: npm
          cache-dependency-path: frontend/package-lock.json
      - name: Install
        working-directory: frontend
        run: npm ci
      - name: Typecheck
        working-directory: frontend
        run: npm run typecheck
      - name: Lint
        working-directory: frontend
        run: npm run lint
      - name: Test
        working-directory: frontend
        run: npm test
      - name: Build
        working-directory: frontend
        run: npm run build
```

`npm ci` requires the lock file to be tracked — that is step-03, and this workflow will
fail loudly if it was skipped.

### 3. Document the commands in `AGENTS.md`

Add to the Commands block:

```
cd frontend && npm run lint         # ESLint
cd frontend && npm test             # Vitest
cd backend && mvn verify            # Compile + tests + JaCoCo report
```

## Do not

- Do not add a Docker build or a deploy step to CI. Images are built and pushed manually
  (see `AGENTS.md`), and deployment targets a private K3s cluster that CI cannot reach.
- Do not make lint failures non-blocking with `continue-on-error` — fix the findings or
  downgrade the specific rule with a comment explaining why.

## Verify

```bash
cd frontend && npm run lint       # must exit 0 (warnings allowed, errors not)
cd frontend && npm test
cd backend && mvn -B verify
```

Then push a branch and confirm both CI jobs go green.

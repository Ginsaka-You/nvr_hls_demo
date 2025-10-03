# Repository Guidelines

## Project Structure & Module Organization
- `backend/` — Spring Boot service for stream control, radar ingestion, and alert persistence (source in `src/main/java`, config in `src/main/resources`).
- `frontend/` — Vite + Vue 3 dashboard (`src/` for pages, components, and Pinia stores; `public/` for static assets; build output in `dist/`).
- `nginx/`, `systemd/`, `scripts/` — deployment helpers (service units, nginx snippets, packaging scripts).
- Root-level artifacts such as `application.yml`, capture samples (`*.pcapng`, `radar.txt`), and docs support local experimentation.

## Build, Test, and Development Commands
- Backend build: `cd backend && mvn -q -DskipTests package` — compiles and packages the Spring Boot jar into `target/`.
- Backend run: `java -jar backend/target/nvr-hls-backend-0.1.0.jar` (ensure environment variables for database connectivity are set first).
- Frontend install: `cd frontend && npm install` — pulls node dependencies; rerun after package.json updates.
- Frontend dev server: `npm run dev` — hot-reload UI on `http://localhost:5173`.
- Frontend production build: `npm run build` — emits optimized assets to `frontend/dist/`.

## Coding Style & Naming Conventions
- Java: 4-space indentation, PascalCase classes (`AlertStreamController`), camelCase methods/fields. Use SLF4J logging and prefer constructor injection for Spring components.
- TypeScript/Vue: 2-space indentation, script setup syntax, exported composables in `src/store` use camelCase (`connectAlerts`). Name Vue components in PascalCase and colocate scoped styles.
- Configuration constants live in `frontend/src/store/config.ts`; keep defaults realistic but non-secret.

## Testing Guidelines
- Automated test suites are not yet defined; before submitting changes, run backend build and frontend production build to catch compilation issues.
- When altering radar or alert flows, exercise the UI against local sample data (`radar.txt`, EventSource endpoints) and verify database tables (`alert_events`, `camera_alarms`, `radar_targets`). Document manual validation steps in the PR description.

## Commit & Pull Request Guidelines
- Follow imperative, concise commit messages (`Add radar payload retry logic`). Squash fixup commits locally when possible.
- Pull requests should include: high-level summary, detailed behavior changes, test/build commands executed, and any deployment/configuration notes. Attach screenshots or console output when UI or API responses change.
- Link to relevant issues or tickets and call out backward-incompatible changes (database schema, environment variables).

## Security & Configuration Tips
- Database connection is controlled via `DB_*` environment variables; avoid committing real credentials.
- NVR and radar endpoints reside in `frontend/src/store/config.ts` defaults—replace with site-specific values via UI settings before deployment.
- Ensure `/var/www/streams` ownership matches the runtime user before enabling automated HLS cleanup tasks.

# CogniHire

CogniHire is a modular recruitment assessment platform. This repository starts with the secure assessment lifecycle: recruiter accounts, question and assessment authoring, candidate assignment and attempt workflows, external code execution boundaries, and policy-enforced AI assistance. Interview and repository-interview modules are planned extensions; they are not represented as implemented here.

## Architecture and current status

- `frontend/`: React, TypeScript, Vite, Tailwind CSS, React Router, Monaco, React Hook Form, Zod, Axios, and Lucide.
- `backend/`: Java 21, Spring Boot 3, Spring Security, JPA, PostgreSQL, Flyway, and REST APIs under `/api/v1`.
- `docker-compose.yml`: local PostgreSQL service.
- Assessment policies and question versions are intended to be snapshotted when an attempt starts. Candidate-facing APIs must only return public problem data.

This is the first working phase, not a claim that the full production definition of done has been met. The current vertical slice covers account registration/login, recruiter assessment/question creation and publication, assignment to an existing candidate, candidate attempt start with frozen public-question/policy snapshots, draft save/reload, public-sample execution through the Judge0 adapter, and a gated AI mentor endpoint with persisted delivered/refused interactions. No provider success is simulated.

### Implementation checklist

- [x] Foundation, PostgreSQL schema, Flyway, validation, JWT auth, role checks.
- [x] Assessment/question authoring, publishing, assignment, attempt snapshot.
- [x] Candidate workspace, private draft persistence, external public-sample execution adapter.
- [x] Deterministic AI request/output policy gates, configured LLM adapter, assistance audit records, and penalty calculation tests.
- [ ] Hidden-test submission scoring, persisted adjusted reports, invitations/email delivery.
- [ ] Provider-backed AI/execution failure integration tests, cumulative disclosure evaluation, and persisted adjusted-score reporting.
- [ ] Coding-verification interview, interactive dry-run, structured interviews, and GitHub project interview.
- [ ] Recruiter evidence reports, admin/provider configuration, retention controls, and full end-to-end database tests.

## Requirements

- Java 21+
- No global Maven install is needed; the backend includes Maven Wrapper.
- Node.js 20.19+ or 22.12+
- PostgreSQL 16+
- Optional: Judge0 compatible endpoint and an OpenAI-compatible LLM endpoint

## Local setup

1. Copy `.env.example` to `.env`, set a random `JWT_SECRET` of at least 32 bytes, and configure your provider values. Never expose backend secrets as `VITE_*` variables.
2. Start PostgreSQL with `docker compose up -d postgres` (or provide an existing PostgreSQL database).
3. In PowerShell, from `backend/`, load the root `.env` into the current process and start the API with `./mvnw.cmd spring-boot:run`:

   ```powershell
   Get-Content ..\.env | Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } | ForEach-Object {
     $key, $value = $_.Split('=', 2)
     [Environment]::SetEnvironmentVariable($key, $value, 'Process')
   }
   .\mvnw.cmd spring-boot:run
   ```
4. Start the web app from `frontend/` with `npm install` then `npm run dev`.

Flyway applies schema migrations at backend startup. The frontend expects the API at `http://localhost:8080/api/v1` by default. Provider-backed features stay unavailable until their server-side credentials are configured.

## Configuration

See `.env.example`. Set a long random `JWT_SECRET` (at least 32 bytes). `JUDGE0_BASE_URL`, `JUDGE0_API_KEY`, and `JUDGE0_LANGUAGE_IDS` configure remote execution. Check language IDs against the configured Judge0 host before use. The app sends bounded CPU and memory limits and never executes candidate code in the API process. `LLM_BASE_URL`, `LLM_API_KEY`, and `LLM_MODEL` configure the optional model provider. GitHub authorization and speech providers are not implemented yet.

## Checks

- Frontend: `npm run build` and `npm run lint` in `frontend/`.
- Backend: `./mvnw test` in `backend/`.

## Security and limitations

The API enforces role/ownership checks for implemented routes, hashes passwords, validates input, and excludes hidden tests/reference solutions from candidate-facing question responses. The Judge0 adapter uses bounded runtime settings and never executes candidate code in the API process. Treat model classifications as fallible; deterministic budgets and refusal rules must remain authoritative. Do not use integrity signals as automatic hiring decisions. Rate limiting, email delivery, live LLM integration, persisted scoring reports, retention controls, and full authorization/integration coverage remain unfinished. Candidate reports and AI evaluations must show evidence and remain subject to human review.

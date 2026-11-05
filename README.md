# CogniHire

CogniHire is a modular recruitment assessment platform. This repository includes recruiter accounts, assessment authoring and candidate assignment, plus a candidate flow with a dry-run warm-up, optional AI-led interviewer, Python editor, Piston public-sample runs, and recruiter-reviewed submission. Final submissions are not automatically graded.

## Architecture and current status

- `frontend/`: React, TypeScript, Vite, Tailwind CSS, React Router, Monaco, React Hook Form, Zod, Axios, and Lucide.
- `backend/`: Java 21, Spring Boot 3, Spring Security, JPA, PostgreSQL, Flyway, and REST APIs under `/api/v1`.
- `docker-compose.yml`: local PostgreSQL service.
- Assessment policies and question versions are intended to be snapshotted when an attempt starts. Candidate-facing APIs must only return public problem data.

This is the first working phase, not a claim that the full production definition of done has been met. The current vertical slice covers account registration/login, recruiter assessment/question creation and publication, assignment to an existing candidate, candidate attempt start with frozen public-question/policy snapshots, dry-run notes and code draft persistence, optional AI interview questions, Piston execution for the first public input, final submission storage, and a recruiter view of submitted evidence. Public sample outputs, hidden tests, and reference solutions are excluded from the candidate start response.

### Implementation checklist

- [x] Foundation, PostgreSQL schema, Flyway, validation, JWT auth, role checks.
- [x] Assessment/question authoring, publishing, assignment, attempt snapshot.
- [x] Candidate workspace, dry-run warm-up, private draft persistence, Python-only Piston sample execution and final code submission without automatic grading.
- [x] Deterministic AI request/output policy gates, configured LLM adapter, assistance audit records, and penalty calculation tests.
- [ ] Hidden-test submission scoring, persisted adjusted reports, invitations/email delivery.
- [ ] Provider-backed AI/execution failure integration tests, cumulative disclosure evaluation, and persisted adjusted-score reporting.
- [x] Guided dry-run and AI-led reasoning interview during the coding assessment.
- [ ] Structured interviews, GitHub project interview, scoring reports, admin/provider configuration, retention controls, and full end-to-end database tests.

## Requirements

- Java 21+
- No global Maven install is needed; the backend includes Maven Wrapper.
- Node.js 20.19+ or 22.12+
- PostgreSQL 16+
- Optional: an OpenAI-compatible LLM endpoint for AI interviewer prompts

## Local setup

1. Copy `.env.example` to `.env`, set a random `JWT_SECRET` of at least 32 bytes, and configure your provider values. Never expose backend secrets as `VITE_*` variables.
2. Start PostgreSQL with `docker compose up -d postgres` (or provide an existing PostgreSQL database). For the native Windows service used in development, open PowerShell as Administrator and run `Start-Service postgresql-x64-16`.
3. From `backend/`, start the API with `./mvnw.cmd spring-boot:run`. Spring Boot loads the root `.env` automatically:

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```
4. Start the web app from `frontend/` with `npm install` then `npm run dev`.

Flyway applies schema migrations at backend startup. The frontend expects the API at `http://localhost:8080/api/v1` by default. Provider-backed features stay unavailable until their server-side credentials are configured.

## Configuration

See `.env.example`. Set a long random `JWT_SECRET` (at least 32 bytes). `JUDGE0_BASE_URL` is a legacy setting name; it selects the Piston-compatible execution API and defaults to local Piston at `http://localhost:2000/api/v2`. Start it with `docker compose -f docker-compose.piston.yml up -d`. Its Python packages are stored in a persistent Docker volume so Windows file permissions do not interfere with runtime startup. Install Python into the local service from the Piston CLI with `node .\index.js -u http://localhost:2000 ppman install python`. The Piston public API now requires authorization, so CogniHire uses the local service instead. `LLM_BASE_URL`, `LLM_API_KEY`, and `LLM_MODEL` configure the optional AI interviewer. Final submissions are stored for recruiter review and are not automatically graded. GitHub authorization and speech providers are not implemented yet.

## Checks

- Frontend: `npm run build` and `npm run lint` in `frontend/`.
- Backend: `./mvnw test` in `backend/`.

## Security and limitations

The API enforces role/ownership checks for implemented routes, hashes passwords, validates input, and excludes public sample outputs, hidden tests, and reference solutions from candidate-facing question responses. Submitted code and interview evidence are visible to the assessment recruiter. Execution and LLM credentials remain server-side. The AI interviewer is optional and requires server-side LLM credentials; it does not provide solutions or make hiring decisions. Rate limiting, email delivery, persisted scoring reports, retention controls, and full authorization/integration coverage remain unfinished. Candidate reports and AI evaluations must show evidence and remain subject to human review.

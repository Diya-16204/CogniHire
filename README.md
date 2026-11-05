# CogniHire

**CogniHire is a recruitment assessment platform for creating structured, evidence-based technical screens.** Recruiters build and assign assessments; candidates work through a guided coding and interview flow; recruiters review code, answers, and session activity.

## Live demo

- **Frontend:** [frontend-eta-plum-55.vercel.app](https://frontend-eta-plum-55.vercel.app/)
- **Backend health:** [cognihire-api-7knk.onrender.com/actuator/health](https://cognihire-api-7knk.onrender.com/actuator/health)

The frontend and health endpoint responded when checked on 5 November 2026. The frontend’s Vercel API variables must be plain **Config** values (`VITE_API_BASE_URL` and `VITE_WS_BASE_URL`) and the frontend must be redeployed after changing them. End-to-end login should be rechecked on the live site after that configuration is saved.

## Product flow

1. A recruiter creates an assessment, adds coding questions and Round 2 subject/interview questions, configures duration and AI-assistance policy, and publishes it.
2. The recruiter assigns candidates by email or imports a candidate email workbook.
3. A candidate signs in, optionally provides a public GitHub repository URL when repository analysis is enabled, and starts the assigned assessment.
4. For each coding question, the candidate completes a dry-run warm-up, writes Python in the browser editor, runs a public sample, submits code, and answers interview prompts. Round 2 supports recruiter-authored subject MCQs and technical interview questions.
5. Recruiters monitor active sessions and review submitted evidence, coding activity, interview turns, candidate responses, and recruiter follow-ups.

## Features

### Recruiter workspace

- Recruiter registration/login with JWT authentication and role-protected APIs.
- Assessment creation, editing before publication, question-bank selection, coding-question authoring, and publishing.
- Assessment duration, AI assistance policy, question points, public samples, private tests, and reference solutions.
- Candidate assignment by email, duplicate-assignment checks, and Excel template/import support.
- Round 2 authoring for core-subject MCQs and recruiter-set interview questions.
- Live sessions list with read-only code mirroring, execution events, transcript/activity feed, recruiter prompt injection, and takeover controls.
- WebRTC audio/video signaling for live recruiter-candidate calls, subject to browser permissions and network connectivity.
- Submission review with code, candidate answers, interview transcripts, follow-ups, and recruiter-presence audit data.

### Candidate workspace

- Candidate registration/login and assigned-assessment list.
- Timed assessment attempts with saved progress and the ability to revisit earlier steps/questions where allowed by the active attempt flow.
- Coding-question dry-run: candidates record expected sample outputs and reasoning before coding.
- Browser-based Monaco editor with Python 3 support, draft persistence, public-sample execution, and final code submission.
- Output panel compares expected and actual sample output. Submitted code is saved for recruiter review; it is not automatically graded against hidden tests.
- Optional AI-led reasoning interview with text input, browser speech features, prepared prompts when a configured live provider is unavailable, and recruiter-authored interview questions.
- Round 2 subject MCQs, recruiter follow-up questions with candidate responses, and progress/completion flow.
- Proctoring HUD for assessment timer, browser/tab focus changes, camera preview, recruiter presence, and relevant session notices.

### Repository-aware interview prompts

When enabled by the recruiter, the candidate can provide a public GitHub repository URL at assessment start. The backend reads public repository metadata and uses available project context to create more specific interview prompts. If the repository cannot be read or has no usable public data, the interview can fall back to role-oriented prompts. Private repository access is not implied.

## Technology stack

| Area | Technologies |
|---|---|
| Frontend | React 19, TypeScript, Vite, React Router, Monaco Editor, Axios, STOMP.js, React Hook Form, Zod |
| Backend | Java 21, Spring Boot 3.5, Spring MVC/REST, Spring Security, JWT, Spring Data JPA, Hibernate, WebSocket/STOMP |
| Data | PostgreSQL, Flyway migrations, Redis (transient interview/session state with in-memory fallback) |
| Code execution | Piston-compatible API; local Docker Piston is the development path |
| Realtime | WebSocket/STOMP for interview/session events; WebRTC signaling for browser calls |
| Hosting | Vercel frontend, Render backend, Neon PostgreSQL, Upstash Redis |
| Optional/inactive by default | OpenAI-compatible LLM provider; Kafka (`INTERVIEW_KAFKA_ENABLED=false` in the current deployment config) |

## Architecture

```text
Candidate / Recruiter browser
        | HTTPS REST + WSS/STOMP
        v
React + TypeScript on Vercel
        |
        v
Spring Boot API on Render
   |           |             |
   | JDBC/TLS  | Redis/TLS   | HTTP
   v           v             v
 Neon DB    Upstash Redis   Piston-compatible runner
   |
   +-- Flyway schema migrations
```

PostgreSQL is the durable store for accounts, assessments, assignments, attempts, submissions, and interview records. Redis is used for transient interview/session state; the realtime service has an in-memory fallback if Redis is unavailable. Kafka is optional and disabled in the current free-tier configuration.

## Run locally

### Requirements

- Java 21
- Node.js 20.19+ or 22.12+
- PostgreSQL 16+
- Docker Desktop is optional for PostgreSQL, Redis, and local Piston

### 1. Configure environment

Copy `.env.example` to `.env`, then set a unique `JWT_SECRET` with at least 32 bytes and valid local PostgreSQL credentials. Keep secrets in the backend `.env`; never put credentials in `VITE_*` variables.

### 2. Start PostgreSQL

From the project root:

```powershell
docker compose up -d postgres
```

Or use a local PostgreSQL service and set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` in `.env`.

### 3. Start the backend

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

The API runs at `http://localhost:8080`. Flyway applies pending migrations on startup. Health endpoint: `http://localhost:8080/actuator/health`.

### 4. Start the frontend

In a second terminal:

```powershell
cd frontend
npm install
npm run dev
```

Vite prints the local URL, usually `http://localhost:5173`. The default API URL is `http://localhost:8080/api/v1`.

### 5. Optional local Redis and Piston

The root Docker Compose file includes local PostgreSQL, Redis, and Piston services:

```powershell
docker compose up -d postgres cognihire-redis cognihire-piston
```

The standalone Piston compose file is also available:

```powershell
docker compose -f docker-compose.piston.yml up -d
```

Piston must have the configured Python runtime installed. Local Docker Piston is the preferred development runner. Public Piston access is not guaranteed to be anonymous or available; it may require an authorized token.

## Production deployment

The repository includes `render.yaml` for the Render backend and `frontend/vercel.json` for client-side route rewrites. Create Neon PostgreSQL and Upstash Redis separately, then deploy the backend Blueprint on Render and the `frontend/` directory on Vercel.

### Render environment

Set these values in the Render backend service:

| Variable | Description |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `SPRING_DATASOURCE_URL` | Neon JDBC URL with `?sslmode=require` |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | Neon credentials |
| `UPSTASH_REDIS_HOST` | Hostname only; no `rediss://` prefix |
| `UPSTASH_REDIS_PORT` | `6379` |
| `UPSTASH_REDIS_PASSWORD` | Upstash Redis password |
| `CORS_ALLOWED_ORIGINS` | Exact Vercel origin, without a trailing slash |
| `JWT_SECRET` | Render-generated or securely generated secret of 32+ bytes |
| `INTERVIEW_KAFKA_ENABLED` | `false` unless Kafka is configured |

Flyway is enabled in the production profile. Render provides the `PORT` value used by Spring Boot.

### Vercel environment

Set these as **Config** variables for the Vercel deployment (they are public frontend URLs, not secrets):

```text
VITE_API_BASE_URL=https://YOUR-RENDER-SERVICE.onrender.com/api/v1
VITE_WS_BASE_URL=wss://YOUR-RENDER-SERVICE.onrender.com/ws/interview
```

Replace the Render hostname, save, and redeploy so Vite bakes the values into the frontend build. Add the exact Vercel site origin to Render’s `CORS_ALLOWED_ORIGINS` and redeploy/restart the backend if needed.

### Free-tier notes

- Render free web services can sleep while idle, so the first request or WebSocket connection can take time to wake the API.
- The free services do not provide an always-on availability guarantee.
- Public Piston execution can fail when the provider requires authorization or is unavailable. Configure a permitted token or use a local/private Piston service.
- Kafka is disabled unless a broker is configured. Redis fallback is in-memory and is not durable across service restarts.

## Checks

From `frontend/`:

```powershell
npm run build
npm run lint
```

From `backend/`:

```powershell
.\mvnw.cmd test
```

## Known limitations

- Code submissions are stored for recruiter review; hidden-test scoring and automatic grading are not implemented in the candidate submission flow.
- The AI interview depends on an optional server-side OpenAI-compatible provider for live generated responses. Prepared prompts provide a fallback; they are not a substitute for a configured LLM.
- Email invitations, account verification/password reset, comprehensive rate limiting, production retention controls, and full end-to-end/provider integration coverage remain future work.
- Public GitHub repository metadata is used only when the candidate provides a public repository URL; private repository authorization is not implemented.
- Browser speech recognition/synthesis and WebRTC call quality depend on the candidate’s browser, permissions, and network.

## Security notes

- Never commit `.env` files, database credentials, Redis passwords, JWT secrets, or provider tokens.
- `VITE_*` variables are shipped to the browser. Store only public configuration such as API URLs in them.
- If a credential was pasted into a chat, issue, screenshot, or repository, rotate it in its provider and update the deployment environment.
- Candidate submissions and interview records are visible to the assessment recruiter. Use synthetic data for demos unless candidates have consented to the assessment and its monitoring features.

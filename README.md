# KLU Sports Management Portal

A full-stack local starter with a separate React + Vite frontend and Spring Boot + PostgreSQL backend. It includes bcrypt password hashing, JWT authentication, and role-based dashboards.

## Project structure

- `frontend/` contains the React/Vite application and runs on `http://localhost:5173`.
- `backend-spring/` contains the Spring Boot API and PostgreSQL schema initialization/migrations. It runs on `http://localhost:4000`.
- The root scripts start the Spring backend and frontend together. The frontend proxies `/api` requests to the backend during development.

## Included

- Student signup and login. Public signup always creates a `STUDENT` account.
- Coordinator signup creates a `PENDING` request that an administrator must approve before coordinator access is enabled.
- Separate Student, Sports Coordinator, and Admin dashboards, selected after login based on the user's database role.
- Student event discovery, registration, personal registration list, attendance status, and published results.
- Coordinator event creation/deletion, registration list, attendance marking, and result publishing, restricted to the coordinator's assigned sport.
- Admin overview, event/registration oversight, account creation, and role management.
- PostgreSQL schema creation and backward-compatible migrations on API startup. No demo events are seeded.
- Admin/coordinator registration entries can be downloaded as a PDF from the registration desk.
- Protected API routes; admin/coordinator actions are enforced server-side, not only hidden in the UI.

## Requirements

- Node.js 20 LTS (Node 18+ should work)
- PostgreSQL installed and running
- Your database already created in pgAdmin: `SPORTS`

## One-time setup on Windows

1. Extract the ZIP to a normal folder.
2. Open that `klu-sports-portal-full` folder in VS Code.
3. Double-click `START-WINDOWS.bat` or open the VS Code terminal in that folder.
4. If the root `.env` file is created, it opens in Notepad. Update these values:
   - `DATABASE_URL=postgresql://postgres:YOUR_POSTGRES_PASSWORD@localhost:5432/SPORTS`
   - Set `JWT_SECRET` to a long random private value.
   - Change `ADMIN_PASSWORD` to a strong private password.
   - You can change `ADMIN_EMAIL` and `ADMIN_NAME` too.
5. Save `.env`, close Notepad, and the launcher starts the app. If you used the terminal instead, run `npm run install:all` then `npm run dev`.
6. Open **http://localhost:5173**.

The API runs at **http://localhost:4000** locally. In hosted environments such as Render, the backend uses Render's `PORT` environment variable automatically. The first backend startup creates the required tables and migrations in the `SPORTS` database. Events must be created by an authorized admin or sport coordinator.

For a separately hosted frontend, set `VITE_API_URL` to the deployed backend URL (for example, `https://your-backend.onrender.com/api`) and set the backend's `FRONTEND_URL` to the deployed frontend origin (for example, `https://your-frontend.onrender.com`).

The Spring backend can be deployed on Render using the Docker runtime. Set the service root directory to `backend-spring`; Render will use the included `Dockerfile`.

### Run frontend and backend separately

In two terminals from the project root:

```text
npm run server
npm --prefix frontend run dev
```

You can also run `npm run dev` from the root.

### Database connection examples

Default PostgreSQL port:

`DATABASE_URL=postgresql://postgres:YOUR_PASSWORD@localhost:5432/SPORTS`

If PostgreSQL is using another port, replace `5432` with that port. If the password contains characters such as `@`, `:`, `/`, `#`, or `%`, URL-encode those characters in the connection string.

## First login

Use the `ADMIN_EMAIL` and `ADMIN_PASSWORD` values you set in `.env` to sign in as the initial administrator. The admin account is created automatically on startup if that email does not already exist. If you later change the environment password, it does not automatically reset an already-created account password.

The admin dashboard can create coordinator accounts. Students can create their own accounts from the signup screen. Do not allow users to select Admin or Coordinator on public signup.

## API overview

- `GET /api/health`
- `POST /api/auth/signup` (student only)
- `POST /api/auth/coordinator-signup` (creates a pending coordinator request)
- `POST /api/auth/login`
- `GET /api/auth/me`
- `GET /api/events`
- `POST /api/events`, `PUT /api/events/:id`, `DELETE /api/events/:id` (admin/coordinator)
- `GET /api/registrations` (students see their own; admin/coordinator see all)
- `POST /api/registrations` (student only)
- `PATCH /api/registrations/:id/attendance` (admin/coordinator)
- `GET /api/results`, `POST /api/results` (posting requires admin/coordinator)
- `GET /api/admin/summary` (admin/coordinator)
- `GET /api/admin/users`, `POST /api/admin/users`, `PATCH /api/admin/users/:id/role` (admin only)
- `GET /api/admin/coordinator-requests`, `PATCH /api/admin/coordinator-requests/:id/approve`, `PATCH /api/admin/coordinator-requests/:id/reject` (admin only)

## Important notes

- Never share your `.env` file or commit it to GitHub. Environment files are ignored by `.gitignore`.
- This is a local project starter, not a production university deployment. Before public deployment, add HTTPS, stronger operational secret management, rate limiting, audit logs, backups, security review, and university-approved account verification.
- JWTs are stored in browser local storage for this local starter. A production deployment should consider secure HttpOnly cookies and a full security review.
- The seeded event dates and descriptions are sample data. Confirm them with the sports department before real use.

## Troubleshooting

- `password authentication failed`: edit the password in `.env`; do not send it in chat.
- `database "SPORTS" does not exist`: check the exact database name in pgAdmin. If PostgreSQL saved it as lowercase `sports`, use `/sports` in `DATABASE_URL`.
- `ECONNREFUSED`: start the PostgreSQL service and verify host/port.
- If frontend/API ports are busy, stop the other process first.
- Keep the terminal running while you use the portal.

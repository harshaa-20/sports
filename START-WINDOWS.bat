@echo off
cd /d "%~dp0"
echo ==============================================
echo    KLU SPORTS MANAGEMENT PORTAL
echo ==============================================
if not exist frontend\node_modules (
  echo Installing frontend dependencies...
  call npm run install:all
  if errorlevel 1 goto error
)
if not exist .env (
  copy .env.example .env >nul
  echo A local .env file was created.
  echo Please enter your PostgreSQL password and change the initial admin password.
  start /wait notepad .env
)
echo Starting separate API and frontend processes...
call npm run dev
goto end
:error
echo.
echo Setup failed. Check that Node.js and npm are installed.
pause
:end

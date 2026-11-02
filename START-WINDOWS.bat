@echo off
cd /d "%~dp0"
echo ==============================================
echo    KLU SPORTS MANAGEMENT PORTAL
echo ==============================================
if not exist node_modules (
  echo Installing project dependencies...
  call npm install
  if errorlevel 1 goto error
)
if not exist .env (
  copy .env.example .env >nul
  echo A local .env file was created.
  echo Please enter your PostgreSQL password and change the initial admin password.
  start /wait notepad .env
)
echo Starting API and frontend...
call npm run dev
goto end
:error
echo.
echo Setup failed. Check that Node.js and npm are installed.
pause
:end

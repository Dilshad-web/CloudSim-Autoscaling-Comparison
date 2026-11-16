@echo off
setlocal
cd /d "%~dp0"
set "EXTRA=-Dbitbrains.fresh=true"
call run_batch.bat

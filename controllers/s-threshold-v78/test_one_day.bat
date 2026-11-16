@echo off
rem ONE-DAY TEST: all VM files, global P95, day 1 only. Writes day01, the Q-table and the summary.
cd /d "%~dp0"
set "EXTRA=-Dbitbrains.days=1"
call run_batch.bat

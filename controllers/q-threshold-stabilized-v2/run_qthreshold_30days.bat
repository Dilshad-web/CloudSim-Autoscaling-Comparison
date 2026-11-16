@echo off
setlocal
cd /d "%~dp0"
where mvn >nul 2>nul || (echo Maven not found. Install Maven and configure PATH.& pause & exit /b 1)
java -version
call mvn -q -DskipTests package
if errorlevel 1 (echo Maven build failed.& pause & exit /b 1)
call mvn -q dependency:build-classpath -Dmdep.outputFile=target\classpath.txt
if errorlevel 1 (echo Could not build dependency classpath.& pause & exit /b 1)
for /f "usebackq delims=" %%i in ("target\classpath.txt") do set "DEPS=%%i"
java -Dbitbrains.days=30 -cp "target\classes;%DEPS%" org.example.QThresholdTraceBatchRunner
pause

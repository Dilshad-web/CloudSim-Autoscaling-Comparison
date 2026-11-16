@echo off
setlocal
cd /d "%~dp0"
where mvn >nul 2>nul || (echo Maven not found. Install Maven 3.8+ and add it to PATH.& exit /b 1)
java -version
call mvn -q -DskipTests package
if errorlevel 1 (echo Maven build failed.& exit /b 1)
call mvn -q dependency:build-classpath -Dmdep.outputFile=target\classpath.txt
if errorlevel 1 (echo Could not build dependency classpath.& exit /b 1)
for /f "usebackq delims=" %%i in ("target\classpath.txt") do set "DEPS=%%i"
java -Dbitbrains.dir=data/bitbrains/fastStorage/2013-8 -Dbitbrains.vms=0 -Dbitbrains.stepsPerDay=288 %EXTRA% -cp "target\classes;%DEPS%" org.example.TraceBatchRunner

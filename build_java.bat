@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"

set "JAVA_HOME="
for /d %%D in ("%CD%\.tools\jdk\*") do if not defined JAVA_HOME set "JAVA_HOME=%%~fD"
set "MAVEN_HOME="
for /d %%D in ("%CD%\.tools\maven\*") do if not defined MAVEN_HOME set "MAVEN_HOME=%%~fD"

if not defined JAVA_HOME (
    echo [ERROR] Java 21 was not found under .tools\jdk.
    exit /b 1
)
if not defined MAVEN_HOME (
    echo [ERROR] Maven was not found under .tools\maven.
    exit /b 1
)

echo Running Java regression tests and building the executable JAR...
call "%MAVEN_HOME%\bin\mvn.cmd" -f backend-java\pom.xml package
if errorlevel 1 exit /b 1

echo.
echo [OK] Built backend-java\target\simplelabel-java-1.0.0-SNAPSHOT.jar
exit /b 0

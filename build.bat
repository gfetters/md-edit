@echo off
rem Builds dist\md-edit.jar (a runnable jar, Main-Class com.mdedit.Main).
rem
rem Usage:  build.bat                          build the jar (version from the VERSION file)
rem         build.bat release [patch^|minor^|major]
rem                                             increment the version (default: patch), then build
rem         build.bat clean                    remove build\ and dist\
rem
rem Uses %JAVA_HOME%\bin\javac.exe and jar.exe when JAVA_HOME is set,
rem otherwise javac and jar from the PATH. Needs a JDK 11 or newer.
setlocal EnableExtensions
cd /d "%~dp0"

if /i "%~1"=="clean" (
    if exist build rmdir /s /q build
    if exist dist rmdir /s /q dist
    echo Cleaned build\ and dist\.
    exit /b 0
)

rem ---- version -------------------------------------------------------------
if not exist VERSION (echo 1.0.0> VERSION)
set "VERSION="
set /p VERSION=<VERSION
for /f "tokens=1-3 delims=. " %%a in ("%VERSION%") do (
    set "VMAJOR=%%a"
    set "VMINOR=%%b"
    set "VPATCH=%%c"
)
if not defined VPATCH (
    echo ERROR: VERSION file must contain MAJOR.MINOR.PATCH ^(found '%VERSION%'^). 1>&2
    exit /b 1
)
set /a VMAJOR=VMAJOR+0, VMINOR=VMINOR+0, VPATCH=VPATCH+0
set "VERSION=%VMAJOR%.%VMINOR%.%VPATCH%"

if /i "%~1"=="release" (
    set "PART=%~2"
    if "%~2"=="" set "PART=patch"
    goto :bump
)
if not "%~1"=="" (
    echo ERROR: unknown command '%~1'. Use: build.bat [release [patch^|minor^|major] ^| clean] 1>&2
    exit /b 1
)
goto :afterbump

:bump
if /i "%PART%"=="major" (
    set /a VMAJOR+=1
    set VMINOR=0
    set VPATCH=0
) else if /i "%PART%"=="minor" (
    set /a VMINOR+=1
    set VPATCH=0
) else if /i "%PART%"=="patch" (
    set /a VPATCH+=1
) else (
    echo ERROR: release part must be patch, minor or major ^(got '%PART%'^). 1>&2
    exit /b 1
)
set "OLDVERSION=%VERSION%"
set "VERSION=%VMAJOR%.%VMINOR%.%VPATCH%"
>VERSION echo %VERSION%
rem Keep the Maven project version (the first <version> in pom.xml) in sync.
if exist pom.xml (
    powershell -NoProfile -Command "$p='pom.xml'; $t=[IO.File]::ReadAllText($p); $r=[regex]'<version>[^<]*</version>'; [IO.File]::WriteAllText($p, $r.Replace($t, '<version>%VERSION%</version>', 1))"
)
echo Version %OLDVERSION% -^> %VERSION%

:afterbump

set "JAVAC="
set "JAR="
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\javac.exe" set "JAVAC=%JAVA_HOME%\bin\javac.exe"
    if exist "%JAVA_HOME%\bin\jar.exe" set "JAR=%JAVA_HOME%\bin\jar.exe"
)
if not defined JAVAC (
    for /f "delims=" %%i in ('where javac 2^>nul') do if not defined JAVAC set "JAVAC=%%i"
)
if not defined JAR (
    for /f "delims=" %%i in ('where jar 2^>nul') do if not defined JAR set "JAR=%%i"
)
if not defined JAVAC (
    echo ERROR: javac not found. 1>&2
    echo Install a JDK ^(version 11 or newer^) and either add its bin folder to PATH 1>&2
    echo or set JAVA_HOME, e.g.  set JAVA_HOME=C:\Program Files\Java\jdk-17 1>&2
    exit /b 1
)
if not defined JAR (
    echo ERROR: the 'jar' tool was not found. Set JAVA_HOME to a full JDK installation. 1>&2
    exit /b 1
)

echo Using "%JAVAC%"

if exist build\classes rmdir /s /q build\classes
mkdir build\classes
if not exist dist mkdir dist

rem List the sources in an argument file: quoted, with forward slashes.
if exist build\sources.txt del build\sources.txt
setlocal EnableDelayedExpansion
for /r "src\main\java" %%f in (*.java) do (
    set "P=%%f"
    set "P=!P:\=/!"
    echo "!P!">> build\sources.txt
)
endlocal
if not exist build\sources.txt (
    echo ERROR: no Java sources found under src\main\java 1>&2
    exit /b 1
)

echo Compiling...
"%JAVAC%" --release 11 -encoding UTF-8 -nowarn -d build\classes @build\sources.txt
if errorlevel 1 (
    echo ERROR: compilation failed ^(javac must be from JDK 11 or newer^). 1>&2
    exit /b 1
)

if exist src\main\resources (
    xcopy /e /i /y /q "src\main\resources\*" "build\classes\" >nul
    if errorlevel 1 (
        echo ERROR: copying resources failed. 1>&2
        exit /b 1
    )
)

>"build\classes\com\mdedit\version.properties" echo version=%VERSION%
(
    echo Main-Class: com.mdedit.Main
    echo Implementation-Title: MD Edit
    echo Implementation-Version: %VERSION%
)>build\manifest.txt

if exist dist\md-edit.jar del dist\md-edit.jar
"%JAR%" cfm dist\md-edit.jar build\manifest.txt -C build\classes .
if errorlevel 1 (
    echo ERROR: creating the jar failed. 1>&2
    exit /b 1
)

echo Built dist\md-edit.jar ^(version %VERSION%^)
echo Run it with:  java -jar dist\md-edit.jar [file.md]
endlocal

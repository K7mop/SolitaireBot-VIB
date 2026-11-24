@echo off
setlocal EnableExtensions DisableDelayedExpansion
title SolitaireBot - Open Project
rem Copies content only into this Windows user's own folder.
rem No elevation, ACL changes, system settings, or policy changes.

set "SOL_SOURCE=%~dp0."
set "SOL_DEST="
set "SOL_SDK="
set "SOL_STUDIO="

if not exist "%SOL_SOURCE%\settings.gradle.kts" goto missing_project
if not exist "%SOL_SOURCE%\gradle\wrapper\gradle-wrapper.jar" goto missing_project
if not defined LOCALAPPDATA goto no_user_folder
if not exist "%LOCALAPPDATA%\" goto no_user_folder

echo Preparing a writable copy of SolitaireBot...
:choose_destination
set "SOL_DEST=%LOCALAPPDATA%\SolitaireBot\Projects\SolitaireBot-0.8.4-%RANDOM%-%RANDOM%"
if exist "%SOL_DEST%" goto choose_destination
mkdir "%SOL_DEST%" >nul 2>&1
if errorlevel 1 goto create_failed
>"%SOL_DEST%\.__write_probe" echo ok
if errorlevel 1 goto create_failed
del "%SOL_DEST%\.__write_probe" >nul 2>&1

rem DT excludes source attributes, ACLs, owner, and audit information.
rem Preserve the supplied Gradle wrapper, but omit generated local state.
robocopy "%SOL_SOURCE%" "%SOL_DEST%" /E /COPY:DT /DCOPY:DT /XJ /R:1 /W:1 /NFL /NDL /NJH /NJS /NP /XD .gradle .idea build _local_gradle .git /XF local.properties PC_CHECK.txt BUILD_LOG.txt OPEN_PROJECT_LOG.txt SDK_SETUP_LOG.txt /LOG:"%SOL_DEST%\OPEN_PROJECT_LOG.txt"
if errorlevel 8 goto copy_failed

rem Prefer an existing SDK with API 35, using only paths on this computer.
if defined ANDROID_HOME if exist "%ANDROID_HOME%\platforms\android-35\android.jar" set "SOL_SDK=%ANDROID_HOME%"
if not defined SOL_SDK if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platforms\android-35\android.jar" set "SOL_SDK=%ANDROID_SDK_ROOT%"
if not defined SOL_SDK if exist "%LOCALAPPDATA%\Android\Sdk\platforms\android-35\android.jar" set "SOL_SDK=%LOCALAPPDATA%\Android\Sdk"
if not defined SOL_SDK if exist "C:\Android\Sdk\platforms\android-35\android.jar" set "SOL_SDK=C:\Android\Sdk"
rem If API 35 is not installed, let Android Studio use an existing SDK root.
if not defined SOL_SDK if exist "%LOCALAPPDATA%\Android\Sdk\platforms\" set "SOL_SDK=%LOCALAPPDATA%\Android\Sdk"
if not defined SOL_SDK if exist "%LOCALAPPDATA%\Android\Sdk\cmdline-tools\" set "SOL_SDK=%LOCALAPPDATA%\Android\Sdk"
if not defined SOL_SDK if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\" set "SOL_SDK=%LOCALAPPDATA%\Android\Sdk"
if not defined SOL_SDK if exist "C:\Android\Sdk\platforms\" set "SOL_SDK=C:\Android\Sdk"
if not defined SOL_SDK if exist "C:\Android\Sdk\cmdline-tools\" set "SOL_SDK=C:\Android\Sdk"
if not defined SOL_SDK if exist "C:\Android\Sdk\platform-tools\" set "SOL_SDK=C:\Android\Sdk"
if not defined SOL_SDK goto sdk_done

rem Environment variables carry paths; no user path is interpolated as code.
rem Java properties require ASCII escapes for non-ASCII path characters.
powershell.exe -NoLogo -NoProfile -NonInteractive -Command "$ErrorActionPreference='Stop'; try { $v='sdk.dir='; foreach($ch in $env:SOL_SDK.Replace('\','/').ToCharArray()) { if([int]$ch -gt 126) { $v+=('\u{0:X4}' -f [int]$ch) } else { $v+=$ch } }; [IO.File]::WriteAllText([IO.Path]::Combine($env:SOL_DEST,'local.properties'),$v+[Environment]::NewLine,[Text.Encoding]::ASCII) } catch { Write-Error $_; exit 1 }" >"%SOL_DEST%\SDK_SETUP_LOG.txt" 2>&1
if errorlevel 1 goto sdk_write_failed
goto sdk_done

:sdk_write_failed
if exist "%SOL_DEST%\local.properties" del "%SOL_DEST%\local.properties" >nul 2>&1
echo Android Studio will locate the Android SDK when the project opens.

:sdk_done
rem Set Java only for this launcher and its child process, if not already set.
if not defined JAVA_HOME for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-17*") do if exist "%%~fD\bin\java.exe" set "JAVA_HOME=%%~fD"

if exist "%ProgramFiles%\Android\Android Studio\bin\studio64.exe" set "SOL_STUDIO=%ProgramFiles%\Android\Android Studio\bin\studio64.exe"
if not defined SOL_STUDIO if exist "%LOCALAPPDATA%\Programs\Android Studio\bin\studio64.exe" set "SOL_STUDIO=%LOCALAPPDATA%\Programs\Android Studio\bin\studio64.exe"
if not defined SOL_STUDIO if exist "%LOCALAPPDATA%\Programs\Android\Android Studio\bin\studio64.exe" set "SOL_STUDIO=%LOCALAPPDATA%\Programs\Android\Android Studio\bin\studio64.exe"
rem App Paths is a read-only lookup for a nonstandard installation location.
if not defined SOL_STUDIO for /f "tokens=2,*" %%A in ('reg.exe query "HKCU\Software\Microsoft\Windows\CurrentVersion\App Paths\studio64.exe" /ve 2^>nul') do if /i "%%A"=="REG_SZ" if exist "%%B" set "SOL_STUDIO=%%B"
if not defined SOL_STUDIO for /f "tokens=2,*" %%A in ('reg.exe query "HKLM\Software\Microsoft\Windows\CurrentVersion\App Paths\studio64.exe" /ve 2^>nul') do if /i "%%A"=="REG_SZ" if exist "%%B" set "SOL_STUDIO=%%B"
if not defined SOL_STUDIO goto manual_open

echo Opening the project in Android Studio...
echo Project folder: "%SOL_DEST%"
echo First sync may download missing Android and Gradle components.
start "" "%SOL_STUDIO%" "%SOL_DEST%"
if errorlevel 1 goto manual_open
exit /b 0

:manual_open
echo.
echo The project copy is ready:
echo "%SOL_DEST%"
echo Android Studio was not found automatically.
echo In Android Studio, choose Open and select the folder shown now.
start "" explorer.exe "%SOL_DEST%"
pause
exit /b 0

:missing_project
echo.
echo Extract the complete ZIP first, then run OPEN-IN-STUDIO.cmd.
echo The project files must stay together in the same extracted folder.
pause
exit /b 1

:no_user_folder
echo.
echo Windows did not provide an accessible Local AppData folder.
echo Send a photo of this message so the location can be checked.
pause
exit /b 2

:create_failed
echo.
echo Windows could not create a writable project copy at:
echo "%SOL_DEST%"
echo No permissions were changed. Send a photo of this message.
pause
exit /b 3

:copy_failed
echo.
echo Copying did not finish. The original project was left unchanged.
echo Details are in: "%SOL_DEST%\OPEN_PROJECT_LOG.txt"
start "" notepad.exe "%SOL_DEST%\OPEN_PROJECT_LOG.txt"
pause
exit /b 4

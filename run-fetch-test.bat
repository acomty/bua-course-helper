@echo off
chcp 65001 >nul
setlocal
rem ============================================================
rem  一键验证脚本：用真实学号密码登录教务系统并打印课表
rem  用法： run-fetch-test.bat 学号 密码 [学年] [学期代码]
rem         学期代码：3=第一学期  12=第二学期  16=第三学期
rem ============================================================

set "JAVA_HOME=D:\android-tools\jdk17"
set "JSON_JAR=D:\android-tools\libs\json-20240303.jar"
set "PROJ=%~dp0"
set "BUILD=%TEMP%\coursehelper-fetchtest"

if not exist "%JAVA_HOME%\bin\javac.exe" (
    echo [错误] 找不到 JDK：%JAVA_HOME%
    echo        请修改本脚本里的 JAVA_HOME。
    pause
    exit /b 1
)
if not exist "%JSON_JAR%" (
    echo [错误] 找不到 org.json 库：%JSON_JAR%
    pause
    exit /b 1
)

if not exist "%BUILD%" mkdir "%BUILD%"

"%JAVA_HOME%\bin\javac.exe" -encoding UTF-8 -cp "%JSON_JAR%" -d "%BUILD%" ^
  "%PROJ%app\src\main\java\com\bua\coursehelper\Course.java" ^
  "%PROJ%app\src\main\java\com\bua\coursehelper\Timetable.java" ^
  "%PROJ%app\src\main\java\com\bua\coursehelper\JwglxtClient.java" ^
  "%PROJ%tools\desktoptest\android\util\Base64.java" ^
  "%PROJ%tools\LoginFetchTest.java"
if errorlevel 1 (
    echo [错误] 编译失败
    pause
    exit /b 1
)

"%JAVA_HOME%\bin\java.exe" -Dfile.encoding=UTF-8 -cp "%BUILD%;%JSON_JAR%" LoginFetchTest %*

echo.
pause
endlocal

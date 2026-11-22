@echo off
chcp 65001 > nul
setlocal EnableDelayedExpansion

:: 1. Kiểm tra tham số đầu vào
if "%~1"=="" (
    echo [LỖI] Bạn chưa nhập tên file! Ví dụ: hrun test_export
    exit /b 1
)

:: 2. Tự động tìm file .hql trong thư mục hql\ hoặc thư mục hiện tại
set "TARGET=%~1"
if /i not "%TARGET:~-4%"==".hql" set "TARGET=%TARGET%.hql"

if exist "%~dp0hql\%TARGET%" (
    set "SCRIPT_PATH=%~dp0hql\%TARGET%"
) else if exist "%TARGET%" (
    set "SCRIPT_PATH=%TARGET%"
) else (
    echo [LỖI] Không tìm thấy file "%TARGET%" trong thư mục hql\ hoặc thư mục hiện tại!
    exit /b 1
)

:: 3. Trỏ thẳng vào JDK 21 tại D:\JDK
set "JAVA_EXE="
set "JAVAC_EXE="

if exist "D:\JDK\bin\java.exe" (
    set "JAVA_EXE=D:\JDK\bin\java.exe"
    set "JAVAC_EXE=D:\JDK\bin\javac.exe"
) else (
    for /f "delims=" %%F in ('dir /b /ad /on "D:\JDK" 2^>nul') do (
        if exist "D:\JDK\%%F\bin\java.exe" (
            set "JAVA_EXE=D:\JDK\%%F\bin\java.exe"
            set "JAVAC_EXE=D:\JDK\%%F\bin\javac.exe"
        )
    )
)

if "!JAVA_EXE!"=="" (
    echo [LỖI] Không tìm thấy java.exe trong D:\JDK! Hãy kiểm tra lại đường dẫn.
    exit /b 1
)

:: 4. Nếu chưa build code Java thì tự động biên dịch luôn bằng JDK 21
set "OUT_DIR=%~dp0out\production\NgonNguLapTrinhMoi"
if not exist "%OUT_DIR%\me\hql\script\LinhScriptRunner.class" (
    echo [HQL] Đang biên dịch mã nguồn Java bằng JDK 21...
    if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"
    dir /s /B "%~dp0src\*.java" > "%TEMP%\hql_sources.txt"
    "!JAVAC_EXE!" -encoding UTF-8 -cp "%~dp0lib\*" -d "%OUT_DIR%" @"%TEMP%\hql_sources.txt"
    del "%TEMP%\hql_sources.txt"
)

:: 5. Chạy LinhScriptRunner
set "CP=%OUT_DIR%;%~dp0lib\*;C:\Program Files\Apache Software Foundation\Tomcat 10.1\lib\servlet-api.jar"
"!JAVA_EXE!" -Dfile.encoding=UTF-8 -cp "%CP%" me.hql.script.LinhScriptRunner "%SCRIPT_PATH%" %2 %3 %4 %5
@echo off
chcp 65001 >nul
REM 启动 mall 前后台服务
set "JAVA_HOME=D:\jdks\jdk-17.0.12"
set "JAVA=D:\jdks\jdk-17.0.12\bin\java.exe"
set "BASE=D:\projects\mall"
if not exist "%BASE%\logs" mkdir "%BASE%\logs"
start "mall-admin" /b "%JAVA%" -jar "%BASE%\backend\mall-admin\target\mall-admin-1.0.0.jar" > "%BASE%\logs\admin.log" 2>&1
start "mall-portal" /b "%JAVA%" -jar "%BASE%\backend\mall-portal\target\mall-portal-1.0.0.jar" > "%BASE%\logs\portal.log" 2>&1
echo mall-admin  -> http://localhost:8081  (log: logs\admin.log)
echo mall-portal -> http://localhost:8090  (log: logs\portal.log)

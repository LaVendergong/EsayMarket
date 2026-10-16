@echo off
chcp 65001 >nul
REM mall 项目构建脚本
set "JAVA_HOME=D:\jdks\jdk-17.0.12"
set "MVN=D:\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin\mvn.cmd"
cd /d D:\projects\mall\backend
call "%MVN%" -B -DskipTests %* 
echo === BUILD_EXIT=%ERRORLEVEL% ===

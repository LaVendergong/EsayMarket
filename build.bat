@echo off
chcp 65001 >nul
REM mall 项目构建脚本
set "JAVA_HOME=D:\jdks\jdk-17.0.12"
set "MVN=D:\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin\mvn.cmd"
cd /d D:\projects\mall\backend
REM 默认跑单元测试（纯内存，不需要数据库）。要跳过就显式加 -DskipTests。
call "%MVN%" -B %* 
echo === BUILD_EXIT=%ERRORLEVEL% ===

@echo off
rem 启动服务端（需要先按 README 安装 JDK 17+ 与 Maven，并确保 mvn 在 PATH 中）
chcp 65001 >nul
cd /d "%~dp0.."

echo [quack-chat] 启动服务端 ws://127.0.0.1:8080/ws
call mvn -q -pl chat-server spring-boot:run
pause

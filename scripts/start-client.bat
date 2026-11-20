@echo off
rem 启动一个客户端窗口。想双开测互聊，就把这个文件再运行一次。
chcp 65001 >nul
cd /d "%~dp0.."

echo [quack-chat] 启动客户端（第一次运行会下载 JavaFX 依赖，稍慢）
call mvn -q -pl chat-client javafx:run
pause

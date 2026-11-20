param(
    [string]$JdkPath = "",
    [string]$MvnPath = "",
    [string]$AppVersion = "1.0.0"
)

# Builds dist/QuackChat/QuackChat.exe (client) and dist/QuackChatServer/QuackChatServer.exe (server).
# ASCII-only on purpose: Windows PowerShell 5.1 may misread UTF-8 scripts without BOM.

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $scriptDir

function Write-Step($text) {
    Write-Host ""
    Write-Host "=== $text ===" -ForegroundColor Cyan
}

# ---------------------------------------------------------------- 1. toolchain
Write-Step "Locate JDK and Maven"

if (-not $JdkPath) {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\jpackage.exe"))) {
        $JdkPath = $env:JAVA_HOME
    } else {
        $candidates = Get-ChildItem (Join-Path $env:USERPROFILE ".jdks") -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending
        foreach ($candidate in $candidates) {
            if (Test-Path (Join-Path $candidate.FullName "bin\jpackage.exe")) {
                $JdkPath = $candidate.FullName
                break
            }
        }
    }
}
if (-not $JdkPath -or -not (Test-Path (Join-Path $JdkPath "bin\jpackage.exe"))) {
    throw "JDK 17+ with jpackage not found. Pass -JdkPath 'C:\path\to\jdk'."
}

if (-not $MvnPath) {
    $mvnCommand = Get-Command mvn -ErrorAction SilentlyContinue
    if ($mvnCommand) {
        $MvnPath = $mvnCommand.Source
    }
}
if (-not $MvnPath) {
    throw "Maven not found on PATH. Pass -MvnPath 'C:\path\to\mvn.cmd'."
}

Write-Host "JDK  : $JdkPath"
Write-Host "Maven: $MvnPath"

$env:JAVA_HOME = $JdkPath
$env:Path = "$JdkPath\bin;$env:Path"

# ---------------------------------------------------------------- 2. build
Write-Step "mvn install (skip tests)"
& $MvnPath -B -f (Join-Path $root "pom.xml") -DskipTests install
if ($LASTEXITCODE -ne 0) { throw "maven build failed" }

$clientJar = Get-ChildItem (Join-Path $root "chat-client\target\chat-client-*.jar") |
    Where-Object { $_.Name -notlike "*.original" } | Select-Object -First 1
$serverJar = Get-ChildItem (Join-Path $root "chat-server\target\chat-server-*.jar") |
    Where-Object { $_.Name -notlike "*.original" } | Select-Object -First 1
if (-not $clientJar -or -not $serverJar) { throw "jars not found after build" }

# ---------------------------------------------------------------- 3. stage
Write-Step "Stage files for jpackage"

$work = Join-Path $root "target\packaging"
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path (Join-Path $work "client-libs") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $work "server-libs") | Out-Null

# runtime deps collected by maven-dependency-plugin
Copy-Item (Join-Path $root "chat-client\target\app-libs\*") (Join-Path $work "client-libs") -Force
Copy-Item $clientJar.FullName (Join-Path $work "client-libs") -Force

# JavaFX goes on the module path, so pick the platform jars from the local repo
# JavaFX ships as plain jars on the classpath: the platform jars carry the native
# libraries, which JavaFX extracts into java.io.tmpdir at startup.
$javafxVersion = "21"
$pomXml = New-Object System.Xml.XmlDocument
$pomXml.Load((Join-Path $root "pom.xml"))
if ($pomXml.project.properties.'javafx.version') {
    $javafxVersion = $pomXml.project.properties.'javafx.version'
}
$repo = Join-Path $env:USERPROFILE ".m2\repository\org\openjfx"
foreach ($module in @("javafx-base", "javafx-graphics", "javafx-controls", "javafx-media")) {
    $jar = Get-ChildItem (Join-Path $repo "$module\$javafxVersion") -Filter "*-win.jar" -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $jar) { throw "JavaFX jar not found for $module $javafxVersion (run 'mvn install' once with network)" }
    Copy-Item $jar.FullName (Join-Path $work "client-libs") -Force
}

Copy-Item $serverJar.FullName (Join-Path $work "server-libs") -Force

# ---------------------------------------------------------------- 4. package
Write-Step "jpackage: client"
$dist = Join-Path $root "dist"
Remove-Item $dist -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $dist | Out-Null

$jpackage = Join-Path $JdkPath "bin\jpackage.exe"
$icon = Join-Path $scriptDir "app.ico"

& $jpackage --type app-image `
    --dest $dist `
    --name "QuackChat" `
    --app-version $AppVersion `
    --vendor "Quack" `
    --description "Quack Chat - desktop instant messenger" `
    --input (Join-Path $work "client-libs") `
    --main-jar $clientJar.Name `
    --main-class "io.quack.client.Launcher" `
    --icon $icon `
    --java-options "-Dfile.encoding=UTF-8" `
    --java-options "-Djava.io.tmpdir=`$APPDIR\..\data\tmp" `
    --java-options "-Djavafx.cachedir=`$APPDIR\..\data\javafx-cache"
if ($LASTEXITCODE -ne 0) { throw "jpackage client failed" }

Write-Step "jpackage: server"
& $jpackage --type app-image `
    --dest $dist `
    --name "QuackChatServer" `
    --app-version $AppVersion `
    --vendor "Quack" `
    --description "Quack Chat server" `
    --input (Join-Path $work "server-libs") `
    --main-jar $serverJar.Name `
    --main-class "org.springframework.boot.loader.launch.JarLauncher" `
    --icon $icon `
    --win-console `
    --java-options "-Dfile.encoding=UTF-8" `
    --java-options "-Djava.io.tmpdir=`$APPDIR\..\data\tmp" `
    --java-options "-Djavafx.cachedir=`$APPDIR\..\data\javafx-cache"
if ($LASTEXITCODE -ne 0) { throw "jpackage server failed" }

# jpackage does not create runtime dirs for us: pre-create data/tmp so the JVM
# can start with a writable java.io.tmpdir (see the java-options above).
foreach ($app in @("QuackChat", "QuackChatServer")) {
    New-Item -ItemType Directory -Force -Path (Join-Path $dist "$app\data\tmp") | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $dist "$app\data\tomcat") | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $dist "$app\data\javafx-cache") | Out-Null
}

# ---------------------------------------------------------------- 5. convenience launcher
Write-Step "Write launcher script"

# ASCII content only: cmd reads .bat in the OEM code page.
$launcher = @"
@echo off
rem UTF-8 code page so the server console shows Chinese log lines correctly.
chcp 65001 >nul
title Quack Chat launcher
start "Quack Chat Server" "%~dp0QuackChatServer\QuackChatServer.exe"
timeout /t 3 /nobreak >nul
start "Quack Chat" "%~dp0QuackChat\QuackChat.exe"
"@
Set-Content -Path (Join-Path $dist "start-chat.bat") -Value $launcher -Encoding ASCII

# ---------------------------------------------------------------- 6. report
Write-Step "Done"
Get-ChildItem $dist -Directory | ForEach-Object {
    $exe = Get-ChildItem $_.FullName -Filter "*.exe" | Select-Object -First 1
    if ($exe) {
        Write-Host ("{0,-22} {1,8:N1} MB  {2}" -f $exe.Name, ((Get-ChildItem $_.FullName -Recurse -File | Measure-Object Length -Sum).Sum / 1MB), $_.FullName)
    }
}

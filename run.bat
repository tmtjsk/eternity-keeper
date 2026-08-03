@echo off

rem Eternity Keeper needs Java 8 (JCEF natives). Prefer the bundled JDK; fall
rem back to whatever java is on PATH only if the bundle is missing.
set "EK_JAVA=%~dp0..\tools\jdk8u492-b09\bin\java.exe"
if not exist "%EK_JAVA%" set "EK_JAVA=java"

"%EK_JAVA%" -jar -Djava.library.path=./lib/native/win64 target/eternity-0.21a.jar

@rem Gradle startup script for Windows

@if "%DEBUG%"=="" @echo off
@rem Set local scope for the variables
setlocal

@rem Execute Gradle
"%JAVA_HOME%\bin\java.exe" -classpath "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*

:end
@rem End local scope for the variables with windows NT shell
if "%ERRORLEVEL%"=="0" goto mainEnd
:fail
exit /b 1
:mainEnd
endlocal

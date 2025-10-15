@echo off
setlocal

:: ==========================================================
::  ISYGATE CLIENT - BUILD AUTOMATICO RELEASE
:: ==========================================================

echo.
echo ================================================
echo     ISYGATE CLIENT - BUILD RELEASE AUTOMATICA
echo ================================================
echo.

:: Chiedi versione nuova (es. 1.1.0)
set /p NEWVER=Inserisci la nuova versione (es. 1.1.0): 

echo.
echo [INFO] Aggiorno pom.xml alla versione %NEWVER%...

powershell -Command "(Get-Content pom.xml) -replace '<version>.*?</version>', ('<version>' + '%NEWVER%' + '</version>') | Set-Content pom.xml"

echo [OK] Versione aggiornata nel pom.xml
echo.

:: Pulizia e compilazione
echo [INFO] Compilazione progetto con Maven...
call mvn clean package -DskipTests
if errorlevel 1 (
  echo [ERRORE] Compilazione fallita.
  pause
  exit /b 1
)
echo [OK] Compilazione completata.
echo.

:: Crea il manifest app.xml
echo [INFO] Creazione file app.xml per FXLauncher...
call mvn exec:java -Dexec.mainClass=fxlauncher.CreateManifest -Dexec.args="https://github.com/albertfre/isygate-client-dist/releases/download/v%NEWVER%/ it.isygate.client.ClientApp target/app"
if errorlevel 1 (
  echo [ERRORE] Creazione app.xml fallita.
  pause
  exit /b 1
)
echo [OK] Manifest creato.
echo.

:: Rigenera fxlauncher con il nuovo manifest
echo [INFO] Inserisco app.xml dentro fxlauncher.jar...
call mvn verify -DskipTests
echo [OK] fxlauncher aggiornato.
echo.

:: Crea nuovo MSI
echo [INFO] Generazione pacchetto MSI...
call mvn clean package -DskipTests
echo [OK] MSI generato.
echo.

:: Mostra dove si trovano i file finali
echo ================================================
echo  BUILD COMPLETATA!
echo  -> Versione: %NEWVER%
echo  -> Cartella: target\app\
echo  -> File MSI: target\IsyGateClient-%NEWVER%.msi
echo ================================================

echo.
echo [INFO] Ora carica tutti i file di target\app\ su GitHub nella release v%NEWVER%.
echo [INFO] Aprendo la pagina delle release...
start https://github.com/albertfre/isygate-client-dist/releases/new?tag=v%NEWVER%&title=IsyGate+Client+v%NEWVER%

echo.
pause
endlocal

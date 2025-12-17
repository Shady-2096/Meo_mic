@echo off
echo Building Meo Mic for Windows...

REM Install dependencies
pip install -r requirements.txt
pip install pyinstaller

REM Clean previous builds
if exist "dist" rmdir /s /q dist
if exist "build" rmdir /s /q build

REM Build executable (using --onedir for FAST startup)
pyinstaller --onedir --windowed --name MeoMic ^
    --icon=icon.ico ^
    --add-data "meomic;meomic" ^
    --exclude-module matplotlib ^
    --exclude-module scipy ^
    --exclude-module pandas ^
    --exclude-module PIL ^
    --exclude-module tkinter.test ^
    main.py

echo.
echo Build complete!
echo.
echo The app is in: dist\MeoMic\MeoMic.exe
echo.
echo To distribute: Zip the entire "dist\MeoMic" folder
echo Users should extract and run MeoMic.exe from inside the folder
pause

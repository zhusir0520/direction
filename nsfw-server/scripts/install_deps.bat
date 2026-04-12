@echo off
echo Installing Python dependencies for YOLOv8s to ONNX conversion...
echo.

REM Check if Python is available
python --version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Python is not installed or not in PATH.
    echo Please install Python 3.8 or higher from https://www.python.org/
    pause
    exit /b 1
)

REM Check if pip is available
pip --version >nul 2>&1
if errorlevel 1 (
    echo ERROR: pip is not installed or not in PATH.
    echo Please ensure pip is installed with Python.
    pause
    exit /b 1
)

echo Python and pip are available.
echo.

REM Upgrade pip
echo Upgrading pip...
python -m pip install --upgrade pip
if errorlevel 1 (
    echo WARNING: Failed to upgrade pip, continuing...
)

REM Install dependencies
echo Installing dependencies from requirements.txt...
pip install -r requirements.txt
if errorlevel 1 (
    echo ERROR: Failed to install dependencies.
    echo You may need to install PyTorch separately.
    echo For CPU: pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cpu
    echo For CUDA 11.8: pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu118
    pause
    exit /b 1
)

echo.
echo Dependencies installed successfully!
echo You can now run the conversion script:
echo   python convert_yolov8s_to_onnx.py
echo.
pause
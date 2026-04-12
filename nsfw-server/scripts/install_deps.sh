#!/bin/bash

echo "Installing Python dependencies for YOLOv8s to ONNX conversion..."
echo ""

# Check if Python is available
if ! command -v python3 &> /dev/null; then
    echo "ERROR: Python3 is not installed or not in PATH."
    echo "Please install Python 3.8 or higher."
    exit 1
fi

# Check if pip is available
if ! command -v pip3 &> /dev/null; then
    echo "ERROR: pip3 is not installed or not in PATH."
    echo "Please ensure pip is installed with Python."
    exit 1
fi

echo "Python and pip are available."
echo ""

# Upgrade pip
echo "Upgrading pip..."
python3 -m pip install --upgrade pip
if [ $? -ne 0 ]; then
    echo "WARNING: Failed to upgrade pip, continuing..."
fi

# Install dependencies
echo "Installing dependencies from requirements.txt..."
pip3 install -r requirements.txt
if [ $? -ne 0 ]; then
    echo "ERROR: Failed to install dependencies."
    echo "You may need to install PyTorch separately."
    echo "For CPU: pip3 install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cpu"
    echo "For CUDA 11.8: pip3 install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu118"
    exit 1
fi

echo ""
echo "Dependencies installed successfully!"
echo "You can now run the conversion script:"
echo "  python3 convert_yolov8s_to_onnx.py"
echo ""
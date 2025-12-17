#!/bin/bash

# WifiMic PC Receiver startup script

# Check if Python is installed
if ! command -v python3 &> /dev/null; then
    echo "Python 3 is not installed."
    echo "Please install Python 3 from your package manager or https://python.org"
    exit 1
fi

# Check if dependencies are installed
if ! python3 -c "import sounddevice" 2>/dev/null; then
    echo "Installing dependencies..."
    pip3 install -r requirements.txt
    if [ $? -ne 0 ]; then
        echo "Failed to install dependencies."
        exit 1
    fi
fi

# Run the application
echo "Starting WifiMic..."
python3 main.py

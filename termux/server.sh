#!/data/data/com.termux/files/usr/bin/bash

ACTIVITY=pw.hexed.fingerprint/.SplashActivity
ADDRESS=127.0.0.1
PORT=10451

am start -n "$ACTIVITY" &> /dev/null

nc $ADDRESS -l -p $PORT | while read CODE; do
    [[ $CODE == "0" ]] && exit 0
    [[ $CODE != "0" ]] && exit $CODE
done
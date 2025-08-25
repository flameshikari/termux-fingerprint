#!/data/data/com.termux/files/usr/bin/bash

ACTIVITY=pw.hexed.fingerprint/.MainActivity
ADDRESS=127.0.0.1
PORT=10451

STATUS=false

am start -n $ACTIVITY &> /dev/null

while true; do
    nc $ADDRESS -l -p $PORT | while read LINE; do
        STATUS=$(echo $LINE | jq -e 'has("auth_result")' 2> /dev/null && true || false)
        [[ $STATUS ]] && (echo $LINE | jq -M; exit 0)
    done
    [[ $STATUS ]] && break
done
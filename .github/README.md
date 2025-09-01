# Termux Fingerprint

It's a simple app that solves the problem with using fingerprint scanning in Termux on Android 14. See related issues:

- https://github.com/termux/termux-api/issues/661
- https://github.com/aeolwyr/tergent/issues/20

<img src='./preview.gif' width="300"/>

## Setup

0. Download [the latest apk](https://github.com/flameshikari/termux-fingerprint/releases/latest) (or from [Google Play](https://play.google.com/store/apps/details?id=pw.hexed.fingerprint)) and install it on the phone.

1. Install dependencies in Termux:
    > nmap-based `nc` is also suitable, just change `netcat-openbsd` to `nmap`
    
    ```sh
    pkg install jq tergent netcat-openbsd
    ```

2. Generate key(s) if you don't have any:

    > specify the values ​​you want, see `termux-keystore` help

    ```sh
    termux-keystore generate keyname -a EC -s 256 -u 10
    ```
    or with RSA:
    ```sh
    termux-keystore generate keyname -a RSA -s 2048 -u 10
    ```

    then export public keys by `ssh-keygen -D $PREFIX/lib/libtergent.so` command to fill them on hosts' `authorized_keys`

3. Download the server script:

    ```sh
    TARGET=~/.ssh/bin/fingerprint
    mkdir -p ~/.ssh/bin
    curl -sL https://github.com/flameshikari/termux-fingerprint/raw/refs/heads/master/termux/.ssh/bin/fingerprint > "$TARGET"
    chmod +x "$TARGET"

4. Add a rule in the ssh config:

    ```sh
    Match host * exec ~/.ssh/bin/fingerprint
        PKCS11Provider /data/data/com.termux/files/usr/lib/libtergent.so
    ```

5. Try to connect to the host that has public key from your keystore.

## How It Works

1. You're making an SSH connection to any host that has a public key exported from your phone's hardware keystore
2. Before the connection `Match` keyword from the SSH config executes the script
3. The script starts a simple TCP server and opens the app, then the fingerprint scanner popups on the screen and waits for your finger (jesus christ)
4. After scanning, the app sends a JSON data to the TCP server based on the authentication result and closes itself, returning you to Termux
5. If the script returns 0, the SSH client continues the connection to the host using keys from unlocked keystore, else it fallbacks to password input if the host accepts it

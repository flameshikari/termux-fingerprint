# Termux Fingerprint

It's a simple app that solves the problem with using fingerprint scanning in Termux on Android 14.

See related issues:

- https://github.com/termux/termux-api/issues/661
- https://github.com/aeolwyr/tergent/issues/20

<img src='./preview.gif' width="300"/>

## Setup

0. Download [the latest apk](https://github.com/flameshikari/termux-fingerprint/releases/latest) and install it on the phone.

1. Install dependencies in Termux:
    ```sh
    pkg install jq tergent
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
    mkdir -p ~/.ssh/bin
    curl -sL https://raw.githubusercontent.com/flameshikari/termux-fingerprint/refs/heads/master/termux/server.sh > ~/.ssh/bin/fingerprint
    chmod +x ~/.ssh/bin/fingerprint
    ```

4. Add a rule in the ssh config:

    ```sh
    Host *
        PKCS11Provider /data/data/com.termux/files/usr/lib/libtergent.so
        Match exec $HOME/.ssh/bin/fingerprint
    ```

    or match only specific hosts:

    ```sh
    Host example.com
        PKCS11Provider /data/data/com.termux/files/usr/lib/libtergent.so
        Match exec $HOME/.ssh/bin/fingerprint
    ```

5. Try to connect to the host that has public key from your keystore.

## How It Works

1. Let's assume a ssh connetion to any host that has a public key from a hardware keystore
2. `match exec` from `.ssh/config` runs the script before connecting to the host
3. The script starts `pw.hexed.fingerprint/.SplashActivity` activity, switching from Termux to my fingerprint app; also it starts a server on `localhost:10451` and waits for a request
4. After scanning, the app sends a request to `localhost:10451`, the script closes if the request is successful, automatically switching back to Termux and continuing the connection to the host

> the pipeline is undone; there are plans to add more logic processing and error handling, but for now this is MVP





# Related

- https://github.com/termux/termux-api/issues/661
- https://github.com/aeolwyr/tergent/issues/20

# Commands

```bash
pkg install tergent
```

# Config

```ssh
Host *
    PKCS11Provider /data/data/com.termux/files/usr/lib/libtergent.so
    Match exec $HOME/.ssh/bin/fingerprint

```
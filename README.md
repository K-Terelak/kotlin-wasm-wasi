# Notes

This solution is based on the Kotlin/Wasm WASI template. It implements a simple stdin-driven loop that reads one byte at a time via WASI `fd_read`, builds lines in a `StringBuilder`, and prints `Wasm received: <line>` for each line. Newlines are detected on `\n` and `\r` is ignored to support CRLF inputs.

## Run

Use the custom `runWasm` task:

```zsh
./gradlew runWasm
```

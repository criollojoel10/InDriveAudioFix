# $(basename "$(pwd)")

Short description: consolidated repository following the OpenClaw project template.

## Project Structure

- README.md — this file
- CHANGELOG.md — project changelog (Keep a Changelog format)
- sysgit.py / sysup.py — repo maintenance automation
- src/ or modules/ — main source code

## Usage

1. Clone the repository:

   git clone git@github.com:criollojoel10/$(basename "$(pwd)").git

2. Use sysgit/sysup for maintenance:

   - python3 sysgit.py --auto   # standardize docs and push
   - python3 sysup.py          # (Nix repos) apply configuration and push

## File tree

<!-- TREE_START -->
```text
.
├── CAMBIOS.md
├── CHANGELOG.md
├── DOCUMENTACION_TECNICA.md
├── GUIA_RAPIDA.md
├── README.md
├── app
├── app-debug.apk
├── build.gradle.kts
├── gradle
├── gradle.properties
├── gradlew
├── gradlew.bat
├── settings.gradle.kts
├── sysgit.py
```
<!-- TREE_END -->


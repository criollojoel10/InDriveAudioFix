#!/usr/bin/env python3
import os
import subprocess
import sys
import json
import urllib.request
import urllib.error
import datetime
import time
import shutil

# --- CONFIGURACIÓN ---
GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY")

# Modelos disponibles en orden de prioridad
MODELS = [
    "gemini-2.0-flash",
    "gemini-1.5-flash",
    "gemini-1.5-pro"
]

IGNORE_DIRS = {'.git', '.gemini', 'result', '__pycache__', '.direnv', '.idea', '.vscode', 'node_modules', 'grafana_data', 'influxdb_data', 'mosquitto_data', 'nodered_data', '.platformio', '.gradle', 'build'}
IGNORE_FILES = {'.DS_Store', 'sysgit.py', 'flake.lock', 'LICENSE', 'sysup.py', '.gitignore', 'package-lock.json', 'pnpm-lock.yaml'}
README_PATH = "README.md"
CHANGELOG_PATH = "CHANGELOG.md"

# --- FUNCIONES BASE ---

def print_color(text, color="32"):
    """Imprime texto con color ANSI (32=verde, 31=rojo, 33=amarillo, 36=cyan)."""
    print(f"\033[1;{color}m{text}\033[0m")

def run_command(command, capture_output=False, input_text=None, check=True):
    """Ejecuta comando de shell."""
    try:
        result = subprocess.run(
            command, 
            check=check, 
            shell=True, 
            text=True, 
            capture_output=capture_output,
            input=input_text
        )
        return result.stdout.strip() if capture_output else True
    except subprocess.CalledProcessError as e:
        if not capture_output:
            print_color(f"❌ Error al ejecutar: {command}", "31")
        if check:
            sys.exit(e.returncode)
        return None

def call_gemini_api(prompt, task_description="IA Task"):
    """Llamada a Gemini API para generar mensajes de commit o documentación."""
    if not GEMINI_API_KEY:
        print_color("⚠️  Falta GEMINI_API_KEY. Saltando funciones de IA.", "33")
        return None

    headers = {"Content-Type": "application/json"}
    data = {"contents": [{"parts": [{"text": prompt}]}]}
    
    for model in MODELS:
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={GEMINI_API_KEY}"
        
        try:
            req = urllib.request.Request(url, data=json.dumps(data).encode('utf-8'), headers=headers)
            with urllib.request.urlopen(req) as response:
                result = json.loads(response.read().decode('utf-8'))
                if 'candidates' in result and result['candidates']:
                    return result['candidates'][0]['content']['parts'][0]['text'].strip()
        except urllib.error.HTTPError as e:
            if e.code == 429:
                time.sleep(1)
                continue
        except Exception:
            continue
            
    print_color("❌ Fallo en IA. Usando valores por defecto.", "31")
    return None

def get_ai_commit_message(diff):
    """Genera mensaje de commit semantic usando IA."""
    if not diff or not GEMINI_API_KEY: return None
        
    prompt = (
        "Genera un mensaje de commit semántico (Conventional Commits) para este 'git diff'.\n"
        "Formato: 'tipo(ámbito): descripción breve'.\n"
        "Ejemplo: 'feat(core): update nix configuration'.\n"
        "Solo devuelve el mensaje, nada más.\n"
        f"\nDIFF:\n{diff[:15000]}"
    )
    return call_gemini_api(prompt, "Generar Commit")

def update_changelog(message):
    """Actualiza CHANGELOG.md con la fecha de hoy."""
    today_str = datetime.datetime.now().strftime("[%Y-%m-%d]")
    entry = f"- {message}\n"
    
    if not os.path.exists(CHANGELOG_PATH):
        with open(CHANGELOG_PATH, 'w') as f:
            f.write(f"# Changelog\n\n## {today_str}\n{entry}")
        return

    with open(CHANGELOG_PATH, 'r') as f:
        lines = f.readlines()
    
    # Insertar bajo la fecha de hoy si existe, o crear nueva sección
    new_lines = []
    inserted = False
    header_found = False
    
    for line in lines:
        if line.strip().startswith(f"## {today_str}"):
            header_found = True
            new_lines.append(line)
            new_lines.append(entry)
            inserted = True
            continue
        new_lines.append(line)
        
    if not inserted:
        # Insertar después del título principal o al principio
        final_content = []
        for i, line in enumerate(lines):
            final_content.append(line)
            if line.startswith("# ") and not inserted:
                 final_content.append(f"\n## {today_str}\n{entry}")
                 inserted = True
        
        if not inserted:
             final_content = [f"# Changelog\n\n## {today_str}\n{entry}"] + lines
        
        with open(CHANGELOG_PATH, 'w') as f:
            f.writelines(final_content)
    else:
        with open(CHANGELOG_PATH, 'w') as f:
            f.writelines(new_lines)
            
    print_color(f"📝 CHANGELOG actualizado.", "36")

def generate_tree(startpath):
    """Genera árbol de directorios ignorando basura."""
    tree_str = ".\n"
    
    def walk(path, prefix=""):
        try:
            entries = sorted(os.listdir(path))
        except PermissionError:
            return
            
        entries = [e for e in entries if e not in IGNORE_DIRS and e not in IGNORE_FILES and not e.startswith('.')]
        
        total = len(entries)
        for i, entry in enumerate(entries):
            is_last = (i == total - 1)
            connector = "└── " if is_last else "├── "
            
            tree_str_local = f"{prefix}{connector}{entry}\n"
            nonlocal tree_str
            tree_str += tree_str_local
            
            full_path = os.path.join(path, entry)
            if os.path.isdir(full_path):
                extension = "    " if is_last else "│   "
                walk(full_path, prefix + extension)

    walk(startpath)
    return tree_str

def update_readme_tree():
    """Actualiza solo la sección del árbol en el README."""
    if not os.path.exists(README_PATH): return

    tree_content = generate_tree(".")
    with open(README_PATH, 'r') as f:
        content = f.read()
    
    if "<!-- TREE_START -->" in content and "<!-- TREE_END -->" in content:
        parts = content.split("<!-- TREE_START -->")
        header = parts[0]
        footer = content.split("<!-- TREE_END -->")[1]
        new_content = f"{header}<!-- TREE_START -->\n```text\n{tree_content}```\n<!-- TREE_END -->{footer}"
        
        with open(README_PATH, 'w') as f:
            f.write(new_content)
        print_color("🌳 README (Árbol) actualizado.", "36")

def main():
    print_color("🚀 SysGit Universal Manager", "35")
    
    # 1. Sync Cloud
    print_color("\n--- 1. Sincronización Cloud ---", "33")
    run_command("git pull", check=False)
    
    # 2. Add preliminar
    run_command("git add .")
    
    # 3. Detectar cambios
    diff = run_command("git diff --cached", capture_output=True)
    if not diff:
        print_color("✨ Todo limpio. Nada que commitear.", "32")
        return

    # 4. Mensaje de Commit (IA o Manual)
    print_color("\n--- 2. Generando Commit ---", "33")
    suggested_msg = get_ai_commit_message(diff)
    
    final_msg = ""
    if suggested_msg:
        print(f"Sugerencia AI: \033[1;36m{suggested_msg}\033[0m")
        if "--auto" in sys.argv:
            final_msg = suggested_msg
        else:
            opt = input("¿Usar? [Y/n/edit]: ").lower()
            if opt in ['', 'y']: final_msg = suggested_msg
            elif opt == 'edit': final_msg = input("Mensaje: ")
            else: 
                print_color("Cancelado.", "31")
                return
    else:
        final_msg = input("Mensaje de commit: ")

    if not final_msg: return

    # 5. Actualizar Docs (Changelog + Readme Tree)
    update_changelog(final_msg)
    update_readme_tree()
    run_command("git add .")
    
    # 6. Commit & Push
    run_command(f'git commit -m "{final_msg}"')
    
    if "--auto" in sys.argv:
        run_command("git push")
        print_color("✅ Push automático completado.", "32")
    else:
        if input("¿Push? [Y/n]: ").lower() in ['', 'y']:
            run_command("git push")
            print_color("✅ Push completado.", "32")

if __name__ == "__main__":
    main()

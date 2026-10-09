# Points the current shell (Git Bash, or Linux/macOS with a JDK unpacked in .tools/jdk-21) at the project-local JDK 21.
# Usage (from the repo root):  source scripts/env.sh
_mitpass_root="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
if [ -x "$_mitpass_root/.tools/jdk-21/bin/java" ] || [ -x "$_mitpass_root/.tools/jdk-21/bin/java.exe" ]; then
  export JAVA_HOME="$_mitpass_root/.tools/jdk-21"
  case ":$PATH:" in *":$JAVA_HOME/bin:"*) ;; *) export PATH="$JAVA_HOME/bin:$PATH" ;; esac
  echo "JAVA_HOME=$JAVA_HOME"
else
  echo "No JDK at $_mitpass_root/.tools/jdk-21. On Windows run: powershell -ExecutionPolicy Bypass -File scripts/setup-jdk.ps1" >&2
fi
unset _mitpass_root

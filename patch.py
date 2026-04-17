from pathlib import Path
path = Path(" src/main/java/com/mycompany/hu_b/service/ChatbotAntwoord.java\)
content = path.read_text()
lines = content.splitlines()
for idx, line in enumerate(lines):
    if line.strip().startswith('String finalSystemPrompt ='):

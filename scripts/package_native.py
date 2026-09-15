"""Package the signed app and a source archive without private build material."""
from pathlib import Path
import hashlib, shutil, zipfile

root = Path(__file__).resolve().parent.parent
dist = root / 'dist'
dist.mkdir(exist_ok=True)
apk = root / 'native/app/build/outputs/apk/release/app-release.apk'
assert apk.is_file(), 'Build the signed release APK first.'
shutil.copy2(apk, dist / 'Dragon-Farmer.apk')
shutil.copy2(root / 'START-HERE.txt', dist / 'Dragon-Farmer-Setup.txt')
excluded = {'build', '.gradle', 'signing', 'local.properties'}
files = [p for p in (root / 'native').rglob('*')
         if p.is_file() and not excluded.intersection(p.relative_to(root / 'native').parts)]
files += [root / 'README.md', root / 'START-HERE.txt', root / '.gitignore']
files += list((root / 'scripts').glob('*'))
archive = dist / 'Dragon-Farmer-Source.zip'
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as z:
    for p in sorted(files):
        if p.is_file(): z.write(p, p.relative_to(root).as_posix())
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None
    assert not any('/signing/' in n or n.endswith(('.jks', '.apk')) for n in z.namelist())
lines = []
for name in ('Dragon-Farmer.apk', 'Dragon-Farmer-Source.zip', 'Dragon-Farmer-Setup.txt'):
    p = dist / name
    lines.append(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + name)
    print(name, p.stat().st_size, 'bytes')
(dist / 'Dragon-Farmer.sha256.txt').write_text('\n'.join(lines) + '\n')

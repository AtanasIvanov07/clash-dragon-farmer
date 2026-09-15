"""Create a local update-signing key; never bundle its private material."""
from pathlib import Path
import json, secrets, subprocess
root=Path(__file__).resolve().parent.parent
paths=json.loads((root/'.toolchain/paths.json').read_text())
directory=root/'native/signing'
directory.mkdir(exist_ok=True)
key=directory/'farmer.jks'
password=directory/'password.txt'
if key.exists():
    if not password.exists(): raise RuntimeError('Existing key needs its original password file.')
    print('Using existing local signing key.')
else:
    if not password.exists(): password.write_text(secrets.token_urlsafe(36),encoding='utf-8')
    subprocess.run([str(Path(paths['jdk'])/'bin/keytool.exe'),'-genkeypair','-keystore',str(key),
        '-storetype','JKS','-storepass:file',str(password),'-keypass:file',str(password),
        '-alias','dragon-farmer','-keyalg','RSA','-keysize','3072','-validity','10000',
        '-dname','CN=Dragon Farmer Local Build, OU=Personal Tools, O=Local Development, C=BG'],check=True)
    print('Created local signing key. Private material is excluded from deliverables.')

"""Download verified, portable build tools. Does not change system settings."""
from pathlib import Path
from urllib.request import Request, urlopen
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent / '.toolchain'
ROOT.mkdir(exist_ok=True)

def get(url):
    return urlopen(Request(url, headers={'User-Agent': 'S25MacroBuild/1.0'}), timeout=120)

def download(name, url, digest, algorithm, destination):
    archive = ROOT / (name + '.zip')
    def valid():
        return archive.exists() and hashlib.new(algorithm, archive.read_bytes()).hexdigest() == digest
    if not valid():
        print('Downloading ' + name, flush=True)
        with get(url) as response, archive.open('wb') as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
    if not valid():
        raise RuntimeError(name + ': checksum mismatch')
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        for entry in z.infolist():
            if not (destination / entry.filename).resolve().is_relative_to(destination.resolve()):
                raise RuntimeError('Invalid archive path')
        z.extractall(destination)
    print(name + ' verified and extracted', flush=True)

assets = json.load(get('https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows'))
jdk = assets[0]['binary']['package']
gradle_sha = get('https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256').read().decode().strip()
repo = ET.fromstring(get('https://dl.google.com/android/repository/repository2-3.xml').read())
sdk = None
for package in repo.findall('remotePackage'):
    if package.attrib.get('path') == 'cmdline-tools;latest':
        for archive in package.findall('./archives/archive'):
            if archive.findtext('host-os') == 'windows':
                sdk = archive.find('complete')
if sdk is None:
    raise RuntimeError('Windows SDK command tools not found')
tasks = [
    ('jdk', jdk['link'], jdk['checksum'], 'sha256', ROOT / 'jdk'),
    ('gradle', 'https://services.gradle.org/distributions/gradle-8.13-bin.zip', gradle_sha, 'sha256', ROOT),
    ('android-tools', 'https://dl.google.com/android/repository/' + sdk.findtext('url'), sdk.findtext('checksum'), 'sha1', ROOT / 'android-bootstrap'),
]
with ThreadPoolExecutor(max_workers=3) as pool:
    list(pool.map(lambda args: download(*args), tasks))
metadata = {'jdk': str(next((ROOT / 'jdk').glob('jdk-*'))), 'gradle': str(ROOT / 'gradle-8.13'),
            'sdk': str(ROOT / 'android-sdk'), 'sdkmanager': str(ROOT / 'android-bootstrap/cmdline-tools/bin/sdkmanager.bat')}
(ROOT / 'paths.json').write_text(json.dumps(metadata, indent=2))
print(json.dumps(metadata, indent=2), flush=True)

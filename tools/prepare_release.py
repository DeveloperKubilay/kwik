import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import xml.etree.ElementTree as xml
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
version = re.search(r"(?m)^version = '([^']+)'$", (root / 'buildSrc/src/main/groovy/buildlogic.java-common-conventions.gradle').read_text()).group(1)
revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
if subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=no'], cwd=root, text=True).strip():
    raise SystemExit('Release sources must match the committed revision')
if os.environ.get('GITHUB_SHA', revision) != revision:
    raise SystemExit('Build revision differs from the workflow revision')
if os.environ.get('GITHUB_REF', '').startswith('refs/tags/') and os.environ['GITHUB_REF_NAME'] != 'v' + version:
    raise SystemExit('Release tag differs from the library version')
jar = root / f'core/build/libs/kwik-{version}.jar'
sources = root / f'core/build/libs/kwik-{version}-sources.jar'
for path in (jar, sources):
    with zipfile.ZipFile(path) as archive:
        if archive.testzip() is not None:
            raise SystemExit(f'Invalid archive: {path.name}')
with zipfile.ZipFile(jar) as archive:
    if archive.read('tech/kwik/core/version.properties').decode().strip() != version:
        raise SystemExit('Embedded library version differs')
pom = root / 'core/build/publications/maven/pom-default.xml'
namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}
if xml.parse(pom).getroot().findtext('m:version', namespaces=namespace) != version:
    raise SystemExit('POM version differs')
destination = root / 'dist'
destination.mkdir(exist_ok=True)
files = {jar: jar.name, sources: sources.name, pom: f'kwik-{version}.pom'}
files.update({root / name: name for name in ('LICENSE.txt', 'LICENSE-LESSER.txt', 'SAFRA.md')})
for source, name in files.items():
    shutil.copy2(source, destination / name)
archive_name = f'kwik-{version}-full-source.zip'
subprocess.run(['git', 'archive', '--format=zip', f'--prefix=kwik-{version}/', f'--output={destination / archive_name}', revision], cwd=root, check=True)
metadata = {'version': version, 'commit': revision, 'upstream': 'ptrd/kwik', 'upstreamCommit': '4d5d40f540d70a1b50ea59651f9d36740e77f165', 'repository': 'https://github.com/DeveloperKubilay/kwik'}
(destination / 'build-info.json').write_text(json.dumps(metadata, indent=2) + '\n', encoding='utf-8')
notes = f'''Safra maintenance build of Kwik {version}.

Source commit: `{revision}`. Upstream base: `4d5d40f540d70a1b50ea59651f9d36740e77f165`.

Adds bounded receive-window auto-tuning, connection-credit retransmission and a stream send-wakeup race fix. Does not add BBR, packet pacing, an application jitter buffer or path MTU discovery. Requires Safra integration to enable auto-tuning.

Release publication requires the core test suite to pass on Linux and Windows. The source archive, source JAR, POM, license texts and SHA-256 checksums accompany the library. This prerelease does not claim compatibility with every network or Minecraft setup.
'''
(destination / 'RELEASE-NOTES.md').write_text(notes, encoding='utf-8')
names = sorted(set(files.values()) | {archive_name, 'build-info.json', 'RELEASE-NOTES.md'})
checksums = ''.join(f'{hashlib.sha256((destination / name).read_bytes()).hexdigest()}  {name}\n' for name in names)
(destination / 'SHA256SUMS').write_text(checksums, encoding='utf-8')
print(f'Prepared Kwik {version} from {revision}')

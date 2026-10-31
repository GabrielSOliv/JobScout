"""Generate license evidence from a real Gradle inventory. Standard library only.

Usage: python scripts/generate-notices.py PATH/TO/build/license-audit/inventory.json
Run Gradle with -I scripts/dependency-inventory.init.gradle first.
This does not infer permissions to any job board or select the project's license.
"""
from pathlib import Path
import gzip
import hashlib
import io
import json
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[1]
data = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8-sig'))
cache = Path(data['cache'])
assets = root / 'app/src/main/assets/licenses'
assets.mkdir(parents=True, exist_ok=True)
third = root / 'third_party'
third.mkdir(exist_ok=True)
artifacts = {a['coordinate']: Path(a['file']) for a in data['artifacts']}
rows = []
components = []
def declared_licenses(group, name, version, visited=None):
    visited = set() if visited is None else visited
    key = (group, name, version)
    if key in visited:
        return []
    visited.add(key)
    paths = sorted((cache / group / name / version).glob('*/*.pom'))
    if not paths:
        raise SystemExit(f'Missing POM: {group}:{name}:{version}')
    doc = ET.parse(paths[0]).getroot()
    for node in doc.iter():
        node.tag = node.tag.rsplit('}', 1)[-1]
    result = [{'name': e.findtext('name', ''), 'url': e.findtext('url', '')}
              for e in doc.findall('licenses/license')]
    if not result:
        parent = doc.find('parent')
        if parent is not None:
            values = [parent.findtext(field) for field in ('groupId', 'artifactId', 'version')]
            if all(values) and not any('${' in v for v in values):
                return declared_licenses(*values, visited)
    return result

for component in data['components']:
    group, name, version = (component[k] for k in ('group', 'name', 'version'))
    coordinate = f'{group}:{name}:{version}'
    licenses = declared_licenses(group, name, version)
    if not licenses:
        raise SystemExit(f'License declaration needs review: {coordinate}')
    artifact = artifacts.get(coordinate)
    notice_files = []
    if artifact and zipfile.is_zipfile(artifact):
        with zipfile.ZipFile(artifact) as archive:
            archives = [archive]
            if 'classes.jar' in archive.namelist():
                archives.append(zipfile.ZipFile(io.BytesIO(archive.read('classes.jar'))))
            for current in archives:
                for info in current.infolist():
                    base = Path(info.filename).name.lower()
                    if (not info.is_dir() and not base.endswith('.class') and info.file_size < 2_000_000
                            and (base.startswith(('license', 'notice', 'copying')) or base.endswith('.license'))):
                        raw = current.read(info)
                        if b'\x00' in raw:
                            continue
                        filename = 'dependency-' + hashlib.sha256(raw).hexdigest()[:16] + '.txt'
                        (assets / filename).write_bytes(raw)
                        notice_files.append(filename)
            for current in archives[1:]:
                current.close()
            resource = 'okhttp3/internal/publicsuffix/publicsuffixes.gz'
            if resource in archive.namelist():
                raw = gzip.decompress(archive.read(resource))
                size = struct.unpack('>I', raw[:4])[0]
                rules = raw[4:4+size]
                offset = 4 + size
                exception_size = struct.unpack('>I', raw[offset:offset+4])[0]
                exceptions = raw[offset+4:offset+4+exception_size]
                assert len(raw) == offset + 4 + exception_size
                text = ('// This Source Code Form is subject to the terms of the Mozilla Public\n'
                        '// License, v. 2.0. https://mozilla.org/MPL/2.0/\n'
                        f'// Rule source form recovered unchanged from {coordinate}.\n'
                        '// Original list comments are not stored in the compiled resource.\n'
                        '// Upstream: https://publicsuffix.org/list/\n\n')
                text += rules.decode('utf-8')
                text += ''.join('!' + line + '\n' for line in exceptions.decode('utf-8').splitlines())
                (third / 'public_suffix_list.dat').write_text(text, encoding='utf-8')
                # Android recipients can also access the corresponding rule source locally.
                (assets / 'Public-Suffix-List-source.txt').write_text(text, encoding='utf-8')
    rows.append((coordinate, licenses, sorted(set(notice_files))))
    entry = {**component, 'licenses': licenses, 'artifactSha256':
             hashlib.sha256(artifact.read_bytes()).hexdigest() if artifact else None}
    components.append(entry)

lines = ['# Third-party notices', '',
         'Generated from the resolved releaseRuntimeClasspath, not a list of all files shipped in the APK.',
         'Platform/metadata components may not have a binary artifact. Original licenses remain applicable.',
         'Complete extracted notices and canonical license texts are in app/src/main/assets/licenses/.',
         'See NOTICE for Freehire adaptations, Poppins, FAANGPath and IBGE attribution.', '',
         'The Public Suffix List resource carried by OkHttp is MPL-2.0; corresponding rule source is',
         'provided in third_party/public_suffix_list.dat and in the app license assets.',
         'This does not relicense the rest of JobScout under MPL.', '']
for coordinate, licenses, notice_files in rows:
    lines += [f'## {coordinate}', '',
              '; '.join(f"{x['name']} — {x['url']}" for x in licenses),
              'Extracted notices: ' + (', '.join(notice_files) or 'none included in the resolved artifact; see declared license and upstream source'), '']
(root / 'THIRD_PARTY_NOTICES.md').write_text('\n'.join(lines), encoding='utf-8')
(assets / 'Dependency-catalog.txt').write_text('\n'.join(lines), encoding='utf-8')
(assets / 'Project-NOTICE.txt').write_text((root / 'NOTICE').read_text(encoding='utf-8'), encoding='utf-8')
(assets / 'Apache-2.0.txt').write_text((root / 'LICENSE').read_text(encoding='utf-8'), encoding='utf-8')
validation = root / 'docs/validation'
validation.mkdir(parents=True, exist_ok=True)
(validation / 'dependencies.json').write_text(json.dumps(components, indent=2, ensure_ascii=False), encoding='utf-8')
sbom = {'bomFormat': 'CycloneDX', 'specVersion': '1.6', 'version': 1,
        'metadata': {'component': {'type': 'application', 'name': 'JobScout Community', 'version': '0.10.0-public'}},
        'components': [{'type': 'library', 'group': c['group'], 'name': c['name'], 'version': c['version'], 'purl': c['purl']}
                       for c in components]}
(validation / 'runtime.cdx.json').write_text(json.dumps(sbom, indent=2), encoding='utf-8')
notice_count = len({filename for _, _, names in rows for filename in names})
print(f'Generated notices for {len(components)} resolved components; {notice_count} distinct embedded notice texts.')

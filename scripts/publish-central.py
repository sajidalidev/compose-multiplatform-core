#!/usr/bin/env python3
"""Upload a validated bundle to Central and wait until it is published.

CENTRAL_TOKEN is base64(username:password) from Central's generated user token.
Authentication is sent to curl on stdin, never as an argument. Upload is never retried:
a timeout can mean Central accepted it; use --deployment-id to resume polling.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.parse
import uuid
import zipfile

API = 'https://central.sonatype.com/api/v1/publisher'


def validate_bundle(path):
    with zipfile.ZipFile(path) as bundle:
        names = bundle.namelist()
        files = {n for n in names if not n.endswith('/')}
        if len(names) != len(set(names)) or not any(n.endswith('.pom') for n in files):
            raise ValueError('Bundle is empty or has duplicate entries')
        for name in files:
            if not name.startswith('dev/sajidali/') or '..' in name.split('/'):
                raise ValueError('Unexpected bundle path: ' + name)
            if name.endswith(('.asc', '.md5', '.sha1')):
                continue
            for ext in ('.asc', '.md5', '.sha1'):
                if name + ext not in files:
                    raise ValueError('Missing companion: ' + name + ext)
            if not bundle.read(name + '.asc').startswith(b'-----BEGIN PGP SIGNATURE-----'):
                raise ValueError('Invalid signature format: ' + name)
            for algo in ('md5', 'sha1'):
                if bundle.read(name + '.' + algo).decode().strip() != hashlib.new(algo, bundle.read(name)).hexdigest():
                    raise ValueError('Checksum mismatch: ' + name)


def request(endpoint, token, extra=()):
    if any(c in token for c in '\r\n"\\'):
        raise ValueError('Malformed CENTRAL_TOKEN')
    result = subprocess.run(
        ['curl', '--silent', '--show-error', '--fail-with-body', '--connect-timeout', '30',
         '--max-time', '600', '--config', '-', '--request', 'POST', *extra, API + endpoint],
        input='header = "Authorization: Bearer ' + token + '"\n', text=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    if result.returncode:
        raise RuntimeError('Central request failed; check the Portal before retrying an upload. ' + result.stderr)
    return result.stdout.strip()


def wait_for_publish(deployment_id, token, timeout, report, request_fn=request, sleep=time.sleep):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        status = json.loads(request_fn('/status?id=' + deployment_id, token))
        report.write_text(json.dumps(status, indent=2) + '\n')
        state = status.get('deploymentState')
        print('Central deployment ' + deployment_id + ': ' + str(state), flush=True)
        if state == 'PUBLISHED':
            return
        if state == 'FAILED':
            raise RuntimeError('Central validation failed: ' + json.dumps(status.get('errors', {})))
        if state not in ('PENDING', 'VALIDATING', 'VALIDATED', 'PUBLISHING'):
            raise RuntimeError('Unexpected Central state: ' + str(state))
        sleep(15)
    raise TimeoutError('Central is still processing. Resume with --deployment-id ' + deployment_id)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--bundle', type=Path)
    source.add_argument('--deployment-id')
    parser.add_argument('--name', default='compose-tvos')
    parser.add_argument('--timeout', type=int, default=3600)
    parser.add_argument('--report-dir', type=Path, default=Path('build/release'))
    args = parser.parse_args()
    if os.environ.get('CI', 'false') != 'false' or os.environ.get('GITHUB_ACTIONS'):
        parser.error('Maven Central publishing is manual only; run outside CI')
    token = os.environ.get('CENTRAL_TOKEN')
    if not token:
        parser.error('CENTRAL_TOKEN is required')
    args.report_dir.mkdir(parents=True, exist_ok=True)
    if args.bundle:
        validate_bundle(args.bundle)
        query = urllib.parse.urlencode({'name': args.name, 'publishingType': 'AUTOMATIC'})
        deployment_id = request('/upload?' + query, token,
                                ['--form', 'bundle=@' + str(args.bundle.resolve())])
    else:
        deployment_id = args.deployment_id
    deployment_id = str(uuid.UUID(deployment_id))
    (args.report_dir / 'deployment-id.txt').write_text(deployment_id + '\n')
    print('Central deployment: ' + deployment_id, flush=True)
    wait_for_publish(deployment_id, token, args.timeout, args.report_dir / 'central-status.json')


if __name__ == '__main__':
    main()

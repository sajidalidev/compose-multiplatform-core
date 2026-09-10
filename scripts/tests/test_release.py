import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPTS = Path(__file__).resolve().parents[1]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


central = load('central', 'publish-central.py')


class CentralTests(unittest.TestCase):
    def test_validate_bundle_then_reject_tampering(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / 'repo'
            artifact = root / 'dev/sajidali/compose/ui/ui/1.0/ui-1.0.pom'
            artifact.parent.mkdir(parents=True)
            artifact.write_text('<project/>')
            Path(str(artifact) + '.asc').write_text('-----BEGIN PGP SIGNATURE-----\ntest')
            for algorithm in ('md5', 'sha1'):
                Path(str(artifact) + '.' + algorithm).write_text(hashlib.new(algorithm, artifact.read_bytes()).hexdigest())
            self.assertEqual(Path(str(artifact) + '.sha1').read_text().strip(), hashlib.sha1(artifact.read_bytes()).hexdigest())
            def pack():
                archive = Path(tmp) / 'bundle.zip'
                with zipfile.ZipFile(archive, 'w') as z:
                    for f in root.rglob('*'):
                        if f.is_file():
                            z.write(f, f.relative_to(root))
                return archive
            central.validate_bundle(pack())
            artifact.write_text('modified after signing')
            with self.assertRaisesRegex(ValueError, 'Checksum mismatch'):
                central.validate_bundle(pack())

    def test_missing_signature_prevents_upload(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive = Path(tmp) / 'bundle.zip'
            with zipfile.ZipFile(archive, 'w') as z:
                z.writestr('dev/sajidali/a/1/a-1.pom', '<project/>')
            with self.assertRaisesRegex(ValueError, 'Missing companion'):
                central.validate_bundle(archive)

    def test_waits_for_published_not_just_validated(self):
        states = iter(['VALIDATED', 'PUBLISHING', 'PUBLISHED'])
        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp) / 'status.json'
            central.wait_for_publish('id', 'token', 30, report,
                request_fn=lambda *args: json.dumps({'deploymentState': next(states)}),
                sleep=lambda seconds: None)
            self.assertEqual(json.loads(report.read_text())['deploymentState'], 'PUBLISHED')

    def test_failed_deployment_is_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(RuntimeError, 'validation failed'):
                central.wait_for_publish('id', 'token', 30, Path(tmp) / 'status.json',
                    request_fn=lambda *args: '{"deploymentState":"FAILED","errors":{"pom":"invalid"}}')

    def test_credentials_only_go_to_stdin(self):
        with patch.object(central.subprocess, 'run') as run:
            run.return_value = subprocess.CompletedProcess([], 0, stdout='ok', stderr='')
            self.assertEqual(central.request('/status?id=id', 'secret-token'), 'ok')
            command = run.call_args.args[0]
            self.assertNotIn('secret-token', ' '.join(command))
            self.assertIn('secret-token', run.call_args.kwargs['input'])
            self.assertNotIn('--retry', command)

    def test_central_entrypoints_refuse_ci(self):
        import os
        env = dict(os.environ, CI='true')
        for args in ([ 'bash', str(SCRIPTS / 'release-central.sh')],
                     ['python3', str(SCRIPTS / 'publish-central.py'), '--bundle', 'unused.zip']):
            result = subprocess.run(args, env=env, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('manual only', result.stderr)


class ReposiliteTests(unittest.TestCase):
    def test_reposilite_refuses_ci(self):
        import os
        for variable in ('CI', 'GITHUB_ACTIONS'):
            result = subprocess.run(['bash', str(SCRIPTS / 'publish-tvos-fork-reposilite.sh'), '--local-only', '--dry-run'],
                                    env=dict(os.environ, **{variable: 'true'}), capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('manual only', result.stderr)

    def test_dry_run_needs_no_credentials_and_makes_no_network_calls(self):
        import os
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            (tmp / 'bin').mkdir()
            java = tmp / 'bin/java'
            java.write_text('#!/bin/sh\necho \'openjdk version "21"\' >&2\n')
            java.chmod(0o755)
            curl = tmp / 'bin/curl'
            curl.write_text('#!/bin/sh\necho UNEXPECTED_NETWORK >&2\nexit 99\n')
            curl.chmod(0o755)
            env = {k: v for k, v in os.environ.items() if not k.startswith('REPOSILITE_')}
            env.update(ANDROIDX_JDK21=str(tmp), JAVA_HOME=str(tmp), DEV_SUFFIX='-dev.20260910.123.1', TVOS_MAVEN_LOCAL=str(tmp / 'ci-maven'), PATH=str(tmp / 'bin') + ':' + env['PATH'])
            result = subprocess.run(['bash', str(SCRIPTS / 'publish-tvos-fork-reposilite.sh'), '--local-only', '--dry-run'], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertNotIn('UNEXPECTED_NETWORK', result.stderr)
            self.assertNotIn('publishComposeJbToRemote', result.stdout)
            self.assertIn('-dev.20260910.123.1', result.stdout)
            self.assertIn('-Dmaven.repo.local=' + str(tmp / 'ci-maven'), result.stdout)
            self.assertIn('--repo-root ' + str(tmp / 'ci-maven'), result.stdout)


if __name__ == '__main__':
    unittest.main()

"""CI only: hash one exact GitHub artifact archive before isolated extraction."""
import argparse
import hashlib
import os
from pathlib import Path, PurePosixPath
import re
import ssl
import stat
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

# Retry the whole download, never a partially verified archive. In particular,
# authentication, redirect policy, digest and member validation are not transient.
RETRY_DELAYS = (1, 3)
TRANSIENT_HTTP = frozenset((429, 500, 502, 503, 504))

class ArtifactError(RuntimeError):
    """A diagnostic code selected by this module, never remote exception text."""
    def __init__(self, code):
        super().__init__(code)
        self.code = code

class DownloadError(ArtifactError):
    def __init__(self, code, transient):
        super().__init__(code)
        self.transient = transient
        self.attempts = 0

def download_error(stage, error):
    if isinstance(error, urllib.error.HTTPError):
        status = error.code if type(error.code) is int else 0
        return DownloadError(f'{stage}-http-{status}', status in TRANSIENT_HTTP)
    reason = error.reason if isinstance(error, urllib.error.URLError) else error
    if isinstance(reason, TimeoutError):
        return DownloadError(f'{stage}-timeout', True)
    if isinstance(reason, ssl.SSLCertVerificationError):
        return DownloadError(f'{stage}-tls-verification-failed', False)
    return DownloadError(f'{stage}-transport-failed', False)

def transport(operation, stage):
    try:
        return operation()
    except ArtifactError:
        raise
    except Exception as error:
        if isinstance(error, urllib.error.HTTPError):
            error.close()
        raise download_error(stage, error) from None

def storage_url(location, code):
    try:
        parsed = urllib.parse.urlparse(location)
        safe = parsed.scheme == 'https' and parsed.hostname and not parsed.username and not parsed.password
    except ValueError:
        safe = False
    if not safe:
        raise ArtifactError(code)
    return location

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

class HttpsOnly(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        storage_url(newurl, 'storage-redirect-unsafe')
        return super().redirect_request(req, fp, code, msg, headers, newurl)

def extract_verified(archive, expected, root, digest):
    root = Path(root).resolve()
    if root.exists() and any(root.iterdir()):
        raise ArtifactError('destination-not-empty')
    if 'sha256:' + digest.hexdigest() != expected:
        raise ArtifactError('archive-digest-mismatch')
    with zipfile.ZipFile(archive) as package:
        seen = set()
        files = set()
        directories = set()
        for member in package.infolist():
            name = member.filename
            path = PurePosixPath(name)
            raw_parts = name.rstrip('/').split('/')
            if not name or '\\' in name or path.is_absolute() or any(part in ('', '.', '..') or re.search(r'[\x00-\x1f<>:"|?*]', part) or part.endswith((' ', '.')) or re.fullmatch(r'(?i)(con|prn|aux|nul|com[1-9\u00b9\u00b2\u00b3]|lpt[1-9\u00b9\u00b2\u00b3])(\..*)?', part) for part in raw_parts):
                raise ArtifactError('archive-member-path-unsafe')
            if stat.S_ISLNK(member.external_attr >> 16):
                raise ArtifactError('archive-member-link')
            key = name.rstrip('/').casefold()
            if key in seen:
                raise ArtifactError('archive-member-duplicate')
            seen.add(key)
            parts = key.split('/')
            for index in range(1, len(parts)):
                directories.add('/'.join(parts[:index]))
            if member.is_dir():
                directories.add(key)
            else:
                files.add(key)
            if not root.joinpath(*path.parts).resolve().is_relative_to(root):
                raise ArtifactError('archive-member-escapes-destination')
        if files & directories:
            raise ArtifactError('archive-member-prefix-collision')
        package.extractall(root)

def artifact_location(api, token):
    request = urllib.request.Request(api, headers={'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28'})
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=60):
            pass
    except urllib.error.HTTPError as response:
        try:
            if response.code != 302:
                raise download_error('api', response) from None
            return storage_url(response.headers.get('Location', ''), 'api-redirect-unsafe')
        finally:
            response.close()
    except Exception as error:
        raise download_error('api', error) from None
    raise ArtifactError('api-redirect-missing')

def download_verified(artifact_id, expected, root):
    if os.environ.get('GITHUB_ACTIONS') != 'true' or not re.fullmatch(r'[1-9][0-9]*', artifact_id) or not re.fullmatch(r'sha256:[0-9a-f]{64}', expected):
        raise ArtifactError('request-invalid')
    repository = os.environ.get('GITHUB_REPOSITORY', '')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ArtifactError('request-repository-invalid')
    token = os.environ.get('GH_TOKEN', '')
    if not token:
        raise ArtifactError('request-credential-missing')
    api = f'https://api.github.com/repos/{repository}/actions/artifacts/{artifact_id}/zip'
    for attempt in range(1, len(RETRY_DELAYS) + 2):
        try:
            # A new signed URL, stream, file and hash on every attempt. The
            # temporary directory is removed before any retry or extraction error.
            with tempfile.TemporaryDirectory(prefix='surface-artifact-') as temp:
                location = artifact_location(api, token)
                archive = Path(temp) / 'artifact.zip'
                digest = hashlib.sha256()
                opener = urllib.request.build_opener(HttpsOnly)
                # The independent storage opener receives only the URL, without
                # the API's Authorization header, including on storage redirects.
                with transport(lambda: opener.open(location, timeout=120), 'storage') as response, archive.open('wb') as output:
                    while chunk := transport(lambda: response.read(1024 * 1024), 'storage'):
                        digest.update(chunk)
                        output.write(chunk)
                extract_verified(archive, expected, root, digest)
            return
        except DownloadError as error:
            error.attempts = attempt
            if not error.transient or attempt > len(RETRY_DELAYS):
                raise
            print(f'SURFACE_ARCHIVE_RETRY id={artifact_id} attempt={attempt} reason={error.code}', file=sys.stderr, flush=True)
            time.sleep(RETRY_DELAYS[attempt - 1])

def failure_code(error):
    if isinstance(error, ArtifactError):
        return error.code
    if isinstance(error, zipfile.BadZipFile):
        return 'archive-invalid'
    if isinstance(error, NotImplementedError):
        return 'archive-compression-unsupported'
    if isinstance(error, PermissionError):
        return 'filesystem-permission-denied'
    if isinstance(error, FileNotFoundError):
        return 'filesystem-file-missing'
    if isinstance(error, OSError):
        return 'filesystem-failed'
    return 'unexpected-error'

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--id', required=True)
    parser.add_argument('--digest', required=True)
    parser.add_argument('--destination', required=True, type=Path)
    a = parser.parse_args()
    try:
        root = a.destination.resolve()
        download_verified(a.id, a.digest, root)
    except Exception as error:
        # Never include remote exception text, signed URLs, tokens or ZIP names.
        display_id = a.id if re.fullmatch(r'[1-9][0-9]*', a.id) else 'invalid'
        attempts = f' attempts={error.attempts}' if isinstance(error, DownloadError) else ''
        raise RuntimeError(f'Artifact {display_id} verification/extraction failed (reason={failure_code(error)}{attempts})') from None
    print(f'SURFACE_VERIFIED_ARCHIVE id={a.id} digest={a.digest} destination={root}', flush=True)


if __name__ == "__main__":
    main()

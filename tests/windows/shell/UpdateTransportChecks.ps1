param([string] $UpdaterPath = (Join-Path $PSScriptRoot '../../../tools/package/gesher-update.ps1'))
$ErrorActionPreference = 'Stop'
. $UpdaterPath
Initialize-GesherUpdateTransport
# Exercise the actual compiled HTTP request/redirect code without network access.
if (-not ('GesherUpdateTests.HttpFixture' -as [type])) {
    $compiler = @{ ErrorAction = 'Stop' }
    if ($PSVersionTable.PSEdition -eq 'Desktop') { $compiler.ReferencedAssemblies = @('System.Net.Http') }
    Add-Type @compiler -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
namespace GesherUpdateTests {
    public sealed class HttpFixture : HttpMessageHandler {
        public readonly Queue<HttpResponseMessage> Responses = new Queue<HttpResponseMessage>();
        public readonly List<string> Requests = new List<string>();
        public string Accept;
        public string ApiVersion;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token) {
            token.ThrowIfCancellationRequested();
            Requests.Add(request.RequestUri.AbsoluteUri);
            Accept = request.Headers.Accept.ToString();
            IEnumerable<string> values;
            if (request.Headers.TryGetValues("X-GitHub-Api-Version", out values))
                foreach (string value in values) ApiVersion = value;
            if (Responses.Count == 0) throw new InvalidOperationException("Unexpected extra HTTP request");
            return Task.FromResult(Responses.Dequeue());
        }
        protected override void Dispose(bool disposing) {
            if (disposing) while (Responses.Count > 0) Responses.Dequeue().Dispose();
            base.Dispose(disposing);
        }
    }
}
'@
}
$script:Checks = 0
function Assert-Transport([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw $Message }
    $script:Checks++
}
function Assert-TransportThrows([scriptblock] $Action, [string] $Message) {
    $threw = $false
    try { & $Action | Out-Null } catch { $threw = $true }
    Assert-Transport $threw $Message
}
$send = [Gesher.Updates.Transport].GetMethod('Send', [Reflection.BindingFlags]'NonPublic,Static')
if (-not $send) { throw 'Cannot inspect production transport request boundary' }
$latest = [uri]'https://api.github.com/repos/gesher-runtime/gesher/releases/latest'
$asset = [uri]'https://github.com/gesher-runtime/gesher/releases/download/v0.2.0/gesher-setup-0.2.0.exe'
function New-HttpFixture {
    $handler = New-Object GesherUpdateTests.HttpFixture
    [PSCustomObject]@{ Handler = $handler; Client = [Net.Http.HttpClient]::new($handler) }
}
function Add-HttpResponse($Fixture, [int] $Status = 200, [string] $Location = '') {
    $response = [Net.Http.HttpResponseMessage]::new([Net.HttpStatusCode] $Status)
    if ($Location) { $response.Headers.Location = [uri] $Location }
    $Fixture.Handler.Responses.Enqueue($response)
}
function Invoke-FixtureSend($Fixture, [uri] $Uri, [bool] $Metadata,
    [Threading.CancellationToken] $Token = [Threading.CancellationToken]::None) {
    $send.Invoke($null, [object[]] @($Fixture.Client, $Uri, $Metadata, $Token))
}
foreach ($status in 200, 404, 403, 429, 500) {
    $fixture = New-HttpFixture
    try {
        Add-HttpResponse $fixture $status
        $response = Invoke-FixtureSend $fixture $latest $true
        try {
            Assert-Transport ([int] $response.StatusCode -eq $status) 'Transport discarded the HTTP result'
            Assert-Transport ($fixture.Handler.Requests.Count -eq 1 -and $fixture.Handler.Requests[0] -ceq $latest.AbsoluteUri) 'Metadata used the wrong endpoint or retried unexpectedly'
            Assert-Transport ($fixture.Handler.Accept -ceq 'application/vnd.github+json' -and $fixture.Handler.ApiVersion -ceq '2022-11-28') 'Metadata omitted the GitHub API headers'
        } finally { $response.Dispose() }
    } finally { $fixture.Client.Dispose() }
}
foreach ($hostName in 'release-assets.githubusercontent.com', 'objects.githubusercontent.com', 'github-releases.githubusercontent.com') {
    $fixture = New-HttpFixture
    try {
        $redirect = "https://$hostName/asset?download=fixture"
        Add-HttpResponse $fixture 302 $redirect
        Add-HttpResponse $fixture 200
        $response = Invoke-FixtureSend $fixture $asset $false
        try { Assert-Transport ($fixture.Handler.Requests.Count -eq 2 -and $fixture.Handler.Requests[1] -ceq $redirect) 'GitHub release asset redirect was not followed' }
        finally { $response.Dispose() }
    } finally { $fixture.Client.Dispose() }
}
foreach ($redirect in 'http://release-assets.githubusercontent.com/asset',
    'https://example.invalid/asset', 'https://github.com/another/repo/releases/download/v0.2.0/file.exe',
    'https://release-assets.githubusercontent.com:444/asset',
    'https://user@release-assets.githubusercontent.com/asset',
    'https://release-assets.githubusercontent.com/asset#fragment') {
    $fixture = New-HttpFixture
    try {
        Add-HttpResponse $fixture 302 $redirect
        Assert-TransportThrows { Invoke-FixtureSend $fixture $asset $false } 'Unapproved redirected download destination accepted'
        Assert-Transport ($fixture.Handler.Requests.Count -eq 1) 'Transport contacted the rejected redirect destination'
    } finally { $fixture.Client.Dispose() }
}
$fixture = New-HttpFixture
try {
    Add-HttpResponse $fixture 302 'https://api.github.com/repos/another/repo/releases/latest'
    Assert-TransportThrows { Invoke-FixtureSend $fixture $latest $true } 'Metadata redirect switched repositories'
    Assert-Transport ($fixture.Handler.Requests.Count -eq 1) 'Metadata contacted another repository after redirect'
} finally { $fixture.Client.Dispose() }
$fixture = New-HttpFixture
try {
    Add-HttpResponse $fixture 302
    Assert-TransportThrows { Invoke-FixtureSend $fixture $asset $false } 'Empty redirect Location was accepted'
    Assert-Transport ($fixture.Handler.Requests.Count -eq 1) 'Missing redirect target caused another request'
} finally { $fixture.Client.Dispose() }
$fixture = New-HttpFixture
try {
    for ($i = 0; $i -lt 8; $i++) { Add-HttpResponse $fixture 307 $asset.AbsoluteUri }
    Assert-TransportThrows { Invoke-FixtureSend $fixture $asset $false } 'Redirect loop was not bounded'
    Assert-Transport ($fixture.Handler.Requests.Count -eq 6) 'Redirect loop exceeded the six-request budget'
} finally { $fixture.Client.Dispose() }
$fixture = New-HttpFixture
$cts = [Threading.CancellationTokenSource]::new()
try {
    Add-HttpResponse $fixture 200
    $cts.Cancel()
    Assert-TransportThrows { Invoke-FixtureSend $fixture $latest $true $cts.Token } 'Pre-cancelled request was sent'
    Assert-Transport ($fixture.Handler.Requests.Count -eq 0) 'Cancellation did not reach HttpClient'
} finally { $cts.Dispose(); $fixture.Client.Dispose() }
"PASS: updater HTTP request and redirect contracts ($script:Checks checks)"

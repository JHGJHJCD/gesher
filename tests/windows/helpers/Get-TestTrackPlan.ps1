param(
  [Parameter(Mandatory)] [string] $Shard,
  [string] $TrackDirectory = (Join-Path $PSScriptRoot '../tracks'),
  [string] $ManifestPath = (Join-Path $PSScriptRoot '../test-shards.json')
)
$ErrorActionPreference = 'Stop'
$manifest = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json
$available = @(Get-ChildItem -LiteralPath $TrackDirectory -Filter *.ps1 | Sort-Object Name)
$owners = @{}
foreach ($group in $manifest.PSObject.Properties) {
  foreach ($name in $group.Value.tracks) {
    if ($owners.ContainsKey($name)) { throw "Track assigned more than once: $name" }
    if ($name -notin $available.Name) { throw "Assigned track is missing: $name" }
    $owners[$name] = $group.Name
  }
}
foreach ($track in $available) {
  if (-not $owners.ContainsKey($track.Name)) { throw "Track has no shard: $($track.Name)" }
}
$group = $manifest.PSObject.Properties[$Shard]
if (-not $group -or -not $group.Value.tracks.Count) { throw "Unknown or empty shard: $Shard" }
$helpers = @(foreach ($source in $group.Value.helpers.PSObject.Properties) {
  $path = Join-Path $TrackDirectory $source.Name
  $tokens = $null; $errors = $null
  $ast = [Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
  if ($errors) { throw "Cannot parse helper source: $path" }
  foreach ($name in $source.Value) {
    $definitions = @($ast.FindAll({ param($node)
      $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
    }, $false))
    if ($definitions.Count -ne 1) { throw "Expected one original helper: $name in $path" }
    $definitions[0].Extent.Text
  }
})
# Each isolated runner needs the same graphics helper and installed system fonts.
$names = @('30-graphics.ps1', '32-fonts.ps1') + @($group.Value.tracks)
[pscustomobject]@{
  Tracks = @($available | Where-Object { $_.Name -in $names })
  Helpers = $helpers
}

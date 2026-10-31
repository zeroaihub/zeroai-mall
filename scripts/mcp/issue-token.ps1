[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 9223372036854775807)]
    [long]$UserId,

    [ValidatePattern('^[A-Za-z0-9._-]{1,64}$')]
    [string]$ClientId = 'local-smoke',

    [ValidateRange(1, 3600)]
    [int]$TtlSeconds = 900,

    [string]$Secret = $env:MCP_TOKEN_SECRET
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Secret) -or
        [Text.Encoding]::UTF8.GetByteCount($Secret) -lt 32) {
    throw 'MCP_TOKEN_SECRET must contain at least 32 UTF-8 bytes.'
}

$issuedAt = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$expiresAt = $issuedAt + $TtlSeconds
$signingInput = "v1.$UserId.$ClientId.$issuedAt.$expiresAt"

$hmac = [Security.Cryptography.HMACSHA256]::new(
    [Text.Encoding]::UTF8.GetBytes($Secret))
try {
    $signature = [Convert]::ToBase64String(
        $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($signingInput)))
} finally {
    $hmac.Dispose()
}

$urlSafeSignature = $signature.TrimEnd('=').Replace('+', '-').Replace('/', '_')
Write-Output "$signingInput.$urlSafeSignature"

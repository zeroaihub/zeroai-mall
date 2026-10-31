[CmdletBinding()]
param(
    [string]$Endpoint = 'http://127.0.0.1:82/mcp',
    [ValidateRange(1, 9223372036854775807)]
    [long]$UserId = 1,
    [string]$ClientId = 'powershell-smoke',
    [string]$Secret = $env:MCP_TOKEN_SECRET,
    [int]$TimeoutSeconds = 15
)

$ErrorActionPreference = 'Stop'
$token = & (Join-Path $PSScriptRoot 'issue-token.ps1') `
    -UserId $UserId -ClientId $ClientId -TtlSeconds 300 -Secret $Secret
$script:RequestId = 0

function Get-JsonRpcResponse {
    param([Parameter(Mandatory = $true)]$Response)

    if (-not [string]::IsNullOrWhiteSpace($Response.Content)) {
        try {
            return $Response.Content | ConvertFrom-Json -Depth 50
        } catch {
            # Streamable HTTP may return text/event-stream instead of JSON.
        }
    }

    foreach ($line in ($Response.Content -split "`r?`n")) {
        if (-not $line.StartsWith('data:')) {
            continue
        }
        $data = $line.Substring(5).Trim()
        if ([string]::IsNullOrWhiteSpace($data) -or $data -eq '[DONE]') {
            continue
        }
        try {
            return $data | ConvertFrom-Json -Depth 50
        } catch {
            continue
        }
    }
    throw 'MCP response did not contain a JSON-RPC payload.'
}

function New-Headers {
    param([switch]$Authenticated, [string]$SessionId, [string]$ProtocolVersion)

    $headers = @{
        Accept = 'application/json, text/event-stream'
        'Content-Type' = 'application/json'
    }
    if ($Authenticated) {
        $headers.Authorization = "Bearer $token"
    }
    if ($SessionId) {
        $headers.'Mcp-Session-Id' = $SessionId
    }
    if ($ProtocolVersion) {
        $headers.'MCP-Protocol-Version' = $ProtocolVersion
    }
    return $headers
}

function Invoke-McpPost {
    param(
        [Parameter(Mandatory = $true)][string]$Body,
        [switch]$Authenticated,
        [string]$SessionId,
        [string]$ProtocolVersion
    )

    Invoke-WebRequest -Uri $Endpoint -Method Post `
        -Headers (New-Headers -Authenticated:$Authenticated -SessionId $SessionId `
            -ProtocolVersion $ProtocolVersion) `
        -Body $Body -TimeoutSec $TimeoutSeconds -SkipHttpErrorCheck
}

$initializeBody = @{
    jsonrpc = '2.0'
    id = ++$script:RequestId
    method = 'initialize'
    params = @{
        protocolVersion = '2025-03-26'
        capabilities = @{}
        clientInfo = @{ name = 'zeroai-powershell-smoke'; version = '1.0.0' }
    }
} | ConvertTo-Json -Depth 10 -Compress
$initialize = Invoke-McpPost -Body $initializeBody -Authenticated
if ($initialize.StatusCode -ne 200) {
    throw "MCP initialize failed with HTTP $($initialize.StatusCode)."
}
$initializeJson = Get-JsonRpcResponse $initialize
$protocolVersion = [string]$initializeJson.result.protocolVersion
$sessionHeader = $initialize.Headers['Mcp-Session-Id']
$sessionId = if ($sessionHeader -is [array]) { [string]$sessionHeader[0] } else { [string]$sessionHeader }

$initializedBody = @{
    jsonrpc = '2.0'
    method = 'notifications/initialized'
} | ConvertTo-Json -Depth 5 -Compress
$null = Invoke-McpPost -Body $initializedBody -Authenticated `
    -SessionId $sessionId -ProtocolVersion $protocolVersion

$listBody = @{
    jsonrpc = '2.0'
    id = ++$script:RequestId
    method = 'tools/list'
    params = @{}
} | ConvertTo-Json -Depth 5 -Compress
$listJson = Get-JsonRpcResponse (Invoke-McpPost -Body $listBody -Authenticated `
        -SessionId $sessionId -ProtocolVersion $protocolVersion)
$toolNames = @($listJson.result.tools | ForEach-Object { $_.name } | Sort-Object)
$expectedTools = @(
    'get_product_detail',
    'list_my_orders',
    'search_knowledge',
    'search_products'
)
if (Compare-Object $expectedTools $toolNames) {
    throw "Unexpected MCP tool list: $($toolNames -join ', ')"
}

function Invoke-Tool {
    param([string]$Name, [hashtable]$Arguments)

    $body = @{
        jsonrpc = '2.0'
        id = ++$script:RequestId
        method = 'tools/call'
        params = @{ name = $Name; arguments = $Arguments }
    } | ConvertTo-Json -Depth 10 -Compress
    $json = Get-JsonRpcResponse (Invoke-McpPost -Body $body -Authenticated `
            -SessionId $sessionId -ProtocolVersion $protocolVersion)
    if ($json.error) {
        throw "MCP tool $Name failed: $($json.error.message)"
    }
    return [string]$json.result.content[0].text
}

$searchText = Invoke-Tool 'search_products' @{ keyword = 'phone'; limit = 1 }
if ($searchText -match '"error"') {
    throw "search_products returned an error: $searchText"
}

$ordersText = Invoke-Tool 'list_my_orders' @{ limit = 1 }
if ($ordersText -match 'AUTH_REQUIRED|"error"') {
    throw "list_my_orders returned an error: $ordersText"
}

# The server must ignore no model-supplied identity; extra fields are rejected
# before the user-scoped order query executes.
$tamperText = Invoke-Tool 'list_my_orders' @{ userId = 999999; limit = 1 }
if ($tamperText -notmatch 'INVALID_ARGUMENTS|validation failed|未定义属性|不允许附加属性') {
    throw "list_my_orders accepted a caller-supplied userId: $tamperText"
}

$unauthenticated = Invoke-McpPost -Body $initializeBody
if ($unauthenticated.StatusCode -ne 401) {
    throw "Unauthenticated MCP initialize returned HTTP $($unauthenticated.StatusCode), expected 401."
}

Write-Output "MCP smoke passed: protocol=$protocolVersion tools=$($toolNames.Count) session=$([bool]$sessionId) authRejected=$($unauthenticated.StatusCode)"

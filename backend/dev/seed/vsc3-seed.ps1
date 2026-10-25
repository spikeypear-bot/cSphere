<#
.SYNOPSIS
Creates a fresh, labelled VS-C3 demo batch through the local application's APIs.
.EXAMPLE
./backend/dev/seed/vsc3-seed.ps1 -LocalDev -ExpiryMinutes 3
#>
[CmdletBinding()]
param(
    [switch]$LocalDev,
    [string]$BaseUrl = 'http://localhost:8080',
    [ValidateRange(1, 120)][int]$ExpiryMinutes = 3,
    [ValidateRange(1, 90)][int]$DaysAhead = 1,
    [ValidatePattern('^[A-Za-z0-9-]{1,40}$')][string]$BatchName = (
        (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 4)
    )
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $LocalDev) { throw 'Pass -LocalDev explicitly. This script creates local demo data and must never target production.' }
$base = [uri]$BaseUrl
if (-not $base.IsAbsoluteUri -or -not $base.IsLoopback -or $base.Scheme -notin @('http', 'https') -or
    $base.UserInfo -or $base.Query -or $base.Fragment -or $base.AbsolutePath.Trim('/') -ne '') {
    throw 'BaseUrl must be a loopback application root, for example http://localhost:8080.'
}
$BaseUrl = $BaseUrl.TrimEnd('/')
$prefix = "[DEMO VS-C3 $BatchName]"
$directory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../logs/vsc3'))
$manifestPath = Join-Path $directory "$BatchName.json"
if (Test-Path -LiteralPath $manifestPath) { throw "Batch manifest already exists: $manifestPath. Choose a new BatchName; existing test edits are preserved." }

function Invoke-DemoApi {
    param([string]$Method, [string]$Path, [string]$Token = '', $Body = $null)
    $arguments = @{ Method = $Method; Uri = "$BaseUrl$Path"; TimeoutSec = 30 }
    if ($Token) { $arguments.Headers = @{ Authorization = "Bearer $Token" } }
    if ($null -ne $Body) {
        $arguments.ContentType = 'application/json; charset=utf-8'
        $arguments.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 12 -Compress))
    }
    try {
        $result = Invoke-RestMethod @arguments
        # Explicitly enumerate JSON lists: Invoke-RestMethod can emit an array as
        # one pipeline object, which breaks strict-mode property filters.
        if ($result -is [array]) { foreach ($row in $result) { Write-Output $row } }
        else { Write-Output $result }
    }
    catch {
        $details = if ($_.ErrorDetails) { $_.ErrorDetails.Message } else { '' }
        throw "$Method $Path failed. $($_.Exception.Message) $details"
    }
}
function Get-DemoToken([string]$Username) {
    (Invoke-DemoApi POST '/api/auth/login' -Body @{ username = $Username; password = '123456' }).accessToken
}

# Check the feature before creating any records. This catches an old Docker image.
$staff = Get-DemoToken 'vs1'
$api = Invoke-DemoApi GET '/v3/api-docs' $staff
if ('/api/venue-bookings/{bookingId}/holds' -notin $api.paths.PSObject.Properties.Name) {
    throw 'The backend does not expose VS-C3. Rebuild/start the updated backend before running this script.'
}
$organiser = Get-DemoToken 'eo1'
$lead = Get-DemoToken 'ecl1'
$coordinator = Get-DemoToken 'ec1'
$coordinators = @(Invoke-DemoApi GET '/api/event-requests/unassigned/coordinators' $lead)
$assigned = @($coordinators | Where-Object username -EQ 'ec1')
if ($assigned.Count -ne 1) { throw 'The local dev account ec1 was not found in the coordinator picker.' }
$existing = @(Invoke-DemoApi GET '/api/event-requests' $organiser)
if (@($existing | Where-Object { $_.eventName.StartsWith($prefix, [StringComparison]::Ordinal) }).Count) {
    throw 'This demo batch already exists on the server. Choose a new BatchName; previous records are never reset.'
}

$day = [DateTimeOffset]::UtcNow.ToOffset([TimeSpan]::FromHours(8)).Date.AddDays($DaysAhead).ToString('yyyy-MM-dd')
$manifest = [ordered]@{
    batch = $BatchName; prefix = $prefix; baseUrl = $BaseUrl
    createdAt = [DateTimeOffset]::UtcNow.ToString('o'); status = 'creating'
    bookingDateSgt = $day; liveExpirySgt = $null; calendarPath = '/venue-staff/availability-calendar'; scenarios = @()
}
function Save-Manifest {
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $manifest | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $manifestPath -Encoding utf8
}
function New-DemoArrangement($Scenario, [string]$Label) {
    $entry = [ordered]@{ label = $Label; requestId = $null; eventId = $null; bookingId = $null; staffPath = $null; coordinatorPath = $null }
    $Scenario.arrangements += $entry
    Save-Manifest
    $draft = Invoke-DemoApi POST '/api/event-requests' $organiser @{
        eventName = "$prefix $($Scenario.label) - $Label"; purpose = 'VS-C3 local hold demonstration'
        description = 'Synthetic development data. Keep this labelled record for audit history.'
        startDatetime = "${day}T10:00:00+08:00"; endDatetime = "${day}T12:00:00+08:00"
        expectedAttendance = 80; venueRequirements = 'Classroom seating for 80 with setup and teardown access.'
        equipmentRequirements = ''; accessibilityNeeds = @('none'); requiredFacilities = @(); registrationNeeds = $false
    }
    $entry.requestId = $draft.requestId
    Save-Manifest
    Invoke-DemoApi POST "/api/event-requests/$($entry.requestId)/submit" $organiser | Out-Null
    Invoke-DemoApi POST "/api/event-requests/unassigned/$($entry.requestId)/assign" $lead @{ coordinatorUserId = $assigned[0].userId } | Out-Null
    $approved = Invoke-DemoApi POST "/api/event-requests/$($entry.requestId)/approve" $coordinator
    $entry.eventId = $approved.eventId
    $entry.coordinatorPath = "/coordinator/events/$($entry.eventId)/venue-booking"
    Save-Manifest
    $booking = Invoke-DemoApi POST "/api/events/$($entry.eventId)/venue-bookings" $coordinator @{
        venueId = $Scenario.venueId; bookingNotes = "$prefix $($Scenario.label) - $Label"
        startDatetime = "${day}T10:00:00+08:00"; endDatetime = "${day}T12:00:00+08:00"
    }
    $entry.bookingId = $booking.bookingId
    $entry.staffPath = "/venue-staff/bookings/$($entry.bookingId)"
    Save-Manifest
    return $entry
}

Save-Manifest
try {
    foreach ($spec in @(
        @{ key = 'create-release'; label = 'Create and release'; competitor = $true },
        @{ key = 'conversion'; label = 'Active hold for conversion'; competitor = $true },
        @{ key = 'history'; label = 'Released and converted history'; competitor = $false },
        @{ key = 'expiry'; label = 'Live expiry'; competitor = $true }
    )) {
        $scenario = [ordered]@{ key = $spec.key; label = $spec.label; venueId = $null; venueAddress = "$prefix $($spec.label)"
            arrangements = @(); holds = @() }
        $manifest.scenarios += $scenario
        $venue = Invoke-DemoApi POST '/api/venues' $staff @{
            venueAddress = $scenario.venueAddress; venueCapacity = 200; supportedLayouts = @('classroom', 'theatre')
            venueAccessibilities = @('step_free_access'); venueFacilities = @('projection')
            additionalInformation = 'Booking 10:00-12:00; occupied 09:30-12:45 Singapore time.'
            setupMinutes = 30; turnaroundMinutes = 45
            operatingHours = @(1..7 | ForEach-Object { @{ dayOfWeek = $_; openTime = '00:00'; closeTime = '23:59' } })
        }
        $scenario.venueId = $venue.venueId
        Save-Manifest
        $primary = New-DemoArrangement $scenario 'Primary'
        # Submit competing requests before creating a hold, as required by the demo.
        if ($spec.competitor) { New-DemoArrangement $scenario 'Competing request' | Out-Null }
        if ($spec.key -in @('conversion', 'history')) {
            $deadline = [DateTimeOffset]::UtcNow.AddHours(2).ToString('o')
            $hold = Invoke-DemoApi POST "/api/venue-bookings/$($primary.bookingId)/holds" $staff @{ expiresAt = $deadline }
            $scenario.holds += $hold
            Save-Manifest
            if ($spec.key -eq 'history') {
                Invoke-DemoApi PATCH "/api/venue-holds/$($hold.holdId)/release" $staff | Out-Null
                $second = Invoke-DemoApi POST "/api/venue-bookings/$($primary.bookingId)/holds" $staff @{ expiresAt = $deadline }
                $scenario.holds += $second
                Save-Manifest
                Invoke-DemoApi PATCH "/api/venue-holds/$($second.holdId)/convert" $staff | Out-Null
                $scenario.holds = @(Invoke-DemoApi GET "/api/venue-bookings/$($primary.bookingId)/holds" $staff)
            }
        }
        Save-Manifest
    }
    # Start the short expiry countdown last, after the rest of the demo is ready.
    $live = @($manifest.scenarios | Where-Object key -EQ 'expiry')[0]
    $liveHold = Invoke-DemoApi POST "/api/venue-bookings/$($live.arrangements[0].bookingId)/holds" $staff @{
        expiresAt = [DateTimeOffset]::UtcNow.AddMinutes($ExpiryMinutes).ToString('o')
    }
    $live.holds = @(Invoke-DemoApi GET "/api/venue-bookings/$($live.arrangements[0].bookingId)/holds" $staff)
    $manifest.liveExpirySgt = ([DateTimeOffset]$liveHold.expiresAt).ToOffset([TimeSpan]::FromHours(8)).ToString('yyyy-MM-dd HH:mm:ss zzz')
    $manifest.status = 'ready'
    Save-Manifest
    Write-Host "VS-C3 batch ready: $prefix"
    Write-Host "Booking date: $day (SGT), booking 10:00-12:00, occupied 09:30-12:45."
    Write-Host "Live expiry (SGT): $($manifest.liveExpirySgt). Test this scenario first."
    foreach ($scenario in $manifest.scenarios) {
        Write-Host "`n$($scenario.label) - $($scenario.venueAddress)"
        foreach ($entry in $scenario.arrangements) { Write-Host "  $($entry.label): $($entry.staffPath)" }
        Write-Host "  Coordinator: $($scenario.arrangements[0].coordinatorPath)"
    }
    Write-Host "`nManifest (IDs and links, no tokens): $manifestPath"
} catch {
    $manifest.status = 'incomplete'
    Save-Manifest
    Write-Warning "Setup stopped; partial demo IDs are retained in $manifestPath. Existing records were not reset."
    throw
}

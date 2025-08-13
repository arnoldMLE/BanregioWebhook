# Test-PowerBIConnection-Compatible.ps1
# Compatible with older PowerShell versions
param(
    [Parameter(Mandatory=$false)]
    [string]$TenantId,
    [Parameter(Mandatory=$false)]
    [string]$ClientId,
    [Parameter(Mandatory=$false)]
    [string]$ClientSecret
)

# Function to convert SecureString to plain text (compatible with older PowerShell)
function ConvertTo-PlainText {
    param([System.Security.SecureString]$SecureString)
    $BSTR = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureString)
    $PlainText = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($BSTR)
    [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($BSTR)
    return $PlainText
}

# If parameters not provided, prompt for them
if (-not $TenantId) { 
    $TenantId = Read-Host "Enter your Tenant ID" 
}
if (-not $ClientId) { 
    $ClientId = Read-Host "Enter your Client ID" 
}
if (-not $ClientSecret) { 
    $SecureSecret = Read-Host "Enter your Client Secret" -AsSecureString
    $ClientSecret = ConvertTo-PlainText -SecureString $SecureSecret
}

Write-Host "Testing Power BI Service Principal Connection..." -ForegroundColor Green
Write-Host "Tenant ID: $TenantId" -ForegroundColor Gray
Write-Host "Client ID: $ClientId" -ForegroundColor Gray

# Safe substring display
$secretDisplay = if ($ClientSecret.Length -ge 8) { 
    $ClientSecret.Substring(0,8) + "..." 
} else { 
    "***..." 
}
Write-Host "Client Secret: $secretDisplay" -ForegroundColor Gray
Write-Host ""

# Validate inputs
if ([string]::IsNullOrWhiteSpace($TenantId) -or [string]::IsNullOrWhiteSpace($ClientId) -or [string]::IsNullOrWhiteSpace($ClientSecret)) {
    Write-Host "ERROR: All parameters (TenantId, ClientId, ClientSecret) are required!" -ForegroundColor Red
    return
}

# Test 1: Authentication
Write-Host "Step 1: Testing Authentication..." -ForegroundColor Yellow
try {
    # Prepare the authentication request
    $authUrl = "https://login.microsoftonline.com/$TenantId/oauth2/v2.0/token"
    
    # Create the body for the request
    $authBody = "grant_type=client_credentials&client_id=$ClientId&client_secret=$([System.Web.HttpUtility]::UrlEncode($ClientSecret))&scope=https%3A//analysis.windows.net/powerbi/api/.default"
    
    # Make the authentication request
    $authResponse = Invoke-RestMethod -Uri $authUrl -Method Post -Body $authBody -ContentType "application/x-www-form-urlencoded"
    
    Write-Host "   SUCCESS: Authentication successful!" -ForegroundColor Green
    Write-Host "   Token Type: $($authResponse.token_type)" -ForegroundColor Cyan
    Write-Host "   Token expires in: $($authResponse.expires_in) seconds" -ForegroundColor Cyan
    
    # Prepare headers for subsequent requests
    $headers = @{
        'Authorization' = "$($authResponse.token_type) $($authResponse.access_token)"
        'Content-Type' = 'application/json'
    }
    
} catch {
    Write-Host "   ERROR: Authentication failed!" -ForegroundColor Red
    Write-Host "   Details: $($_.Exception.Message)" -ForegroundColor Red
    
    # More specific error checking
    $errorMessage = $_.Exception.Message
    if ($errorMessage -like "*400*" -or $errorMessage -like "*invalid_request*") {
        Write-Host "   TIP: Check your Tenant ID format (should be a GUID)" -ForegroundColor Yellow
    }
    elseif ($errorMessage -like "*401*" -or $errorMessage -like "*invalid_client*") {
        Write-Host "   TIP: Check your Client ID and Client Secret" -ForegroundColor Yellow
        Write-Host "   TIP: Make sure the client secret hasn't expired" -ForegroundColor Yellow
    }
    elseif ($errorMessage -like "*invalid_scope*") {
        Write-Host "   TIP: The Power BI scope might not be configured correctly" -ForegroundColor Yellow
    }
    
    Write-Host ""
    Write-Host "Troubleshooting steps:" -ForegroundColor Yellow
    Write-Host "1. Verify your app registration exists in Azure AD" -ForegroundColor White
    Write-Host "2. Check that the client secret is correct and not expired" -ForegroundColor White
    Write-Host "3. Ensure the Tenant ID is the correct Azure AD directory ID" -ForegroundColor White
    return
}

# Test 2: Power BI API Access
Write-Host ""
Write-Host "Step 2: Testing Power BI API Access..." -ForegroundColor Yellow
try {
    $workspacesUrl = "https://api.powerbi.com/v1.0/myorg/groups"
    $workspaces = Invoke-RestMethod -Uri $workspacesUrl -Headers $headers
    
    Write-Host "   SUCCESS: Power BI API access successful!" -ForegroundColor Green
    Write-Host "   Found $($workspaces.value.Count) accessible workspaces" -ForegroundColor Cyan
    
    if ($workspaces.value.Count -eq 0) {
        Write-Host "   INFO: No workspaces accessible yet" -ForegroundColor Yellow
        Write-Host "   This is normal for new service principals" -ForegroundColor Yellow
        Write-Host "   You'll need to grant workspace access manually" -ForegroundColor Yellow
    }
    
} catch {
    Write-Host "   ERROR: Power BI API access failed!" -ForegroundColor Red
    Write-Host "   Details: $($_.Exception.Message)" -ForegroundColor Red
    
    $errorMessage = $_.Exception.Message
    if ($errorMessage -like "*403*" -or $errorMessage -like "*Forbidden*") {
        Write-Host ""
        Write-Host "   This usually means:" -ForegroundColor Yellow
        Write-Host "   1. Service principals are not enabled in Power BI admin settings" -ForegroundColor White
        Write-Host "   2. Your service principal is not in the allowed security group" -ForegroundColor White
        Write-Host ""
        Write-Host "   To fix this:" -ForegroundColor Yellow
        Write-Host "   1. Go to Power BI Admin Portal > Tenant Settings" -ForegroundColor White
        Write-Host "   2. Find 'Allow service principals to use Power BI APIs'" -ForegroundColor White
        Write-Host "   3. Enable it and add your service principal to a security group" -ForegroundColor White
    }
    elseif ($errorMessage -like "*401*" -or $errorMessage -like "*Unauthorized*") {
        Write-Host "   TIP: The access token might be invalid or expired" -ForegroundColor Yellow
    }
    return
}

# Test 3: List Workspaces (if any accessible)
if ($workspaces.value.Count -gt 0) {
    Write-Host ""
    Write-Host "Step 3: Listing Accessible Workspaces..." -ForegroundColor Yellow
    
    foreach ($workspace in $workspaces.value) {
        Write-Host "   Workspace: $($workspace.name)" -ForegroundColor White
        Write-Host "      ID: $($workspace.id)" -ForegroundColor Gray
        Write-Host "      Type: $($workspace.type)" -ForegroundColor Gray
        
        # Try to get datasets in this workspace
        try {
            $datasetsUrl = "https://api.powerbi.com/v1.0/myorg/groups/$($workspace.id)/datasets"
            $datasets = Invoke-RestMethod -Uri $datasetsUrl -Headers $headers
            
            Write-Host "      Datasets: $($datasets.value.Count)" -ForegroundColor Cyan
            
            if ($datasets.value.Count -gt 0) {
                foreach ($dataset in $datasets.value) {
                    Write-Host "         - $($dataset.name)" -ForegroundColor White
                    Write-Host "           ID: $($dataset.id)" -ForegroundColor Gray
                }
            }
        } catch {
            Write-Host "      WARNING: Cannot access datasets (insufficient permissions)" -ForegroundColor Yellow
        }
        Write-Host ""
    }
} else {
    Write-Host ""
    Write-Host "Step 3: No workspaces to list" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "To grant workspace access:" -ForegroundColor Cyan
    Write-Host "1. Go to Power BI Service (app.powerbi.com)" -ForegroundColor White
    Write-Host "2. Navigate to your workspace" -ForegroundColor White
    Write-Host "3. Click workspace settings > Access" -ForegroundColor White
    Write-Host "4. Add your service principal as Member or Admin" -ForegroundColor White
    Write-Host "5. Search for: Your app registration name" -ForegroundColor White
}

Write-Host ""
Write-Host "Connection test completed!" -ForegroundColor Green
Write-Host ""
Write-Host "Summary:" -ForegroundColor Cyan
if ($workspaces.value.Count -gt 0) {
    Write-Host "SUCCESS: Everything is working! You can now deploy your service." -ForegroundColor Green
} else {
    Write-Host "PARTIAL SUCCESS: Authentication works, but you need to grant workspace access." -ForegroundColor Yellow
}
Write-Host ""
Write-Host "Your configuration should be:" -ForegroundColor Cyan
Write-Host "TenantId: $TenantId" -ForegroundColor White
Write-Host "ClientId: $ClientId" -ForegroundColor White
Write-Host "ClientSecret: [use the secret you entered]" -ForegroundColor White
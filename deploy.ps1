# ================================================================
# Complete PaymentBanregio Local Development Setup
# Handles Maven build + Container deployment
# ================================================================

param(
    [Parameter(Position=0)]
    [ValidateSet("setup", "build", "start", "stop", "restart", "logs", "status", "clean")]
    [string]$Action = "setup"
)

function Write-Info {
    param([string]$Message)
    Write-Host "[INFO] $Message" -ForegroundColor Green
}

function Write-Error {
    param([string]$Message)
    Write-Host "[ERROR] $Message" -ForegroundColor Red
}

function Write-Warning {
    param([string]$Message)
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function Test-Prerequisites {
    Write-Info "Checking prerequisites..."
    
    $errors = @()
    
    # Check Maven
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
        $errors += "Maven not found. Install from: https://maven.apache.org/download.cgi"
    } else {
        Write-Info "✅ Maven found"
    }
    
    # Check Java
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        $errors += "Java not found. Install from: https://adoptium.net/"
    } else {
        $javaVersion = & java -version 2>&1 | Select-Object -First 1
        Write-Info "✅ Java found: $javaVersion"
    }
    
    # Check container runtime
    $podman = Get-Command podman -ErrorAction SilentlyContinue
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    
    if (-not $podman -and -not $docker) {
        $errors += "Neither Podman nor Docker found. Install one of them."
    } elseif ($podman) {
        Write-Info "✅ Podman found"
        $script:ContainerRuntime = "podman"
    } else {
        Write-Info "✅ Docker found"
        $script:ContainerRuntime = "docker"
    }
    
    # Check project files
    if (-not (Test-Path "pom.xml")) {
        $errors += "pom.xml not found. Are you in the project root directory?"
    } else {
        Write-Info "✅ Project files found"
    }
    
    if ($errors.Count -gt 0) {
        Write-Error "Prerequisites missing:"
        $errors | ForEach-Object { Write-Error "  - $_" }
        return $false
    }
    
    return $true
}

function Build-SpringBootApp {
    Write-Info "Building Spring Boot application..."
    
    # Clean any existing target directory
    if (Test-Path "target") {
        Write-Info "Cleaning existing target directory..."
        Remove-Item "target" -Recurse -Force -ErrorAction SilentlyContinue
    }
    
    # Run Maven build
    Write-Info "Running Maven build (this may take a few minutes)..."
    & mvn clean package -DskipTests
    
    if ($LASTEXITCODE -eq 0) {
        # Verify JAR file was created
        $jarFiles = Get-ChildItem -Path "target" -Filter "PaymentBanregio-*.jar" -ErrorAction SilentlyContinue
        if ($jarFiles) {
            $jarFile = $jarFiles | Where-Object { $_.Name -notlike "*sources*" } | Select-Object -First 1
            if ($jarFile) {
                Write-Info "✅ Spring Boot application built successfully"
                Write-Info "   JAR file: $($jarFile.Name)"
                Write-Info "   Size: $([math]::Round($jarFile.Length/1MB, 2)) MB"
                return $true
            }
        }
        
        Write-Error "❌ Build succeeded but JAR file not found"
        Write-Error "   Expected: target/PaymentBanregio-*.jar"
        return $false
    } else {
        Write-Error "❌ Maven build failed"
        Write-Error "   Check the build output above for errors"
        return $false
    }
}

function Setup-ComposeFiles {
    Write-Info "Setting up compose files..."
    
    # Create docker-compose.yml if using Docker or if it doesn't exist
    if ($script:ContainerRuntime -eq "docker" -or -not (Test-Path "docker-compose.yml")) {
        if (Test-Path "podman-compose.yml") {
            Write-Info "Creating docker-compose.yml from podman-compose.yml..."
            $composeContent = Get-Content "podman-compose.yml" -Raw
            
            # Remove Podman-specific configurations
            $composeContent = $composeContent -replace ":Z", ""  # Remove SELinux labels
            $composeContent = $composeContent -replace "docker\.io/", ""  # Remove docker.io prefix
            
            $composeContent | Out-File -FilePath "docker-compose.yml" -Encoding UTF8
            Write-Info "✅ docker-compose.yml created"
        } else {
            Write-Error "No podman-compose.yml found to convert"
            return $false
        }
    }
    
    # Create .env file
    if (-not (Test-Path ".env")) {
        Write-Info "Creating .env file..."
        
        $envContent = @"
# Development Environment Variables

# Database Configuration
DB_HOST=postgres
DB_PORT=5432
DB_NAME=procesador_pagos_db
DB_USERNAME=postgres
DB_PASSWORD=a

# Azure/Microsoft Graph Configuration
AZURE_TENANT_ID=0f3dc3aa-441d-4e6a-b1d3-c4a0e2313462
AZURE_CLIENT_ID=fad67f80-1180-4504-8ad5-5d87bb79022c
AZURE_CLIENT_SECRET=A558Q~W6Gdj3D6M5SwvgTOCxOJa0zq7oRtxC1cRq
AZURE_USER_ID=5a4146ec-ee2f-4009-8adf-3eda7e1aaee3

# NetSuite Configuration
NETSUITE_ACCOUNT_ID=9519366-sb1
NETSUITE_CONSUMER_KEY=ce6a9c35977890b311ea7f7c674a9fdb911204bc347a8ad32814144199fb126e
NETSUITE_CONSUMER_SECRET=d858eb7cc673e8631b94d06a496e59b6be57a6369a73becd60b25360c3c86bf8
NETSUITE_TOKEN_ID=18ebaa5d564c96c240a4051d8c37f84a61f046144ffafbcd3a01adcc5922f4b5
NETSUITE_TOKEN_SECRET=5ec39768aa38e879b2f6b29ef1e5cf2a632663be3f6cffb14b7f538a1ba5c9f9

# Application Configuration
SPRING_PROFILES_ACTIVE=dev
JAVA_OPTS=-Xmx1024m -Xms512m -XX:+UseG1GC

# Webhook Configuration
WEBHOOK_NOTIFICATION_URL=http://localhost:8080/api/v1/webhooks/notifications
WEBHOOK_AUTO_CREATE=true
"@
        
        $envContent | Out-File -FilePath ".env" -Encoding UTF8
        Write-Info "✅ .env file created"
    } else {
        Write-Info "✅ .env file already exists"
    }
    
    return $true
}

function Start-Containers {
    Write-Info "Starting containers with $script:ContainerRuntime..."
    
    # Stop any existing containers first
    Stop-Containers -Silent
    
    # Start containers
    if ($script:ContainerRuntime -eq "podman") {
        if (Test-Path "podman-compose.yml") {
            & podman compose -f podman-compose.yml up -d --build
        } else {
            & podman compose up -d --build
        }
    } else {
        & docker compose up -d --build
    }
    
    if ($LASTEXITCODE -eq 0) {
        Write-Info "✅ Containers started successfully"
        
        Write-Info "Waiting for services to initialize..."
        Start-Sleep -Seconds 20
        
        return $true
    } else {
        Write-Error "❌ Failed to start containers"
        Write-Error "   Check the build output above for errors"
        return $false
    }
}

function Stop-Containers {
    param([switch]$Silent)
    
    if (-not $Silent) {
        Write-Info "Stopping containers..."
    }
    
    if ($script:ContainerRuntime -eq "podman") {
        if (Test-Path "podman-compose.yml") {
            & podman compose -f podman-compose.yml down 2>$null
        } else {
            & podman compose down 2>$null
        }
    } else {
        & docker compose down 2>$null
    }
    
    if (-not $Silent) {
        Write-Info "✅ Containers stopped"
    }
}

function Test-Services {
    Write-Info "Testing services..."
    
    $allGood = $true
    
    # Test PostgreSQL
    try {
        if ($script:ContainerRuntime -eq "podman") {
            & podman exec payment-postgres pg_isready -U postgres -d procesador_pagos_db >$null 2>&1
        } else {
            & docker exec payment-postgres pg_isready -U postgres -d procesador_pagos_db >$null 2>&1
        }
        
        if ($LASTEXITCODE -eq 0) {
            Write-Info "✅ PostgreSQL: Ready and accepting connections"
        } else {
            Write-Warning "⚠️ PostgreSQL: Not ready yet"
            $allGood = $false
        }
    } catch {
        Write-Warning "⚠️ PostgreSQL: Could not test connection"
        $allGood = $false
    }
    
    # Test Spring Boot application
    $maxAttempts = 15
    $attempt = 1
    $appReady = $false
    
    Write-Info "Testing Spring Boot application..."
    
    while ($attempt -le $maxAttempts) {
        try {
            $response = Invoke-WebRequest -Uri "http://localhost:8080/actuator/health" -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -eq 200) {
                Write-Info "✅ Spring Boot Application: Healthy"
                $appReady = $true
                break
            }
        } catch {
            if ($attempt -eq 1) {
                Write-Info "Application starting up..."
            }
            Start-Sleep -Seconds 8
            $attempt++
        }
    }
    
    if (-not $appReady) {
        Write-Error "❌ Spring Boot Application: Not responding after $($maxAttempts * 8) seconds"
        $allGood = $false
    }
    
    # Test webhook endpoint if app is ready
    if ($appReady) {
        try {
            $webhookResponse = Invoke-WebRequest -Uri "http://localhost:8080/api/v1/admin/webhooks/status" -UseBasicParsing -TimeoutSec 5
            Write-Info "✅ Webhook Admin: Available"
        } catch {
            Write-Info "⚠️ Webhook Admin: May need Microsoft Graph configuration"
        }
    }
    
    return $allGood
}

function Show-Status {
    Write-Info "=== Service Status ==="
    
    # Show container status
    if ($script:ContainerRuntime -eq "podman") {
        if (Test-Path "podman-compose.yml") {
            & podman compose -f podman-compose.yml ps
        } else {
            & podman compose ps
        }
    } else {
        & docker compose ps
    }
    
    Write-Info ""
    Write-Info "📊 Application Access:"
    Write-Info "  • Main Application: http://localhost:8080"
    Write-Info "  • Health Check: http://localhost:8080/actuator/health"
    Write-Info "  • Webhook Admin: http://localhost:8080/api/v1/admin/webhooks/status"
    Write-Info ""
    Write-Info "🐘 Database Access:"
    Write-Info "  • Host: localhost"
    Write-Info "  • Port: 5432"
    Write-Info "  • Database: procesador_pagos_db"
    Write-Info "  • Username: postgres"
    Write-Info "  • Password: a"
    Write-Info ""
    Write-Info "📁 Important Files:"
    Write-Info "  • JAR: $(Get-ChildItem -Path 'target' -Filter 'PaymentBanregio-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1 | ForEach-Object { $_.Name })"
    Write-Info "  • Compose: $(if (Test-Path 'docker-compose.yml') { 'docker-compose.yml' } elseif (Test-Path 'podman-compose.yml') { 'podman-compose.yml' } else { 'Not found' })"
    Write-Info "  • Environment: $(if (Test-Path '.env') { '.env' } else { 'Not found' })"
}

function Show-Logs {
    Write-Info "Showing application logs (last 50 lines)..."
    Write-Info "Press Ctrl+C to stop following logs"
    Write-Info ""
    
    if ($script:ContainerRuntime -eq "podman") {
        & podman compose logs --tail=50 -f payment-app
    } else {
        & docker compose logs --tail=50 -f payment-app
    }
}

function Clean-Environment {
    Write-Warning "This will remove all containers, images, and volumes. Continue? (y/N)"
    $response = Read-Host
    
    if ($response -match '^[Yy]$') {
        Write-Info "Cleaning up environment..."
        
        # Stop containers
        Stop-Containers -Silent
        
        # Remove volumes and clean up
        if ($script:ContainerRuntime -eq "podman") {
            & podman compose down -v 2>$null
            & podman system prune -f 2>$null
            & podman volume prune -f 2>$null
        } else {
            & docker compose down -v 2>$null
            & docker system prune -f 2>$null
            & docker volume prune -f 2>$null
        }
        
        Write-Info "✅ Environment cleaned"
    } else {
        Write-Info "Cleanup cancelled"
    }
}

# Initialize container runtime variable
$script:ContainerRuntime = $null

# Main execution
Write-Host "================================================" -ForegroundColor Cyan
Write-Host "PaymentBanregio Local Development Environment" -ForegroundColor Cyan
Write-Host "================================================" -ForegroundColor Cyan
Write-Host ""

switch ($Action.ToLower()) {
    "setup" {
        Write-Info "=== Complete Setup ==="
        Write-Info "This will:"
        Write-Info "  1. Check all prerequisites"
        Write-Info "  2. Build the Spring Boot application"
        Write-Info "  3. Setup compose files"
        Write-Info "  4. Start all containers"
        Write-Info "  5. Test the services"
        Write-Info ""
        
        if (-not (Test-Prerequisites)) { exit 1 }
        if (-not (Build-SpringBootApp)) { exit 1 }
        if (-not (Setup-ComposeFiles)) { exit 1 }
        if (-not (Start-Containers)) { exit 1 }
        
        if (Test-Services) {
            Write-Info ""
            Write-Info "🎉 Development environment is ready!"
            Write-Info ""
            Show-Status
            Write-Info ""
            Write-Info "🚀 Quick start:"
            Write-Info "  • Open: http://localhost:8080/actuator/health"
            Write-Info "  • View logs: .\local-dev.ps1 logs"
            Write-Info "  • After code changes: .\local-dev.ps1 restart"
        } else {
            Write-Warning "Setup completed but some services may need attention"
            Write-Info "View logs with: .\local-dev.ps1 logs"
        }
    }
    "build" {
        if (-not (Test-Prerequisites)) { exit 1 }
        Build-SpringBootApp
    }
    "start" {
        if (-not (Test-Prerequisites)) { exit 1 }
        
        # Check if JAR exists
        $jarExists = Get-ChildItem -Path "target" -Filter "PaymentBanregio-*.jar" -ErrorAction SilentlyContinue
        if (-not $jarExists) {
            Write-Warning "JAR file not found. Building application first..."
            if (-not (Build-SpringBootApp)) { exit 1 }
        }
        
        if (-not (Setup-ComposeFiles)) { exit 1 }
        Start-Containers
        Start-Sleep -Seconds 10
        Show-Status
    }
    "stop" {
        if (-not (Test-Prerequisites)) { exit 1 }
        Stop-Containers
    }
    "restart" {
        if (-not (Test-Prerequisites)) { exit 1 }
        
        Write-Info "=== Rebuilding and Restarting ==="
        if (-not (Build-SpringBootApp)) { exit 1 }
        Stop-Containers -Silent
        Start-Sleep -Seconds 3
        Start-Containers
        Start-Sleep -Seconds 10
        Show-Status
    }
    "logs" {
        if (-not (Test-Prerequisites)) { exit 1 }
        Show-Logs
    }
    "status" {
        if (-not (Test-Prerequisites)) { exit 1 }
        Show-Status
    }
    "clean" {
        if (-not (Test-Prerequisites)) { exit 1 }
        Clean-Environment
    }
    default {
        Write-Error "Unknown action: $Action"
        Write-Info ""
        Write-Info "Available actions:"
        Write-Info "  setup   - Complete setup (build + compose + start + test)"
        Write-Info "  build   - Build Spring Boot application only"
        Write-Info "  start   - Start containers (builds if needed)"
        Write-Info "  stop    - Stop all containers"
        Write-Info "  restart - Rebuild application and restart containers"
        Write-Info "  logs    - Show and follow application logs"
        Write-Info "  status  - Show detailed status"
        Write-Info "  clean   - Remove all containers and volumes"
        Write-Info ""
        Write-Info "Examples:"
        Write-Info "  .\local-dev.ps1 setup     # First time setup"
        Write-Info "  .\local-dev.ps1 restart   # After code changes"
        Write-Info "  .\local-dev.ps1 logs      # Debug issues"
        Write-Info ""
        Write-Info "💡 For first time setup, just run: .\local-dev.ps1 setup"
    }
}
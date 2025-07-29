# ================================================================
# Script de Despliegue para PaymentBanregio en Windows Server
# Autor: PaymentBanregio Team
# Versión: 1.0.0
# ================================================================

# Configurar política de ejecución si es necesario
# Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser

param(
    [Parameter(Position=0)]
    [ValidateSet("deploy", "restart", "stop", "logs", "status", "clean", "help")]
    [string]$Action = "deploy"
)

# Variables globales
$ProjectName = "PaymentBanregio"
$ContainerName = "payment-banregio"
$PostgresContainer = "payment-postgres"
$NetworkName = "payment-network"
$ServerIP = ""

# Funciones de logging con colores
function Write-Info {
    param([string]$Message)
    Write-Host "[INFO] $Message" -ForegroundColor Green
}

function Write-Warn {
    param([string]$Message)
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function Write-Error {
    param([string]$Message)
    Write-Host "[ERROR] $Message" -ForegroundColor Red
}

function Write-Step {
    param([string]$Message)
    Write-Host "[STEP] $Message" -ForegroundColor Blue
}

# Función para obtener la IP del servidor Windows
function Get-ServerIP {
    Write-Step "Detectando IP del servidor..."
    
    try {
        # Intentar obtener IP pública primero
        $publicIP = (Invoke-WebRequest -Uri "http://ifconfig.me/ip" -UseBasicParsing -TimeoutSec 10).Content.Trim()
        if ($publicIP -match '^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$') {
            $script:ServerIP = $publicIP
            Write-Info "IP pública detectada: $script:ServerIP"
            return
        }
    }
    catch {
        Write-Warn "No se pudo obtener IP pública"
    }
    
    # Obtener IP local como fallback
    try {
        $localIP = (Get-NetIPConfiguration | Where-Object {$_.IPv4DefaultGateway -ne $null -and $_.NetAdapter.Status -ne "Disconnected"}).IPv4Address.IPAddress | Select-Object -First 1
        if ($localIP) {
            $script:ServerIP = $localIP
            Write-Info "IP local detectada: $script:ServerIP"
        }
        else {
            $script:ServerIP = "localhost"
            Write-Warn "Usando localhost como fallback"
        }
    }
    catch {
        $script:ServerIP = "localhost"
        Write-Warn "Error detectando IP, usando localhost"
    }
}

# Función para verificar requisitos del sistema
function Test-Requirements {
    Write-Step "Verificando requisitos del sistema..."
    
    $errors = @()
    
    # Verificar Docker Desktop
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        $errors += "Docker Desktop no está instalado o no está en el PATH"
    }
    else {
        try {
            $dockerVersion = docker --version
            Write-Info "✅ Docker encontrado: $dockerVersion"
            
            # Verificar que Docker esté corriendo
            docker ps > $null 2>&1
            if ($LASTEXITCODE -ne 0) {
                $errors += "Docker Desktop no está corriendo. Inicia Docker Desktop."
            }
        }
        catch {
            $errors += "Error ejecutando Docker"
        }
    }
    
    # Verificar Docker Compose
    if (-not (Get-Command docker-compose -ErrorAction SilentlyContinue)) {
        $errors += "Docker Compose no está instalado"
    }
    else {
        Write-Info "✅ Docker Compose encontrado"
    }
    
    # Verificar Java/Maven
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
        Write-Warn "⚠️  Maven no encontrado. Asegúrate de que esté instalado para compilar."
    }
    else {
        Write-Info "✅ Maven encontrado"
    }
    
    # Verificar que el JAR existe o se puede compilar
    $jarFiles = Get-ChildItem -Path "target" -Filter "PaymentBanregio-*.jar" -ErrorAction SilentlyContinue
    if (-not $jarFiles) {
        Write-Warn "⚠️  JAR no encontrado. Se intentará compilar automáticamente."
    }
    else {
        Write-Info "✅ JAR encontrado: $($jarFiles[0].Name)"
    }
    
    if ($errors.Count -gt 0) {
        Write-Error "❌ Errores encontrados:"
        $errors | ForEach-Object { Write-Error "   - $_" }
        Write-Error ""
        Write-Error "Por favor instala los requisitos faltantes:"
        Write-Error "1. Docker Desktop: https://www.docker.com/products/docker-desktop"
        Write-Error "2. Maven: https://maven.apache.org/download.cgi"
        Write-Error "3. Java 21: https://adoptium.net/"
        exit 1
    }
    
    Write-Info "✅ Todos los requisitos están satisfechos"
}

# Función para crear archivo .env
function New-EnvFile {
    if (-not (Test-Path ".env")) {
        Write-Step "Creando archivo .env con valores por defecto..."
        
        $envContent = @"
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

# Database Configuration
DB_HOST=postgres
DB_PORT=5432
DB_NAME=procesador_pagos_db
DB_USERNAME=postgres
DB_PASSWORD=a

# Webhook Configuration
WEBHOOK_NOTIFICATION_URL=http://$script:ServerIP`:8080/api/v1/webhooks/notifications
WEBHOOK_AUTO_CREATE=true

# JVM Configuration
JAVA_OPTS=-Xmx1024m -Xms512m -XX:+UseG1GC

# Spring Profile
SPRING_PROFILES_ACTIVE=prod
"@
        
        $envContent | Out-File -FilePath ".env" -Encoding UTF8
        
        Write-Warn "⚠️  Archivo .env creado con valores por defecto"
        Write-Warn "⚠️  Si necesitas cambiar credenciales, edita el archivo .env"
        Write-Info "Presiona cualquier tecla para continuar..."
        $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
    }
    else {
        Write-Info "Archivo .env encontrado"
        # Actualizar la URL del webhook con la IP actual
        $envContent = Get-Content ".env" -Raw
        $envContent = $envContent -replace "WEBHOOK_NOTIFICATION_URL=.*", "WEBHOOK_NOTIFICATION_URL=http://$script:ServerIP`:8080/api/v1/webhooks/notifications"
        $envContent | Out-File -FilePath ".env" -Encoding UTF8 -NoNewline
        Write-Info "URL del webhook actualizada en .env"
    }
}

# Función para compilar la aplicación
function Build-Application {
    Write-Step "Compilando aplicación..."
    
    if (Get-Command mvn -ErrorAction SilentlyContinue) {
        try {
            & mvn clean package -DskipTests
            if ($LASTEXITCODE -eq 0) {
                Write-Info "✅ Aplicación compilada exitosamente"
            }
            else {
                throw "Maven build falló"
            }
        }
        catch {
            Write-Error "❌ Error compilando la aplicación: $_"
            exit 1
        }
    }
    else {
        Write-Error "❌ Maven no está disponible. Instala Maven o proporciona el JAR compilado."
        exit 1
    }
}

# Función para detener contenedores existentes
function Stop-ExistingContainers {
    Write-Step "Deteniendo contenedores existentes..."
    
    try {
        docker-compose down --remove-orphans 2>$null
        
        # Limpiar contenedores huérfanos específicos
        docker stop $ContainerName 2>$null
        docker rm $ContainerName 2>$null
        docker stop $PostgresContainer 2>$null
        docker rm $PostgresContainer 2>$null
        
        Write-Info "✅ Contenedores existentes detenidos"
    }
    catch {
        Write-Warn "⚠️  Algunos contenedores podrían no haber existido"
    }
}

# Función para desplegar la aplicación
function Deploy-Application {
    Write-Step "Desplegando aplicación..."
    
    try {
        # Construir y levantar servicios
        docker-compose up --build -d
        
        if ($LASTEXITCODE -eq 0) {
            Write-Info "✅ Aplicación desplegada"
            
            # Esperar a que los servicios estén listos
            Write-Step "Esperando a que los servicios estén listos..."
            Start-Sleep -Seconds 30
            
            # Verificar estado de los servicios
            Test-ServiceHealth
        }
        else {
            throw "Docker Compose falló"
        }
    }
    catch {
        Write-Error "❌ Error desplegando la aplicación: $_"
        Show-Logs
        exit 1
    }
}

# Función para verificar la salud de los servicios
function Test-ServiceHealth {
    Write-Step "Verificando salud de los servicios..."
    
    # Verificar PostgreSQL
    try {
        $pgResult = docker exec $PostgresContainer pg_isready -U postgres -d procesador_pagos_db 2>$null
        if ($LASTEXITCODE -eq 0) {
            Write-Info "✅ PostgreSQL está funcionando correctamente"
        }
        else {
            Write-Error "❌ PostgreSQL no está respondiendo"
        }
    }
    catch {
        Write-Error "❌ Error verificando PostgreSQL"
    }
    
    # Verificar aplicación
    $maxAttempts = 12
    $attempt = 1
    
    while ($attempt -le $maxAttempts) {
        try {
            $response = Invoke-WebRequest -Uri "http://localhost:8080/actuator/health" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
            if ($response.StatusCode -eq 200) {
                Write-Info "✅ Aplicación está funcionando correctamente"
                break
            }
        }
        catch {
            Write-Warn "Intento $attempt/$maxAttempts`: Aplicación aún no está lista..."
            Start-Sleep -Seconds 10
            $attempt++
        }
    }
    
    if ($attempt -gt $maxAttempts) {
        Write-Error "❌ La aplicación no respondió después de $maxAttempts intentos"
        Show-Logs
        exit 1
    }
}

# Función para mostrar logs
function Show-Logs {
    Write-Step "Mostrando logs de la aplicación..."
    docker-compose logs --tail=50 payment-app
}

# Función para configurar el firewall de Windows
function Set-WindowsFirewall {
    Write-Step "Configurando Windows Firewall..."
    
    try {
        # Verificar si las reglas ya existen
        $rule8080 = Get-NetFirewallRule -DisplayName "PaymentBanregio HTTP" -ErrorAction SilentlyContinue
        $rule5432 = Get-NetFirewallRule -DisplayName "PaymentBanregio PostgreSQL" -ErrorAction SilentlyContinue
        
        if (-not $rule8080) {
            New-NetFirewallRule -DisplayName "PaymentBanregio HTTP" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow -Profile Any
            Write-Info "✅ Regla de firewall creada para puerto 8080"
        }
        else {
            Write-Info "✅ Regla de firewall para puerto 8080 ya existe"
        }
        
        if (-not $rule5432) {
            New-NetFirewallRule -DisplayName "PaymentBanregio PostgreSQL" -Direction Inbound -Protocol TCP -LocalPort 5432 -Action Allow -Profile Any
            Write-Info "✅ Regla de firewall creada para puerto 5432"
        }
        else {
            Write-Info "✅ Regla de firewall para puerto 5432 ya existe"
        }
    }
    catch {
        Write-Warn "⚠️  No se pudo configurar el firewall automáticamente. Configura manualmente:"
        Write-Warn "   - Puerto 8080 TCP (Entrada)"
        Write-Warn "   - Puerto 5432 TCP (Entrada)"
        Write-Warn "Error: $_"
    }
}

# Función para mostrar el estado final
function Show-Status {
    Write-Step "Estado del despliegue:"
    Write-Host ""
    Write-Info "🚀 Aplicación PaymentBanregio desplegada exitosamente!"
    Write-Host ""
    Write-Info "📊 URLs importantes:"
    Write-Info "   • Aplicación: http://$script:ServerIP`:8080"
    Write-Info "   • Health Check: http://$script:ServerIP`:8080/actuator/health"
    Write-Info "   • Webhook Admin: http://$script:ServerIP`:8080/api/v1/admin/webhooks/status"
    Write-Info "   • Crear Webhook: http://$script:ServerIP`:8080/api/v1/admin/webhooks/recreate"
    Write-Host ""
    Write-Info "📧 URL del Webhook para Microsoft Graph:"
    Write-Info "   http://$script:ServerIP`:8080/api/v1/webhooks/notifications"
    Write-Host ""
    Write-Info "🐘 PostgreSQL:"
    Write-Info "   • Host: localhost:5432"
    Write-Info "   • Base de datos: procesador_pagos_db"
    Write-Host ""
    Write-Info "📝 Comandos útiles:"
    Write-Info "   • Ver logs: docker-compose logs -f payment-app"
    Write-Info "   • Reiniciar: docker-compose restart payment-app"
    Write-Info "   • Detener: docker-compose down"
    Write-Host ""
    Write-Info "🔧 Para administrar:"
    Write-Info "   • PowerShell: .\deploy.ps1 <comando>"
    Write-Info "   • Comandos: deploy, restart, stop, logs, status, clean"
    Write-Host ""
}

# Función para reiniciar la aplicación
function Restart-Application {
    Write-Step "Reiniciando aplicación..."
    docker-compose restart payment-app
    Start-Sleep -Seconds 10
    Test-ServiceHealth
    Write-Info "✅ Aplicación reiniciada"
}

# Función para limpiar el sistema
function Clear-System {
    Write-Step "Limpiando sistema..."
    
    Write-Warn "⚠️  Esto eliminará todos los contenedores, imágenes y volúmenes"
    $response = Read-Host "⚠️  ¿Estás seguro? (y/N)"
    
    if ($response -match '^[Yy]$') {
        docker-compose down -v --remove-orphans
        docker system prune -f
        docker volume prune -f
        Write-Info "✅ Sistema limpiado"
    }
    else {
        Write-Info "Operación cancelada"
    }
}

# Función para mostrar el estado de los servicios
function Show-ServiceStatus {
    Write-Step "Estado de los servicios:"
    docker-compose ps
    Write-Host ""
    
    Write-Step "Uso de recursos:"
    docker stats --no-stream
}

# Función de ayuda
function Show-Help {
    Write-Host "Script de despliegue para PaymentBanregio en Windows Server" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Uso: .\deploy.ps1 [OPCIÓN]" -ForegroundColor White
    Write-Host ""
    Write-Host "Opciones:" -ForegroundColor White
    Write-Host "  deploy     Despliega la aplicación completa (por defecto)" -ForegroundColor Gray
    Write-Host "  restart    Reinicia la aplicación" -ForegroundColor Gray
    Write-Host "  stop       Detiene todos los servicios" -ForegroundColor Gray
    Write-Host "  logs       Muestra los logs de la aplicación" -ForegroundColor Gray
    Write-Host "  status     Muestra el estado de los servicios" -ForegroundColor Gray
    Write-Host "  clean      Limpia contenedores e imágenes" -ForegroundColor Gray
    Write-Host "  help       Muestra esta ayuda" -ForegroundColor Gray
    Write-Host ""
    Write-Host "Ejemplos:" -ForegroundColor White
    Write-Host "  .\deploy.ps1 deploy" -ForegroundColor Yellow
    Write-Host "  .\deploy.ps1 logs" -ForegroundColor Yellow
    Write-Host "  .\deploy.ps1 restart" -ForegroundColor Yellow
    Write-Host ""
}

# Función principal
function Main {
    Write-Host "==================================================" -ForegroundColor Cyan
    Write-Host "🚀 Despliegue de PaymentBanregio en Windows Server" -ForegroundColor Cyan
    Write-Host "==================================================" -ForegroundColor Cyan
    Write-Host ""
    
    # Obtener IP del servidor
    Get-ServerIP
    
    switch ($Action.ToLower()) {
        "deploy" {
            Test-Requirements
            New-EnvFile
            Build-Application
            Stop-ExistingContainers
            Deploy-Application
            Set-WindowsFirewall
            Show-Status
        }
        "restart" {
            Restart-Application
        }
        "stop" {
            Write-Step "Deteniendo servicios..."
            docker-compose down
            Write-Info "✅ Servicios detenidos"
        }
        "logs" {
            Show-Logs
        }
        "status" {
            Show-ServiceStatus
        }
        "clean" {
            Clear-System
        }
        "help" {
            Show-Help
        }
        default {
            Write-Error "Opción desconocida: $Action"
            Show-Help
            exit 1
        }
    }
}

# Ejecutar función principal
try {
    Main
}
catch {
    Write-Error "❌ Error inesperado: $_"
    Write-Error "Stack trace: $($_.ScriptStackTrace)"
    exit 1
}
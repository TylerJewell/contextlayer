# Runs the service on its own, with the Neo4j settings it needs, for a local session.
#
#   powershell -File scripts\run-local.ps1
#
# The Akka MCP tools start a service without passing environment variables, so the service cannot sign in to Neo4j that way.
# This sets them and starts the service the way the SDK documents (mvn compile exec:java). The port is
# akka.javasdk.dev-mode.http-port in application.conf.
param(
  [string]$Password = "localdevpass123",
  [string]$Uri = "bolt://localhost:7687",
  [string]$User = "neo4j"
)
$env:NEO4J_PASSWORD = $Password
$env:NEO4J_URI = $Uri
$env:NEO4J_USER = $User
Set-Location (Join-Path $PSScriptRoot "..")
mvn compile exec:java

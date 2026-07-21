# AKS + Azure Database for PostgreSQL substrate for genalpha-bss. After apply:
#   az aks get-credentials --resource-group <rg> --name <cluster>
#   helm install bss ../../helm/genalpha-bss \
#     --set local.postgres.enabled=false \
#     --set config.dbHost=<postgres_fqdn output> \
#     --set config.dbUsername=<db_username var> \
#     --set config.dbPassword=<your secret> \
#     --set image.prefix=<registry_login_server output>/bss-java-
# Azure Event Hubs speaks the Kafka protocol; to use it instead of in-cluster
# Kafka, set local.kafka.enabled=false and point config.kafkaBootstrapServers
# at the Event Hubs namespace.
#
# ops/k8s-soak/aks-run.sh wraps the whole run (up | smoke | down).

terraform {
  required_version = ">= 1.6"
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.0"
    }
  }
}

provider "azurerm" {
  features {}
}

resource "azurerm_resource_group" "bss" {
  name     = "${var.name}-rg"
  location = var.location
}

# The fleet's images live here (the EKS run's lesson: a registry is part
# of the substrate, not an afterthought).
resource "azurerm_container_registry" "bss" {
  # ACR names: alphanumeric only, globally unique
  name                = replace("${var.name}acr", "-", "")
  resource_group_name = azurerm_resource_group.bss.name
  location            = azurerm_resource_group.bss.location
  sku                 = "Basic"
}

resource "azurerm_kubernetes_cluster" "bss" {
  name                = var.name
  location            = azurerm_resource_group.bss.location
  resource_group_name = azurerm_resource_group.bss.name
  dns_prefix          = var.name
  kubernetes_version  = var.kubernetes_version

  default_node_pool {
    name       = "default"
    node_count = 2
    vm_size    = var.node_vm_size
  }

  identity {
    type = "SystemAssigned"
  }
}

# The nodes may pull from the registry without a password — the managed
# identity carries the right.
resource "azurerm_role_assignment" "acr_pull" {
  scope                            = azurerm_container_registry.bss.id
  role_definition_name             = "AcrPull"
  principal_id                     = azurerm_kubernetes_cluster.bss.kubelet_identity[0].object_id
  skip_service_principal_aad_check = true
}

resource "azurerm_postgresql_flexible_server" "bss" {
  name                          = "${var.name}-postgres"
  resource_group_name           = azurerm_resource_group.bss.name
  location                      = azurerm_resource_group.bss.location
  version                       = "16"
  administrator_login           = var.db_username
  administrator_password        = var.db_password
  sku_name                      = var.db_sku
  storage_mb                    = 32768
  public_network_access_enabled = true
  zone                          = "1"
}

# The 0.0.0.0 sentinel rule = "allow Azure services" — the AKS pods'
# outbound addresses are Azure's; without this every connection is
# refused at the door.
resource "azurerm_postgresql_flexible_server_firewall_rule" "azure_services" {
  name             = "allow-azure-services"
  server_id        = azurerm_postgresql_flexible_server.bss.id
  start_ip_address = "0.0.0.0"
  end_ip_address   = "0.0.0.0"
}

# B1ms defaults to ~50 connections — thirteen services' pools exceed it
# (the fleet's fifth live-run truth: EKS's RDS allowed ~4x more). 85 is
# the burstable tier's ceiling; the chart also shrinks idle pools.
resource "azurerm_postgresql_flexible_server_configuration" "max_connections" {
  name      = "max_connections"
  server_id = azurerm_postgresql_flexible_server.bss.id
  value     = "85"
}

# Flexible Server demands TLS by default and the fleet's JDBC URLs do
# not speak it — for the SOAK we turn the demand off (honest trade-off,
# documented); a production deployment keeps it on and adds
# sslmode=require to the datasource URLs instead.
resource "azurerm_postgresql_flexible_server_configuration" "no_tls_requirement" {
  name      = "require_secure_transport"
  server_id = azurerm_postgresql_flexible_server.bss.id
  value     = "off"
}

# Flexible Server refuses CREATE EXTENSION unless the extension is
# allow-listed first — the fleet's migrations need pg_trgm (customer
# search typo net) and vector (the knowledge semantic net).
resource "azurerm_postgresql_flexible_server_configuration" "extensions" {
  name      = "azure.extensions"
  server_id = azurerm_postgresql_flexible_server.bss.id
  value     = "PG_TRGM,VECTOR"
}

# Every database the fleet knows — mirrors infra/postgres/init-databases.sql
# (creating them here replaces the psql init step the EKS run needed).
resource "azurerm_postgresql_flexible_server_database" "dbs" {
  for_each = toset([
    "product_catalog", "product_ordering", "product_inventory", "party_account",
    "product_stock", "payment", "billing", "qualification", "appointment",
    "trouble_ticket", "party_interaction", "communication", "shopping_cart",
    "usage", "agreement", "promotion", "campaign", "intelligence", "quote",
    "porting", "policy", "geographic_address", "payment_method", "service_om",
    "assurance", "document", "knowledge", "insight"
  ])
  name      = each.key
  server_id = azurerm_postgresql_flexible_server.bss.id
}

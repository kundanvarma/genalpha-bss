# AKS + Azure Database for PostgreSQL substrate for genalpha-bss. After apply:
#   az aks get-credentials --resource-group <rg> --name <cluster>
#   helm install bss ../../helm/genalpha-bss \
#     --set local.postgres.enabled=false \
#     --set config.dbHost=<postgres_fqdn output> \
#     --set config.dbUsername=<admin>@<server> \
#     --set config.dbPassword=<your secret> \
#     --set image.prefix=<your registry>/
# Azure Event Hubs speaks the Kafka protocol; to use it instead of in-cluster
# Kafka, set local.kafka.enabled=false and point config.kafkaBootstrapServers
# at the Event Hubs namespace.

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

resource "azurerm_postgresql_flexible_server_database" "dbs" {
  for_each  = toset(["product_catalog", "product_ordering", "product_inventory", "party_account"])
  name      = each.key
  server_id = azurerm_postgresql_flexible_server.bss.id
}

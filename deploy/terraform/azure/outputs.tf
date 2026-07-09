output "cluster_name" {
  value = azurerm_kubernetes_cluster.bss.name
}

output "resource_group" {
  value = azurerm_resource_group.bss.name
}

output "postgres_fqdn" {
  description = "Set config.dbHost to this in the Helm values"
  value       = azurerm_postgresql_flexible_server.bss.fqdn
}

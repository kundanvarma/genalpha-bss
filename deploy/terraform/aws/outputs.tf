output "cluster_name" {
  value = module.eks.cluster_name
}

output "rds_address" {
  description = "Set config.dbHost to this in the Helm values"
  value       = aws_db_instance.bss.address
}

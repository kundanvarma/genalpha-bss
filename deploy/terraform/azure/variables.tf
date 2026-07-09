variable "name" {
  type    = string
  default = "genalpha-bss"
}

variable "location" {
  type    = string
  default = "westeurope"
}

variable "kubernetes_version" {
  type    = string
  default = "1.31"
}

variable "node_vm_size" {
  type    = string
  default = "Standard_D2s_v5"
}

variable "db_sku" {
  type    = string
  default = "B_Standard_B1ms"
}

variable "db_username" {
  type    = string
  default = "bssadmin"
}

variable "db_password" {
  type      = string
  sensitive = true
}

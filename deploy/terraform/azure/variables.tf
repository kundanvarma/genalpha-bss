variable "name" {
  type    = string
  default = "genalpha-bss"
}

variable "location" {
  type = string
  # Region choice is SUBSCRIPTION-dependent (learned live, twice):
  # westeurope refuses new subscriptions outright; swedencentral had no
  # AKS capacity; and this sponsorship tier's VM catalog varies by
  # region. northeurope offered a usable size — probe with
  # `az vm list-skus --location <region>` before moving.
  default = "northeurope"
}

variable "kubernetes_version" {
  type = string
  # null = AKS picks its current default channel version — a pinned
  # version ages out of support (the EKS run paid 6x for that lesson)
  default = null
}

variable "node_vm_size" {
  type = string
  # Sponsorship-tier subscriptions gate VM sizes TWICE: the offered
  # catalog (restrictions) AND per-family vCPU quota — a size can be
  # listed with zero quota (DC2ads_v6 was). EC2as_v5 (x86 AMD,
  # 2 vCPU/16 GB) is both offered AND quota'd on this tier. Images must
  # be amd64 — aks-run.sh cross-builds them (jars are arch-independent).
  # Probe both axes before changing:
  #   az vm list-skus --location <r>   (restrictions)
  #   az vm list-usage --location <r>  (family quota)
  default = "Standard_EC2as_v5"
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

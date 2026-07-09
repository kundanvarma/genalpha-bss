variable "region" {
  type    = string
  default = "eu-central-1"
}

variable "name" {
  type    = string
  default = "genalpha-bss"
}

variable "availability_zones" {
  type    = list(string)
  default = ["eu-central-1a", "eu-central-1b"]
}

variable "kubernetes_version" {
  type    = string
  default = "1.31"
}

variable "node_instance_type" {
  type    = string
  default = "t3.large"
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.small"
}

variable "db_username" {
  type    = string
  default = "postgres"
}

variable "db_password" {
  type      = string
  sensitive = true
}

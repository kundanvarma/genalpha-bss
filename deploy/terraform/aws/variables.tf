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
  # keep this inside EKS STANDARD support — versions that age into
  # extended support cost 6x for the control plane ($0.60/hr vs $0.10)
  default = "1.33"
}

variable "node_instance_type" {
  type    = string
  # MATCH THE NODE ARCH TO YOUR IMAGE BUILDS: images built on Apple
  # Silicon are arm64 — Graviton (t4g) runs them natively and costs
  # ~30% less than t3; set t3.large for x86-built images. (The first
  # live run pulled arm64 images onto x86 nodes: ImagePullBackOff.)
  default = "t4g.large"
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

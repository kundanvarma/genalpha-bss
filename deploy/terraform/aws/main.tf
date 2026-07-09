# EKS + RDS substrate for genalpha-bss. After apply, install the Helm chart:
#   aws eks update-kubeconfig --name <cluster_name>
#   helm install bss ../../helm/genalpha-bss \
#     --set local.postgres.enabled=false \
#     --set config.dbHost=<rds_address output> \
#     --set config.dbPassword=<your secret> \
#     --set config.oidcIssuerUri=<your IdP or in-cluster Keycloak> \
#     --set image.prefix=<your registry>/
# The per-service databases must exist on the RDS instance; create them with
# psql using infra/postgres/init-databases.sql.

terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.region
}

module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.8"

  name = "${var.name}-vpc"
  cidr = "10.60.0.0/16"

  azs             = var.availability_zones
  private_subnets = ["10.60.1.0/24", "10.60.2.0/24"]
  public_subnets  = ["10.60.101.0/24", "10.60.102.0/24"]

  enable_nat_gateway = true
  single_nat_gateway = true
}

module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.0"

  cluster_name    = var.name
  cluster_version = var.kubernetes_version

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets

  cluster_endpoint_public_access = true

  eks_managed_node_groups = {
    default = {
      instance_types = [var.node_instance_type]
      min_size       = 2
      max_size       = 4
      desired_size   = 2
    }
  }
}

resource "aws_db_subnet_group" "bss" {
  name       = "${var.name}-db"
  subnet_ids = module.vpc.private_subnets
}

resource "aws_security_group" "db" {
  name   = "${var.name}-db"
  vpc_id = module.vpc.vpc_id

  ingress {
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [module.vpc.vpc_cidr_block]
  }
}

resource "aws_db_instance" "bss" {
  identifier             = "${var.name}-postgres"
  engine                 = "postgres"
  engine_version         = "16"
  instance_class         = var.db_instance_class
  allocated_storage      = 20
  db_name                = "postgres"
  username               = var.db_username
  password               = var.db_password
  db_subnet_group_name   = aws_db_subnet_group.bss.name
  vpc_security_group_ids = [aws_security_group.db.id]
  skip_final_snapshot    = true
}

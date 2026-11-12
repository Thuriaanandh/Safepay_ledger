terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }
}

provider "aws" {
  region                      = var.aws_region
  access_key                  = "test"
  secret_key                  = "test"
  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true

  endpoints {
    dynamodb = var.localstack_endpoint
    iam      = var.localstack_endpoint
  }

  default_tags {
    tags = {
      Environment = "local-dev"
      System      = "SafePay-Ledger"
      ManagedBy   = "Terraform"
    }
  }
}

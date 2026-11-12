variable "aws_region" {
  description = "AWS region for payment resources"
  type        = string
  default     = "us-east-1"
}

variable "localstack_endpoint" {
  description = "LocalStack endpoint URL for local emulation"
  type        = string
  default     = "http://localhost:4566"
}

variable "idempotency_table_name" {
  description = "Name of the DynamoDB idempotency key store"
  type        = string
  default     = "safepay_idempotency_keys"
}
